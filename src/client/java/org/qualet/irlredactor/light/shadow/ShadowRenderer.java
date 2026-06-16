package org.qualet.irlredactor.light.shadow;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * Bakes spot/point shadow depth maps into the per-light atlas tiles / cube faces.
 *
 * <p><b>1.21.11 port — block occluders rasterized with a self-contained raw-GL
 * depth program.</b> The 1.20.4 baker drew occluders with MC's {@code VertexBuffer}
 * + {@code RenderSystem} matrices, both removed by the 1.21.5 render-pipeline
 * rework. Rather than chase MC's churning {@code GpuDevice}/{@code RenderPass}
 * model, the block-shadow bake now owns a tiny GLSL program ({@code gl_Position =
 * uViewProj * pos}, no colour output) plus a VAO and a per-light VBO cache, and
 * draws the blocks' {@link BlockShadowEntry#shape} AABBs as triangles straight
 * into the bound depth FBO. This is independent of MC's render pipeline, so it is
 * robust across MC versions.</p>
 *
 * <p>GL-state safety: every piece of global GL state the draw touches (program,
 * VAO, array buffer, depth test/func/mask, cull) is snapshotted with {@code glGet*}
 * and restored to its real prior value, and the FBO/viewport/scissor are restored
 * by {@link #endPass}. MC's {@code GlStateManager} caches the FBO binding but NOT
 * program/VAO/buffer; restoring the actual binding to its captured value keeps
 * MC's cache consistent (verified: the Stage-1 raw-GL FBO binds rendered clean
 * in-world with shaders on).</p>
 *
 * <p>STILL STUBBED: {@link #renderCaster} (entity occluders) — entity rendering is
 * now the deferred {@code OrderedRenderCommandQueue}; an approximate raw-GL box
 * occluder could be added here later. And nothing is VISIBLE until the GLSL/
 * {@code .irlights} patcher (Stage 3) makes a shaderpack sample these depth maps.</p>
 *
 * <p>TODO(precision): vertices are absolute world coordinates, so the depth bake
 * loses float precision far from the world origin; switch to light-relative
 * coordinates (subtract the light position on the CPU, view = light-at-origin)
 * if banding shows up at distance.</p>
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

    /** The current light's view/projection (world space), combined into
     *  {@link #viewProj} for the depth program's uViewProj uniform. */
    private static final Matrix4f currentView = new Matrix4f();
    private static final Matrix4f currentProj = new Matrix4f();
    private static final Matrix4f viewProj = new Matrix4f();

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
     * Rasterize one occluder into the bound depth FBO. STUBBED for entities — see
     * the class note. (Block occluders go through {@link #renderBlocksDepth}.)
     *
     * <p>TODO(Stage 3): entity occluders. The 1.20.4 path used the immediate
     * entity dispatcher; 1.21.11's {@code EntityRenderManager.render} only enqueues
     * onto a deferred {@code OrderedRenderCommandQueue}. An approximate fix is to
     * draw the entity's interpolated bounding box through the depth program here
     * (a blob occluder), which needs the entity's box passed down from
     * {@link ShadowBaker}. No-op for now.</p>
     */
    public static void renderCaster(Object caster, int casterType, float tickDelta)
    {
        if (caster == null || !inPass)
        {
            return;
        }
        // TODO(Stage 3): entity-box occluder via the depth program.
    }

    // --- Block-shadow depth bake (raw-GL) -------------------------------------

    /** Shared depth program + its uniform/state, lazily created on the first bake. */
    private static int program = 0;
    private static int uViewProjLoc = -1;
    private static int vao = 0;
    private static boolean glInit = false;
    private static boolean glBroken = false;

    /** Per-light cached VBO of triangle vertices (POSITION). Rebuilt only when the
     *  block list instance changes ({@link BlockShadowCache} returns a stable
     *  instance until a block in range changes), so static lamps just redraw. */
    private static final Long2ObjectOpenHashMap<List<BlockShadowEntry>> vboList = new Long2ObjectOpenHashMap<>();
    private static final Long2IntOpenHashMap vboId = new Long2IntOpenHashMap();
    private static final Long2IntOpenHashMap vboVertCount = new Long2IntOpenHashMap();

    private static boolean blockErrorLogged = false;

    /**
     * Render a light's block occluders into the currently-bound depth FBO, between
     * a begin*()/endPass() bracket. Draws each block's {@link BlockShadowEntry#shape}
     * AABBs as triangles with the per-light view/projection.
     */
    public static void renderBlocksDepth(long id, List<BlockShadowEntry> blocks)
    {
        if (blocks == null || blocks.isEmpty() || !inPass || glBroken)
        {
            return;
        }

        if (!glInit)
        {
            initGl();
        }
        if (glBroken || program == 0)
        {
            return;
        }

        // (Re)build this light's VBO only when its block list instance changed.
        int vbo = vboId.get(id);
        int verts;
        if (vbo == 0 || vboList.get(id) != blocks)
        {
            verts = buildVbo(id, blocks);
        }
        else
        {
            verts = vboVertCount.get(id);
        }
        if (verts <= 0)
        {
            return;
        }
        vbo = vboId.get(id);

        // Snapshot every global GL state the draw mutates, then restore it — MC's
        // GlStateManager doesn't cache program/VAO/buffer, and the FBO/viewport are
        // restored by endPass, so this keeps the surrounding renderer consistent.
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        try
        {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            // Cull OFF: both faces of every box triangle write depth and the depth
            // test keeps the nearest (light-facing) surface — tight, correct block
            // silhouettes regardless of winding.
            GL11.glDisable(GL11.GL_CULL_FACE);

            GL20.glUseProgram(program);
            currentProj.mul(currentView, viewProj);
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush())
            {
                FloatBuffer fb = stack.mallocFloat(16);
                viewProj.get(fb);
                GL20.glUniformMatrix4fv(uViewProjLoc, false, fb);
            }

            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
        }
        catch (Throwable t)
        {
            if (!blockErrorLogged)
            {
                blockErrorLogged = true;
                System.err.println("[irl-redactor] block shadow bake failed: " + t);
                t.printStackTrace();
            }
        }
        finally
        {
            // Restore exactly what we changed (actual values -> matches MC's cache).
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL11.glDepthMask(prevDepthMask);
            GL11.glDepthFunc(prevDepthFunc);
            if (prevDepthTest) { GL11.glEnable(GL11.GL_DEPTH_TEST); } else { GL11.glDisable(GL11.GL_DEPTH_TEST); }
            if (prevCull) { GL11.glEnable(GL11.GL_CULL_FACE); } else { GL11.glDisable(GL11.GL_CULL_FACE); }
        }
    }

    /** Build (or rebuild) the per-light triangle VBO from the block shape AABBs.
     *  Returns the vertex count (0 if there is no shaped geometry). */
    private static int buildVbo(long id, List<BlockShadowEntry> blocks)
    {
        // Count boxes first (cheap AABB iteration) to size one allocation.
        int[] boxes = {0};
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry e = blocks.get(i);
            if (e != null && e.shape != null)
            {
                e.shape.forEachBox((a, b, c, d, f, g) -> boxes[0]++);
            }
        }
        int boxCount = boxes[0];
        if (boxCount == 0)
        {
            // No shaped geometry (e.g. only the old cutout entries): drop any VBO.
            releaseBlockVbo(id);
            vboList.put(id, blocks);
            vboVertCount.put(id, 0);
            return 0;
        }

        int vertCount = boxCount * 36; // 6 faces * 2 tris * 3 verts
        FloatBuffer fb = MemoryUtil.memAllocFloat(vertCount * 3);
        try
        {
            for (int i = 0, n = blocks.size(); i < n; i++)
            {
                BlockShadowEntry e = blocks.get(i);
                if (e == null || e.shape == null)
                {
                    continue;
                }
                final float ox = e.pos.getX(), oy = e.pos.getY(), oz = e.pos.getZ();
                e.shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) ->
                    emitBox(fb,
                        ox + (float) minX, oy + (float) minY, oz + (float) minZ,
                        ox + (float) maxX, oy + (float) maxY, oz + (float) maxZ));
            }
            fb.flip();

            int vbo = vboId.get(id);
            if (vbo == 0)
            {
                vbo = GL15.glGenBuffers();
                vboId.put(id, vbo);
            }
            int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);

            vboList.put(id, blocks);
            vboVertCount.put(id, vertCount);
            return vertCount;
        }
        finally
        {
            MemoryUtil.memFree(fb);
        }
    }

    /** Append one axis-aligned box as 12 triangles (36 verts, POSITION only).
     *  Winding is irrelevant — culling is disabled during the bake. */
    private static void emitBox(FloatBuffer b,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2)
    {
        // -X
        tri(b, x1,y1,z1, x1,y1,z2, x1,y2,z2);  tri(b, x1,y1,z1, x1,y2,z2, x1,y2,z1);
        // +X
        tri(b, x2,y1,z1, x2,y2,z1, x2,y2,z2);  tri(b, x2,y1,z1, x2,y2,z2, x2,y1,z2);
        // -Y
        tri(b, x1,y1,z1, x2,y1,z1, x2,y1,z2);  tri(b, x1,y1,z1, x2,y1,z2, x1,y1,z2);
        // +Y
        tri(b, x1,y2,z1, x1,y2,z2, x2,y2,z2);  tri(b, x1,y2,z1, x2,y2,z2, x2,y2,z1);
        // -Z
        tri(b, x1,y1,z1, x1,y2,z1, x2,y2,z1);  tri(b, x1,y1,z1, x2,y2,z1, x2,y1,z1);
        // +Z
        tri(b, x1,y1,z2, x2,y1,z2, x2,y2,z2);  tri(b, x1,y1,z2, x2,y2,z2, x1,y2,z2);
    }

    private static void tri(FloatBuffer b,
                            float ax, float ay, float az,
                            float bx, float by, float bz,
                            float cx, float cy, float cz)
    {
        b.put(ax).put(ay).put(az);
        b.put(bx).put(by).put(bz);
        b.put(cx).put(cy).put(cz);
    }

    /** Lazily compile the depth program + create the shared VAO. */
    private static void initGl()
    {
        glInit = true;
        try
        {
            int vs = compile(GL20.GL_VERTEX_SHADER,
                "#version 150\n" +
                "in vec3 aPos;\n" +
                "uniform mat4 uViewProj;\n" +
                "void main() { gl_Position = uViewProj * vec4(aPos, 1.0); }\n");
            int fs = compile(GL20.GL_FRAGMENT_SHADER,
                "#version 150\n" +
                "void main() {}\n");

            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            GL20.glBindAttribLocation(prog, 0, "aPos");
            GL20.glLinkProgram(prog);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            {
                throw new IllegalStateException("link: " + GL20.glGetProgramInfoLog(prog));
            }
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            program = prog;
            uViewProjLoc = GL20.glGetUniformLocation(prog, "uViewProj");
            vao = GL30.glGenVertexArrays();
        }
        catch (Throwable t)
        {
            glBroken = true;
            System.err.println("[irl-redactor] shadow depth program init failed: " + t);
            t.printStackTrace();
        }
    }

    private static int compile(int type, String src)
    {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            throw new IllegalStateException("compile: " + GL20.glGetShaderInfoLog(sh));
        }
        return sh;
    }

    /** Free one lamp's cached block VBO. */
    public static void releaseBlockVbo(long id)
    {
        int vbo = vboId.remove(id);
        if (vbo != 0)
        {
            GL15.glDeleteBuffers(vbo);
        }
        vboList.remove(id);
        vboVertCount.remove(id);
    }

    /** Free block VBOs for lamps not in {@code liveIds} (gone, or feature off ->
     *  empty set drains all). Run once per bake after the light loops. */
    public static void retainBlockVbos(LongSet liveIds)
    {
        if (vboId.isEmpty())
        {
            return;
        }
        ObjectIterator<Long2ObjectMap.Entry<List<BlockShadowEntry>>> it = vboList.long2ObjectEntrySet().iterator();
        while (it.hasNext())
        {
            long key = it.next().getLongKey();
            if (!liveIds.contains(key))
            {
                int vbo = vboId.remove(key);
                if (vbo != 0)
                {
                    GL15.glDeleteBuffers(vbo);
                }
                vboVertCount.remove(key);
                it.remove();
            }
        }
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
     * each). {@link #beginBake} re-arms it.
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
