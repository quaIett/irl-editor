package org.qualet.irlredactor.editor;

import imgui.ImGui;
import imgui.extension.imguizmo.ImGuizmo;
import imgui.extension.imguizmo.flag.Mode;
import imgui.extension.imguizmo.flag.Operation;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.qualet.irlredactor.light.LightConfig;
import org.qualet.irlredactor.light.LightScene;
import org.qualet.irlredactor.light.PlacedLight;
import org.qualet.irlredactor.light.auto.AutoLightManager;
import org.qualet.irlredactor.light.cookie.CookieArray;

import java.util.List;

/**
 * Builds the light-editor panel in immediate mode, mirroring the layout of the
 * HTML prototype with a BBS / vanilla-Minecraft look. Now wired to the live
 * pipeline: it manages the {@link LightScene} (add / duplicate / delete / select)
 * and edits the selected {@link PlacedLight} through the {@link LightState} buffer.
 *
 * <p>Per-frame flow: draw the source list (may change the selection), {@link
 * #syncSelection() pull} on a selection change, draw the editor groups bound to
 * {@link #state}, then {@link LightSync#push push} the buffer back into the
 * selected light. The driver reads the scene each frame, so edits are live.</p>
 */
public class LightEditorPanel
{
    private static final float PANEL_W = 360f;
    private static final float WIN_PAD_X = 12f;
    private static final float ITEM_SP_X = 6f;

    /** Gizmo size in clip space (ImGuizmo default 0.1 — smaller = more modest). */
    private static final float GIZMO_SIZE = 0.08f;

    /** Source-list scroll region: row height (selectable 22 + item spacing 6) and
     *  how many rows are shown before it scrolls. */
    private static final float LIST_ROW_H = 28f;
    private static final int LIST_VISIBLE_ROWS = 6;

    /** Shader-patcher popup (visual prototype; opened from the engine settings). */
    private final PatcherPanel patcher = new PatcherPanel();

    /** ImGui scratch mirrored to/from the selected light. */
    private final LightState state = new LightState();
    /** Currently edited light (null when the scene is empty / nothing selected). */
    private PlacedLight selected;
    /** id currently mirrored into {@link #state}; drives the pull-on-change. */
    private long syncedId = 0L;

    /** Cached cookie file list for the gobo picker; null = needs a (re)scan. */
    private String[] cookieFiles;

    // Engine-settings mirrors (LightConfig is plain static fields; toggles need ImBoolean).
    private final ImBoolean cfgCache  = new ImBoolean(LightConfig.shadowCache);
    private final ImBoolean cfgBlocks = new ImBoolean(LightConfig.shadowBlocks);
    private final ImBoolean cfgGuides = new ImBoolean(LightConfig.showGuides);
    private final float[]   cfgRadius = { LightConfig.shadowBlockRadius };
    private final ImBoolean cfgAutoLights    = new ImBoolean(LightConfig.autoLights);
    private final ImBoolean cfgAutoShadows   = new ImBoolean(LightConfig.autoLightShadows);
    private final float[]   cfgAutoIntensity = { LightConfig.autoLightIntensity };
    private final float[]   cfgAutoReach     = { LightConfig.autoLightReach };
    private final float[]   cfgAutoRadius    = { LightConfig.autoLightRadius };
    private final float[]   cfgAutoMax       = { LightConfig.autoLightMax };

    /** Experimental-feature warning popup id. */
    private static final String WARN_POPUP_ID = "##irl_auto_warn";
    /** Shown once per game session (JVM): set when the warning is first displayed
     *  after the user enables auto-lights; static so it survives the editor being
     *  reopened, and resets on a game restart. */
    private static boolean experimentalWarnShown;
    /** Request to open the warning popup on the next root render. */
    private boolean wantOpenWarn;

    // Reused per-frame matrix buffers for the move/rotate gizmo (column-major float[16]).
    private final float[] gizmoView  = new float[16];
    private final float[] gizmoProj  = new float[16];
    private final float[] gizmoModel = new float[16];
    private final Matrix4f mat = new Matrix4f();
    // Spot orientation scratch: persisted across frames so a rotate drag stays
    // continuous; rebuilt from state.dir whenever the gizmo isn't being dragged.
    private final Matrix4f gizmoRot = new Matrix4f();
    private final Quaternionf gizmoQuat = new Quaternionf();
    private boolean gizmoRotating;

    public void draw()
    {
        // Must run after ImGui.newFrame() (it does, via ImGuiRuntime.frame) and
        // before any ImGuizmo.manipulate this frame.
        ImGuizmo.beginFrame();

        float w = ImGui.getIO().getDisplaySizeX();
        float h = ImGui.getIO().getDisplaySizeY();

        ImGui.setNextWindowPos(w - PANEL_W, 0f);
        ImGui.setNextWindowSize(PANEL_W, h);

        int flags = ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoBringToFrontOnFocus;

        if (ImGui.begin("##irl_panel", flags))
        {
            sourceList();
            syncSelection();

            ImGui.separator();

            if (selected == null)
            {
                Widgets.textDisabled(Lang.t("irl-redactor.editor.empty1"));
                Widgets.textDisabled(Lang.t("irl-redactor.editor.empty2"));
            }
            else
            {
                header();
                ImGui.separator();
                placementGroup();
                basicGroup();
                cookieGroup();
                volumetricGroup();
                shadowGroup();
            }

            ImGui.separator();
            engineGroup();

            ImGui.separator();
            Widgets.textDisabled(Lang.t("irl-redactor.editor.footer"));
        }

        ImGui.end();

        // The move gizmo is drawn over the world (background draw list, so it sits
        // behind the panel) and may update state.pos; push commits everything —
        // including a gizmo drag — into the engine model for the driver next frame.
        if (selected != null)
        {
            drawGizmo();
            LightSync.push(state, selected);
        }

        // Rendered at the root (after the panel's end) so the modal sits on top
        // of the panel and dims the world behind it.
        patcher.draw();
        drawExperimentalWarning();
    }

    /** One-shot-per-session experimental-feature warning, shown when auto-lights
     *  are first enabled. Mirrors the patcher's deferred openPopup-at-root pattern
     *  so the modal isn't nested inside the panel window. */
    private void drawExperimentalWarning()
    {
        if (wantOpenWarn)
        {
            ImGui.openPopup(WARN_POPUP_ID);
            wantOpenWarn = false;
        }

        float cx = ImGui.getIO().getDisplaySizeX() * 0.5f;
        float cy = ImGui.getIO().getDisplaySizeY() * 0.5f;
        ImGui.setNextWindowPos(cx, cy, ImGuiCond.Appearing, 0.5f, 0.5f);
        // Fixed width, auto height (0). NOT AlwaysAutoResize — that would size to
        // the unwrapped text and make the modal very wide; an explicit wrap pos
        // below keeps the body wrapped at the fixed width.
        ImGui.setNextWindowSize(360f, 0f, ImGuiCond.Appearing);

        int flags = ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoResize;

        if (ImGui.beginPopupModal(WARN_POPUP_ID, flags))
        {
            Widgets.text(Lang.t("irl-redactor.editor.autoLightWarnTitle"));
            ImGui.dummy(0f, 4f);
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + 336f);
            ImGui.textWrapped(Lang.t("irl-redactor.editor.autoLightWarnBody"));
            ImGui.popTextWrapPos();
            ImGui.dummy(0f, 8f);
            if (Widgets.primaryButton("warn_ok", Lang.t("irl-redactor.editor.autoLightWarnOk"), ImGui.getContentRegionAvail().x))
            {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    // ---- source list (Phase B) --------------------------------------------

    private void sourceList()
    {
        Widgets.textDisabled(Lang.t("irl-redactor.editor.sources"));

        float avail = ImGui.getContentRegionAvail().x;
        float btnW = (avail - 2f * ITEM_SP_X) / 3f;

        if (Widgets.button("add", Lang.t("irl-redactor.editor.add"), btnW, false))
        {
            addLight();
        }
        ImGui.sameLine();
        if (Widgets.button("dup", Lang.t("irl-redactor.editor.duplicate"), btnW, false))
        {
            duplicateSelected();
        }
        ImGui.sameLine();
        if (Widgets.button("del", Lang.t("irl-redactor.editor.delete"), btnW, false))
        {
            deleteSelected();
        }

        // Mutations above happen outside iteration; here we only read + capture a
        // click, applying the selection change after the loop (no CME). The rows
        // live in a fixed-height scroll region so a long list never pushes the
        // editor groups off-screen.
        PlacedLight toSelect = null;
        List<PlacedLight> all = LightScene.all();
        if (!all.isEmpty())
        {
            int visible = Math.min(all.size(), LIST_VISIBLE_ROWS);
            float listH = visible * LIST_ROW_H + 4f;
            if (ImGui.beginChild("##src_list", 0f, listH, true))
            {
                for (int i = 0; i < all.size(); i++)
                {
                    PlacedLight l = all.get(i);
                    String type = Lang.t(l.type == PlacedLight.Type.SPOT
                        ? "irl-redactor.editor.type.spot" : "irl-redactor.editor.type.point");
                    if (Widgets.selectable("li_" + l.id, l.name, type, l == selected))
                    {
                        toSelect = l;
                    }
                }
            }
            ImGui.endChild();
        }
        if (toSelect != null)
        {
            selected = toSelect;
        }
    }

    private void addLight()
    {
        PlacedLight l = PlacedLight.point();
        Vec3d eye = playerEye();
        if (eye != null)
        {
            l.x = eye.x; l.y = eye.y; l.z = eye.z;
        }
        l.name = Lang.t("irl-redactor.editor.sourceName", l.id);
        LightScene.add(l);
        selected = l;
    }

    private void duplicateSelected()
    {
        if (selected == null)
        {
            return;
        }
        PlacedLight l = PlacedLight.copyOf(selected);
        l.x += 1.0; // nudge so the copy isn't hidden inside the original
        l.name = duplicateName(selected.name);
        LightScene.add(l);
        selected = l;
    }

    // Matches a trailing "копия"/"copy" [N] tail in either provided language, so the
    // word never piles up when duplicating (even across an in-game language switch).
    private static final java.util.regex.Pattern COPY_SUFFIX =
        java.util.regex.Pattern.compile("^(.*?)\\s+(?:копия|copy)(?:\\s+\\d+)?$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** "света" -&gt; "света копия" -&gt; "света копия 2"… (the suffix word follows the
     *  game language). Strips any stacked copy tail first, so it never piles up. */
    private static String duplicateName(String name)
    {
        String base = name == null ? Lang.t("irl-redactor.editor.sourceBase") : name;
        java.util.regex.Matcher m;
        while ((m = COPY_SUFFIX.matcher(base)).matches() && !m.group(1).isEmpty())
        {
            base = m.group(1);
        }

        String suffix = Lang.t("irl-redactor.editor.copySuffix");
        String candidate = base + " " + suffix;
        for (int n = 2; nameExists(candidate); n++)
        {
            candidate = base + " " + suffix + " " + n;
        }
        return candidate;
    }

    private static boolean nameExists(String name)
    {
        for (PlacedLight l : LightScene.all())
        {
            if (name.equals(l.name))
            {
                return true;
            }
        }
        return false;
    }

    private void deleteSelected()
    {
        if (selected == null)
        {
            return;
        }
        List<PlacedLight> all = LightScene.all();
        int idx = all.indexOf(selected);
        LightScene.remove(selected);
        selected = all.isEmpty() ? null : all.get(Math.min(idx, all.size() - 1));
    }

    /** Pull engine -> UI once whenever the selection identity changes. */
    private void syncSelection()
    {
        // Drop a selection that no longer exists (world reload / external clear),
        // so we never push edits into a light detached from the scene.
        if (selected != null && !LightScene.all().contains(selected))
        {
            selected = null;
        }

        if (selected == null)
        {
            syncedId = 0L;
            return;
        }
        if (selected.id != syncedId)
        {
            LightSync.pull(selected, state);
            syncedId = selected.id;
        }
    }

    // ---- header ------------------------------------------------------------

    private void header()
    {
        Widgets.textDisabled(Lang.t("irl-redactor.editor.lightSource"));
        ImGui.sameLine();

        float btnW = 56f;
        ImGui.setCursorPosX(ImGui.getWindowWidth() - btnW - WIN_PAD_X);
        if (Widgets.button("reset", Lang.t("irl-redactor.editor.reset"), btnW, false))
        {
            state.reset();
        }

        ImGui.setNextItemWidth(-1f);
        ImGui.inputText("##name", state.name);

        float segW = (ImGui.getContentRegionAvail().x - ITEM_SP_X) * 0.5f;
        if (Widgets.button("seg_point", Lang.t("irl-redactor.editor.type.point"), segW, state.type == LightState.Type.POINT))
        {
            state.type = LightState.Type.POINT;
        }
        ImGui.sameLine();
        if (Widgets.button("seg_spot", Lang.t("irl-redactor.editor.type.spot"), segW, state.type == LightState.Type.SPOT))
        {
            state.type = LightState.Type.SPOT;
        }
    }

    // ---- placement group (Phase C) ----------------------------------------

    private void placementGroup()
    {
        if (!Widgets.collapsingHeader("placement", Lang.t("irl-redactor.editor.placement"), true))
        {
            return;
        }

        Widgets.dragValue("pos_x", "X", state.pos, 0, 0.05f, "%.2f");
        Widgets.dragValue("pos_y", "Y", state.pos, 1, 0.05f, "%.2f");
        Widgets.dragValue("pos_z", "Z", state.pos, 2, 0.05f, "%.2f");

        if (Widgets.button("place_here", Lang.t("irl-redactor.editor.moveHere"), ImGui.getContentRegionAvail().x, false))
        {
            Vec3d eye = playerEye();
            if (eye != null)
            {
                state.pos[0] = (float) eye.x;
                state.pos[1] = (float) eye.y;
                state.pos[2] = (float) eye.z;
            }
        }

        if (state.type == LightState.Type.SPOT)
        {
            Widgets.textDisabled(Lang.t("irl-redactor.editor.direction",
                fmt(state.dir[0]), fmt(state.dir[1]), fmt(state.dir[2])));
            if (Widgets.button("aim_look", Lang.t("irl-redactor.editor.aimLook"), ImGui.getContentRegionAvail().x, false))
            {
                Vec3d look = playerLook();
                if (look != null)
                {
                    state.dir[0] = (float) look.x;
                    state.dir[1] = (float) look.y;
                    state.dir[2] = (float) look.z;
                }
            }
        }
    }

    // ---- basic group -------------------------------------------------------

    private void basicGroup()
    {
        if (!Widgets.collapsingHeader("basic", Lang.t("irl-redactor.editor.basic"), true))
        {
            return;
        }

        Widgets.text(Lang.t("irl-redactor.editor.color"));
        ImGui.sameLine();
        float swatchW = 46f;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getContentRegionAvail().x - swatchW);
        ImGui.colorEdit4("##color", state.color, ImGuiColorEditFlags.NoInputs);

        Widgets.trackpad("intensity", Lang.t("irl-redactor.editor.intensity"), state.intensity, 0f, 20f, "%.2f");

        if (state.type == LightState.Type.POINT)
        {
            Widgets.trackpad("radius", Lang.t("irl-redactor.editor.radius"), state.radius, 0.1f, 64f, "%.1f");
        }
        else
        {
            Widgets.trackpad("range", Lang.t("irl-redactor.editor.range"), state.range, 0.1f, 128f, "%.1f");
            Widgets.trackpad("angle", Lang.t("irl-redactor.editor.angle"), state.angle, 1f, 179f, "%.0f°");
            Widgets.trackpad("soft", Lang.t("irl-redactor.editor.soft"), state.soft, 0f, 60f, "%.0f°");
        }
    }

    private void volumetricGroup()
    {
        if (!Widgets.collapsingHeader("vol", Lang.t("irl-redactor.editor.volumetric"), false))
        {
            return;
        }

        Widgets.toggleRow("vol_on", Lang.t("irl-redactor.editor.enable"), state.vol);

        ImGui.beginDisabled(!state.vol.get());
        Widgets.trackpad("beam", Lang.t("irl-redactor.editor.beam"), state.beam, 0f, 5f, "%.2f");
        Widgets.textDisabled(Lang.t("irl-redactor.editor.fineTune"));
        Widgets.trackpad("density", Lang.t("irl-redactor.editor.density"), state.density, 0.005f, 0.5f, "%.3f");
        Widgets.trackpad("aniso", Lang.t("irl-redactor.editor.aniso"), state.aniso, -0.95f, 0.95f, "%.2f");
        ImGui.endDisabled();
    }

    private void shadowGroup()
    {
        if (!Widgets.collapsingHeader("shadows", Lang.t("irl-redactor.editor.shadowsBehavior"), false))
        {
            return;
        }

        Widgets.toggleRow("shadows_on", Lang.t("irl-redactor.editor.shadows"), state.shadows);
        Widgets.trackpad("bulb", Lang.t("irl-redactor.editor.shadowSoft"), state.bulb, 0f, 2f, "%.2f");

        if (Widgets.toggleRow("entities", Lang.t("irl-redactor.editor.entitiesOnly"), state.entitiesOnly) && state.entitiesOnly.get())
        {
            state.blocksOnly.set(false);
        }
        if (Widgets.toggleRow("blocks", Lang.t("irl-redactor.editor.blocksOnly"), state.blocksOnly) && state.blocksOnly.get())
        {
            state.entitiesOnly.set(false);
        }
    }

    // ---- cookie / gobo (spot only) ----------------------------------------

    /** Projected-mask picker, shown only for spotlights: pick an image from the
     *  cookies folder + rotation / scale / invert. A point light has no projection
     *  frustum, so the whole group is hidden for it. */
    private void cookieGroup()
    {
        if (state.type != LightState.Type.SPOT)
        {
            return;
        }
        if (!Widgets.collapsingHeader("cookie", Lang.t("irl-redactor.editor.cookie"), false))
        {
            return;
        }

        if (cookieFiles == null)
        {
            refreshCookieFiles();
        }

        // file picker: a "(none)" row + every image in the cookies folder
        float listH = 5f * 26f + 6f;
        if (ImGui.beginChild("##cookie_list", 0f, listH, true))
        {
            String cur = state.cookie.get();
            if (Widgets.listItem("ck_none", Lang.t("irl-redactor.editor.cookieNone"), cur.isEmpty()))
            {
                state.cookie.set("");
            }
            for (String f : cookieFiles)
            {
                if (Widgets.listItem("ck_" + f, f, f.equals(cur)))
                {
                    state.cookie.set(f);
                }
            }
        }
        ImGui.endChild();

        float avail = ImGui.getContentRegionAvail().x;
        float btnW = (avail - ITEM_SP_X) * 0.5f;
        if (Widgets.button("ck_refresh", Lang.t("irl-redactor.editor.cookieRefresh"), btnW, false))
        {
            CookieArray.reload();
            refreshCookieFiles();
        }
        ImGui.sameLine();
        if (Widgets.button("ck_folder", Lang.t("irl-redactor.editor.cookieFolder"), btnW, false))
        {
            openCookieFolder();
        }

        ImGui.beginDisabled(state.cookie.get().isEmpty());
        Widgets.trackpad("ck_rot", Lang.t("irl-redactor.editor.cookieRotation"), state.cookieRotation, 0f, 360f, "%.0f°");
        Widgets.trackpad("ck_scale", Lang.t("irl-redactor.editor.cookieScale"), state.cookieScale, 0.1f, 4f, "%.2f");
        Widgets.toggleRow("ck_invert", Lang.t("irl-redactor.editor.cookieInvert"), state.cookieInvert);
        ImGui.endDisabled();

        Widgets.textDisabled(Lang.t("irl-redactor.editor.cookieHint"));
    }

    private void refreshCookieFiles()
    {
        cookieFiles = CookieArray.available().toArray(new String[0]);
    }

    private void openCookieFolder()
    {
        try
        {
            java.nio.file.Path d = CookieArray.dir();
            java.nio.file.Files.createDirectories(d);
            net.minecraft.util.Util.getOperatingSystem().open(d.toFile());
        }
        catch (Exception ignored)
        {
            // best-effort: opening the OS file manager is non-critical
        }
    }

    // ---- engine settings (Phase D) ----------------------------------------

    private void engineGroup()
    {
        if (!Widgets.collapsingHeader("engine", Lang.t("irl-redactor.editor.engine"), false))
        {
            return;
        }

        Widgets.text(Lang.t("irl-redactor.editor.shadowQuality"));
        String[] q = {"LOW", "MED", "HIGH", "ULTRA"};
        float segW = (ImGui.getContentRegionAvail().x - 3f * ITEM_SP_X) / 4f;
        for (int i = 0; i < q.length; i++)
        {
            if (Widgets.button("q_" + i, q[i], segW, LightConfig.shadowQuality == i))
            {
                LightConfig.shadowQuality = i;
            }
            if (i < q.length - 1)
            {
                ImGui.sameLine();
            }
        }

        Widgets.toggleRow("cfg_cache", Lang.t("irl-redactor.editor.shadowCache"), cfgCache);
        LightConfig.shadowCache = cfgCache.get();

        Widgets.toggleRow("cfg_blocks", Lang.t("irl-redactor.editor.shadowBlocks"), cfgBlocks);
        LightConfig.shadowBlocks = cfgBlocks.get();

        Widgets.trackpad("cfg_radius", Lang.t("irl-redactor.editor.shadowBlockRadius"), cfgRadius, 4f, 96f, "%.0f");
        LightConfig.shadowBlockRadius = Math.round(cfgRadius[0]);

        Widgets.toggleRow("cfg_guides", Lang.t("irl-redactor.editor.showGuides"), cfgGuides);
        LightConfig.showGuides = cfgGuides.get();

        // --- auto block-lights ---
        ImGui.dummy(0f, 4f);
        boolean autoWas = LightConfig.autoLights;
        Widgets.toggleRow("cfg_autolights", Lang.t("irl-redactor.editor.autoLights"), cfgAutoLights);
        LightConfig.autoLights = cfgAutoLights.get();
        // Just turned ON -> show the experimental-feature warning, once per session.
        if (LightConfig.autoLights && !autoWas && !experimentalWarnShown)
        {
            experimentalWarnShown = true;
            wantOpenWarn = true;
        }

        ImGui.beginDisabled(!LightConfig.autoLights);
        Widgets.toggleRow("cfg_autoshadows", Lang.t("irl-redactor.editor.autoLightShadows"), cfgAutoShadows);
        LightConfig.autoLightShadows = cfgAutoShadows.get();

        // Brightness / reach scale the hardcoded per-block table; scan radius is
        // how far emitters are searched for. Changes are picked up by the rolling
        // scan within ~1s (no explicit signal needed).
        Widgets.trackpad("cfg_autointensity", Lang.t("irl-redactor.editor.autoLightIntensity"), cfgAutoIntensity, 0f, 5f, "%.2f");
        LightConfig.autoLightIntensity = cfgAutoIntensity[0];
        Widgets.trackpad("cfg_autoreach", Lang.t("irl-redactor.editor.autoLightReach"), cfgAutoReach, 0.25f, 3f, "%.2f");
        LightConfig.autoLightReach = cfgAutoReach[0];
        Widgets.trackpad("cfg_autoradius", Lang.t("irl-redactor.editor.autoLightRadius"), cfgAutoRadius, 8f, 96f, "%.0f");
        LightConfig.autoLightRadius = Math.round(cfgAutoRadius[0]);

        // Source limit (nearest-first). Applied live in the feed — no rescan needed,
        // so it's NOT in autoChanged. High values get expensive: the shaderpack
        // loops over every light per pixel. 0 = no auto-lights.
        Widgets.trackpad("cfg_automax", Lang.t("irl-redactor.editor.autoLightMax"), cfgAutoMax, 0f, 2000f, "%.0f");
        LightConfig.autoLightMax = Math.round(cfgAutoMax[0]);

        Widgets.textDisabled(Lang.t("irl-redactor.editor.autoLightActive", AutoLightManager.count()));
        ImGui.endDisabled();

        ImGui.dummy(0f, 2f);
        if (Widgets.button("open_patcher", Lang.t("irl-redactor.patcher.title"), ImGui.getContentRegionAvail().x, false))
        {
            patcher.open();
        }
    }

    // ---- move gizmo --------------------------------------------------------

    /**
     * Draws an ImGuizmo handle at the selected light and writes any drag back into
     * {@code state}. Point lights get a translate handle; spotlights get translate
     * + rotate rings, with the dragged orientation read back into {@code state.dir}
     * (the engine model stays a plain direction vector — no orientation is stored
     * on {@code PlacedLight}).
     *
     * <p>The view/projection matrices are reconstructed to mirror Minecraft's world
     * camera exactly (rotation Rx(pitch)·Ry(yaw+180); a perspective with the current
     * vertical FOV + framebuffer aspect), so the gizmo lines up with the rendered
     * world. Near/far don't affect the on-screen x/y of a projected point, so the
     * placeholder far plane is harmless.</p>
     */
    private void drawGizmo()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        Camera cam = mc.gameRenderer == null ? null : mc.gameRenderer.getCamera();
        if (cam == null || mc.world == null || mc.getWindow().getFramebufferHeight() == 0)
        {
            return;
        }

        boolean spot = state.type == LightState.Type.SPOT;
        Vec3d cp = cam.getPos();
        float aspect = (float) mc.getWindow().getFramebufferWidth() / (float) mc.getWindow().getFramebufferHeight();
        double fovDeg = mc.options.getFov().getValue();

        // View = camera rotation only; world is kept camera-relative via the model.
        mat.identity()
            .rotateX((float) Math.toRadians(cam.getPitch()))
            .rotateY((float) Math.toRadians(cam.getYaw() + 180.0));
        mat.get(gizmoView);

        mat.identity().perspective((float) Math.toRadians(fovDeg), aspect, 0.05f, 1000f);
        mat.get(gizmoProj);

        // Model = translate to the light (camera-relative, float-safe) · orientation.
        mat.identity().translation(
            (float) (state.pos[0] - cp.x),
            (float) (state.pos[1] - cp.y),
            (float) (state.pos[2] - cp.z));
        if (spot)
        {
            // Sync the orientation from dir except while actively rotating (keeps a
            // rotate drag continuous instead of snapping back to a canonical roll).
            if (!gizmoRotating)
            {
                orientationFromDir();
            }
            mat.mul(gizmoRot);
        }
        mat.get(gizmoModel);

        int op = spot ? (Operation.TRANSLATE | Operation.ROTATE) : Operation.TRANSLATE;
        ImGuizmo.setOrthographic(false);
        ImGuizmo.setDrawList(ImGui.getBackgroundDrawList());
        ImGuizmo.setRect(0f, 0f, ImGui.getIO().getDisplaySizeX(), ImGui.getIO().getDisplaySizeY());
        ImGuizmo.setGizmoSizeClipSpace(GIZMO_SIZE); // a touch smaller than default 0.1
        ImGuizmo.allowAxisFlip(false);              // steady axes, no flip toward camera
        ImGuizmo.manipulate(gizmoView, gizmoProj, op, Mode.WORLD, gizmoModel);

        boolean using = ImGuizmo.isUsing();
        if (using)
        {
            state.pos[0] = (float) (cp.x + gizmoModel[12]);
            state.pos[1] = (float) (cp.y + gizmoModel[13]);
            state.pos[2] = (float) (cp.z + gizmoModel[14]);

            if (spot)
            {
                // dir = the model's forward (local +Z) column, normalized.
                float dx = gizmoModel[8], dy = gizmoModel[9], dz = gizmoModel[10];
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 1e-4f)
                {
                    state.dir[0] = dx / len;
                    state.dir[1] = dy / len;
                    state.dir[2] = dz / len;
                }
                // Persist the manipulated rotation for next frame's continuity.
                gizmoRot.set(gizmoModel).setTranslation(0f, 0f, 0f);
            }
        }

        gizmoRotating = using && spot;
    }

    /** Rebuilds {@link #gizmoRot} as the rotation taking local +Z to {@code state.dir}. */
    private void orientationFromDir()
    {
        float dx = state.dir[0], dy = state.dir[1], dz = state.dir[2];
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f)
        {
            dx = 0f; dy = -1f; dz = 0f; len = 1f;
        }
        gizmoQuat.rotationTo(0f, 0f, 1f, dx / len, dy / len, dz / len);
        gizmoRot.rotation(gizmoQuat);
    }

    // ---- world helpers -----------------------------------------------------

    /** Locale-stable %.2f for the direction readout (avoids comma decimals). */
    private static String fmt(float v)
    {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static Vec3d playerEye()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player == null ? null : new Vec3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
    }

    private static Vec3d playerLook()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player == null ? null : mc.player.getRotationVector();
    }
}
