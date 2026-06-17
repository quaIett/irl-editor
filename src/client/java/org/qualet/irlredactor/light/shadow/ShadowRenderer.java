package org.qualet.irlredactor.light.shadow;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.List;

/**
 * Bakes spotlight shadow depth maps. Phase 1: perspective depth tile per spot,
 * entity occluders only (vanilla render dispatcher), hard shadow. Ported from
 * the original IRLights ShadowRenderer (spot + entity path).
 *
 * Usage: beginSpot(...) -> renderEntity(...) * -> endPass().
 */
public final class ShadowRenderer
{
    private static final float NEAR = 0.05f;
    private static final int FULL_LIGHT = LightmapTextureManager.pack(15, 15);

    // Constant "up" axes for the spot lookAt — lookAt only reads them, so a
    // shared instance replaces the per-call allocation (T1.3).
    private static final Vector3f UP_Y = new Vector3f(0f, 1f, 0f);
    private static final Vector3f UP_Z = new Vector3f(0f, 0f, 1f);

    private static boolean inPass = false;
    /** True once {@link #savePassState} has snapshotted the original GL state
     *  this bake. {@link #beginBake} clears it so the next bake re-grabs the
     *  (possibly different) state, while passes within one bake share the
     *  single snapshot instead of re-running the glGet* sync points. */
    private static boolean passStateSaved = false;

    private static int savedFbo;
    private static final int[] savedViewport = new int[4];
    private static boolean savedScissorEnabled;
    private static final int[] savedScissorBox = new int[4];
    private static Matrix4f savedProj;
    private static VertexSorter savedSorter;
    private static boolean savedMaskR, savedMaskG, savedMaskB, savedMaskA;

    private static final Matrix4f currentView = new Matrix4f();
    /** The current light's projection, mirrored from what applyMatrices set on
     *  RenderSystem. The block batch draws with this + currentView directly,
     *  NOT RenderSystem's live matrices, which a caster can leave corrupted. */
    private static final Matrix4f currentProj = new Matrix4f();

    // --- Reused matrix scratch (T1.3) ----------------------------------------
    // RenderSystem.setProjectionMatrix copies its argument, and the block batch
    // draws with currentProj (a copy made in applyMatrices), so reusing these
    // projection objects across passes is safe — endPass restores the saved one.
    /** Spot projection; rebuilt only when its fov/far change (all of a light's
     *  passes, and consecutive equal lights, reuse it). */
    private static final Matrix4f spotProj = new Matrix4f();
    private static float spotProjFov = Float.NaN;
    private static float spotProjFar = Float.NaN;
    /** Point projection — identical for all 6 cube faces; rebuilt only when far
     *  (= radius) changes. */
    private static final Matrix4f pointProj = new Matrix4f();
    private static float pointProjFar = Float.NaN;
    /** Shared per-caster / per-cutout-block MatrixStack (the render thread is
     *  single-threaded). {@link #resetScratch} drains + identity-resets it
     *  before each use, so a caster that throws mid-build can't leave it dirty. */
    private static final MatrixStack scratch = new MatrixStack();

    private ShadowRenderer()
    {}

    /** Call once at the start of a bake, before any begin*()/endPass(). Arms a
     *  fresh snapshot of the MC/Iris GL state on the first pass of this bake
     *  (see {@link #savePassState}), so the per-pass glGet* stalls collapse to
     *  one per bake. */
    public static void beginBake()
    {
        passStateSaved = false;
    }

    /**
     * Begin a spot depth pass into the live ({@code toStatic} false) or static
     * ({@code toStatic} true) atlas tile. {@code clear} false keeps the tile's
     * current depth — used for the dynamic-caster overlay drawn on top of a
     * static base just restored by {@link SpotlightDepthAtlas#copyStaticToLive}.
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
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float fovDeg = Math.max(outerDeg, 1.0f);
        float far = Math.max(range, NEAR + 0.1f);
        if (fovDeg != spotProjFov || far != spotProjFar)
        {
            spotProj.identity().perspective((float) Math.toRadians(fovDeg), 1.0f, NEAR, far);
            spotProjFov = fovDeg;
            spotProjFar = far;
        }

        Vector3f up = pickStableUp(ldy);
        currentView.identity().lookAt(
            lpx, lpy, lpz,
            lpx + ldx, lpy + ldy, lpz + ldz,
            up.x, up.y, up.z
        );

        applyMatrices(spotProj);
    }

    /**
     * Begin a point-cube face depth pass into the live or static array (see
     * {@link #beginSpot} for the {@code toStatic}/{@code clear} semantics; the
     * static base of a whole cube is restored by
     * {@link PointShadowArray#copyStaticToLive}).
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
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float far = Math.max(radius, NEAR + 0.1f);
        if (far != pointProjFar)
        {
            pointProj.identity().perspective((float) Math.toRadians(90.0), 1.0f, NEAR, far);
            pointProjFar = far;
        }

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

        applyMatrices(pointProj);
    }

    public static final int CASTER_ENTITY = 0;
    public static final int CASTER_MODEL_BLOCK = 1;
    public static final int CASTER_REPLAY = 2;

    public static void renderCaster(Object caster, int casterType, float tickDelta)
    {
        if (caster == null || !inPass)
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        resetScratch();

        // Form-renderer paths inherit GL state, so pin what we need per caster.
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        ShadowBakeState.setBaking(true);
        try
        {
            // BBS-free engine: only vanilla world entities cast shadows. Model-block
            // and film-replay casters were a BBS-form feature and are gone.
            drawEntity((Entity) caster, scratch, immediate, tickDelta);
            immediate.draw();
        }
        catch (Throwable t)
        {
            // swallow — a single bad caster must not abort the whole bake
        }
        finally
        {
            ShadowBakeState.setBaking(false);
        }
    }

    private static void drawEntity(Entity entity, MatrixStack matrices, VertexConsumerProvider.Immediate immediate, float tickDelta)
    {
        double cx = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX());
        double cy = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY());
        double cz = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ());
        float yaw = entity.getYaw(tickDelta);

        EntityRenderDispatcher dispatcher = MinecraftClient.getInstance().getEntityRenderDispatcher();
        if (dispatcher != null)
        {
            dispatcher.render(entity, cx, cy, cz, yaw, tickDelta, matrices, immediate, FULL_LIGHT);
        }
    }

    // --- Block-shadow batch draw (non-full-shape blocks within an active pass) ---

    private static boolean blockRenderErrorLogged = false;
    private static final Random cutoutRandom = Random.create();

    // --- Per-light block VBO cache (Stage B), keyed by LightRegistry.id ---
    // The block list from BlockShadowCache is referentially stable on a cache
    // hit (same List instance), so a reference compare detects a rebuild
    // perfectly. One VertexBuffer per lamp, built once and redrawn across all
    // 6 cube faces (point) / the single atlas tile (spot). Static lamps
    // re-upload nothing. Evicted by retainBlockVbos when the lamp disappears.
    private static final Long2ObjectOpenHashMap<VertexBuffer> blockVboById = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<List<BlockShadowEntry>> blockVboListById = new Long2ObjectOpenHashMap<>();

    /**
     * Render a light's non-full-cube blocks into the currently-bound depth FBO,
     * between a begin*()/endPass() bracket and after the entity casters. Two
     * paths: cutout blocks bake from their textured BakedModel (alpha-test
     * shader drops transparent texels), opaque shapes draw as a cached
     * POSITION-only quad-box VBO decomposed from their VoxelShape AABBs.
     */
    public static void renderBlocksDepth(long id, List<BlockShadowEntry> blocks)
    {
        if (blocks == null || blocks.isEmpty() || !inPass)
        {
            return;
        }

        // Cutout blocks first (their own textured pass), then opaque AABBs.
        renderBlocksDepthCutout(blocks);

        boolean anyShape = false;
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry e = blocks.get(i);
            if (e != null && e.shape != null)
            {
                anyShape = true;
                break;
            }
        }
        if (!anyShape)
        {
            // All entries were cutout — begin/end on an empty buffer would throw.
            return;
        }

        ShadowBakeState.setBaking(true);
        boolean disabledCull = false;
        try
        {
            // Culling OFF: both faces of every quad write depth and the depth test
            // keeps the nearest (light-facing) surface — the true occluder — so
            // blocks cast tight, correct shadows (matches the proven IRLEngine bake).
            RenderSystem.disableCull();
            disabledCull = true;
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.setShader(GameRenderer::getPositionProgram);

            // Rebuild only when the list instance changed (BlockShadowCache
            // returns the same instance on a hit) — static lamps just redraw.
            VertexBuffer vb = blockVboById.get(id);
            if (vb == null || blockVboListById.get(id) != blocks)
            {
                if (vb != null)
                {
                    try { vb.close(); } catch (Throwable ignored) {}
                }
                vb = buildBlockVbo(blocks);
                blockVboById.put(id, vb);
                blockVboListById.put(id, blocks);
            }

            if (vb != null)
            {
                vb.bind();
                // Draw with the LIGHT's own view/proj, NOT RenderSystem's live
                // matrices. A caster baked earlier in this pass — notably a model
                // block whose form is a vanilla mob, drawn through the vanilla
                // EntityRenderer — can leave RenderSystem's modelview changed; the
                // block batch would then transform wrong and land nothing in the
                // depth map, so the light's block shadows silently vanished (no GL
                // error). currentView/currentProj are set in applyMatrices.
                vb.draw(currentView, currentProj, RenderSystem.getShader());
                VertexBuffer.unbind();
            }
        }
        catch (Throwable t)
        {
            if (!blockRenderErrorLogged)
            {
                blockRenderErrorLogged = true;
                System.err.println("[irlite] renderBlocksDepth failed: " + t);
                t.printStackTrace();
            }
            // Drop the (possibly broken) VBO so the next frame retries clean.
            releaseBlockVbo(id);
        }
        finally
        {
            if (disabledCull)
            {
                RenderSystem.enableCull();   // restore MC's default (back-face cull)
            }
            ShadowBakeState.setBaking(false);
        }
    }

    /** Build a STATIC POSITION VBO from a block list's shaped entries. Only
     *  called when at least one entry has a shape, so the buffer is non-empty. */
    private static VertexBuffer buildBlockVbo(List<BlockShadowEntry> blocks)
    {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);

        QuadBoxConsumer consumer = new QuadBoxConsumer(buf);
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry entry = blocks.get(i);
            if (entry == null || entry.shape == null)
            {
                continue;
            }
            consumer.ox = entry.pos.getX();
            consumer.oy = entry.pos.getY();
            consumer.oz = entry.pos.getZ();
            entry.shape.forEachBox(consumer);
        }

        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(buf.end());
        VertexBuffer.unbind();
        return vb;
    }

    /** Free one lamp's cached block VBO. */
    public static void releaseBlockVbo(long id)
    {
        VertexBuffer vb = blockVboById.remove(id);
        if (vb != null)
        {
            try { vb.close(); } catch (Throwable ignored) {}
        }
        blockVboListById.remove(id);
    }

    /** Free block VBOs for lamps not in {@code liveIds} (gone, or feature off
     *  -> empty set drains all). Run once per bake after the light loops. */
    public static void retainBlockVbos(LongSet liveIds)
    {
        if (blockVboById.isEmpty())
        {
            return;
        }
        ObjectIterator<Long2ObjectMap.Entry<VertexBuffer>> it = blockVboById.long2ObjectEntrySet().iterator();
        while (it.hasNext())
        {
            Long2ObjectMap.Entry<VertexBuffer> me = it.next();
            if (!liveIds.contains(me.getLongKey()))
            {
                VertexBuffer vb = me.getValue();
                if (vb != null)
                {
                    try { vb.close(); } catch (Throwable ignored) {}
                }
                blockVboListById.remove(me.getLongKey());
                it.remove();
            }
        }
    }

    // Cutout blocks bake from their textured BakedModel through vanilla's
    // alpha-test cutout shader so transparent texture pixels (door glass, iron
    // grates, ladder gaps, leaves) let light pass through. immediate.draw binds
    // the block atlas to Sampler0 and sets the cutout(_mipped) shader whose FS
    // does the alpha discard; colour writes land on no attachment in our
    // depth-only FBO, so only depth is recorded.
    private static void renderBlocksDepthCutout(List<BlockShadowEntry> blocks)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null)
        {
            return;
        }

        BlockRenderManager brm = mc.getBlockRenderManager();
        if (brm == null)
        {
            return;
        }

        boolean any = false;
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry e = blocks.get(i);
            if (e != null && e.cutout)
            {
                any = true;
                break;
            }
        }
        if (!any)
        {
            return;
        }

        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();

        ShadowBakeState.setBaking(true);
        MatrixStack mv = RenderSystem.getModelViewStack();
        boolean mvPushed = false;
        try
        {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();

            // Establish the LIGHT's own view/proj BEFORE the collection loop, not
            // just before the final immediate.draw(). Cutout blocks of different
            // RenderLayers (a door/trapdoor on CUTOUT; leaves/glass/bars on
            // CUTOUT_MIPPED) all share the Immediate's fallback BufferBuilder, so
            // requesting a buffer for a NEW layer auto-flushes the PREVIOUS layer
            // mid-loop. That flush reads RenderSystem's live matrices — which a
            // vanilla-mob caster baked earlier in this pass leaves corrupted — so
            // with the matrices fixed only after the loop, every cutout layer but
            // the last drew into nowhere and its shadows vanished the moment a
            // second cutout layer was present (one layer alone always worked, hence
            // "remove the trapdoor and the leaf/glass shadows come back"). Setting
            // them first makes both the mid-loop auto-flushes and the final draw
            // land in the depth map. mvPushed + the finally keep the stack balanced.
            mv.push();
            mvPushed = true;
            mv.loadIdentity();
            mv.multiplyPositionMatrix(currentView);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(currentProj, VertexSorter.BY_DISTANCE);

            resetScratch();
            MatrixStack stack = scratch;
            for (int i = 0, n = blocks.size(); i < n; i++)
            {
                BlockShadowEntry entry = blocks.get(i);
                if (entry == null || !entry.cutout)
                {
                    continue;
                }
                BlockPos p = entry.pos;
                BlockState state = world.getBlockState(p);
                if (state.isAir())
                {
                    continue;
                }

                RenderLayer layer;
                try
                {
                    layer = RenderLayers.getBlockLayer(state);
                }
                catch (Throwable t)
                {
                    continue;
                }
                if (layer != RenderLayer.getCutout() && layer != RenderLayer.getCutoutMipped())
                {
                    continue;
                }

                VertexConsumer consumer = immediate.getBuffer(layer);
                stack.push();
                stack.translate(p.getX(), p.getY(), p.getZ());
                cutoutRandom.setSeed(state.getRenderingSeed(p));
                try
                {
                    brm.renderBlock(state, p, world, stack, consumer, true, cutoutRandom);
                }
                catch (Throwable t)
                {
                    // skip a single broken block, keep baking the rest
                }
                stack.pop();
            }
            // Flush the final layer. The mid-loop auto-flushes (on each layer
            // switch, see the note above) and this one all run with the light's
            // matrices that were set before the loop, so every cutout layer lands
            // in the depth map regardless of what a caster left on RenderSystem.
            immediate.draw();
        }
        catch (Throwable t)
        {
            if (!blockRenderErrorLogged)
            {
                blockRenderErrorLogged = true;
                System.err.println("[irlite] renderBlocksDepthCutout failed: " + t);
                t.printStackTrace();
            }
        }
        finally
        {
            if (mvPushed)
            {
                mv.pop();
                RenderSystem.applyModelViewMatrix();
            }
            ShadowBakeState.setBaking(false);
        }
    }

    /**
     * Reusable forEachBox consumer that emits one block-aligned VoxelShape AABB
     * as 6 world-space quads. The block origin (ox, oy, oz) is mutated per
     * block; the shape's box coords are local (0..1). Winding is irrelevant —
     * culling is disabled, so both faces of each quad write depth.
     */
    private static final class QuadBoxConsumer implements net.minecraft.util.shape.VoxelShapes.BoxConsumer
    {
        final BufferBuilder buf;
        int ox, oy, oz;

        QuadBoxConsumer(BufferBuilder buf)
        {
            this.buf = buf;
        }

        @Override
        public void consume(double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ)
        {
            float x1 = (float) (ox + minX);
            float y1 = (float) (oy + minY);
            float z1 = (float) (oz + minZ);
            float x2 = (float) (ox + maxX);
            float y2 = (float) (oy + maxY);
            float z2 = (float) (oz + maxZ);

            // -X
            buf.vertex(x1, y1, z1).next();
            buf.vertex(x1, y1, z2).next();
            buf.vertex(x1, y2, z2).next();
            buf.vertex(x1, y2, z1).next();
            // +X
            buf.vertex(x2, y1, z2).next();
            buf.vertex(x2, y1, z1).next();
            buf.vertex(x2, y2, z1).next();
            buf.vertex(x2, y2, z2).next();
            // -Y
            buf.vertex(x1, y1, z2).next();
            buf.vertex(x1, y1, z1).next();
            buf.vertex(x2, y1, z1).next();
            buf.vertex(x2, y1, z2).next();
            // +Y
            buf.vertex(x1, y2, z1).next();
            buf.vertex(x1, y2, z2).next();
            buf.vertex(x2, y2, z2).next();
            buf.vertex(x2, y2, z1).next();
            // -Z
            buf.vertex(x2, y1, z1).next();
            buf.vertex(x1, y1, z1).next();
            buf.vertex(x1, y2, z1).next();
            buf.vertex(x2, y2, z1).next();
            // +Z
            buf.vertex(x1, y1, z2).next();
            buf.vertex(x2, y1, z2).next();
            buf.vertex(x2, y2, z2).next();
            buf.vertex(x1, y2, z2).next();
        }
    }

    public static void endPass()
    {
        if (!inPass)
        {
            return;
        }

        MatrixStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pop();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(savedProj, savedSorter);

        GL11.glColorMask(savedMaskR, savedMaskG, savedMaskB, savedMaskA);

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

    private static void savePassState()
    {
        // The saved state is the original MC/Iris GL state, which every endPass
        // restores — so it is invariant across all passes of one bake. Snapshot
        // it only on the first pass (the glGet* are CPU<->GPU sync points; up to
        // 16 spots + 16*6 point faces = ~112 passes would otherwise issue ~5
        // each). beginBake() re-arms it for the next bake.
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
        savedProj = RenderSystem.getProjectionMatrix();
        savedSorter = RenderSystem.getVertexSorting();

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush())
        {
            java.nio.IntBuffer maskBuf = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, maskBuf);
            savedMaskR = maskBuf.get(0) != 0;
            savedMaskG = maskBuf.get(1) != 0;
            savedMaskB = maskBuf.get(2) != 0;
            savedMaskA = maskBuf.get(3) != 0;
        }

        passStateSaved = true;
    }

    private static void applyMatrices(Matrix4f proj)
    {
        GL11.glColorMask(true, true, true, true);

        RenderSystem.setProjectionMatrix(proj, VertexSorter.BY_DISTANCE);
        currentProj.set(proj);

        MatrixStack mvStack = RenderSystem.getModelViewStack();
        mvStack.push();
        mvStack.loadIdentity();
        mvStack.multiplyPositionMatrix(currentView);
        RenderSystem.applyModelViewMatrix();
    }

    private static Vector3f pickStableUp(float dy)
    {
        return Math.abs(dy) > 0.99f ? UP_Z : UP_Y;
    }

    /** Drain the shared {@link #scratch} back to its single root entry (popping
     *  anything a thrown caster left pushed — {@code isEmpty()} is true at size
     *  1) and reset that entry to identity, so each use starts clean. */
    private static void resetScratch()
    {
        while (!scratch.isEmpty())
        {
            scratch.pop();
        }
        scratch.loadIdentity();
    }
}
