package org.qualet.irlredactor.editor;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

/**
 * Shader-patcher popup — a centred modal that visually mirrors the original BBS /
 * IRLite patcher window (Shaderpacks list, Patches list, "create new pack each
 * time" toggle, Validate / Patch actions, status line) in the same flat BBS skin.
 *
 * <p><b>Visual prototype only.</b> The actual {@code .irlights} patcher has not been
 * ported into this mod yet (it is the remaining "Stage 3" work), so the lists hold
 * sample entries and the actions are no-ops that update the status line. Selection
 * and the toggle are live UI state, so the window feels real.</p>
 */
public class PatcherPanel
{
    private static final String POPUP_ID = "##irl_patcher";

    private static final float WIN_W = 440f;
    private static final float WIN_H = 540f;
    private static final float LIST_H = 150f;
    private static final float FOOTER_H = 84f;
    private static final float ICON = 20f;
    private static final float ICON_SP = 6f;

    private static final int COL_ICON      = ImColor.rgba(0xb4, 0xb4, 0xb4, 0xff);
    private static final int COL_ICON_HOV  = ImColor.rgba(0xff, 0xff, 0xff, 0xff);
    private static final int COL_X_HOV     = ImColor.rgba(0xe6, 0x2e, 0x8b, 0xff);
    private static final int COL_LIST_BG   = ImColor.rgba(0x1b, 0x1b, 0x1b, 0xff);
    private static final int COL_SCROLL    = ImColor.rgba(0xe6, 0x2e, 0x8b, 0xff);

    // Sample data (visual prototype — see class doc). Real listings come once the
    // patcher core + the .irlights patches are ported.
    private static final String[] PACKS = {
        "Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip",
        "BSL_v10.1.3.zip",
        "ComplementaryReimagined_r5.8.1.zip",
        "IterationRP_v1.0.zip",
        "photon_v1.3b.zip",
        "solas_v2.1.zip",
    };
    private static final String[] PATCHES = {
        "bliss.irlights",
        "bsl.irlights",
        "complementaryreimagined.irlights",
        "iterationrp.irlights",
        "photon.irlights",
        "solas.irlights",
    };

    private boolean wantOpen;
    private int selPack = -1;
    private int selPatch = -1;
    private final ImBoolean newPackEachTime = new ImBoolean(false);
    private String status = "Выберите шейдерпак и патч.";

    /** Request the popup to open on the next frame (from the editor's button). */
    public void open()
    {
        wantOpen = true;
    }

    /** Render the modal. Call once per frame at the root (no window active). */
    public void draw()
    {
        if (wantOpen)
        {
            ImGui.openPopup(POPUP_ID);
            wantOpen = false;
        }

        float cx = ImGui.getIO().getDisplaySizeX() * 0.5f;
        float cy = ImGui.getIO().getDisplaySizeY() * 0.5f;
        ImGui.setNextWindowPos(cx, cy, ImGuiCond.Appearing, 0.5f, 0.5f);
        ImGui.setNextWindowSize(WIN_W, WIN_H, ImGuiCond.Appearing);

        int flags = ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoResize;

        if (ImGui.beginPopupModal(POPUP_ID, flags))
        {
            content();
            ImGui.endPopup();
        }
    }

    private void content()
    {
        // --- title row + close ---------------------------------------------
        Widgets.text("Патчер");
        ImGui.sameLine();
        float closeS = 18f;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getContentRegionAvail().x - closeS);
        if (closeIcon("patch_close", closeS))
        {
            ImGui.closeCurrentPopup();
        }
        ImGui.separator();

        // --- shaderpacks ----------------------------------------------------
        headerRow("Шейдерпаки", true);
        selPack = fileList("packs", PACKS, selPack);

        ImGui.dummy(0f, 2f);

        // --- patches --------------------------------------------------------
        headerRow("Патчи", false);
        selPatch = fileList("patches", PATCHES, selPatch);

        // --- footer pinned to the bottom -----------------------------------
        float avail = ImGui.getContentRegionAvail().y;
        if (avail > FOOTER_H)
        {
            ImGui.dummy(0f, avail - FOOTER_H);
        }

        Widgets.toggleRow("patch_newpack", "Создавать новый пак каждый раз", newPackEachTime);

        float bw = (ImGui.getContentRegionAvail().x - 8f) * 0.5f;
        if (Widgets.primaryButton("patch_validate", "Проверить", bw))
        {
            onAction(true);
        }
        ImGui.sameLine(0f, 8f);
        if (Widgets.primaryButton("patch_patch", "Патчить", bw))
        {
            onAction(false);
        }

        Widgets.textDisabled(status);
    }

    /** Validate / Patch are no-ops for now — report selection state, or that the
     *  patcher engine isn't wired up yet. */
    private void onAction(boolean validate)
    {
        if (selPack < 0 && selPatch < 0)
        {
            status = "Выберите шейдерпак и патч.";
        }
        else if (selPack < 0)
        {
            status = "Выберите шейдерпак.";
        }
        else if (selPatch < 0)
        {
            status = "Выберите патч.";
        }
        else
        {
            status = (validate ? "Проверка" : "Патч") + ": движок патчера ещё не перенесён.";
        }
    }

    // ---- list of files -----------------------------------------------------

    /** A bordered, scrollable list of file names; returns the (possibly updated)
     *  selected index. Magenta scrollbar + darker background to match the prototype. */
    private int fileList(String id, String[] items, int selected)
    {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, COL_LIST_BG);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrab, COL_SCROLL);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrabHovered, COL_SCROLL);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrabActive, COL_SCROLL);

        int result = selected;
        if (ImGui.beginChild("##list_" + id, 0f, LIST_H, true))
        {
            for (int i = 0; i < items.length; i++)
            {
                if (Widgets.listItem(id + "_" + i, items[i], i == selected))
                {
                    result = i;
                }
            }
        }
        ImGui.endChild();

        ImGui.popStyleColor(4);
        return result;
    }

    // ---- header row with right-aligned icon buttons ------------------------

    private void headerRow(String title, boolean withRefresh)
    {
        Widgets.text(title);
        ImGui.sameLine();

        int n = withRefresh ? 2 : 1;
        float iconsW = n * ICON + (n - 1) * ICON_SP;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getContentRegionAvail().x - iconsW);

        if (withRefresh)
        {
            refreshIcon("ic_refresh_" + title);
            ImGui.sameLine(0f, ICON_SP);
        }
        folderIcon("ic_folder_" + title);
    }

    // ---- icon buttons (drawn via the draw list) ----------------------------

    private boolean folderIcon(String id)
    {
        ImVec2 pos = ImGui.getCursorScreenPos();
        ImGui.invisibleButton("##" + id, ICON, ICON);
        boolean clicked = ImGui.isItemClicked();
        int col = ImGui.isItemHovered() ? COL_ICON_HOV : COL_ICON;

        ImDrawList dl = ImGui.getWindowDrawList();
        float x = pos.x + 2f;
        float y = pos.y + 6f;
        float w = ICON - 4f;
        float h = ICON - 9f;
        // tab + body
        dl.addRectFilled(x, y - 3f, x + w * 0.45f, y + 1f, col);
        dl.addRectFilled(x, y, x + w, y + h, col);
        return clicked;
    }

    private boolean refreshIcon(String id)
    {
        ImVec2 pos = ImGui.getCursorScreenPos();
        ImGui.invisibleButton("##" + id, ICON, ICON);
        boolean clicked = ImGui.isItemClicked();
        int col = ImGui.isItemHovered() ? COL_ICON_HOV : COL_ICON;

        ImDrawList dl = ImGui.getWindowDrawList();
        float cx = pos.x + ICON * 0.5f;
        float cy = pos.y + ICON * 0.5f;
        float r = ICON * 0.30f;
        dl.addCircle(cx, cy, r, col, 18, 1.8f);
        // arrowhead at the top, hinting at clockwise rotation
        dl.addTriangleFilled(cx + 5f, cy - r, cx - 1f, cy - r - 4f, cx - 1f, cy - r + 4f, col);
        return clicked;
    }

    private boolean closeIcon(String id, float size)
    {
        ImVec2 pos = ImGui.getCursorScreenPos();
        ImGui.invisibleButton("##" + id, size, size);
        boolean clicked = ImGui.isItemClicked();
        int col = ImGui.isItemHovered() ? COL_X_HOV : COL_ICON;

        ImDrawList dl = ImGui.getWindowDrawList();
        float p = 4f;
        dl.addLine(pos.x + p, pos.y + p, pos.x + size - p, pos.y + size - p, col, 2f);
        dl.addLine(pos.x + size - p, pos.y + p, pos.x + p, pos.y + size - p, col, 2f);
        return clicked;
    }
}
