package org.qualet.irlredactor.light.auto;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.qualet.irl.light.iris.IrisShadersState;
import org.qualet.irlredactor.light.LightConfig;
import org.qualet.irlredactor.light.PlacedLight;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Auto-places a point light on every light-emitting vanilla block in range,
 * with hardcoded colour / reach per block type (see {@link BlockLightDefs}).
 * The BBS-free engine seam takes its lights from {@link org.qualet.irlredactor.light.LightDriver};
 * this manager is the second, world-derived feed alongside the editor's manual
 * {@link org.qualet.irlredactor.light.LightScene}.
 *
 * <h2>Why a separate list (not LightScene)</h2>
 * Auto-lights are derived from the world, so they must NOT be persisted by
 * {@code LightStore}, shown in the editor's source list, or drawn as guides —
 * all of which operate on {@link org.qualet.irlredactor.light.LightScene}. Keeping
 * them here keeps all of that automatic.
 *
 * <h2>Incremental rolling scan (no main-thread freeze)</h2>
 * Scanning the whole {@link LightConfig#autoLightRadius()} sphere at once is
 * O(radius³) and would freeze the client thread for tens-to-hundreds of ms each
 * time (catastrophic for editor input even when the average frame rate looks
 * fine). Instead the scan is AMORTIZED: each client tick advances a cursor over
 * the in-range chunks, full-scanning at most {@link #CHUNKS_PER_TICK} chunks /
 * {@link #SECTIONS_PER_TICK} emissive sections, then pausing. When the cursor
 * wraps the whole range, the pass completes (evicting blocks no longer seen) and
 * a fresh pass starts — so the set refreshes continuously roughly once per second
 * with a small, bounded per-tick cost. World edits need no explicit signal; the
 * rolling pass picks them up within a cycle.
 *
 * <h2>Surface culling</h2>
 * When {@link LightConfig#autoLightCulling()} is on (the default), an emissive
 * block only becomes a light if it is EXPOSED — at least one of its six
 * face-neighbours opens onto visible space (air / glass / any non-opaque,
 * non-emitter cell). A block buried inside opaque terrain, or in the interior of
 * a solid cluster of emitters (surrounded on all sides by opaque blocks or other
 * emitters), is invisible and its light can't escape, so it emits nothing — only
 * the outward-facing shell of a cluster stays lit. This keeps a wall or cube of
 * glowstone from flooding the light buffer with hundreds of hidden, redundant
 * sources. See {@link #isExposed}.
 *
 * <h2>Cluster merge</h2>
 * Culling can't thin a lava lake or a Nether glowstone vein: those are dense
 * masses where almost every block is air-exposed surface, so the shell alone is
 * still hundreds of lights. Emitter types that occur in bulk are therefore
 * flagged for cluster merge ({@code BlockLightDefs.Def.mergeCells > 1}: lava,
 * glowstone, shroomlight, froglights, …). A flagged block is merged ONLY where it
 * is genuinely part of a cluster — at least {@link #MERGE_MIN_SAME} of its six
 * face-neighbours are the same block — in which case all such blocks within one
 * cell collapse to a single light (the first the pass reaches wins the cell,
 * {@link #claimedCellsThisPass}; the rest are skipped). A lone / thinly-placed
 * source (a decorative glowstone, a 1-wide lava stream) fails that test and keeps
 * its full per-block light, so intentional single lights are untouched. A lava
 * lake / glowstone vein thus becomes roughly one light per cell — still a smooth
 * glow given their wide reach, with no more per-block pile-up over-brightening the
 * mass. Exposure and same-neighbour counts come from one combined sweep
 * ({@link #neighborSweep}).
 *
 * <h2>Stable ids</h2>
 * Each light is keyed by its host {@code BlockPos.asLong()} and the SAME
 * {@link PlacedLight} instance (hence its stable {@link PlacedLight#id}) is
 * reused while that block keeps emitting — so the shadow caches (keyed on light
 * id) don't thrash. A block that stops emitting / leaves range drops its light at
 * the end of the pass that doesn't see it; a fresh one is minted if it returns.
 *
 * <h2>Threading</h2>
 * {@link #tick} (client tick) and {@link #nearest} (world render) both run on the
 * render/client thread in 1.20.x and never overlap, so the FastUtil collections
 * need no synchronization. No chunk reference is held across ticks (each chunk is
 * re-fetched), so an unload between ticks can't dangle.
 */
public final class AutoLightManager
{
    /** Palette pre-filter: skip a whole 16³ section unless it contains a block
     *  that could yield an auto-light (luminous, or powered redstone dust). */
    private static final Predicate<BlockState> EMISSIVE = BlockLightDefs::paletteCandidate;

    /** Max chunks fetched + palette-pre-checked per tick (bounds the light work). */
    private static final int CHUNKS_PER_TICK = 12;
    /** Max emissive sections FULL-scanned (16³) per tick (bounds the heavy work;
     *  may overshoot by one chunk's worth since a chunk is never paused mid-way). */
    private static final int SECTIONS_PER_TICK = 16;

    /** host blockPos.asLong() -> its auto-light (instance reused for a stable id). */
    private static final Long2ObjectOpenHashMap<PlacedLight> byPos = new Long2ObjectOpenHashMap<>();
    /** Positions seen so far in the IN-PROGRESS pass; at pass end, byPos entries
     *  not in here are evicted. */
    private static final LongOpenHashSet seenThisPass = new LongOpenHashSet();
    /** Merge cells already claimed in the IN-PROGRESS pass (see {@code Def.mergeCells}).
     *  The first mergeable emitter (lava) the pass reaches in a cell wins and gets
     *  the cell's single light; later blocks in the same cell probe this set and are
     *  skipped, collapsing a lava lake to one light per cell instead of per block.
     *  Cleared at pass start alongside {@link #seenThisPass}. */
    private static final LongOpenHashSet claimedCellsThisPass = new LongOpenHashSet();
    /** Reused nearest-first feed list (rebuilt LAZILY by {@link #nearest}; see the
     *  feed cache below — it is NOT re-sorted every render frame). */
    private static final List<PlacedLight> feed = new ArrayList<>();

    // --- nearest-feed cache --------------------------------------------------
    // Re-sorting all of byPos every render frame is the bulk of LightDriver's
    // per-frame CPU cost when a scene has many emissive blocks (measured ~12 ms
    // at a 48-block scan radius, regardless of the source cap — the sort runs
    // before the cap truncates). But the nearest-first ordering only changes when
    // the auto-light SET changes (a scan pass adds/evicts a block) or the camera
    // moves enough to shift it; auto-lights never move (a block is fixed), and
    // their light DATA stays live through the same PlacedLight instances. So the
    // feed is rebuilt only on those events and reused verbatim otherwise.
    /** Bumped on every structural change to {@link #byPos} (add / evict / clear);
     *  a change from the value the feed was last built for forces a rebuild. */
    private static int setGeneration;
    /** {@link #setGeneration} the cached feed was last built for. */
    private static int feedGeneration = Integer.MIN_VALUE;
    /** The cap the cached feed was last truncated to (a larger cap needs a rebuild). */
    private static int feedMax = -1;
    /** Camera position the cached feed was last sorted around. */
    private static double feedCamX, feedCamY, feedCamZ;
    /** Re-sort once the camera moves more than this (blocks, squared) since the
     *  last sort: the nearest-first cut only matters to within a couple of blocks,
     *  so a small drift is invisible but saves the per-frame sort while editing in
     *  place (the common case). */
    private static final double FEED_RESORT_DIST2 = 4.0; // (2 blocks)^2

    // --- rolling-pass cursor / parameters (captured at pass start) -----------
    private static boolean passActive;
    private static double passCx, passCy, passCz;
    private static float passR2;
    private static int passMinX, passMaxX, passMinY, passMaxY, passMinZ, passMaxZ;
    private static int passMinChunkX, passMinChunkZ, passSpanX, passSpanZ;
    private static int passChunkIdx; // linear index 0..(spanX*spanZ); == end -> pass done

    private AutoLightManager()
    {}

    /** Number of auto-lights currently tracked (before the nearest-first cap) —
     *  i.e. every emissive block the rolling scan has found in range. This can be
     *  far larger than the number actually used; for the live "how many are really
     *  active" figure use {@link #activeCount()}. */
    public static int count()
    {
        return byPos.size();
    }

    /** How many auto-lights were ACTUALLY fed to the pipeline on the last
     *  {@link #nearest} call — that is {@link #count()} truncated to the source
     *  cap ({@link LightConfig#autoLightMax()}) and the SSBO headroom. This is the
     *  meaningful "active" count and never exceeds the limit; the editor shows it
     *  instead of {@link #count()} so the figure doesn't just climb with the scan. */
    private static int lastActiveCount;

    /** @see #lastActiveCount */
    public static int activeCount()
    {
        return lastActiveCount;
    }

    /** Forget all auto-lights + reset the pass (world left / feature off). */
    public static void clear()
    {
        byPos.clear();
        seenThisPass.clear();
        claimedCellsThisPass.clear();
        feed.clear();
        lastActiveCount = 0;
        passActive = false;
        passChunkIdx = 0;
        setGeneration++; // invalidate the cached nearest feed
    }

    /**
     * Per client tick: advance the rolling scan by one bounded step. Starts a new
     * pass when the previous one finished, so the auto-light set refreshes
     * continuously without ever doing the whole (potentially huge) scan in a
     * single tick.
     */
    public static void tick(ClientWorld world, double centerX, double centerY, double centerZ)
    {
        if (!LightConfig.autoLights() || world == null)
        {
            if (!byPos.isEmpty() || passActive)
            {
                clear();
            }
            return;
        }
        // Nothing consumes the lights while shaders are off (the whole pipeline is
        // dormant); don't burn CPU scanning. The set is kept; the next pass after
        // shaders return refreshes it.
        if (IrisShadersState.shadersDisabled())
        {
            return;
        }

        if (!passActive)
        {
            startPass(centerX, centerY, centerZ);
        }
        stepPass(world);
    }

    /** Begin a fresh pass centred on the current position with the current radius. */
    private static void startPass(double centerX, double centerY, double centerZ)
    {
        int radius = Math.max(1, LightConfig.autoLightRadius());
        passCx = centerX;
        passCy = centerY;
        passCz = centerZ;
        passR2 = (float) radius * radius;

        passMinX = (int) Math.floor(centerX - radius);
        passMaxX = (int) Math.floor(centerX + radius);
        passMinY = (int) Math.floor(centerY - radius);
        passMaxY = (int) Math.floor(centerY + radius);
        passMinZ = (int) Math.floor(centerZ - radius);
        passMaxZ = (int) Math.floor(centerZ + radius);

        passMinChunkX = passMinX >> 4;
        passMinChunkZ = passMinZ >> 4;
        passSpanX = (passMaxX >> 4) - passMinChunkX + 1;
        passSpanZ = (passMaxZ >> 4) - passMinChunkZ + 1;

        passChunkIdx = 0;
        seenThisPass.clear();
        claimedCellsThisPass.clear();
        passActive = true;
    }

    /** Advance the current pass by up to a tick's worth of work, then pause. */
    private static void stepPass(ClientWorld world)
    {
        int total = passSpanX * passSpanZ;
        int chunksThisTick = 0;
        int sectionsThisTick = 0;

        while (passChunkIdx < total
            && chunksThisTick < CHUNKS_PER_TICK
            && sectionsThisTick < SECTIONS_PER_TICK)
        {
            int idx = passChunkIdx++;
            chunksThisTick++;

            int chunkX = passMinChunkX + (idx % passSpanX);
            int chunkZ = passMinChunkZ + (idx / passSpanX);
            WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
            if (chunk == null)
            {
                continue;
            }
            sectionsThisTick += scanChunk(world, chunk, chunkX, chunkZ);
        }

        if (passChunkIdx >= total)
        {
            // Pass complete: drop lights whose host block wasn't seen this pass
            // (block broken / stopped emitting / left range), then end the pass so
            // the next tick starts a fresh one.
            if (!byPos.isEmpty())
            {
                ObjectIterator<Long2ObjectMap.Entry<PlacedLight>> it = byPos.long2ObjectEntrySet().iterator();
                while (it.hasNext())
                {
                    if (!seenThisPass.contains(it.next().getLongKey()))
                    {
                        it.remove();
                        setGeneration++; // invalidate the cached nearest feed
                    }
                }
            }
            passActive = false;
        }
    }

    /** Full-scan one chunk's in-range emissive sections; returns how many sections
     *  were full-scanned (for the per-tick section budget). */
    private static int scanChunk(ClientWorld world, WorldChunk chunk, int chunkX, int chunkZ)
    {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int fullScanned = 0;

        for (int s = 0; s < sections.length; s++)
        {
            ChunkSection sec = sections[s];
            if (sec == null || sec.isEmpty())
            {
                continue;
            }

            int secMinY = bottomY + (s << 4);
            if (secMinY + 15 < passMinY || secMinY > passMaxY)
            {
                continue; // section's whole 16-block band is outside the sphere bbox
            }
            if (!sec.hasAny(EMISSIVE))
            {
                continue; // palette pre-check: no emitter in this section
            }
            fullScanned++;

            int ly0 = Math.max(0, passMinY - secMinY);
            int ly1 = Math.min(15, passMaxY - secMinY);
            for (int ly = ly0; ly <= ly1; ly++)
            {
                int wy = secMinY + ly;
                double dy = (wy + 0.5) - passCy;
                double dy2 = dy * dy;

                for (int lx = 0; lx < 16; lx++)
                {
                    int wx = baseX + lx;
                    if (wx < passMinX || wx > passMaxX)
                    {
                        continue;
                    }
                    double dx = (wx + 0.5) - passCx;
                    double dxy2 = dx * dx + dy2;
                    if (dxy2 > passR2)
                    {
                        continue;
                    }

                    for (int lz = 0; lz < 16; lz++)
                    {
                        int wz = baseZ + lz;
                        if (wz < passMinZ || wz > passMaxZ)
                        {
                            continue;
                        }
                        double dz = (wz + 0.5) - passCz;
                        if (dxy2 + dz * dz > passR2)
                        {
                            continue;
                        }

                        BlockState state = sec.getBlockState(lx, ly, lz);
                        // resolve() returns null unless the state actually emits +
                        // is in the curated table (handles lit/unlit lamp, dead
                        // campfire, uncharged anchor, unlit candle, …), or is a
                        // non-luminous special case (powered redstone dust).
                        BlockLightDefs.Def def = BlockLightDefs.resolve(state);
                        if (def == null)
                        {
                            continue;
                        }

                        // Cull (skip buried / invisible emitters) and cluster-merge
                        // (thin dense masses). A non-mergeable emitter only needs the
                        // exposure test, which short-circuits and is cheap. A
                        // mergeable one (lava, glowstone, … — Def.mergeCells > 1)
                        // needs BOTH its exposure AND how many like neighbours it has,
                        // so it takes one combined neighbour sweep.
                        if (def.mergeCells > 1)
                        {
                            long swept = neighborSweep(world, sec, state.getBlock(), lx, ly, lz, wx, wy, wz);
                            // Surface culling (invisible -> no light). By NOT adding it
                            // to seenThisPass, any light it had is evicted at pass end.
                            if (LightConfig.autoLightCulling() && (swept & 1L) == 0L)
                            {
                                continue;
                            }
                            // Merge ONLY where the emitter is genuinely part of a
                            // cluster (>= MERGE_MIN_SAME like neighbours): keep just
                            // the first block the pass reaches in each cell, so a lava
                            // lake / glowstone vein collapses to one light per cell. A
                            // lone or thin placement (a decorative glowstone, a 1-wide
                            // lava stream) fails the test and keeps its per-block light.
                            if ((int) (swept >>> 1) >= MERGE_MIN_SAME)
                            {
                                long cellKey = BlockPos.asLong(
                                    Math.floorDiv(wx, def.mergeCells),
                                    Math.floorDiv(wy, def.mergeCells),
                                    Math.floorDiv(wz, def.mergeCells));
                                if (!claimedCellsThisPass.add(cellKey))
                                {
                                    continue; // this cell already has its one light this pass
                                }
                            }
                        }
                        else if (LightConfig.autoLightCulling()
                            && !isExposed(world, sec, lx, ly, lz, wx, wy, wz))
                        {
                            continue; // buried / invisible -> evicted at pass end (not seen)
                        }

                        long key = BlockPos.asLong(wx, wy, wz);
                        seenThisPass.add(key);
                        upsert(key, wx, wy, wz, def);
                    }
                }
            }
        }
        return fullScanned;
    }

    /** Reused scratch position for the six neighbour lookups in {@link #isOpen}
     *  (render/client thread only — never overlaps with {@link #nearest}). */
    private static final BlockPos.Mutable NB = new BlockPos.Mutable();

    /**
     * Is the emitter at (wx,wy,wz) EXPOSED — does at least one of its six
     * face-neighbours open onto visible space? Short-circuits on the first opening,
     * so a surface block is cheap; only a fully buried block pays for all six.
     */
    private static boolean isExposed(ClientWorld world, ChunkSection sec,
                                     int lx, int ly, int lz,
                                     int wx, int wy, int wz)
    {
        return isOpen(world, sec, lx + 1, ly, lz, wx + 1, wy, wz)
            || isOpen(world, sec, lx - 1, ly, lz, wx - 1, wy, wz)
            || isOpen(world, sec, lx, ly + 1, lz, wx, wy + 1, wz)
            || isOpen(world, sec, lx, ly - 1, lz, wx, wy - 1, wz)
            || isOpen(world, sec, lx, ly, lz + 1, wx, wy, wz + 1)
            || isOpen(world, sec, lx, ly, lz - 1, wx, wy, wz - 1);
    }

    /**
     * Does the neighbour cell open onto visible space? True unless it BLOCKS the
     * emitter's exposure, which happens when the neighbour is either:
     * <ul>
     *   <li>an opaque full cube — buries the emitter, blocking both vision and
     *       light (stone, wool, and opaque emitters like glowstone alike); or</li>
     *   <li>itself a resolvable auto-emitter — the interior of a solid cluster of
     *       lights is invisible and redundant with the cluster's outer shell, so a
     *       neighbouring emitter counts as closed too (this is what culls the
     *       "surrounded by the same sources" case, even for non-full-cube emitters
     *       like lanterns or end rods that don't occlude on their own).</li>
     * </ul>
     * Everything else — air, glass, water, slabs, fences, leaves, … — is an opening
     * the light escapes through, so the emitter stays lit.
     *
     * <p>In-section neighbours are read straight from the {@link ChunkSection}
     * (a cheap array access); only boundary crossings fall back to a full world
     * lookup. A neighbour in an unloaded chunk reads as air (open) — we keep the
     * light rather than risk culling something that might be visible.</p>
     */
    private static boolean isOpen(ClientWorld world, ChunkSection sec,
                                  int lx, int ly, int lz,
                                  int wx, int wy, int wz)
    {
        NB.set(wx, wy, wz);
        BlockState n = inSection(lx, ly, lz) ? sec.getBlockState(lx, ly, lz) : world.getBlockState(NB);
        return isOpenState(world, n, NB);
    }

    /** True if these local coords fall inside the current 16³ section. Only one
     *  axis ever leaves [0,16) per neighbour, so a miss means a section/chunk
     *  boundary crossing (read from the world instead). {@code (lx|ly|lz) >= 0}
     *  rejects any -1; the {@code < 16} checks reject any 16. */
    private static boolean inSection(int lx, int ly, int lz)
    {
        return (lx | ly | lz) >= 0 && lx < 16 && ly < 16 && lz < 16;
    }

    /** Does this neighbour state open onto visible space (light + vision escape)?
     *  Closed iff it is another auto-emitter (interior of a light cluster) or an
     *  opaque full cube (buries the emitter); open for everything else — air,
     *  glass, water, slabs, fences, leaves, … {@code pos} must already be set to
     *  the neighbour for the opaque-full-cube shape query. */
    private static boolean isOpenState(ClientWorld world, BlockState n, BlockPos pos)
    {
        if (BlockLightDefs.resolve(n) != null)
        {
            return false; // neighbouring emitter -> closed
        }
        return !n.isOpaqueFullCube(world, pos); // opaque full cube -> closed; else open
    }

    // Face-neighbour offsets (±x, ±y, ±z) for the combined sweep below.
    private static final int[] FX = {1, -1, 0, 0, 0, 0};
    private static final int[] FY = {0, 0, 1, -1, 0, 0};
    private static final int[] FZ = {0, 0, 0, 0, 1, -1};

    /** Minimum like face-neighbours for a mergeable emitter to count as part of a
     *  cluster (and so be cell-merged). 0-2 — a lone block, a pair, a straight line
     *  — stays a per-block light; ≥ 3 — a 2D sheet or 3D blob — merges. */
    private static final int MERGE_MIN_SAME = 3;

    /**
     * One sweep of a mergeable emitter's six face-neighbours, returning both signals
     * it needs packed into a long: bit 0 = EXPOSED (≥ 1 opening — for culling); bits
     * 1.. = how many neighbours are the SAME block (its cluster density — for the
     * merge gate). Combined into a single pass so glowstone / lava, which need both,
     * don't walk their neighbourhood twice. In-section neighbours are read straight
     * from the {@link ChunkSection}; only boundary crossings hit the world.
     */
    private static long neighborSweep(ClientWorld world, ChunkSection sec, Block self,
                                      int lx, int ly, int lz, int wx, int wy, int wz)
    {
        boolean exposed = false;
        int same = 0;
        for (int f = 0; f < 6; f++)
        {
            int nlx = lx + FX[f], nly = ly + FY[f], nlz = lz + FZ[f];
            NB.set(wx + FX[f], wy + FY[f], wz + FZ[f]);
            BlockState n = inSection(nlx, nly, nlz) ? sec.getBlockState(nlx, nly, nlz) : world.getBlockState(NB);
            if (n.getBlock() == self)
            {
                same++;
            }
            if (!exposed && isOpenState(world, n, NB))
            {
                exposed = true;
            }
        }
        return (exposed ? 1L : 0L) | ((long) same << 1);
    }

    /**
     * The nearest-first, capped feed. Nearest-first ordering means the closest
     * lights win the limited shadow slots (the baker allocates them in registration
     * order); {@link LightDriver} decides which of them actually cast shadows (it
     * sets each light's {@code shadows} flag based on the remaining slot budget).
     * The returned list is owned by the manager — copy it to retain.
     *
     * <p>Cached: the sort runs only when the set, the cap, or the camera position
     * (beyond {@link #FEED_RESORT_DIST2}) changed since the last build — NOT every
     * call. See the feed-cache fields.</p>
     */
    public static List<PlacedLight> nearest(Vec3d cameraPos, int max)
    {
        // max <= 0 means feed nothing (the cap slider at 0 = no auto-lights).
        if (byPos.isEmpty() || cameraPos == null || max <= 0)
        {
            feed.clear();
            feedGeneration = setGeneration;
            feedMax = max;
            lastActiveCount = 0;
            return feed;
        }

        final double cx = cameraPos.x, cy = cameraPos.y, cz = cameraPos.z;
        double dcx = cx - feedCamX, dcy = cy - feedCamY, dcz = cz - feedCamZ;
        boolean camMoved = dcx * dcx + dcy * dcy + dcz * dcz > FEED_RESORT_DIST2;

        // Reuse the cached feed unless the set changed, the cap grew, or the camera
        // moved enough to shift the nearest cut. The fed PlacedLight INSTANCES are
        // stable and their fields stay live via the rolling scan, so a reused
        // ordering never stales the light data — only the nearest-first cut, which
        // tolerates a couple of blocks of drift.
        if (setGeneration == feedGeneration && max == feedMax && !camMoved)
        {
            lastActiveCount = feed.size();
            return feed;
        }

        feed.clear();
        for (PlacedLight l : byPos.values())
        {
            feed.add(l);
        }
        feed.sort((a, b) -> Double.compare(dist2(a, cx, cy, cz), dist2(b, cx, cy, cz)));
        if (feed.size() > max)
        {
            feed.subList(max, feed.size()).clear();
        }

        feedGeneration = setGeneration;
        feedMax = max;
        feedCamX = cx;
        feedCamY = cy;
        feedCamZ = cz;
        lastActiveCount = feed.size();
        return feed;
    }

    private static double dist2(PlacedLight l, double cx, double cy, double cz)
    {
        double dx = l.x - cx, dy = l.y - cy, dz = l.z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Create or update the auto-light for one emitting block, keeping its id. */
    private static void upsert(long key, int wx, int wy, int wz, BlockLightDefs.Def def)
    {
        PlacedLight l = byPos.get(key);
        if (l == null)
        {
            l = PlacedLight.point();
            l.name = "auto";
            byPos.put(key, l);
            setGeneration++; // new light -> invalidate the cached nearest feed
        }
        l.x = wx + 0.5;
        l.y = wy + 0.5;
        l.z = wz + 0.5;
        l.r = def.r;
        l.g = def.g;
        l.b = def.b;
        // Global brightness / reach sliders scale the hardcoded table values. A
        // slider change is picked up as the rolling pass re-scans this block (within
        // ~1s); no per-frame work and no full rescan.
        l.intensity = def.intensity * LightConfig.autoLightIntensity();
        l.radius = def.radius * LightConfig.autoLightReach();
        l.autoShadowEligible = def.shadows; // redstone dust etc. never take a slot
        // type stays POINT; LightDriver sets the live shadows flag each frame.
    }
}
