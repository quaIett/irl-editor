package org.qualet.irlredactor.client.diag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.opengl.GL33C;

/**
 * Dev GPU profiler for the redactor's light/shadow pipeline — the editor-side
 * port of the IRLite addon's {@code qualet.irlite.client.diag.VlProfiler}
 * (keep the two in sync hunk-by-hunk when the shared parts change; the
 * VlSweep level-2 differential sweep is deliberately NOT ported).
 *
 * <p>Unlike the addon (boot-only {@code -Dirlite.profileVl=true}), collection
 * here is a RUNTIME toggle driven from the editor's ImGui "perf" section
 * ({@link #setCollecting}); {@code -Dirlredactor.profileVl=true} merely
 * pre-arms it from launch. Everything is a cheap no-op while off, so the
 * probe/mixin wiring stays installed unconditionally.</p>
 *
 * <p>GL timer queries: every Iris fullscreen pass is bracketed with
 * GL_TIME_ELAPSED via CompositeRendererTimerMixin, and the mod-side shadow
 * bake is bracketed around FramePipeline.frame in GameRendererLightMixin (the
 * bake runs strictly before the Iris pass sequence, so the sibling brackets
 * never nest). The bake bracket is further PARTITIONED into sibling segments
 * at the bakeInner seams by the core-side {@code ShadowBakeProbe} (installed
 * in IRLRedactorClient): bake-head -> bake-spot -> bake-spot-pyr ->
 * bake-spot-evsm -> bake-point -> bake-point-pyr -> bake-point-evsm ->
 * bake-tail, with a derived "bake=SUM" cell in the window line. The probe also
 * feeds per-window WORK COUNTERS printed as a second "[irl-redactor] bake:"
 * line. Results are read back asynchronously from a query pool a few frames
 * later — the pipeline is never stalled — and aggregated into 1-second windows
 * printed to the log and mirrored onto a small HUD overlay.</p>
 */
public final class VlProfiler
{
    /** Runtime collection switch (ImGui "perf" section); the -D flag only
     *  pre-arms it so a launch script can profile from the very first frame.
     *  Volatile: flipped from the ImGui draw (render thread today, but keep
     *  the read cheap and ordered for any future caller). */
    private static volatile boolean collecting = Boolean.getBoolean("irlredactor.profileVl");

    /** Synthetic pass name for the mod-side shadow bake bracket opened at
     *  renderWorld HEAD. The core-side ShadowBakeProbe sections then switch it
     *  to bake-spot/-spot-pyr/-spot-evsm/-point/-point-pyr/-point-evsm/-tail
     *  siblings, so this name ends up covering only the pre-spot-loop head
     *  (collect/prioritize, quality apply, beginBake) — the derived "bake"
     *  total in the window line is the whole-bracket number. */
    public static final String PASS_BAKE = "bake-head";

    /** Every bake segment (the head bracket + the probe-switched siblings)
     *  carries this prefix; the window flush sums them into the derived total. */
    private static final String BAKE_PREFIX = "bake-";

    private static final int POOL_LIMIT = 512;
    private static final long WINDOW_NS = 1_000_000_000L;

    /** Frames after which an unavailable FIFO head is presumed poisoned (a query
     *  whose begin never took) and evicted so the drain can continue behind it. */
    private static final int STUCK_FRAMES = 120;

    /** Iris Program -> pass name, filled at pipeline construction (createProgram
     *  RETURN inject). ALWAYS recorded, even while not collecting — the
     *  pipeline is built at shader load, typically long before the user flips
     *  the toggle; gating this on {@link #collecting} would leave every pass
     *  "unnamed" until an F3+R reload. Weak keys: programs are dropped
     *  wholesale on reload. */
    private static final Map<Object, String> PASS_NAMES = new WeakHashMap<>();

    /** GL query ids not currently in flight (allocated lazily, never deleted). */
    private static final ArrayDeque<Integer> freeQueries = new ArrayDeque<>();
    private static int allocatedQueries;

    /** In-flight queries, FIFO — timer queries complete in submission order, so
     *  the drain can stop at the first unavailable result. */
    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();

    private static int activeQuery = -1;
    private static String activePass;
    private static long frameNo;
    private static int droppedSamples;
    private static int externalTimerSkips;

    private static final Map<String, Stat> window = new HashMap<>();
    private static long windowStart;
    private static volatile List<String> hudLines = List.of();

    /** Per-window work counters fed by the core-side ShadowBakeProbe. Values
     *  are WINDOW SUMS; the flush line prints the window's frame count next to
     *  them so per-frame rates can be read off. Render thread only. */
    private static final Map<String, long[]> counters = new HashMap<>();
    /** Frames seen since the last window flush (normalizes the counters). */
    private static int windowFrames;

    // --- VRAM telemetry (GL_NVX_gpu_memory_info, NVIDIA only) ----------------
    // One "[irl-redactor] vram:" line per window: free dedicated VRAM + the
    // driver's cumulative eviction count/size with per-window deltas.
    private static final int NVX_DEDICATED_VIDMEM = 0x9047;
    private static final int NVX_TOTAL_AVAILABLE = 0x9048;
    private static final int NVX_CURRENT_AVAILABLE = 0x9049;
    private static final int NVX_EVICTION_COUNT = 0x904A;
    private static final int NVX_EVICTED_MEMORY = 0x904B;
    /** null = not probed yet; probed once on the render thread. */
    private static Boolean nvxMemoryInfo;
    private static long lastEvictionCount = -1;
    private static long lastEvictedKb = -1;

    private VlProfiler()
    {}

    private static final class Pending
    {
        final int query;
        final String pass;
        final long frame;

        Pending(int query, String pass, long frame)
        {
            this.query = query;
            this.pass = pass;
            this.frame = frame;
        }
    }

    private static final class Stat
    {
        long sumNs;
        long maxNs;
        int samples;

        void add(long ns)
        {
            this.sumNs += ns;
            this.maxNs = Math.max(this.maxNs, ns);
            this.samples += 1;
        }

        double avgMs()
        {
            return this.samples == 0 ? 0D : this.sumNs / 1_000_000D / this.samples;
        }
    }

    /* ---- runtime toggle (ImGui "perf" section) ----------------------------- */

    public static boolean isCollecting()
    {
        return collecting;
    }

    /** Flip collection. Called from the ImGui draw (render thread, GL context
     *  current). Stopping closes any open bracket and resets the window state;
     *  in-flight queries keep draining in later frameTicks so the pool never
     *  leaks. Starting resets the window clock so the first printed window
     *  never spans the idle gap. */
    public static void setCollecting(boolean on)
    {
        if (collecting == on)
        {
            return;
        }
        if (!on)
        {
            endPass();
            collecting = false;
            window.clear();
            counters.clear();
            hudLines = List.of();
            windowStart = 0L;
            windowFrames = 0;
            droppedSamples = 0;
            externalTimerSkips = 0;
        }
        else
        {
            windowStart = 0L;
            windowFrames = 0;
            collecting = true;
        }
    }

    /* ---- wiring from mixins ------------------------------------------------ */

    /** CompositeRendererTimerMixin, at createProgram RETURN: remembers which Iris
     *  Program object is which pass ("deferred2", "composite1", ...). */
    public static void registerPassName(Object program, String name)
    {
        if (program == null || name == null)
        {
            return;
        }
        PASS_NAMES.put(program, name);
    }

    public static String irisPassName(Object program)
    {
        String name = PASS_NAMES.get(program);
        return name == null ? "unnamed" : name;
    }

    /**
     * Once per frame, at renderWorld HEAD, before any bracket of this frame is
     * opened: drains finished queries and flushes the 1-second stats window.
     * The drain runs even while not collecting so queries submitted before a
     * stop still return to the pool. All GL work happens on the render thread
     * with the context current.
     */
    public static void frameTick()
    {
        frameNo += 1;
        if (pending.isEmpty() && !collecting)
        {
            return;
        }
        drainCompleted();
        if (!collecting)
        {
            return;
        }
        maybeFlushWindow();
        // AFTER the flush: this tick's bake counters land after the flush too,
        // so the window's tick count and its bake-frame span coincide exactly.
        windowFrames += 1;
    }

    /**
     * Core-side ShadowBakeProbe.section: close the current bake bracket and
     * open the named sibling. Fired at the bakeInner seams while the mixin's
     * bake-head bracket is active, so the whole bake stays covered by
     * consecutive sibling brackets (GL_TIME_ELAPSED cannot nest). If the head
     * bracket never opened this frame (F3 timer active, pool exhausted), both
     * halves degrade to the same no-op/skip and the segment samples drop
     * consistently.
     */
    public static void switchPass(String name)
    {
        if (!collecting)
        {
            return;
        }
        endPass();
        beginPass(name);
    }

    /** Core-side ShadowBakeProbe.counter: accumulate into the 1-second window. */
    public static void counter(String key, int amount)
    {
        if (!collecting)
        {
            return;
        }
        counters.computeIfAbsent(key, k -> new long[1])[0] += amount;
    }

    /** Opens a GL_TIME_ELAPSED bracket. No-ops (and drops the sample) if a
     *  bracket is already active — timer queries cannot nest. */
    public static void beginPass(String name)
    {
        if (!collecting)
        {
            return;
        }
        if (activeQuery != -1)
        {
            droppedSamples += 1;
            return;
        }
        // Vanilla GlTimer (F3 GPU% / debug recorder) owns GL_TIME_ELAPSED for the
        // whole frame — a nested begin would GL_INVALID_OPERATION, and ending it
        // would kill the vanilla query and poison our FIFO. Skip those frames.
        if (GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) != 0)
        {
            externalTimerSkips += 1;
            return;
        }
        int query = allocQuery();
        if (query == -1)
        {
            droppedSamples += 1;
            return;
        }
        GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, query);
        if (GL33C.glGetQueryi(GL33C.GL_TIME_ELAPSED, GL33C.GL_CURRENT_QUERY) != query)
        {
            // The begin didn't take (an external timer raced in) — recycle the id
            // and drop the sample instead of tracking a query that never began.
            freeQueries.addLast(query);
            droppedSamples += 1;
            return;
        }
        activeQuery = query;
        activePass = name;
    }

    /** Closes the currently active bracket, if any. Intentionally NOT gated on
     *  {@link #collecting}: a stop between begin and end must still close the
     *  bracket (setCollecting relies on this ordering). */
    public static void endPass()
    {
        if (activeQuery == -1)
        {
            return;
        }
        GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        pending.addLast(new Pending(activeQuery, activePass, frameNo));
        activeQuery = -1;
        activePass = null;
    }

    /* ---- query pool -------------------------------------------------------- */

    private static int allocQuery()
    {
        Integer free = freeQueries.pollFirst();
        if (free != null)
        {
            return free;
        }
        if (allocatedQueries >= POOL_LIMIT)
        {
            return -1;
        }
        allocatedQueries += 1;
        return GL33C.glGenQueries();
    }

    private static void drainCompleted()
    {
        Pending head;
        while ((head = pending.peekFirst()) != null)
        {
            if (GL33C.glGetQueryObjecti(head.query, GL33C.GL_QUERY_RESULT_AVAILABLE) == 0)
            {
                // Self-heal: a head whose begin silently failed would report
                // "unavailable" forever and dam the whole FIFO — evict it and
                // keep draining the valid results queued behind it.
                if (frameNo - head.frame > STUCK_FRAMES)
                {
                    pending.pollFirst();
                    GL33C.glDeleteQueries(head.query);
                    allocatedQueries -= 1;
                    System.out.println("[irl-redactor] gpu: evicted stuck timer query for pass " + head.pass);
                    continue;
                }
                break;
            }
            long ns = GL33C.glGetQueryObjecti64(head.query, GL33C.GL_QUERY_RESULT);
            pending.pollFirst();
            freeQueries.addLast(head.query);
            if (collecting)
            {
                window.computeIfAbsent(head.pass, key -> new Stat()).add(ns);
            }
        }
    }

    /* ---- 1-second window + HUD -------------------------------------------- */

    private static void maybeFlushWindow()
    {
        long now = System.nanoTime();
        if (windowStart == 0L)
        {
            windowStart = now;
            return;
        }
        if (now - windowStart < WINDOW_NS)
        {
            return;
        }

        List<Map.Entry<String, Stat>> entries = new ArrayList<>(window.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().sumNs, a.getValue().sumNs));

        // Derived whole-bake total: the probe partitions the single shadow-bake
        // bracket into bake-* siblings, so their sum restores the whole number.
        long bakeSumNs = 0L;
        int bakeSegments = 0;
        int bakeSamples = 0;
        for (Map.Entry<String, Stat> e : entries)
        {
            if (e.getKey().startsWith(BAKE_PREFIX))
            {
                bakeSumNs += e.getValue().sumNs;
                bakeSamples = Math.max(bakeSamples, e.getValue().samples);
                bakeSegments += 1;
            }
        }

        StringBuilder line = new StringBuilder("[irl-redactor] gpu:");
        List<String> hud = new ArrayList<>();
        int shown = 0;
        if (bakeSegments >= 2 && bakeSamples > 0)
        {
            String cell = String.format(Locale.ROOT, "bake %.2f ms", bakeSumNs / 1_000_000D / bakeSamples);
            line.append(' ').append(cell);
            hud.add(cell);
            shown += 1;
        }
        for (Map.Entry<String, Stat> e : entries)
        {
            Stat s = e.getValue();
            String cell = String.format(Locale.ROOT, "%s %.2f/%.2f ms",
                e.getKey(), s.avgMs(), s.maxNs / 1_000_000D);
            if (shown < 16)
            {
                line.append(shown == 0 ? " " : " | ").append(cell);
            }
            if (hud.size() < 14)
            {
                hud.add(cell);
            }
            shown += 1;
        }
        if (shown == 0)
        {
            line.append(" (no samples — shaders off or no passes yet)");
        }
        if (droppedSamples > 0)
        {
            line.append(" | dropped ").append(droppedSamples);
            droppedSamples = 0;
        }
        if (externalTimerSkips > 0)
        {
            line.append(" | skipped ").append(externalTimerSkips).append(" (F3 timer active)");
            externalTimerSkips = 0;
        }
        System.out.println(line);

        // Second line: the bake work counters (window sums + the frame count
        // to read per-frame rates off), mirrored onto the HUD in packed rows.
        if (!counters.isEmpty())
        {
            List<String> keys = new ArrayList<>(counters.keySet());
            Collections.sort(keys);
            StringBuilder bakeLine = new StringBuilder("[irl-redactor] bake:");
            List<String> cells = new ArrayList<>(keys.size());
            for (String key : keys)
            {
                String cell = key + " " + counters.get(key)[0];
                bakeLine.append(cells.isEmpty() ? " " : " | ").append(cell);
                cells.add(cell);
            }
            bakeLine.append(" | ").append(windowFrames).append(" frames");
            System.out.println(bakeLine);

            for (int i = 0; i < cells.size(); i += 4)
            {
                hud.add(String.join(" | ", cells.subList(i, Math.min(i + 4, cells.size()))));
            }
            hud.add(windowFrames + " frames");
            counters.clear();
        }
        windowFrames = 0;

        String vram = vramLine();
        if (vram != null)
        {
            System.out.println("[irl-redactor] vram: " + vram);
            hud.add(vram);
        }

        hudLines = hud;
        window.clear();
        windowStart = now;
    }

    /** One-line VRAM/eviction snapshot via GL_NVX_gpu_memory_info, or null when
     *  the extension is absent (non-NVIDIA). Values arrive in KiB; the eviction
     *  count/size deltas are per window. */
    private static String vramLine()
    {
        if (nvxMemoryInfo == null)
        {
            nvxMemoryInfo = org.lwjgl.opengl.GL.getCapabilities().GL_NVX_gpu_memory_info;
        }
        if (!nvxMemoryInfo)
        {
            return null;
        }
        long dedicatedKb = GL33C.glGetInteger(NVX_DEDICATED_VIDMEM) & 0xffffffffL;
        long totalKb = GL33C.glGetInteger(NVX_TOTAL_AVAILABLE) & 0xffffffffL;
        long freeKb = GL33C.glGetInteger(NVX_CURRENT_AVAILABLE) & 0xffffffffL;
        long evictions = GL33C.glGetInteger(NVX_EVICTION_COUNT) & 0xffffffffL;
        long evictedKb = GL33C.glGetInteger(NVX_EVICTED_MEMORY) & 0xffffffffL;
        long dEvictions = lastEvictionCount < 0 ? 0 : evictions - lastEvictionCount;
        long dEvictedKb = lastEvictedKb < 0 ? 0 : evictedKb - lastEvictedKb;
        lastEvictionCount = evictions;
        lastEvictedKb = evictedKb;
        return String.format(Locale.ROOT,
            "free %d/%d MiB (total avail %d) | evictions %d (+%d), evicted %d MiB (+%d)",
            freeKb >> 10, dedicatedKb >> 10, totalKb >> 10,
            evictions, dEvictions, evictedKb >> 10, dEvictedKb >> 10);
    }

    /** HudRenderCallback (registered unconditionally in IRLRedactorClient;
     *  draws nothing while not collecting — hudLines is cleared on stop). */
    public static void renderHud(DrawContext ctx)
    {
        if (!collecting)
        {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null)
        {
            return;
        }
        int y = 4;
        for (String lineText : hudLines)
        {
            ctx.drawText(mc.textRenderer, lineText, 4, y, 0xFFE0E0E0, true);
            y += 10;
        }
    }
}
