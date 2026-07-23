package org.qualet.irlredactor.light;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.qualet.irl.light.LightMath;
import org.qualet.irlredactor.editor.LightEditorScreen;

/**
 * In-world wireframe guides for the placed lights, gated by
 * {@link LightConfig#showGuides} (the "Показывать гайды" toggle). Spotlights draw
 * a cone along their {@code dir} (so direction + beam spread are visible); point
 * lights draw a small position cross. The BBS-free stand-in for IRLite's dropped
 * {@code LightGuideRenderer}.
 *
 * <p>1.21.11 port: the 1.20.4 path (Tessellator + a hand-managed RenderSystem GL
 * state + the {@code WorldRenderEvents.LAST} hook) is gone — the render-pipeline
 * rework removed {@code RenderSystem.setShader}/blend/depth/line-width and the
 * Fabric rendering API replaced {@code LAST} with phase-named events. Guides now
 * emit into the world renderer's own {@code VertexConsumerProvider} via the
 * vanilla {@code RenderLayers.lines()} layer at {@code BEFORE_DEBUG_RENDER}, the
 * event Fabric documents for "lines, overlays and other content similar to
 * vanilla debug renders". Coordinates are camera-relative (the consumers
 * convention), transformed by the event's world matrix.</p>
 *
 * <p>TODO(1.21.11): the {@code lines()} layer is depth-tested, so guides are now
 * occluded by terrain instead of the old x-ray look — restoring "always visible"
 * needs a custom depth-disabled line layer/pipeline. Also re-verify visibility
 * with Iris shaders ON (the old LAST hook drew after the shader composite).</p>
 */
public final class LightGuideRenderer
{
    private static final int CONE_SEGMENTS = 20;
    private static final int CONE_SPOKES = 4;
    private static final float POINT_CROSS = 0.4f;
    private static final float MAX_CONE_LEN = 16f;
    private static final float LINE_WIDTH = 2.0f;

    /** Latched off after any render failure — a non-critical overlay must never
     *  crash the game (and never spam the log frame after frame). */
    private static boolean broken = false;

    private LightGuideRenderer()
    {}

    public static void register()
    {
        // BEFORE_DEBUG_RENDER = Fabric's recommended hook for debug-style lines;
        // the world renderer flushes its line buffer right after.
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(LightGuideRenderer::onRender);
    }

    private static void onRender(WorldRenderContext ctx)
    {
        if (broken || !LightConfig.showGuides || LightScene.count() == 0)
        {
            return;
        }

        try
        {
            renderGuides(ctx);
        }
        catch (Throwable t)
        {
            broken = true;
            System.err.println("[irl-redactor] light guides disabled after render error: " + t);
        }
    }

    private static void renderGuides(WorldRenderContext ctx)
    {
        // When the editor overlay is up it draws the richer ImGui guides itself
        // (GuideOverlay) — suppress this in-world wire pass so they don't double up.
        // With the editor closed this keeps drawing the lightweight world guides.
        if (LightEditorScreen.isOverlayActive())
        {
            return;
        }

        Camera cam = ctx.gameRenderer().getCamera();
        if (cam == null)
        {
            return;
        }

        Vec3d c = cam.getCameraPos();
        MatrixStack ms = ctx.matrices();
        if (ms == null)
        {
            return;
        }
        MatrixStack.Entry entry = ms.peek();

        // Vanilla line layer (POSITION_COLOR_NORMAL); the world renderer owns the
        // flush, so we only emit segments here.
        VertexConsumer buf = ctx.consumers().getBuffer(RenderLayers.lines());

        for (PlacedLight l : LightScene.all())
        {
            if (l == null)
            {
                continue;
            }

            float r = vis(l.r), g = vis(l.g), b = vis(l.b);
            float x = (float) (l.x - c.x);
            float y = (float) (l.y - c.y);
            float z = (float) (l.z - c.z);

            if (l.type == PlacedLight.Type.SPOT)
            {
                drawSpot(buf, entry, x, y, z, l, r, g, b);
            }
            else
            {
                drawPoint(buf, entry, x, y, z, r, g, b);
            }
        }
    }

    private static void drawPoint(VertexConsumer buf, MatrixStack.Entry e, float x, float y, float z, float r, float g, float b)
    {
        line(buf, e, x - POINT_CROSS, y, z, x + POINT_CROSS, y, z, r, g, b);
        line(buf, e, x, y - POINT_CROSS, z, x, y + POINT_CROSS, z, r, g, b);
        line(buf, e, x, y, z - POINT_CROSS, x, y, z + POINT_CROSS, r, g, b);
    }

    private static void drawSpot(VertexConsumer buf, MatrixStack.Entry e, float x, float y, float z, PlacedLight l, float r, float g, float b)
    {
        // Normalized direction (defaults straight down when degenerate).
        float[] dir = LightMath.normalizeDir(l.dirX, l.dirY, l.dirZ, 0f, -1f, 0f, new float[3]);
        float dx = dir[0], dy = dir[1], dz = dir[2];

        float len = Math.max(1f, Math.min(l.range, MAX_CONE_LEN));
        float radius = (float) (len * Math.tan(Math.toRadians(l.outerAngleDeg * 0.5f)));

        // End-cap centre.
        float ex = x + dx * len, ey = y + dy * len, ez = z + dz * len;

        // Orthonormal basis (u, v) spanning the end-cap plane.
        float rx, ry, rz;
        if (Math.abs(dy) < 0.99f) { rx = 0f; ry = 1f; rz = 0f; }
        else { rx = 1f; ry = 0f; rz = 0f; }
        float ux = dy * rz - dz * ry, uy = dz * rx - dx * rz, uz = dx * ry - dy * rx;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        float vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;

        // Centre axis line (the direction indicator).
        line(buf, e, x, y, z, ex, ey, ez, r, g, b);

        // End ring + spokes from the apex.
        float px = 0f, py = 0f, pz = 0f;
        for (int i = 0; i <= CONE_SEGMENTS; i++)
        {
            double a = (Math.PI * 2.0) * i / CONE_SEGMENTS;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float qx = ex + (ux * cos + vx * sin) * radius;
            float qy = ey + (uy * cos + vy * sin) * radius;
            float qz = ez + (uz * cos + vz * sin) * radius;

            if (i > 0)
            {
                line(buf, e, px, py, pz, qx, qy, qz, r, g, b);
            }
            if (i % (CONE_SEGMENTS / CONE_SPOKES) == 0)
            {
                line(buf, e, x, y, z, qx, qy, qz, r, g, b);
            }
            px = qx; py = qy; pz = qz;
        }
    }

    /** Emit one camera-relative segment into the line consumer. The POSITION_COLOR_NORMAL
     *  format needs a normal per vertex; the line direction is the natural choice
     *  (matches vanilla debug-line rendering). */
    private static void line(VertexConsumer buf, MatrixStack.Entry e, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b)
    {
        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-5f)
        {
            nx = 0f; ny = 1f; nz = 0f;
        }
        else
        {
            nx /= nl; ny /= nl; nz /= nl;
        }
        // The lines layer uses POSITION_COLOR_NORMAL_LINE_WIDTH — the per-vertex
        // LineWidth element is mandatory (omitting it throws "Missing elements in
        // vertex: LineWidth"), so it must be written on every vertex.
        buf.vertex(e.getPositionMatrix(), x1, y1, z1).color(r, g, b, 1f).normal(e, nx, ny, nz).lineWidth(LINE_WIDTH);
        buf.vertex(e.getPositionMatrix(), x2, y2, z2).color(r, g, b, 1f).normal(e, nx, ny, nz).lineWidth(LINE_WIDTH);
    }

    /** Clamp to [0,1] with a floor so a very dark light still has a visible guide. */
    private static float vis(float v)
    {
        float c = v < 0f ? 0f : Math.min(v, 1f);
        return Math.max(c, 0.25f);
    }
}
