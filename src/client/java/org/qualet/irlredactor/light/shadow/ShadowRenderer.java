package org.qualet.irlredactor.light.shadow;

import it.unimi.dsi.fastutil.longs.LongSet;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.List;

/**
 * Bakes spot/point shadow depth maps into the per-light atlas tiles / cube faces.
 *
 * <p><b>1.21.11 port status — content rasterization STUBBED.</b> The 1.20.4
 * baker rendered occluders into a custom depth FBO with custom view/projection
 * matrices set on {@code RenderSystem}, using vanilla's entity dispatcher and a
 * cached {@code VertexBuffer} of block quads. Every one of those primitives was
 * removed or made incompatible by the 1.21.5 render-pipeline rework and the
 * 1.21.9 {@code EntityRenderState} rewrite:</p>
 * <ul>
 *   <li>{@code RenderSystem.setShader / applyModelViewMatrix / setProjectionMatrix(Matrix4f,..)} — gone;</li>
 *   <li>{@code net.minecraft.client.gl.VertexBuffer} — removed entirely;</li>
 *   <li>{@code EntityRenderDispatcher} → {@code EntityRenderManager} whose
 *       {@code render(..)} now only ENQUEUES onto a deferred {@code OrderedRenderCommandQueue}
 *       (no immediate rasterization into an arbitrary FBO);</li>
 *   <li>{@code BlockRenderManager.renderBlock(..)} last arg changed and
 *       {@code RenderLayers.getBlockLayer(BlockState)} was removed.</li>
 * </ul>
 *
 * <p>What this Stage-1 port KEEPS working: the raw-GL FBO/viewport/scissor/clear
 * management (so the depth textures allocate, bind as Iris samplers, and stay in
 * a valid cleared = "no occluder" state) and the JOML view/projection per light
 * (computed here, ready for the Stage-2 raw-GL depth program). The bake DRIVER
 * ({@link ShadowBaker}) still runs its cull / cache / sticky-tile logic and
 * assigns each light its shadow tile in the SSBO; only the actual occluder
 * RASTERIZATION ({@link #renderCaster}, {@link #renderBlocksDepth}) is a no-op.</p>
 *
 * <p>TODO(Stage 2): rasterize block (and entity-box) occluders into the depth
 * FBOs via a self-contained raw-GL depth program (a trivial {@code proj*view*pos}
 * vertex shader + a reusable VAO/VBO), bypassing MC's render pipeline entirely
 * for version robustness. TODO(Stage 3): full entity-model occluders via the new
 * deferred {@code OrderedRenderCommandQueue} + the {@code .irlights} GLSL patcher
 * for actually-visible light.</p>
 *
 * <p>Net effect on 1.21.11: the collect→bake→SSBO→Iris-sampler seam is intact and
 * the engine boots clean; shadows are simply absent (every map reads far-plane),
 * which is identical to the pre-patcher state where no light was visible anyway.</p>
 */
public final class ShadowRenderer
{
    private static final float NEAR = 0.05f;

    private static boolean inPass = false;
    /** True once {@link #savePassState} has snapshotted the original GL state
     *  this bake; passes within one bake share the single snapshot. */
    private static boolean passStateSaved = false;

    private static int savedFbo;
    private static final int[] savedViewport = new int[4];
    private static boolean savedScissorEnabled;
    private static final int[] savedScissorBox = new int[4];
    private static boolean savedDepthMask;

    /** The current light's view/projection. Computed each pass (cheap) and kept
     *  for the Stage-2 raw-GL depth draw; unused while rasterization is stubbed. */
    private static final Matrix4f currentView = new Matrix4f();
    private static final Matrix4f currentProj = new Matrix4f();

    public static final int CASTER_ENTITY = 0;
    public static final int CASTER_MODEL_BLOCK = 1;
    public static final int CASTER_REPLAY = 2;

    private ShadowRenderer()
    {}

    /** Call once at the start of a bake, before any begin*()/endPass(). Arms a
     *  fresh snapshot of the GL state on the first pass of this bake. */
    public static void beginBake()
    {
        passStateSaved = false;
    }

    /**
     * Begin a spot depth pass into the live ({@code toStatic} false) or static
     * ({@code toStatic} true) atlas tile. {@code clear} false keeps the tile's
     * current depth (used for the dynamic-caster overlay on top of a static base
     * restored by {@link SpotlightDepthAtlas#copyStaticToLive}).
     */
    public static void beginSpot(int tile,
                                 float lpx, float lpy, float lpz,
                                 float ldx, float ldy, float ldz,
                                 float range, float outerDeg,
                                 boolean toStatic, boolean clear)
    {
        savePassState();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, SpotlightDepthAtlas.getFboId(toStatic));
        int px = SpotlightDepthAtlas.tilePixelX(tile);
        int py = SpotlightDepthAtlas.tilePixelY(tile);
        int ts = SpotlightDepthAtlas.TILE_SIZE;
        GL11.glViewport(px, py, ts, ts);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, py, ts, ts);
        if (clear)
        {
            GL11.glDepthMask(true);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float fovDeg = Math.max(outerDeg, 1.0f);
        float far = Math.max(range, NEAR + 0.1f);
        currentProj.identity().perspective((float) Math.toRadians(fovDeg), 1.0f, NEAR, far);

        Vector3f up = pickStableUp(ldy);
        currentView.identity().lookAt(
            lpx, lpy, lpz,
            lpx + ldx, lpy + ldy, lpz + ldz,
            up.x, up.y, up.z
        );
    }

    /**
     * Begin a point-cube face depth pass into the live or static array (see
     * {@link #beginSpot} for the {@code toStatic}/{@code clear} semantics).
     */
    public static void beginPointFace(int slot, int face,
                                      float lpx, float lpy, float lpz,
                                      float radius,
                                      boolean toStatic, boolean clear)
    {
        savePassState();

        PointShadowArray.bindFaceForRender(slot, face, toStatic);
        int fs = PointShadowArray.FACE_SIZE;
        GL11.glViewport(0, 0, fs, fs);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, 0, fs, fs);
        if (clear)
        {
            GL11.glDepthMask(true);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float far = Math.max(radius, NEAR + 0.1f);
        currentProj.identity().perspective((float) Math.toRadians(90.0), 1.0f, NEAR, far);

        float dx, dy, dz, ux, uy, uz;
        switch (face)
        {
            case 0:  dx =  1; dy =  0; dz =  0; ux = 0; uy = -1; uz =  0; break; // +X
            case 1:  dx = -1; dy =  0; dz =  0; ux = 0; uy = -1; uz =  0; break; // -X
            case 2:  dx =  0; dy =  1; dz =  0; ux = 0; uy =  0; uz =  1; break; // +Y
            case 3:  dx =  0; dy = -1; dz =  0; ux = 0; uy =  0; uz = -1; break; // -Y
            case 4:  dx =  0; dy =  0; dz =  1; ux = 0; uy = -1; uz =  0; break; // +Z
            default: dx =  0; dy =  0; dz = -1; ux = 0; uy = -1; uz =  0; break; // -Z
        }

        currentView.identity().lookAt(
            lpx, lpy, lpz,
            lpx + dx, lpy + dy, lpz + dz,
            ux, uy, uz
        );
    }

    /**
     * Rasterize one occluder into the bound depth FBO.
     *
     * <p>TODO(1.21.11 Stage 3): STUBBED. Entity occluders were drawn through
     * {@code EntityRenderDispatcher.render(entity, x,y,z, yaw, tickDelta, matrices,
     * immediate, light)} which immediately rasterized into the bound FBO with the
     * matrices set on {@code RenderSystem}. In 1.21.11 the dispatcher is
     * {@code EntityRenderManager} and {@code render(..)} only enqueues onto a
     * deferred {@code OrderedRenderCommandQueue} that the engine flushes during
     * its own world pass — there is no immediate one-shot rasterization into an
     * arbitrary FBO with a custom view/projection. Re-implementing this needs a
     * self-contained queue + {@code CameraRenderState} (or an approximate raw-GL
     * box occluder). No-op for now — entities cast no shadow.</p>
     */
    public static void renderCaster(Object caster, int casterType, float tickDelta)
    {
        if (caster == null || !inPass)
        {
            return;
        }
        // TODO(Stage 3): occluder rasterization via the deferred render queue.
    }

    /**
     * Rasterize a light's block occluders into the bound depth FBO.
     *
     * <p>TODO(1.21.11 Stage 2): STUBBED. The 1.20.4 path built a POSITION-only
     * {@code VertexBuffer} of the blocks' shape quads (plus a textured cutout
     * pass) and drew it with {@code vb.draw(view, proj, RenderSystem.getShader())}.
     * {@code VertexBuffer} no longer exists and {@code RenderSystem} no longer
     * exposes a per-draw shader/projection. Stage 2 re-implements this with a
     * self-contained raw-GL depth program (proj*view*pos) over the same shape
     * quads (see {@link BlockShadowEntry#shape}), using {@link #currentView} /
     * {@link #currentProj} already computed in begin*(). No-op for now — blocks
     * cast no shadow.</p>
     */
    public static void renderBlocksDepth(long id, List<BlockShadowEntry> blocks)
    {
        if (blocks == null || blocks.isEmpty() || !inPass)
        {
            return;
        }
        // TODO(Stage 2): raw-GL depth bake of the block shape quads.
    }

    /** Free one lamp's cached block geometry. No cache while rasterization is
     *  stubbed; retained as a no-op so {@link ShadowBaker} keeps compiling and
     *  the contract is ready for the Stage-2 VBO/VAO cache. */
    public static void releaseBlockVbo(long id)
    {
        // no-op (Stage 1): no block geometry cache yet.
    }

    /** Evict cached block geometry for lamps not in {@code liveIds}. No-op while
     *  rasterization is stubbed (see {@link #releaseBlockVbo}). */
    public static void retainBlockVbos(LongSet liveIds)
    {
        // no-op (Stage 1): no block geometry cache yet.
    }

    public static void endPass()
    {
        if (!inPass)
        {
            return;
        }

        GL11.glDepthMask(savedDepthMask);

        if (savedScissorEnabled)
        {
            GL11.glScissor(savedScissorBox[0], savedScissorBox[1], savedScissorBox[2], savedScissorBox[3]);
        }
        else
        {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);

        inPass = false;
    }

    /**
     * Snapshot the GL state every endPass restores, once per bake (the glGet* are
     * CPU<->GPU sync points; up to ~112 passes per bake would otherwise stall on
     * each). {@link #beginBake} re-arms it. Pure raw-GL now that the bake no
     * longer touches RenderSystem's matrices.
     */
    private static void savePassState()
    {
        inPass = true;
        if (passStateSaved)
        {
            return;
        }

        savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        savedScissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (savedScissorEnabled)
        {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, savedScissorBox);
        }
        savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        passStateSaved = true;
    }

    private static Vector3f pickStableUp(float dy)
    {
        return Math.abs(dy) > 0.99f ? new Vector3f(0f, 0f, 1f) : new Vector3f(0f, 1f, 0f);
    }
}
