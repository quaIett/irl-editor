package org.qualet.irl.light.shadow;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The redactor (BBS-free) {@link ShadowCasterSource}: world entities only, drawn
 * through the real vanilla {@link EntityRenderDispatcher} — the distinguishing
 * feature of redactor-main vs the box-occluder port. All casters are dynamic
 * (isStatic=false, staticHash=0): the model-block / film-replay arms were a
 * BBS-form feature and live in the IRLite source instead.
 *
 * <p>This is the variant-specific CAST half of the seam; the orchestration
 * ({@link ShadowBaker}/{@link ShadowRenderer}) is variant-agnostic and unchanged.
 */
public final class RedactorEntityCasterSource implements ShadowCasterSource
{
    /** Max distance (from the camera) at which an entity is considered a caster. */
    private static final double COLLECT_DIST = 72.0;
    private static final double COLLECT_DIST_SQ = COLLECT_DIST * COLLECT_DIST;
    private static final int FULL_LIGHT = LightmapTextureManager.pack(15, 15);

    @Override
    public void collect(ClientWorld world, Vec3d camPos, float tickDelta, OccluderSink sink)
    {
        double camX = camPos.x, camY = camPos.y, camZ = camPos.z;

        // --- world entities (vanilla render path) ---
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
        // BBS-free engine: only vanilla world entities cast shadows, so the type
        // switch has a single arm. The batch is the shared entity Immediate.
        ImmediateOccluderBatch b = (ImmediateOccluderBatch) batch;
        drawEntity((Entity) caster, b.matrices(), b.immediate(), tickDelta);
    }

    private static void drawEntity(Entity entity, MatrixStack matrices, VertexConsumerProvider.Immediate immediate, float tickDelta)
    {
        // Light-relative bake (irl-core): the shared ShadowRenderer view now looks
        // from the pass anchor (eye = L - A), so caster geometry must be emitted
        // relative to that same anchor. Subtract currentOrigin* in double BEFORE
        // dispatcher.render's float cast, exactly as the IRLite source does — else
        // absolute cx/cy/cz land ~A (~1e5) outside the light frustum and the
        // entity's shadow vanishes far from the world origin.
        double ox = ShadowRenderer.currentOriginX();
        double oy = ShadowRenderer.currentOriginY();
        double oz = ShadowRenderer.currentOriginZ();
        double cx = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()) - ox;
        double cy = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()) - oy;
        double cz = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ()) - oz;

        EntityRenderDispatcher dispatcher = MinecraftClient.getInstance().getEntityRenderDispatcher();
        if (dispatcher != null)
        {
            // 1.21.2: render() no longer takes a yaw arg — the EntityRenderState
            // built internally derives body/head yaw from the entity + tickDelta.
            dispatcher.render(entity, cx, cy, cz, tickDelta, matrices, immediate, FULL_LIGHT);
        }
    }
}
