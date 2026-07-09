package org.qualet.irl.light.shadow;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The redactor (BBS-free) {@link ShadowCasterSource} for the MC 1.21.11 line:
 * world entities only, captured as their REAL model silhouette by
 * {@link OccluderGeometryCapturer} and appended as anchor-relative (world - A)
 * POSITION triangles into the raw-GL depth batch. This is the 1.21.11 equivalent of
 * redactor-main's
 * {@code EntityRenderDispatcher} Immediate draw — the 1.21.5 RenderPipeline +
 * 1.21.9 EntityRenderState rewrites removed the immediate {@code VertexConsumerProvider}
 * rasterization the {@code ImmediateOccluderBatch} rode, so the batch here is the
 * {@link RawOccluderBatch} triangle accumulator instead. All casters are dynamic
 * (isStatic=false, staticHash=0): the model-block / film-replay arms were a
 * BBS-form feature and live in the IRLite source instead.
 *
 * <p>This is the variant-specific CAST half of the seam; the orchestration
 * ({@link ShadowBaker}/{@link ShadowRenderer}) is variant-agnostic and unchanged.
 * The collection logic (what counts as a caster, the distance, the box-derived
 * sphere) is identical to redactor-main; only the {@link #emitOccluder} backend
 * differs (capture-queue triangles vs. an Immediate entity draw).
 */
public final class RedactorEntityCasterSource implements ShadowCasterSource
{
    /** Max distance (from the camera) at which an entity is considered a caster. */
    private static final double COLLECT_DIST = 72.0;
    private static final double COLLECT_DIST_SQ = COLLECT_DIST * COLLECT_DIST;

    /**
     * Per-bake cache of each caster's captured POSITION triangles, keyed by
     * {@link Entity#getId()}. The shared orchestration calls {@link #emitOccluder} up
     * to six times per entity per bake (once per point-cube face, plus every spot pass
     * the entity is shortlisted for), so the expensive model capture must run at most
     * once per entity per bake and be reused across those passes.
     *
     * <p><b>Anchor.</b> Under the light-relative bake each pass draws with the eye
     * {@code L - A} for that light's anchor {@code A = round(lightPos)}, and {@link
     * OccluderGeometryCapturer#captureEntityTris} captures the entity RELATIVE to the
     * anchor in force at capture time ({@code v - A0}). That makes the cached geometry
     * anchor-DEPENDENT, so — unlike a point light's six faces, which share one
     * {@code A} — a second shadow light in a different cell cannot reuse the raw
     * triangles verbatim. Each entry therefore also stores its capture anchor
     * {@code A0}; {@link #emitOccluder} re-bases the triangles onto the current pass's
     * anchor by the exact integer delta {@code A0 - A_pass} before appending. Without
     * this the entity shadow would be offset by that per-cell delta for every light
     * after the first (block shadows, keyed per-light, would stay correct — a visibly
     * split shadow).</p>
     *
     * <p>Cleared at the start of every {@link #collect} (once per bake), reproducing
     * the old port {@code ShadowRenderer.beginBake} reset of its {@code entityGeom}
     * map now that the caster owns the cache. An empty capture is cached too, so it is
     * not re-attempted within the bake. (The persistent cross-bake fail-skip for
     * entities that throw hard in MC's pipeline lives in {@link
     * OccluderGeometryCapturer#captureEntityTris}.)</p>
     */
    private final Int2ObjectOpenHashMap<Captured> entityGeom = new Int2ObjectOpenHashMap<>();

    /** Reusable scratch for re-basing a cached capture onto a different pass anchor
     *  (grows to the largest entity's triangle count, reused single-threaded). */
    private float[] rebase = new float[0];

    /** A cached entity capture: its POSITION triangles (stride 3) relative to the
     *  anchor {@code A0 = (ax, ay, az)} that was in force when it was captured. The
     *  anchor is {@code Math.round(lightPos)}, so it is integer-valued and the re-base
     *  delta to another pass anchor is an exact integer. */
    private static final class Captured
    {
        final float[] tris;
        final double ax, ay, az;

        Captured(float[] tris, double ax, double ay, double az)
        {
            this.tris = tris;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
        }
    }

    @Override
    public void collect(ClientWorld world, Vec3d camPos, float tickDelta, OccluderSink sink)
    {
        // New bake: drop the previous bake's captured geometry so a moved / re-posed
        // entity is re-captured this bake (keyed by entity id, reused across passes).
        entityGeom.clear();

        double camX = camPos.x, camY = camPos.y, camZ = camPos.z;

        // --- world entities (real model capture path) ---
        for (Entity entity : world.getEntities())
        {
            if (!(entity instanceof LivingEntity) && !(entity instanceof ItemEntity))
            {
                continue;
            }

            double ex = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX());
            double ey = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY());
            double ez = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ());
            double dx = ex - camX, dy = ey - camY, dz = ez - camZ;
            if (dx * dx + dy * dy + dz * dz > COLLECT_DIST_SQ)
            {
                continue;
            }

            // emitFromBox raises the center to mid-height and derives the
            // circumscribing (box-diagonal) radius (INVARIANT 5); over-cap casters
            // are silently dropped by the sink. Dynamic -> isStatic=false, hash 0.
            sink.emitFromBox(entity, CasterType.ENTITY, false, ex, ey, ez, entity.getBoundingBox(), 1f, 0L);
        }
    }

    @Override
    public void emitOccluder(Object caster, int type, float tickDelta, OccluderBatch batch)
    {
        // BBS-free engine: only vanilla world entities cast shadows. The batch is the
        // raw-GL depth accumulator (INVARIANT-1 exempt: anchor-relative (world - A)
        // geometry drawn through the depth program's own uViewProj, never RenderSystem's
        // live modelview). The shared wrapper opens/flushes the batch and isolates a
        // throw, so this method neither draws, flushes, nor catches — it only APPENDS
        // this caster's captured triangles.
        if (!(caster instanceof Entity))
        {
            return;
        }
        Entity entity = (Entity) caster;

        // This pass's light-relative anchor A = round(lightPos) (set by ShadowRenderer
        // in beginSpot/beginPointFace). captureEntityTris subtracts this SAME anchor
        // internally, so a fresh capture is relative to (pax, pay, paz).
        double pax = ShadowRenderer.currentOriginX();
        double pay = ShadowRenderer.currentOriginY();
        double paz = ShadowRenderer.currentOriginZ();

        // Capture once per entity per bake, reuse across the (up to six) passes this
        // entity is drawn in. captureEntityTris returns an empty array (never null) for
        // an entity that captured nothing or is on the capturer's fail-skip list; cache
        // that (with the capture anchor) too so it is not re-attempted this bake.
        Captured cached = entityGeom.get(entity.getId());
        if (cached == null)
        {
            float[] tris = OccluderGeometryCapturer.captureEntityTris(entity, tickDelta);
            cached = new Captured(tris, pax, pay, paz);
            entityGeom.put(entity.getId(), cached);
        }
        if (cached.tris.length == 0)
        {
            return;
        }

        // The cached triangles are relative to the anchor A0 in force when this entity
        // was first captured this bake. A is per-light, so a second shadow light in a
        // different cell reuses this cache with a different pass anchor. Re-base onto
        // the current anchor: (v - A0) + (A0 - A_pass) = v - A_pass. Both anchors are
        // Math.round(...) integers, so (A0 - A_pass) is an EXACT integer delta and the
        // shifted vertices stay small-magnitude (float-precise).
        if (pax == cached.ax && pay == cached.ay && paz == cached.az)
        {
            // Same anchor (a point light's six faces, or a same-cell light): zero-copy.
            ((RawOccluderBatch) batch).append(cached.tris);
            return;
        }
        float dx = (float) (cached.ax - pax);
        float dy = (float) (cached.ay - pay);
        float dz = (float) (cached.az - paz);
        float[] src = cached.tris;
        int n = src.length;
        if (rebase.length < n)
        {
            rebase = new float[n];
        }
        float[] dst = rebase;
        for (int i = 0; i + 3 <= n; i += 3)   // POSITION triangles, stride 3
        {
            dst[i]     = src[i]     + dx;
            dst[i + 1] = src[i + 1] + dy;
            dst[i + 2] = src[i + 2] + dz;
        }
        ((RawOccluderBatch) batch).append(dst, 0, n);
    }
}
