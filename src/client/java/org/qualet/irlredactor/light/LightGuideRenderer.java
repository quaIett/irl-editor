package org.qualet.irlredactor.light;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.qualet.irl.light.LightMath;
import org.qualet.irlredactor.editor.LightEditorScreen;

/**
 * In-world wireframe guides for the placed lights, gated by
 * {@link LightConfig#showGuides} (the "Показывать гайды" toggle). Spotlights draw
 * a cone along their {@code dir} (so direction + beam spread are visible); point
 * lights draw a small position cross. The BBS-free stand-in for IRLite's dropped
 * {@code LightGuideRenderer}.
 *
 * <p>Drawn at {@code LAST} (after Iris has composited the frame into the main
 * framebuffer) with depth test off (always visible), in camera-relative
 * coordinates transformed by the world matrix. Using {@code LAST} rather than
 * {@code AFTER_TRANSLUCENT} is what makes the guides survive shaders: at the
 * earlier hook the lines land inside the shaderpack's gbuffer/translucent pass and
 * get discarded by the deferred composite.</p>
 */
public final class LightGuideRenderer
{
    private static final int CONE_SEGMENTS = 20;
    private static final int CONE_SPOKES = 4;
    private static final float POINT_CROSS = 0.4f;
    private static final float MAX_CONE_LEN = 16f;

    private LightGuideRenderer()
    {}

    public static void register()
    {
        // LAST = after Iris composites the world, so the guides survive shaders.
        WorldRenderEvents.LAST.register(LightGuideRenderer::onRender);
    }

    private static void onRender(WorldRenderContext ctx)
    {
        if (!LightConfig.showGuides || LightScene.count() == 0)
        {
            return;
        }

        // When the editor overlay is up it draws the richer ImGui guides itself
        // (GuideOverlay) — suppress this in-world wire pass so they don't double up.
        // With the editor closed this keeps drawing the lightweight world guides.
        if (LightEditorScreen.isOverlayActive())
        {
            return;
        }

        Camera cam = ctx.camera();
        if (cam == null)
        {
            return;
        }

        Vec3d c = cam.getPos();
        // World camera-rotation matrix: use the event's stack when present, else
        // reconstruct it (Rx(pitch)·Ry(yaw+180)) exactly like the move gizmo does.
        MatrixStack ms = ctx.matrixStack();
        Matrix4f m = ms != null
            ? ms.peek().getPositionMatrix()
            : new Matrix4f()
                .rotateX((float) Math.toRadians(cam.getPitch()))
                .rotateY((float) Math.toRadians(cam.getYaw() + 180.0));

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest(); // guides stay visible through geometry
        RenderSystem.disableCull();
        RenderSystem.lineWidth(2.0f);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

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
                drawSpot(buf, m, x, y, z, l, r, g, b);
            }
            else
            {
                drawPoint(buf, m, x, y, z, r, g, b);
            }
        }

        tess.draw();

        RenderSystem.lineWidth(1.0f);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    private static void drawPoint(BufferBuilder buf, Matrix4f m, float x, float y, float z, float r, float g, float b)
    {
        line(buf, m, x - POINT_CROSS, y, z, x + POINT_CROSS, y, z, r, g, b);
        line(buf, m, x, y - POINT_CROSS, z, x, y + POINT_CROSS, z, r, g, b);
        line(buf, m, x, y, z - POINT_CROSS, x, y, z + POINT_CROSS, r, g, b);
    }

    private static void drawSpot(BufferBuilder buf, Matrix4f m, float x, float y, float z, PlacedLight l, float r, float g, float b)
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
        line(buf, m, x, y, z, ex, ey, ez, r, g, b);

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
                line(buf, m, px, py, pz, qx, qy, qz, r, g, b);
            }
            if (i % (CONE_SEGMENTS / CONE_SPOKES) == 0)
            {
                line(buf, m, x, y, z, qx, qy, qz, r, g, b);
            }
            px = qx; py = qy; pz = qz;
        }
    }

    private static void line(BufferBuilder buf, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b)
    {
        buf.vertex(m, x1, y1, z1).color(r, g, b, 1f).next();
        buf.vertex(m, x2, y2, z2).color(r, g, b, 1f).next();
    }

    /** Clamp to [0,1] with a floor so a very dark light still has a visible guide. */
    private static float vis(float v)
    {
        float c = v < 0f ? 0f : Math.min(v, 1f);
        return Math.max(c, 0.25f);
    }
}
