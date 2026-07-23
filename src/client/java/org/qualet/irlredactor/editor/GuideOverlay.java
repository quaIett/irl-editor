package org.qualet.irlredactor.editor;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiMouseCursor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.qualet.irl.light.LightMath;
import org.qualet.irlredactor.imgui.EditorTheme;
import org.qualet.irlredactor.light.PlacedLight;

/**
 * Screen-space guide overlay for the placed lights — the BBS-free port of the
 * addon's {@code LightGuideRenderer} visual + its interactive spotlight handles
 * ({@code SpotGuideDrag}), rebuilt as an ImGui overlay in the exact style of the
 * transform gizmo.
 *
 * <p>Everything is drawn onto {@link ImGui#getBackgroundDrawList()} (over the
 * world, under the panel) by projecting camera-relative world points through the
 * same reconstructed view/projection the move gizmo uses, so the guides line up
 * with the rendered world and always sit crisply on top of the shader composite —
 * no depth fighting, no 3D wire pass. Point lights draw three radius rings on the
 * world axes; spotlights draw the cone (axis + spokes), the outer + inner cap
 * rings and a range disc at the cap centre.</p>
 *
 * <p>The selected spotlight's rings/disc are grab handles: a 2D hit-test picks the
 * one under the cursor, and while dragging the value is solved in 3D (a mouse ray
 * unprojected into the cone's local frame, intersected with the cap plane for the
 * angles or closest-point-to-axis for the range) — the same math as the addon,
 * minus BBS's stencil picking. Drags write the editor {@link LightState} (outer =
 * {@code angle}, inner = {@code angle - soft}, {@code range}); {@code LightSync.push}
 * then commits them like any other edit, so the change is live.</p>
 */
public final class GuideOverlay
{
    /** Grab-handle ids (also the drag target while a drag is active). */
    private static final int NONE = 0, OUTER = 1, INNER = 2, RANGE = 3;

    private static final int RING_SEGMENTS = 48;
    /** Cursor-to-ring pixel tolerance for grabbing an angle ring. */
    private static final float GRAB_RING_PX = 7f;
    /** Cursor-to-centre pixel tolerance for grabbing the range disc. */
    private static final float GRAB_DISC_PX = 10f;
    private static final float LINE_T = 1.7f;
    private static final float LINE_T_HOT = 3.0f;
    /** Range-disc screen radius. */
    private static final float DISC_PX = 5.5f;
    /** Colour floor so a near-black light still shows a visible guide. */
    private static final float MIN_VIS = 0.28f;
    private static final float EPS = 1e-5f;

    // Reused per-frame matrices (camera-relative world -> clip and back).
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f viewProj = new Matrix4f();
    private final Matrix4f invViewProj = new Matrix4f();
    private final Vector4f clip = new Vector4f();

    // Reused scratch for the spot's local frame + ray math.
    private final Vector3f origin = new Vector3f(); // selected light, camera-relative
    private final Vector3f fwd = new Vector3f();
    private final Vector3f uAxis = new Vector3f();
    private final Vector3f vAxis = new Vector3f();
    private final Vector3f rayO = new Vector3f();
    private final Vector3f rayD = new Vector3f();
    private final Vector3f loO = new Vector3f();
    private final Vector3f loD = new Vector3f();
    private final Vector3f tmp = new Vector3f();
    private final float[] sp = new float[2];   // project() output (screen x,y)
    private final float[] dir3 = new float[3]; // normalizeDir output

    // Ring screen-point buffers (reused for the selected spot's hit-test + draw).
    private final float[] ringX = new float[RING_SEGMENTS];
    private final float[] ringY = new float[RING_SEGMENTS];
    private final boolean[] ringOk = new boolean[RING_SEGMENTS];

    private float dispW, dispH;

    /** Handle currently being dragged (spot only), or {@link #NONE}. */
    private int drag = NONE;

    /** True while a ring/disc drag owns the mouse — the panel skips the gizmo then. */
    public boolean isDragging()
    {
        return drag != NONE;
    }

    /** True when the cursor is over a spot handle or a handle drag is active. The
     *  free camera polls this (statically, no overlay reference needed) to yield
     *  hold-LMB mouse-look to a guide drag — the drag wins. */
    private static volatile boolean handleActive;

    public static boolean isHandleActive()
    {
        return handleActive;
    }

    /**
     * Per-frame pass: draw guides for every scene light and run the selected
     * spotlight's handle drag.
     *
     * @param state      live editor buffer for the selected light (its guide is
     *                   drawn from this so an in-progress edit is crisp)
     * @param selected   the selected light (null = nothing selected)
     * @param allowInput hit-testing / starting a drag is allowed (cursor over the
     *                   world, gizmo idle); an active drag continues regardless
     */
    public void frame(LightState state, PlacedLight selected, boolean allowInput)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        handleActive = false;
        Camera cam = mc.gameRenderer == null ? null : mc.gameRenderer.getCamera();
        if (cam == null || mc.world == null || mc.getWindow().getFramebufferHeight() == 0)
        {
            drag = NONE;
            return;
        }

        buildMatrices(cam, mc);
        Vec3d cp = cam.getPos();

        // ---- selected spotlight: drag + local frame ------------------------
        boolean selSpot = selected != null && state.type == LightState.Type.SPOT;
        int hot = NONE;
        if (selSpot)
        {
            origin.set(
                (float) (state.pos[0] - cp.x),
                (float) (state.pos[1] - cp.y),
                (float) (state.pos[2] - cp.z));
            buildSpotBasis(state);

            if (drag != NONE)
            {
                if (!ImGui.isMouseDown(0))
                {
                    drag = NONE;
                }
                else
                {
                    updateDrag(state);
                }
                hot = drag;
            }
            else if (allowInput)
            {
                hot = hitTest(state);
                if (hot != NONE)
                {
                    ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
                    if (ImGui.isMouseClicked(0))
                    {
                        drag = hot;
                    }
                }
            }
        }
        else
        {
            drag = NONE;
        }

        // Free camera yields hold-LMB look while a handle is hot or being dragged.
        handleActive = drag != NONE || hot != NONE;

        // ---- draw ONLY the selected light's guide -------------------------
        // Gizmo-like: the guide + its grab handles belong to the active selection,
        // exactly like the move gizmo. Non-selected lights get no overlay (their
        // positions are in the source list); a busy scene stays uncluttered.
        if (selected == null)
        {
            return;
        }
        origin.set(
            (float) (state.pos[0] - cp.x),
            (float) (state.pos[1] - cp.y),
            (float) (state.pos[2] - cp.z));
        if (selSpot)
        {
            buildSpotBasis(state);
        }
        drawSelected(ImGui.getBackgroundDrawList(), state, hot);
    }

    // ---- matrices / projection --------------------------------------------

    /** Reconstruct MC's world camera exactly as the move gizmo does (view = camera
     *  rotation only, world kept camera-relative; a perspective with the live FOV +
     *  framebuffer aspect), so guides register with the rendered world + the gizmo. */
    private void buildMatrices(Camera cam, MinecraftClient mc)
    {
        dispW = ImGui.getIO().getDisplaySizeX();
        dispH = ImGui.getIO().getDisplaySizeY();
        float aspect = (float) mc.getWindow().getFramebufferWidth()
            / (float) mc.getWindow().getFramebufferHeight();
        double fovDeg = mc.options.getFov().getValue();

        view.identity()
            .rotateX((float) Math.toRadians(cam.getPitch()))
            .rotateY((float) Math.toRadians(cam.getYaw() + 180.0));
        proj.identity().perspective((float) Math.toRadians(fovDeg), aspect, 0.05f, 1000f);
        viewProj.set(proj).mul(view);
        invViewProj.set(viewProj).invert();
    }

    /** Camera-relative world point -> screen (display) pixels. False = behind the
     *  camera (caller skips the segment). */
    private boolean project(float x, float y, float z, float[] out)
    {
        clip.set(x, y, z, 1f);
        viewProj.transform(clip);
        if (clip.w <= 1e-4f)
        {
            return false;
        }
        float ndcx = clip.x / clip.w;
        float ndcy = clip.y / clip.w;
        out[0] = (ndcx * 0.5f + 0.5f) * dispW;
        out[1] = (1f - (ndcy * 0.5f + 0.5f)) * dispH;
        return true;
    }

    /** Mouse pixel -> camera-relative world ray (origin on the near plane, unit dir). */
    private void mouseRay()
    {
        ImVec2 m = ImGui.getMousePos();
        float ndcx = 2f * m.x / dispW - 1f;
        float ndcy = 1f - 2f * m.y / dispH;
        unproject(ndcx, ndcy, -1f, rayO);
        unproject(ndcx, ndcy, 1f, rayD);
        rayD.sub(rayO).normalize();
    }

    /** NDC point -> camera-relative world (inverse view-proj, perspective divide). */
    private void unproject(float ndcx, float ndcy, float ndcz, Vector3f out)
    {
        clip.set(ndcx, ndcy, ndcz, 1f);
        invViewProj.transform(clip);
        float inv = Math.abs(clip.w) < 1e-9f ? 0f : 1f / clip.w;
        out.set(clip.x * inv, clip.y * inv, clip.z * inv);
    }

    // ---- spot local frame + drag solving ----------------------------------

    /** Orthonormal (u, v, fwd) frame of the cone, matching the world guide's basis. */
    private void buildSpotBasis(LightState state)
    {
        LightMath.normalizeDir(state.dir[0], state.dir[1], state.dir[2], 0f, -1f, 0f, dir3);
        fwd.set(dir3[0], dir3[1], dir3[2]);
        float rx, ry, rz;
        if (Math.abs(fwd.y) < 0.99f) { rx = 0f; ry = 1f; rz = 0f; }
        else { rx = 1f; ry = 0f; rz = 0f; }
        uAxis.set(fwd).cross(rx, ry, rz).normalize();
        vAxis.set(fwd).cross(uAxis); // already unit (fwd ⟂ u, both unit)
    }

    /** Camera-relative world point -> cone-local (x=u, y=v, z=fwd, origin at light). */
    private void toLocalPoint(Vector3f p, Vector3f out)
    {
        tmp.set(p).sub(origin);
        out.set(tmp.dot(uAxis), tmp.dot(vAxis), tmp.dot(fwd));
    }

    /** Camera-relative world direction -> cone-local (rotation only). */
    private void toLocalDir(Vector3f d, Vector3f out)
    {
        out.set(d.dot(uAxis), d.dot(vAxis), d.dot(fwd));
    }

    private void updateDrag(LightState state)
    {
        mouseRay();
        toLocalPoint(rayO, loO);
        toLocalDir(rayD, loD);
        if (loD.lengthSquared() < EPS * EPS)
        {
            return;
        }
        if (drag == RANGE)
        {
            updateRange(state);
        }
        else
        {
            updateAngle(state, drag == INNER);
        }
    }

    /** Range disc slides along the cone axis: new range = z of the closest point on
     *  that axis to the mouse ray (addon SpotGuideDrag.updateRange, cap on +fwd). */
    private void updateRange(LightState state)
    {
        float a = loD.lengthSquared();
        float b = loD.z;
        float denom = a - b * b;
        if (denom < EPS)
        {
            return; // ray (anti)parallel to the axis
        }
        float oDotD = loO.dot(loD);
        float axisZ = (a * loO.z - b * oDotD) / denom;
        state.range[0] = clamp(axisZ, 0.1f, 128f);
    }

    /** Angle rings live in the cap plane: intersect the ray, map the radial distance
     *  back to the cone angle (addon SpotGuideDrag.updateAngle). Inner is stored as
     *  the {@code soft} penumbra width (outer - inner). */
    private void updateAngle(LightState state, boolean inner)
    {
        float capZ = Math.max(state.range[0], 0.05f);
        if (Math.abs(loD.z) < EPS)
        {
            return;
        }
        float t = (capZ - loO.z) / loD.z;
        if (t <= 0f)
        {
            return;
        }
        float hx = loO.x + loD.x * t;
        float hy = loO.y + loD.y * t;
        float radial = (float) Math.sqrt(hx * hx + hy * hy);
        float angle = (float) Math.toDegrees(2.0 * Math.atan2(radial, capZ));

        if (inner)
        {
            float outer = state.angle[0];
            float in = clamp(angle, 1f, outer);
            state.soft[0] = outer - in;
        }
        else
        {
            state.angle[0] = clamp(angle, 1f, 179f);
        }
    }

    // ---- hit-test (selected spot) -----------------------------------------

    /** 2D pick of the ring/disc under the cursor. Disc wins at the centre; otherwise
     *  the nearer of the inner/outer rings within tolerance. */
    private int hitTest(LightState state)
    {
        ImVec2 m = ImGui.getMousePos();
        float range = Math.max(state.range[0], 0.05f);
        float outer = Math.max(state.angle[0], 1f);
        float inner = clamp(outer - state.soft[0], 1f, outer);

        // Range disc at the cap centre.
        capCenter(range, tmp);
        if (project(tmp.x, tmp.y, tmp.z, sp)
            && dist(m.x, m.y, sp[0], sp[1]) <= GRAB_DISC_PX)
        {
            return RANGE;
        }

        int best = NONE;
        float bestD = GRAB_RING_PX;

        float outerR = coneRadius(range, outer);
        float dOuter = ringDistance(range, outerR);
        if (dOuter <= bestD)
        {
            bestD = dOuter;
            best = OUTER;
        }
        if (inner < outer - 0.5f)
        {
            float innerR = coneRadius(range, inner);
            float dInner = ringDistance(range, innerR);
            if (dInner < bestD)
            {
                best = INNER;
            }
        }
        return best;
    }

    /** Minimum cursor-to-ring pixel distance for the cap ring of the given radius. */
    private float ringDistance(float range, float radius)
    {
        projectRing(range, radius);
        ImVec2 m = ImGui.getMousePos();
        float best = Float.MAX_VALUE;
        for (int i = 0; i < RING_SEGMENTS; i++)
        {
            int j = (i + 1) % RING_SEGMENTS;
            if (!ringOk[i] || !ringOk[j])
            {
                continue;
            }
            float d = distToSegment(m.x, m.y, ringX[i], ringY[i], ringX[j], ringY[j]);
            if (d < best)
            {
                best = d;
            }
        }
        return best;
    }

    // ---- drawing -----------------------------------------------------------

    /** The selected light, drawn from the live editor state (crisp during a drag),
     *  with the hot handle highlighted. */
    private void drawSelected(ImDrawList dl, LightState state, int hot)
    {
        int col = colorOf(state.color[0], state.color[1], state.color[2], 0.92f);
        if (state.type == LightState.Type.SPOT)
        {
            drawSpot(dl, col, state.range[0], state.angle[0],
                clamp(state.angle[0] - state.soft[0], 1f, state.angle[0]), hot);
        }
        else
        {
            drawPoint(dl, col, state.radius[0]);
        }
    }

    /** Cone: centre axis, four spokes, outer + inner cap rings, range disc. Uses the
     *  current {@link #origin}/{@link #fwd}/{@link #uAxis}/{@link #vAxis} frame. */
    private void drawSpot(ImDrawList dl, int col, float range, float outerDeg, float innerDeg, int hot)
    {
        float r = Math.max(range, 0.05f);
        float outer = Math.max(outerDeg, 1f);
        float inner = clamp(innerDeg, 1f, outer);
        float outerR = coneRadius(r, outer);

        int hotCol = EditorTheme.accentU32();

        // Centre axis (apex -> cap centre).
        capCenter(r, tmp);
        line(dl, origin.x, origin.y, origin.z, tmp.x, tmp.y, tmp.z, col, LINE_T);

        // Four spokes to the outer ring (0/90/180/270°).
        for (int k = 0; k < 4; k++)
        {
            double a = Math.PI * 0.5 * k;
            ringPoint(r, outerR, (float) Math.cos(a), (float) Math.sin(a), tmp);
            line(dl, origin.x, origin.y, origin.z, tmp.x, tmp.y, tmp.z, col, LINE_T);
        }

        // Outer ring.
        boolean hotOuter = hot == OUTER;
        drawRing(dl, r, outerR, hotOuter ? hotCol : col, hotOuter ? LINE_T_HOT : LINE_T);

        // Inner ring (only when it is meaningfully tighter than the outer).
        if (inner < outer - 0.5f)
        {
            float innerR = coneRadius(r, inner);
            boolean hotInner = hot == INNER;
            drawRing(dl, r, innerR, hotInner ? hotCol : col, hotInner ? LINE_T_HOT : LINE_T);
        }

        // Range disc at the cap centre.
        capCenter(r, tmp);
        if (project(tmp.x, tmp.y, tmp.z, sp))
        {
            boolean hotRange = hot == RANGE;
            dl.addCircleFilled(sp[0], sp[1], hotRange ? DISC_PX + 1.5f : DISC_PX,
                hotRange ? hotCol : col);
        }
    }

    /** Point light: three radius rings on the world axes + three axis segments. */
    private void drawPoint(ImDrawList dl, int col, float radius)
    {
        float r = Math.max(radius, 0.05f);
        drawAxisRing(dl, col, r, 0); // plane ⟂ X (Y,Z circle)
        drawAxisRing(dl, col, r, 1); // plane ⟂ Y (X,Z circle)
        drawAxisRing(dl, col, r, 2); // plane ⟂ Z (X,Y circle)

        line(dl, origin.x - r, origin.y, origin.z, origin.x + r, origin.y, origin.z, col, LINE_T);
        line(dl, origin.x, origin.y - r, origin.z, origin.x, origin.y + r, origin.z, col, LINE_T);
        line(dl, origin.x, origin.y, origin.z - r, origin.x, origin.y, origin.z + r, col, LINE_T);
    }

    private void drawAxisRing(ImDrawList dl, int col, float radius, int axis)
    {
        float px = 0f, py = 0f;
        boolean pOk = false, first = true;
        float fx = 0f, fy = 0f;
        boolean fOk = false;
        for (int i = 0; i < RING_SEGMENTS; i++)
        {
            double a = Math.PI * 2.0 * i / RING_SEGMENTS;
            float c = (float) Math.cos(a) * radius;
            float s = (float) Math.sin(a) * radius;
            float wx, wy, wz;
            if (axis == 0) { wx = origin.x; wy = origin.y + c; wz = origin.z + s; }
            else if (axis == 1) { wx = origin.x + c; wy = origin.y; wz = origin.z + s; }
            else { wx = origin.x + c; wy = origin.y + s; wz = origin.z; }

            boolean ok = project(wx, wy, wz, sp);
            float cx = sp[0], cy = sp[1];
            if (!first && pOk && ok)
            {
                dl.addLine(px, py, cx, cy, col, LINE_T);
            }
            if (first) { fx = cx; fy = cy; fOk = ok; first = false; }
            px = cx; py = cy; pOk = ok;
        }
        if (pOk && fOk)
        {
            dl.addLine(px, py, fx, fy, col, LINE_T); // close the loop
        }
    }

    /** Project the cap ring of {@code radius} into {@link #ringX}/{@link #ringY}. */
    private void projectRing(float range, float radius)
    {
        for (int i = 0; i < RING_SEGMENTS; i++)
        {
            double a = Math.PI * 2.0 * i / RING_SEGMENTS;
            ringPoint(range, radius, (float) Math.cos(a), (float) Math.sin(a), tmp);
            ringOk[i] = project(tmp.x, tmp.y, tmp.z, sp);
            ringX[i] = sp[0];
            ringY[i] = sp[1];
        }
    }

    private void drawRing(ImDrawList dl, float range, float radius, int col, float thickness)
    {
        projectRing(range, radius);
        for (int i = 0; i < RING_SEGMENTS; i++)
        {
            int j = (i + 1) % RING_SEGMENTS;
            if (ringOk[i] && ringOk[j])
            {
                dl.addLine(ringX[i], ringY[i], ringX[j], ringY[j], col, thickness);
            }
        }
    }

    // ---- geometry helpers --------------------------------------------------

    /** Cap-plane point at (cos,sin)*radius, in camera-relative world. */
    private void ringPoint(float range, float radius, float cos, float sin, Vector3f out)
    {
        out.set(origin)
            .fma(range, fwd)
            .fma(radius * cos, uAxis)
            .fma(radius * sin, vAxis);
    }

    /** Cap centre (apex + fwd*range), camera-relative world. */
    private void capCenter(float range, Vector3f out)
    {
        out.set(origin).fma(range, fwd);
    }

    private static float coneRadius(float range, float angleDeg)
    {
        return (float) (Math.tan(Math.toRadians(angleDeg * 0.5f)) * range);
    }

    private void line(ImDrawList dl, float x1, float y1, float z1, float x2, float y2, float z2, int col, float t)
    {
        boolean a = project(x1, y1, z1, sp);
        float ax = sp[0], ay = sp[1];
        boolean b = project(x2, y2, z2, sp);
        if (a && b)
        {
            dl.addLine(ax, ay, sp[0], sp[1], col, t);
        }
    }

    private int colorOf(float r, float g, float b, float alpha)
    {
        return ImColor.rgba(vis(r), vis(g), vis(b), alpha);
    }

    private static float vis(float v)
    {
        float c = v < 0f ? 0f : Math.min(v, 1f);
        return Math.max(c, MIN_VIS);
    }

    private static float dist(float ax, float ay, float bx, float by)
    {
        float dx = ax - bx, dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Distance from point p to segment ab, in 2D. */
    private static float distToSegment(float px, float py, float ax, float ay, float bx, float by)
    {
        float abx = bx - ax, aby = by - ay;
        float len2 = abx * abx + aby * aby;
        float t = len2 <= EPS ? 0f : ((px - ax) * abx + (py - ay) * aby) / len2;
        t = Math.max(0f, Math.min(1f, t));
        float cx = ax + abx * t, cy = ay + aby * t;
        return dist(px, py, cx, cy);
    }

    private static float clamp(float v, float lo, float hi)
    {
        return Math.max(lo, Math.min(hi, v));
    }
}
