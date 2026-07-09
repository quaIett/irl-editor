package org.qualet.irl.light.shadow;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.MovingBlockRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Captures the REAL model geometry of a shadow occluder into a flat CPU triangle
 * buffer, so {@link ShadowRenderer} can rasterize a faithful silhouette into its
 * depth maps — the 1.21.11 equivalent of the {@code dispatcher.render(...,
 * Immediate, ...)} path used on the 1.20.4 / 1.21.1 redactor lines.
 *
 * <p><b>Why this exists.</b> 1.21.11 (post 1.21.5 RenderPipeline + 1.21.9
 * {@code EntityRenderState}) no longer rasterizes entities through an immediate
 * {@code VertexConsumerProvider}; entity renderers instead <i>submit deferred
 * commands</i> to an {@link OrderedRenderCommandQueue} that MC later executes
 * against its {@code GpuDevice}. There is no reachable immediate-to-FBO path, so
 * the earlier port fell back to an AABB box occluder. But the queue is an
 * <i>interface</i>, and MC's executor simply does {@code model.setAngles(state);
 * model.render(matrices, vertexConsumer, ...)}. So a capturing queue can run the
 * real entity render and redirect its geometry into a position-recording
 * {@link VertexConsumer}, reconstructing the true model silhouette — no GL is
 * issued here (the consumer just records floats); {@link ShadowRenderer} draws
 * the captured triangles itself.</p>
 *
 * <p><b>Coordinate space.</b> The entity is rendered at its lerped world position
 * MINUS the pass's light-relative anchor {@code A = round(lightPos)}
 * ({@link ShadowRenderer#currentOriginX()}), so captured entity vertices come out in
 * anchor-relative {@code world - A} space — matching the anchor-relative light view
 * the depth bake uses (eye {@code = L - A}), the same {@code A} the core block /
 * cutout arms subtract. The subtraction is done in DOUBLE before {@code
 * EntityRenderManager.render}'s matrix narrows to float, keeping sub-block precision
 * at large world coordinates. {@code ModelPart}/{@code quad} hand the consumer
 * positions already transformed by the matrix, so the consumer applies no matrix of
 * its own. (The dead {@link #captureCutoutBlockTris} helper still pre-translates to
 * the absolute block origin; it has no caller on this line — the live cutout bake is
 * core {@code ShadowRenderer}'s.)</p>
 *
 * <p>Single-threaded (render thread); one shared {@link Capture} + {@link CaptureQueue}.</p>
 */
public final class OccluderGeometryCapturer
{
    private OccluderGeometryCapturer()
    {}

    private static final Capture CAPTURE = new Capture();
    private static final CaptureQueue QUEUE = new CaptureQueue(CAPTURE);
    /** Reused, so a held armor-stand / mob silhouette pose is deterministic. */
    private static final CameraRenderState CAMERA_STATE = new CameraRenderState();
    /** Entity ids whose render threw hard inside MC's entity pipeline (which wraps
     *  the failure in a CrashReport + CrashException). Skipped on later bakes so a
     *  consistently-broken (e.g. buggy modded) entity is not re-attempted — and its
     *  crash report rebuilt — every frame. Per-entity granularity; a healthy entity
     *  is never skipped. (Id reuse after despawn could skip a new entity sharing the
     *  id; benign — at worst one missing shadow, vs. per-frame crash-report spam.) */
    private static final IntOpenHashSet failedEntities = new IntOpenHashSet();

    private static final float[] EMPTY = new float[0];

    /**
     * Capture one entity's model as world-space POSITION triangles (3 floats per
     * vertex, 6 vertices per quad). Runs MC's real entity render through the
     * capturing queue. Returns an empty array on any failure (degrade to no
     * shadow for that entity — never crash the bake).
     */
    public static float[] captureEntityTris(Entity entity, float tickDelta)
    {
        if (entity == null || failedEntities.contains(entity.getId()))
        {
            return EMPTY;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null)
        {
            return EMPTY;
        }
        EntityRenderManager mgr = mc.getEntityRenderDispatcher();
        if (mgr == null)
        {
            return EMPTY;
        }

        try
        {
            double wx = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX());
            double wy = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY());
            double wz = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ());

            EntityRenderState state = mgr.getAndUpdateRenderState(entity, tickDelta);
            if (state == null)
            {
                return EMPTY;
            }

            // Orientation/pos for any camera-aligned sub-render; bodies of living
            // entities pose from the render state itself. Default (identity) is
            // safe when the camera is not yet configured.
            Camera cam = mgr.camera;
            if (cam != null)
            {
                CAMERA_STATE.pos = cam.getCameraPos();
                CAMERA_STATE.orientation.set(cam.getRotation());
                CAMERA_STATE.initialized = true;
            }

            CAPTURE.reset();
            // Subtract the pass's light-relative anchor A = round(lightPos) (worldPos
            // - A) in DOUBLE before EntityRenderManager.render's MatrixStack.translate
            // narrows to float, so captured vertices come out in anchor-relative
            // (world - A) space — matching the anchor-relative view eye (L - A) and the
            // same A the core block / cutout arms subtract. Without it the ~1e5
            // absolute coords fall outside the light frustum and the entity casts no
            // shadow. The caster (RedactorEntityCasterSource) records this A alongside
            // the cached geometry so a later light in a different cell can re-base it.
            double ox = ShadowRenderer.currentOriginX();
            double oy = ShadowRenderer.currentOriginY();
            double oz = ShadowRenderer.currentOriginZ();
            mgr.render(state, CAMERA_STATE, wx - ox, wy - oy, wz - oz, new MatrixStack(), QUEUE);
            return CAPTURE.toTris(false);
        }
        catch (Throwable t)
        {
            // A single bad entity must not abort the bake. MC wraps a render
            // failure in a CrashReport + CrashException, so skip this entity on
            // later bakes instead of rebuilding that report every frame.
            failedEntities.add(entity.getId());
            return EMPTY;
        }
    }

    /**
     * Capture one cutout block as world-space POSITION+UV triangles (5 floats per
     * vertex) through MC's real {@link BlockRenderManager#renderBlock} tessellation
     * (neighbour-culled, correct atlas UVs) — replacing the earlier hand-rolled
     * {@code BakedQuad} extraction. {@link ShadowRenderer} draws these with the
     * atlas-sampling, alpha-discarding cutout program. Empty array on failure.
     */
    public static float[] captureCutoutBlockTris(BlockRenderView world, BlockRenderManager brm,
                                                 BlockPos pos, BlockState state, Random random)
    {
        if (world == null || brm == null || state == null)
        {
            return EMPTY;
        }
        try
        {
            BlockStateModel model = brm.getModel(state);
            if (model == null)
            {
                return EMPTY;
            }
            random.setSeed(state.getRenderingSeed(pos));
            List<BlockModelPart> parts = model.getParts(random);
            if (parts == null || parts.isEmpty())
            {
                return EMPTY;
            }

            MatrixStack ms = new MatrixStack();
            // Pre-translate to the absolute block origin: BlockModelRenderer only
            // applies the block's model offset, never its position.
            ms.translate(pos.getX(), pos.getY(), pos.getZ());

            CAPTURE.reset();
            brm.renderBlock(state, pos, world, ms, CAPTURE, true, parts);
            return CAPTURE.toTris(true);
        }
        catch (Throwable t)
        {
            return EMPTY;
        }
    }

    // --- Position(+UV) recording VertexConsumer -------------------------------

    /**
     * Records committed vertices as (x, y, z, u, v) and triangulates the implied
     * QUADS (every 4 vertices -> 2 triangles). Positions arrive already
     * transformed (see {@link ModelPart.Cuboid#renderCuboid} and the default
     * {@link VertexConsumer#quad}); only POSITION (+ UV for cutout) is kept, every
     * other element is dropped. A vertex is committed when the next {@link
     * #vertex(float, float, float)} starts or {@link #toTris} flushes the last.
     */
    private static final class Capture implements VertexConsumer
    {
        private final FloatArrayList verts = new FloatArrayList(2048);
        private float cx, cy, cz, cu, cv;
        private boolean pending;

        void reset()
        {
            verts.clear();
            pending = false;
        }

        private void commit()
        {
            if (pending)
            {
                verts.add(cx);
                verts.add(cy);
                verts.add(cz);
                verts.add(cu);
                verts.add(cv);
                pending = false;
            }
        }

        /** Flush the last pending vertex and triangulate. {@code withUv} keeps the
         *  UV pair (cutout, stride 5) else POSITION only (entities, stride 3). */
        float[] toTris(boolean withUv)
        {
            commit();
            int n = verts.size() / 5;       // committed vertices
            int quads = n / 4;              // 4 verts per quad (model + block + item)
            if (quads == 0)
            {
                return EMPTY;
            }
            int per = withUv ? 5 : 3;
            float[] out = new float[quads * 6 * per];
            float[] v = verts.elements();
            int w = 0;
            for (int q = 0; q < quads; q++)
            {
                int b = q * 4 * 5; // base float index of this quad's first vertex
                // triangle (0,1,2) then (0,2,3)
                w = put(out, w, v, b, 0, withUv);
                w = put(out, w, v, b, 1, withUv);
                w = put(out, w, v, b, 2, withUv);
                w = put(out, w, v, b, 0, withUv);
                w = put(out, w, v, b, 2, withUv);
                w = put(out, w, v, b, 3, withUv);
            }
            return out;
        }

        private static int put(float[] out, int w, float[] v, int quadBase, int corner, boolean withUv)
        {
            int s = quadBase + corner * 5;
            out[w++] = v[s];
            out[w++] = v[s + 1];
            out[w++] = v[s + 2];
            if (withUv)
            {
                out[w++] = v[s + 3];
                out[w++] = v[s + 4];
            }
            return w;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z)
        {
            commit();
            cx = x; cy = y; cz = z; cu = 0f; cv = 0f;
            pending = true;
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v)
        {
            cu = u; cv = v;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha)
        {
            return this;
        }

        @Override
        public VertexConsumer color(int argb)
        {
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z)
        {
            return this;
        }

        @Override
        public VertexConsumer lineWidth(float width)
        {
            return this;
        }
    }

    // --- Capturing render-command queue ---------------------------------------

    /**
     * An {@link OrderedRenderCommandQueue} that, instead of deferring, immediately
     * renders submitted model geometry into the {@link Capture} consumer. Only the
     * geometry-bearing submits (model / model part / item) are handled; text /
     * labels / fire / leashes / shadow pieces / custom layers are dropped (they
     * are not part of an occluder silhouette). Mirrors {@code
     * OrderedRenderCommandQueueImpl}'s method surface.
     */
    private static final class CaptureQueue implements OrderedRenderCommandQueue
    {
        private final Capture capture;

        CaptureQueue(Capture capture)
        {
            this.capture = capture;
        }

        @Override
        public RenderCommandQueue getBatchingQueue(int order)
        {
            return this; // no batching: we render straight into the capture
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, MatrixStack matrices, RenderLayer renderLayer,
                                    int light, int overlay, int tintedColor, Sprite sprite, int outlineColor,
                                    ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay)
        {
            if (model == null)
            {
                return;
            }
            try
            {
                model.setAngles(state);
                model.render(matrices, capture, light, overlay, tintedColor);
            }
            catch (Throwable ignored)
            {
                // skip one bad model; the silhouette degrades, the bake survives
            }
        }

        @Override
        public void submitModelPart(ModelPart part, MatrixStack matrices, RenderLayer renderLayer, int light, int overlay,
                                    Sprite sprite, boolean sheeted, boolean hasGlint, int tintedColor,
                                    ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay, int i)
        {
            if (part == null)
            {
                return;
            }
            try
            {
                part.render(matrices, capture, light, overlay, tintedColor);
            }
            catch (Throwable ignored)
            {
            }
        }

        @Override
        public void submitItem(MatrixStack matrices, ItemDisplayContext displayContext, int light, int overlay,
                               int outlineColors, int[] tintLayers, List<BakedQuad> quads, RenderLayer renderLayer,
                               ItemRenderState.Glint glintType)
        {
            if (quads == null || quads.isEmpty())
            {
                return;
            }
            try
            {
                MatrixStack.Entry e = matrices.peek();
                for (int qi = 0, n = quads.size(); qi < n; qi++)
                {
                    BakedQuad q = quads.get(qi);
                    if (q != null)
                    {
                        // default quad(...) transforms by the matrix + feeds our
                        // vertex()/texture() -> captured in world space.
                        capture.quad(e, q, 1f, 1f, 1f, 1f, light, overlay);
                    }
                }
            }
            catch (Throwable ignored)
            {
            }
        }

        // --- no-op submits (not part of an occluder silhouette) ---------------

        @Override
        public void submitShadowPieces(MatrixStack matrices, float shadowRadius, List<EntityRenderState.ShadowPiece> shadowPieces)
        {
        }

        @Override
        public void submitLabel(MatrixStack matrices, Vec3d nameLabelPos, int y, Text label, boolean notSneaking,
                                int light, double squaredDistanceToCamera, CameraRenderState cameraState)
        {
        }

        @Override
        public void submitText(MatrixStack matrices, float x, float y, OrderedText text, boolean dropShadow,
                               TextRenderer.TextLayerType layerType, int light, int color, int backgroundColor, int outlineColor)
        {
        }

        @Override
        public void submitFire(MatrixStack matrices, EntityRenderState renderState, Quaternionf rotation)
        {
        }

        @Override
        public void submitLeash(MatrixStack matrices, EntityRenderState.LeashData leashData)
        {
        }

        @Override
        public void submitBlock(MatrixStack matrices, BlockState state, int light, int overlay, int outlineColor)
        {
            // Block held/worn by a living mob (enderman carried block, iron-golem
            // poppy, copper-golem head, ...). Resolve its model and tessellate it
            // into the capture so the attachment casts a silhouette too.
            if (state == null || state.getRenderType() == BlockRenderType.INVISIBLE)
            {
                return;
            }
            try
            {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc == null)
                {
                    return;
                }
                BlockStateModel model = mc.getBlockRenderManager().getModel(state);
                if (model != null)
                {
                    BlockModelRenderer.render(matrices.peek(), capture, model, 1f, 1f, 1f, light, overlay);
                }
            }
            catch (Throwable ignored)
            {
            }
        }

        @Override
        public void submitMovingBlock(MatrixStack matrices, MovingBlockRenderState state)
        {
            // Falling/piston blocks are not living-mob attachments (their entities
            // are not collected as occluders), so nothing to capture here.
        }

        @Override
        public void submitBlockStateModel(MatrixStack matrices, RenderLayer renderLayer, BlockStateModel model,
                                          float r, float g, float b, int light, int overlay, int outlineColor)
        {
            // Block-state model attached to a living mob (snow-golem pumpkin head,
            // mooshroom back mushrooms, ...) — tessellate it into the capture.
            if (model == null)
            {
                return;
            }
            try
            {
                BlockModelRenderer.render(matrices.peek(), capture, model, r, g, b, light, overlay);
            }
            catch (Throwable ignored)
            {
            }
        }

        @Override
        public void submitCustom(MatrixStack matrices, RenderLayer renderLayer, OrderedRenderCommandQueue.Custom customRenderer)
        {
        }

        @Override
        public void submitCustom(OrderedRenderCommandQueue.LayeredCustom customRenderer)
        {
        }
    }
}
