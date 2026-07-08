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
 * {@link OccluderGeometryCapturer} and appended as world-space POSITION triangles
 * into the raw-GL depth batch. This is the 1.21.11 equivalent of redactor-main's
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
     * Per-bake cache of each caster's captured world-space POSITION triangles, keyed
     * by {@link Entity#getId()}. The shared orchestration calls {@link #emitOccluder}
     * up to six times per entity per bake (once per point-cube face, plus every spot
     * pass the entity is shortlisted for), so the expensive model capture must run at
     * most once per entity per bake and be reused across passes — the geometry is
     * view-independent world space. Cleared at the start of every {@link #collect}
     * (once per bake), reproducing the old port {@code ShadowRenderer.beginBake} reset
     * of its {@code entityGeom} map now that the caster owns the cache. An empty array
     * is cached for an entity that captured nothing, so it is not re-attempted within
     * the bake. (The persistent cross-bake fail-skip for entities that throw hard in
     * MC's pipeline lives in {@link OccluderGeometryCapturer#captureEntityTris}.)
     */
    private final Int2ObjectOpenHashMap<float[]> entityGeom = new Int2ObjectOpenHashMap<>();

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
        // raw-GL depth accumulator (INVARIANT-1 exempt: absolute world-space geometry
        // drawn through the depth program's own uViewProj, never RenderSystem's live
        // modelview). The shared wrapper opens/flushes the batch and isolates a throw,
        // so this method neither draws, flushes, nor catches — it only APPENDS this
        // caster's captured triangles.
        if (!(caster instanceof Entity))
        {
            return;
        }
        Entity entity = (Entity) caster;

        // Capture once per entity per bake, reuse across the (up to six) passes this
        // entity is drawn in. captureEntityTris returns an empty array (never null) for
        // an entity that captured nothing or is on the capturer's fail-skip list; cache
        // that too so it is not re-attempted this bake.
        float[] tris = entityGeom.get(entity.getId());
        if (tris == null)
        {
            tris = OccluderGeometryCapturer.captureEntityTris(entity, tickDelta);
            entityGeom.put(entity.getId(), tris);
        }
        if (tris.length == 0)
        {
            return;
        }
        ((RawOccluderBatch) batch).append(tris);
    }
}
