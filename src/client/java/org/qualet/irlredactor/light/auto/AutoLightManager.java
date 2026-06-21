package org.qualet.irlredactor.light.auto;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.qualet.irlredactor.light.IrisShadersState;
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

    /** Number of auto-lights currently tracked (before the nearest-first cap). */
    public static int count()
    {
        return byPos.size();
    }

    /** Forget all auto-lights + reset the pass (world left / feature off). */
    public static void clear()
    {
        byPos.clear();
        seenThisPass.clear();
        feed.clear();
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
            sectionsThisTick += scanChunk(chunk, chunkX, chunkZ);
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
    private static int scanChunk(WorldChunk chunk, int chunkX, int chunkZ)
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

                        long key = BlockPos.asLong(wx, wy, wz);
                        seenThisPass.add(key);
                        upsert(key, wx, wy, wz, def);
                    }
                }
            }
        }
        return fullScanned;
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
