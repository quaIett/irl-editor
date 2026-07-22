package org.qualet.irlredactor.editor;

import imgui.ImGui;
import imgui.extension.imguizmo.ImGuizmo;
import imgui.extension.imguizmo.flag.Mode;
import imgui.extension.imguizmo.flag.Operation;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.qualet.irl.light.LightMath;
import org.qualet.irlredactor.client.diag.VlProfiler;
import org.qualet.irlredactor.light.LightConfig;
import org.qualet.irlredactor.light.LightScene;
import org.qualet.irlredactor.light.PlacedLight;
import org.qualet.irlredactor.light.auto.AutoLightManager;
import org.qualet.irlredactor.light.cookie.CookieArray;

import java.util.List;

/**
 * Builds the light-editor panel in immediate mode with the new two-area layout:
 * a left-docked panel (sources + the selected light's inspector) plus a separate
 * movable "Settings" window on the right holding the global engine settings,
 * grouped into a category nav.
 *
 * <p>Per-frame flow: draw the source list (may change the selection), {@link
 * #syncSelection() pull} on a selection change, draw the inspector groups bound to
 * {@link #state}, then {@link LightSync#push push} the buffer back into the
 * selected light. The driver reads the scene each frame, so edits are live.</p>
 */
public class LightEditorPanel
{
    private static final float PANEL_W = 360f;
    /** Left panel floor height: it auto-sizes to content above this, so short tabs
     *  don't leave a tall void, while the floor keeps it from collapsing and limits
     *  jitter when switching between the shortest tabs. */
    private static final float MIN_PANEL_H = 480f;
    private static final float WIN_PAD_X = 12f;
    private static final float ITEM_SP_X = 6f;

    /** Gizmo size in clip space (ImGuizmo default 0.1 — smaller = more modest). */
    private static final float GIZMO_SIZE = 0.08f;

    /** Source-list row height (selectable 22 + item spacing 6). */
    private static final float LIST_ROW_H = 28f;
    /** Minimum source rows kept before the list scrolls. */
    private static final int LIST_MIN_ROWS = 3;
    /** Space (px) reserved below the list for the inspector + Settings + footer, so
     *  the list grows with the source count only into room that keeps the rest
     *  on-screen (beyond that it scrolls) — lets the panel reach the screen bottom. */
    private static final float LIST_RESERVE = 420f;

    /** Shader-patcher popup (visual prototype; opened from the settings window). */
    private final PatcherPanel patcher = new PatcherPanel();

    /** ImGui scratch mirrored to/from the selected light. */
    private final LightState state = new LightState();
    /** Currently edited light (null when the scene is empty / nothing selected). */
    private PlacedLight selected;
    /** id currently mirrored into {@link #state}; drives the pull-on-change. */
    private long syncedId = 0L;

    /** Cached cookie file list for the gobo picker; null = needs a (re)scan. */
    private String[] cookieFiles;

    // ---- settings window (right, movable) ---------------------------------
    private static final float SETTINGS_W = 600f;
    private static final int CAT_PRESETS = 0;
    private static final int CAT_VOLUMETRIC = 1;
    private static final int CAT_SHADOWS = 2;
    private static final int CAT_OUTLINE = 3;
    private static final int CAT_AUTO = 4;
    private static final int CAT_PATCHER = 5;
    /** Whether the floating settings window is shown (toggled from the left panel,
     *  closed via its own title-bar [x] through this same flag). */
    private final ImBoolean settingsOpen = new ImBoolean(false);
    /** Active settings category (one of the CAT_* constants). */
    private int settingsCat = CAT_PRESETS;
    /** Settings search box buffer (filtering wired up in a later step). */
    private final ImString settingsSearch = new ImString(64);

    // ---- inspector tabs ---------------------------------------------------
    private static final int TAB_PLACEMENT = 0;
    private static final int TAB_BASIC = 1;
    private static final int TAB_VOLUME = 2;
    private static final int TAB_SHADOW = 3;
    /** Active inspector tab (one of the TAB_* constants). */
    private int inspectorTab = TAB_PLACEMENT;

    // Perf-section mirrors. Resynced from the source of truth every frame before
    // drawing (unlike the one-directional cfg* mirrors below): holdBake is also
    // flipped outside the panel (world-join arm, "bake now" button), and the
    // profiler pre-arm flag may have set collection at boot.
    private final ImBoolean cfgPerfProfiler = new ImBoolean(false);
    private final ImBoolean cfgHoldBake = new ImBoolean(false);
    private final ImBoolean cfgHoldOnJoin = new ImBoolean(LightConfig.holdBakeOnJoin);

    // Engine-settings mirrors (LightConfig is plain static fields; toggles need ImBoolean).
    private final ImBoolean cfgCache  = new ImBoolean(LightConfig.shadowCache);
    private final ImBoolean cfgBlocks = new ImBoolean(LightConfig.shadowBlocks);
    private final ImBoolean cfgGuides = new ImBoolean(LightConfig.showGuides);
    private final float[]   cfgRadius = { LightConfig.shadowBlockRadius };
    private final ImBoolean cfgAutoLights    = new ImBoolean(LightConfig.autoLights);
    private final ImBoolean cfgAutoCulling   = new ImBoolean(LightConfig.autoLightCulling);
    private final ImBoolean cfgAutoShadows   = new ImBoolean(LightConfig.autoLightShadows);
    private final float[]   cfgAutoIntensity = { LightConfig.autoLightIntensity };
    private final float[]   cfgAutoReach     = { LightConfig.autoLightReach };
    private final float[]   cfgAutoRadius    = { LightConfig.autoLightRadius };
    private final float[]   cfgAutoMax       = { LightConfig.autoLightMax };

    // Global-VL mirrors: pushed into the binding-7 globals UBO each frame by
    // LightDriver.collect, so every knob applies live (no shader recompile).
    private final float[]   cfgVlIntensity    = { LightConfig.vlIntensity };
    private final float[]   cfgVlSteps        = { LightConfig.vlSteps };
    private final float[]   cfgVlMaxDist      = { LightConfig.vlMaxDist };
    private final ImBoolean cfgVlShadows      = new ImBoolean(LightConfig.vlShadows);
    private final float[]   cfgVlShadowStride = { LightConfig.vlShadowStride };
    private final float[]   cfgVlTipBoost     = { LightConfig.vlTipBoost };
    private final float[]   cfgVlTipRadius    = { LightConfig.vlTipRadius };
    private final ImBoolean cfgVlNoise        = new ImBoolean(LightConfig.vlNoise);
    private final float[]   cfgVlNoiseAmount  = { LightConfig.vlNoiseAmount };
    private final float[]   cfgVlNoiseScale   = { LightConfig.vlNoiseScale };
    private final float[]   cfgVlNoiseSpeed   = { LightConfig.vlNoiseSpeed };
    private final float[]   cfgVlNoiseMorph   = { LightConfig.vlNoiseMorph };
    private final float[]   cfgVlNoiseStride  = { LightConfig.vlNoiseStride };
    private final ImBoolean cfgVlDitherTemporal = new ImBoolean(LightConfig.vlDitherTemporal);

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

        // Left-docked panel: sources + the selected light's inspector. Global
        // engine settings live in the separate movable window (drawSettingsWindow).
        ImGui.setNextWindowPos(0f, 0f);
        // Auto-height: the panel hugs its content (width locked to PANEL_W, height
        // between MIN_PANEL_H and the screen) instead of filling the screen, so a
        // short tab no longer leaves a tall void above the footer.
        ImGui.setNextWindowSizeConstraints(PANEL_W, MIN_PANEL_H, PANEL_W, h);

        int flags = ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoBringToFrontOnFocus
            | ImGuiWindowFlags.AlwaysAutoResize;

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
                inspectorTabs();
                switch (inspectorTab)
                {
                    case TAB_BASIC -> { basicGroup(); cookieGroup(); }
                    case TAB_VOLUME -> volumetricGroup();
                    case TAB_SHADOW -> shadowGroup();
                    default -> placementGroup();
                }
            }

            // "Settings" opener + footer, right after the inspector (the panel
            // auto-sizes to content now, so there is nothing to pin against).
            ImGui.dummy(0f, 4f);
            ImGui.separator();
            if (Widgets.button("open_settings", Lang.t("irl-redactor.editor.openSettings"),
                ImGui.getContentRegionAvail().x, settingsOpen.get()))
            {
                settingsOpen.set(!settingsOpen.get());
            }
            Widgets.textDisabled(Lang.t("irl-redactor.editor.footer"));
        }

        ImGui.end();

        // Global settings, in a separate movable window on the right.
        drawSettingsWindow(w, h);

        // The move gizmo is drawn over the world (background draw list, so it sits
        // behind the panel) and may update state.pos; push commits everything —
        // including a gizmo drag — into the engine model for the driver next frame.
        if (selected != null)
        {
            drawGizmo();
            LightSync.push(state, selected);
        }

        // Rendered at the root (after the panel's end) so the modals sit on top
        // of the panel and dim the world behind them.
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
        // inspector off-screen.
        PlacedLight toSelect = null;
        List<PlacedLight> all = LightScene.all();
        if (!all.isEmpty())
        {
            // Grow the list with the source count so the panel can reach the bottom
            // of the screen, but cap it so the inspector + footer below stay visible;
            // beyond that the list scrolls internally.
            float screenH = ImGui.getIO().getDisplaySizeY();
            float maxListH = Math.max(LIST_MIN_ROWS * LIST_ROW_H, screenH - LIST_RESERVE);
            float listH = Math.min(all.size() * LIST_ROW_H + 4f, maxListH);
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

    // ---- inspector tab strip ----------------------------------------------

    /** Horizontal tab strip under the light header: switches which inspector
     *  section is shown (Placement / Basic / Volume / Shadows), matching the
     *  HTML prototype. */
    private void inspectorTabs()
    {
        String[] labels = {
            Lang.t("irl-redactor.editor.tab.placement"),
            Lang.t("irl-redactor.editor.tab.basic"),
            Lang.t("irl-redactor.editor.tab.volume"),
            Lang.t("irl-redactor.editor.tab.shadows"),
        };
        float cellW = ImGui.getContentRegionAvail().x / labels.length;
        for (int i = 0; i < labels.length; i++)
        {
            if (Widgets.tab("insp_tab_" + i, labels[i], cellW, inspectorTab == i))
            {
                inspectorTab = i;
            }
            if (i < labels.length - 1)
            {
                ImGui.sameLine(0f, 0f);
            }
        }
        ImGui.dummy(0f, 4f);
    }

    // ---- placement group (Phase C) ----------------------------------------

    private void placementGroup()
    {
        Widgets.dragValue("pos_x", "X", state.pos, 0, 0.05f, "%.2f");
        Widgets.dragValue("pos_y", "Y", state.pos, 1, 0.05f, "%.2f");
        Widgets.dragValue("pos_z", "Z", state.pos, 2, 0.05f, "%.2f");

        if (Widgets.button("place_here", Lang.t("irl-redactor.editor.moveHere"), ImGui.getContentRegionAvail().x, false))
        {
            Vec3d eye = playerEye();
            if (eye != null)
            {
                state.pos[0] = eye.x;
                state.pos[1] = eye.y;
                state.pos[2] = eye.z;
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

    // ---- settings window (right, movable) ---------------------------------

    /** The floating global-settings window: a normal, movable ImGui window with a
     *  category nav on the left and the selected category's controls on the right.
     *  Opened from the left panel's "Settings" button, closed via its title-bar
     *  [x] (both routed through {@link #settingsOpen}). */
    private void drawSettingsWindow(float w, float h)
    {
        if (!settingsOpen.get())
        {
            return;
        }

        ImGui.setNextWindowPos(w - SETTINGS_W - 16f, 16f, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(SETTINGS_W, Math.min(520f, h - 32f), ImGuiCond.FirstUseEver);

        if (ImGui.begin(Lang.t("irl-redactor.editor.settingsTitle"), settingsOpen, ImGuiWindowFlags.NoCollapse))
        {
            if (ImGui.beginChild("##set_nav", 150f, 0f, true))
            {
                ImGui.setNextItemWidth(-1f);
                ImGui.inputTextWithHint("##set_search", Lang.t("irl-redactor.editor.searchHint"), settingsSearch);
                ImGui.dummy(0f, 4f);
                settingsNavItem(CAT_PRESETS, "irl-redactor.editor.cat.presets");
                settingsNavItem(CAT_VOLUMETRIC, "irl-redactor.editor.cat.volumetric");
                settingsNavItem(CAT_SHADOWS, "irl-redactor.editor.cat.shadows");
                settingsNavItem(CAT_OUTLINE, "irl-redactor.editor.cat.outline");
                settingsNavItem(CAT_AUTO, "irl-redactor.editor.cat.auto");
                settingsNavItem(CAT_PATCHER, "irl-redactor.editor.cat.patcher");
            }
            ImGui.endChild();

            ImGui.sameLine();

            if (ImGui.beginChild("##set_content", 0f, 0f, false))
            {
                switch (settingsCat)
                {
                    case CAT_VOLUMETRIC -> volumetricCategory();
                    case CAT_SHADOWS -> shadowsCategory();
                    case CAT_OUTLINE -> Widgets.textDisabledWrapped(Lang.t("irl-redactor.editor.outlineSoon"));
                    case CAT_AUTO -> autoCategory();
                    case CAT_PATCHER -> patcherCategory();
                    default -> presetsCategory();
                }
            }
            ImGui.endChild();
        }
        ImGui.end();
    }

    private void settingsNavItem(int cat, String labelKey)
    {
        if (Widgets.selectable("set_nav_" + cat, Lang.t(labelKey), null, settingsCat == cat))
        {
            settingsCat = cat;
        }
    }

    // ---- settings categories ----------------------------------------------

    /** Presets: the loose global knobs + the dev profiler / deferred-bake controls.
     *  (The profiler + hold-bake flags are owned elsewhere and can change outside
     *  this panel, so their mirrors resync from the source of truth each frame.) */
    private void presetsCategory()
    {
        Widgets.trackpad("cfg_vlintensity", Lang.t("irl-redactor.editor.vlIntensity"), cfgVlIntensity, 0f, 5f, "%.2f");
        LightConfig.vlIntensity = cfgVlIntensity[0];

        Widgets.toggleRow("cfg_guides", Lang.t("irl-redactor.editor.showGuides"), cfgGuides);
        LightConfig.showGuides = cfgGuides.get();

        ImGui.dummy(0f, 6f);
        Widgets.textDisabled(Lang.t("irl-redactor.editor.perf"));

        cfgPerfProfiler.set(VlProfiler.isCollecting());
        Widgets.toggleRow("cfg_perf_profiler", Lang.t("irl-redactor.editor.perfProfiler"), cfgPerfProfiler);
        VlProfiler.setCollecting(cfgPerfProfiler.get());

        cfgHoldBake.set(LightConfig.holdBake);
        Widgets.toggleRow("cfg_perf_hold", Lang.t("irl-redactor.editor.holdBake"), cfgHoldBake);
        LightConfig.holdBake = cfgHoldBake.get();

        cfgHoldOnJoin.set(LightConfig.holdBakeOnJoin);
        Widgets.toggleRow("cfg_perf_holdjoin", Lang.t("irl-redactor.editor.holdBakeOnJoin"), cfgHoldOnJoin);
        LightConfig.holdBakeOnJoin = cfgHoldOnJoin.get();

        if (LightConfig.holdBake)
        {
            Widgets.textDisabled(Lang.t("irl-redactor.editor.holdActive"));
            if (Widgets.primaryButton("perf_bake_now", Lang.t("irl-redactor.editor.bakeNow"), ImGui.getContentRegionAvail().x))
            {
                LightConfig.holdBake = false;
            }
        }
    }

    /** Shadows: quality preset + block-shadow toggles and radius. */
    private void shadowsCategory()
    {
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

        Widgets.toggleRow("cfg_blocks", Lang.t("irl-redactor.editor.shadowBlocks"), cfgBlocks);
        LightConfig.shadowBlocks = cfgBlocks.get();

        Widgets.toggleRow("cfg_cache", Lang.t("irl-redactor.editor.shadowCache"), cfgCache);
        LightConfig.shadowCache = cfgCache.get();

        Widgets.trackpad("cfg_radius", Lang.t("irl-redactor.editor.shadowBlockRadius"), cfgRadius, 4f, 96f, "%.0f");
        LightConfig.shadowBlockRadius = Math.round(cfgRadius[0]);
    }

    /** Auto block-lights: the whole emissive-block auto-light block. */
    private void autoCategory()
    {
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
        // Surface culling: only light emissive blocks exposed to visible space
        // (skip ones buried in terrain / inside a solid cluster of emitters).
        Widgets.toggleRow("cfg_autoculling", Lang.t("irl-redactor.editor.autoLightCulling"), cfgAutoCulling);
        LightConfig.autoLightCulling = cfgAutoCulling.get();

        Widgets.toggleRow("cfg_autoshadows", Lang.t("irl-redactor.editor.autoLightShadows"), cfgAutoShadows);
        LightConfig.autoLightShadows = cfgAutoShadows.get();

        Widgets.trackpad("cfg_autointensity", Lang.t("irl-redactor.editor.autoLightIntensity"), cfgAutoIntensity, 0f, 5f, "%.2f");
        LightConfig.autoLightIntensity = cfgAutoIntensity[0];
        Widgets.trackpad("cfg_autoreach", Lang.t("irl-redactor.editor.autoLightReach"), cfgAutoReach, 0.25f, 3f, "%.2f");
        LightConfig.autoLightReach = cfgAutoReach[0];
        Widgets.trackpad("cfg_autoradius", Lang.t("irl-redactor.editor.autoLightRadius"), cfgAutoRadius, 8f, 96f, "%.0f");
        LightConfig.autoLightRadius = Math.round(cfgAutoRadius[0]);
        Widgets.trackpad("cfg_automax", Lang.t("irl-redactor.editor.autoLightMax"), cfgAutoMax, 0f, 2000f, "%.0f");
        LightConfig.autoLightMax = Math.round(cfgAutoMax[0]);

        Widgets.textDisabled(Lang.t("irl-redactor.editor.autoLightActive",
            AutoLightManager.activeCount(), AutoLightManager.count()));
        ImGui.endDisabled();
    }

    /** Patcher: opens the shader-patcher modal. */
    private void patcherCategory()
    {
        Widgets.textDisabledWrapped(Lang.t("irl-redactor.editor.patcherHint"));
        ImGui.dummy(0f, 4f);
        if (Widgets.button("open_patcher", Lang.t("irl-redactor.editor.openPatcher"), ImGui.getContentRegionAvail().x, false))
        {
            patcher.open();
        }
    }

    // ---- global volumetrics (live) ----------------------------------------

    /** Volumetric category: scene-wide VL knobs, applied live (pushed to the
     *  binding-7 globals UBO by the driver next frame — no shaderpack recompile).
     *  Intensity lives under Presets; the march / noise knobs are here. Ranges and
     *  defaults mirror the packs' IRLITE_VL_* option lists. Only shaderpacks
     *  patched with runtime VL globals respond; older patches keep compiled values. */
    private void volumetricCategory()
    {
        // Two columns matching the HTML prototype: beam knobs left, noise right.
        ImGui.columns(2, "##vl_cols", false);

        Widgets.textDisabled(Lang.t("irl-redactor.editor.vlGroupBeam"));
        Widgets.trackpad("cfg_vlsteps", Lang.t("irl-redactor.editor.vlSteps"), cfgVlSteps, 8f, 64f, "%.0f");
        LightConfig.vlSteps = Math.round(cfgVlSteps[0]);

        Widgets.trackpad("cfg_vlmaxdist", Lang.t("irl-redactor.editor.vlMaxDist"), cfgVlMaxDist, 32f, 256f, "%.0f");
        LightConfig.vlMaxDist = cfgVlMaxDist[0];

        Widgets.toggleRow("cfg_vlshadows", Lang.t("irl-redactor.editor.vlShadows"), cfgVlShadows);
        LightConfig.vlShadows = cfgVlShadows.get();

        ImGui.beginDisabled(!LightConfig.vlShadows);
        Widgets.trackpad("cfg_vlshadowstride", Lang.t("irl-redactor.editor.vlShadowStride"), cfgVlShadowStride, 1f, 4f, "%.0f");
        LightConfig.vlShadowStride = Math.round(cfgVlShadowStride[0]);
        ImGui.endDisabled();

        Widgets.trackpad("cfg_vltipboost", Lang.t("irl-redactor.editor.vlTipBoost"), cfgVlTipBoost, 0f, 4f, "%.2f");
        LightConfig.vlTipBoost = cfgVlTipBoost[0];

        Widgets.trackpad("cfg_vltipradius", Lang.t("irl-redactor.editor.vlTipRadius"), cfgVlTipRadius, 0.5f, 4f, "%.2f");
        LightConfig.vlTipRadius = cfgVlTipRadius[0];

        ImGui.nextColumn();

        Widgets.textDisabled(Lang.t("irl-redactor.editor.vlGroupNoise"));
        Widgets.toggleRow("cfg_vlnoise", Lang.t("irl-redactor.editor.vlNoise"), cfgVlNoise);
        LightConfig.vlNoise = cfgVlNoise.get();

        ImGui.beginDisabled(!LightConfig.vlNoise);
        Widgets.trackpad("cfg_vlnoiseamount", Lang.t("irl-redactor.editor.vlNoiseAmount"), cfgVlNoiseAmount, 0.2f, 1f, "%.2f");
        LightConfig.vlNoiseAmount = cfgVlNoiseAmount[0];

        Widgets.trackpad("cfg_vlnoisescale", Lang.t("irl-redactor.editor.vlNoiseScale"), cfgVlNoiseScale, 0.5f, 6f, "%.2f");
        LightConfig.vlNoiseScale = cfgVlNoiseScale[0];

        // Snap the mirror to 0.25 too, so the readout shows the effective value
        // (in-between drift speeds pop on the shader's time wrap; the core setter
        // quantizes as well).
        Widgets.trackpad("cfg_vlnoisespeed", Lang.t("irl-redactor.editor.vlNoiseSpeed"), cfgVlNoiseSpeed, 0f, 3f, "%.2f");
        cfgVlNoiseSpeed[0] = Math.round(cfgVlNoiseSpeed[0] * 4f) / 4f;
        LightConfig.vlNoiseSpeed = cfgVlNoiseSpeed[0];

        // Same 0.25 snap as the drift speed (the morph phase must complete whole
        // slice-periods per the wrap, or the crossfade pops).
        Widgets.trackpad("cfg_vlnoisemorph", Lang.t("irl-redactor.editor.vlNoiseMorph"), cfgVlNoiseMorph, 0f, 3f, "%.2f");
        cfgVlNoiseMorph[0] = Math.round(cfgVlNoiseMorph[0] * 4f) / 4f;
        LightConfig.vlNoiseMorph = cfgVlNoiseMorph[0];

        Widgets.trackpad("cfg_vlnoisestride", Lang.t("irl-redactor.editor.vlNoiseStride"), cfgVlNoiseStride, 1f, 4f, "%.0f");
        LightConfig.vlNoiseStride = Math.round(cfgVlNoiseStride[0]);
        ImGui.endDisabled();

        Widgets.toggleRow("cfg_vldithertemporal", Lang.t("irl-redactor.editor.vlDitherTemporal"), cfgVlDitherTemporal);
        LightConfig.vlDitherTemporal = cfgVlDitherTemporal.get();

        ImGui.columns(1);

        if (LightConfig.vlDitherTemporal)
        {
            Widgets.textDisabled(Lang.t("irl-redactor.editor.vlDitherTemporalWarn"));
        }

        Widgets.textDisabled(Lang.t("irl-redactor.editor.vlHint"));
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
            // Keep the write-back in double: cp is exact and gizmoModel's translation
            // is a small camera-relative float. Narrowing the SUM to float would
            // re-snap the light to the ~8mm float lattice at large coordinates on
            // every frame of the drag (stuttering handle).
            state.pos[0] = cp.x + gizmoModel[12];
            state.pos[1] = cp.y + gizmoModel[13];
            state.pos[2] = cp.z + gizmoModel[14];

            if (spot)
            {
                // dir = the model's forward (local +Z) column, normalized (falls
                // back to straight down for a degenerate column, matching orientationFromDir).
                LightMath.normalizeDir(gizmoModel[8], gizmoModel[9], gizmoModel[10], 0f, -1f, 0f, state.dir);
                // Persist the manipulated rotation for next frame's continuity.
                gizmoRot.set(gizmoModel).setTranslation(0f, 0f, 0f);
            }
        }

        gizmoRotating = using && spot;
    }

    /** Rebuilds {@link #gizmoRot} as the rotation taking local +Z to {@code state.dir}. */
    private void orientationFromDir()
    {
        float[] dir = LightMath.normalizeDir(state.dir[0], state.dir[1], state.dir[2], 0f, -1f, 0f, new float[3]);
        gizmoQuat.rotationTo(0f, 0f, 1f, dir[0], dir[1], dir[2]);
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
