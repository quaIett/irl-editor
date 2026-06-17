package org.qualet.irlredactor.editor;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.qualet.irl.patcher.IrlPatch;
import org.qualet.irl.patcher.IrlPatchApplier;
import org.qualet.irl.patcher.IrlPatchParser;
import org.qualet.irl.patcher.PatchLibrary;
import org.qualet.irl.patcher.PatchResult;
import org.qualet.irl.patcher.Shaderpacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shader-patcher popup — a centred modal that mirrors the original BBS / IRLite
 * patcher window (Shaderpacks list, Patches list, "create new pack each time"
 * toggle, Validate / Patch actions, status line) in the flat BBS skin.
 *
 * <p>The {@code .irlights} patcher core ({@link org.qualet.irlredactor.patcher})
 * is wired up: the lists are populated live from Iris's shaderpack folder and the
 * bundled patch library, Validate dry-runs every op against the selected pack
 * (writing nothing), and Patch produces a fresh {@code *_IRLights} pack. The
 * full per-op log goes to the {@code irl-redactor} logger; the modal shows a
 * one-line coloured status.</p>
 */
public class PatcherPanel
{
    private static final Logger LOG = LoggerFactory.getLogger("irl-redactor");

    private static final String POPUP_ID = "##irl_patcher";

    private static final float WIN_W = 440f;
    private static final float WIN_H = 560f;
    private static final float LIST_H = 150f;
    private static final float FOOTER_H = 84f;
    private static final float ICON = 20f;
    private static final float ICON_SP = 6f;

    private static final int COL_ICON      = ImColor.rgba(0xb4, 0xb4, 0xb4, 0xff);
    private static final int COL_ICON_HOV  = ImColor.rgba(0xff, 0xff, 0xff, 0xff);
    private static final int COL_X_HOV     = ImColor.rgba(0xe6, 0x2e, 0x8b, 0xff);
    private static final int COL_LIST_BG   = ImColor.rgba(0x1b, 0x1b, 0x1b, 0xff);
    private static final int COL_SCROLL    = ImColor.rgba(0xe6, 0x2e, 0x8b, 0xff);

    // Status / meta colours (0xRRGGBB).
    private static final int RGB_OK   = 0x55FF55;
    private static final int RGB_ERR  = 0xFF5555;
    private static final int RGB_META = 0xAAAAAA;
    private static final int RGB_WARN = 0xFFAA33;

    private boolean wantOpen;

    // Live listings (loaded on open + the refresh icon).
    private List<String> packs = List.of();
    private List<Path> patches = List.of();
    private List<String> patchLabels = List.of();

    private int selPack = -1;
    private int selPatch = -1;

    private final ImBoolean newPackEachTime = new ImBoolean(false);

    // Parsed metadata of the selected patch, cached on selection change (a patch
    // file is tens of KB — never re-parse per frame). Composed into the meta line
    // each frame so it re-localizes live on a language switch.
    private int parsedPatch = -1;
    private boolean patchBroken;
    private String patchError = "";
    private String patchName = "";
    private String patchTarget = "";
    private String patchVersion = "";
    private int patchOps;

    // Status line: a localized guard key (statusText == null) OR a raw engine
    // summary (statusText != null, shown coloured by statusOk).
    private String statusKey = "irl-redactor.patcher.status.selectBoth";
    private String statusText;
    private boolean statusOk = true;

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
            reload();
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
        Widgets.text(Lang.t("irl-redactor.patcher.title"));
        ImGui.sameLine();
        float closeS = 18f;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getContentRegionAvail().x - closeS);
        if (closeIcon("patch_close", closeS))
        {
            ImGui.closeCurrentPopup();
        }
        ImGui.separator();

        // --- shaderpacks ----------------------------------------------------
        headerRow(Lang.t("irl-redactor.patcher.shaderpacks"), this::reload, Shaderpacks::openFolder);
        selPack = fileList("packs", packs, selPack);

        ImGui.dummy(0f, 2f);

        // --- patches --------------------------------------------------------
        headerRow(Lang.t("irl-redactor.patcher.patches"), null, PatchLibrary::openFolder);
        selPatch = fileList("patches", patchLabels, selPatch);

        // --- selected-patch metadata ---------------------------------------
        metaLine();

        // --- footer pinned to the bottom -----------------------------------
        float avail = ImGui.getContentRegionAvail().y;
        if (avail > FOOTER_H)
        {
            ImGui.dummy(0f, avail - FOOTER_H);
        }

        Widgets.toggleRow("patch_newpack", Lang.t("irl-redactor.patcher.newPackEachTime"), newPackEachTime);

        float bw = (ImGui.getContentRegionAvail().x - 8f) * 0.5f;
        if (Widgets.primaryButton("patch_validate", Lang.t("irl-redactor.patcher.validate"), bw))
        {
            onAction(true);
        }
        ImGui.sameLine(0f, 8f);
        if (Widgets.primaryButton("patch_patch", Lang.t("irl-redactor.patcher.patch"), bw))
        {
            onAction(false);
        }

        statusLine();
    }

    // ---- listings ----------------------------------------------------------

    /** (Re)reads the shaderpack + patch listings, preserving the current selection by value. */
    private void reload()
    {
        String keepPack = selPack >= 0 && selPack < packs.size() ? packs.get(selPack) : null;
        Path keepPatch = selPatch >= 0 && selPatch < patches.size() ? patches.get(selPatch) : null;

        packs = Shaderpacks.list();
        patches = PatchLibrary.list();

        List<String> labels = new ArrayList<>(patches.size());
        for (Path p : patches)
        {
            labels.add(p.getFileName().toString());
        }
        patchLabels = labels;

        selPack = keepPack == null ? -1 : packs.indexOf(keepPack);
        selPatch = keepPatch == null ? -1 : patches.indexOf(keepPatch);
        parsedPatch = -1; // force the meta cache to refresh
    }

    // ---- selected-patch metadata ------------------------------------------

    /** Parses the selected patch once on selection change; renders the meta line each frame. */
    private void metaLine()
    {
        if (selPatch != parsedPatch)
        {
            parseSelectedPatch();
        }

        if (selPatch < 0)
        {
            ImGui.dummy(0f, ImGui.getTextLineHeight());
            return;
        }

        if (patchBroken)
        {
            Widgets.textColored(Lang.t("irl-redactor.patcher.meta.broken", patchError), RGB_ERR);
            return;
        }

        String text = (patchName.isEmpty() ? patchLabels.get(selPatch) : patchName)
            + " → " + (patchTarget.isEmpty() ? "?" : patchTarget)
            + (patchVersion.isEmpty() ? "" : " " + patchVersion)
            + "  (" + Lang.t("irl-redactor.patcher.meta.ops", patchOps) + ")";

        String pack = selPack >= 0 && selPack < packs.size() ? packs.get(selPack) : null;
        if (pack != null && !patchTarget.isEmpty() && !packMatchesTarget(pack, patchTarget))
        {
            Widgets.textColored(text + Lang.t("irl-redactor.patcher.meta.mismatch"), RGB_WARN);
        }
        else
        {
            Widgets.textColored(text, RGB_META);
        }
    }

    /** Caches the selected patch's fields; auto-selects the single matching pack when none is chosen. */
    private void parseSelectedPatch()
    {
        parsedPatch = selPatch;
        patchBroken = false;
        patchError = "";
        patchName = "";
        patchTarget = "";
        patchVersion = "";
        patchOps = 0;

        if (selPatch < 0 || selPatch >= patches.size())
        {
            return;
        }

        IrlPatch parsed;
        try
        {
            parsed = IrlPatchParser.parse(Files.readString(patches.get(selPatch), StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            patchBroken = true;
            patchError = e.getMessage();
            return;
        }

        patchName = parsed.name;
        patchTarget = parsed.target;
        patchVersion = parsed.packVersion;
        patchOps = parsed.ops.size();

        // No pack chosen yet: if exactly one pack matches the patch's @target, pick it.
        if (selPack < 0 && !patchTarget.isEmpty())
        {
            int match = -1;
            for (int i = 0; i < packs.size(); i++)
            {
                if (packMatchesTarget(packs.get(i), patchTarget))
                {
                    if (match >= 0)
                    {
                        match = -1;
                        break;
                    }
                    match = i;
                }
            }
            if (match >= 0)
            {
                selPack = match;
            }
        }
    }

    /** "Photon_v1.2.zip" matches target "Photon": lowercase, alphanumerics only, substring. */
    private static boolean packMatchesTarget(String pack, String target)
    {
        String p = norm(pack);
        String t = norm(target);
        return t.isEmpty() || p.contains(t);
    }

    private static String norm(String s)
    {
        String lower = s.toLowerCase();
        if (lower.endsWith(".zip"))
        {
            lower = lower.substring(0, lower.length() - 4);
        }
        return lower.replaceAll("[^a-z0-9]", "");
    }

    // ---- Validate / Patch --------------------------------------------------

    private void onAction(boolean validate)
    {
        if (selPack < 0 && selPatch < 0)
        {
            setGuard("irl-redactor.patcher.status.selectBoth");
            return;
        }
        if (selPack < 0)
        {
            setGuard("irl-redactor.patcher.status.selectPack");
            return;
        }
        if (selPatch < 0)
        {
            setGuard("irl-redactor.patcher.status.selectPatch");
            return;
        }

        IrlPatch parsed;
        try
        {
            parsed = IrlPatchParser.parse(Files.readString(patches.get(selPatch), StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            setResult(false, "Parse error: " + e.getMessage());
            return;
        }

        String packName = packs.get(selPack);
        if (validate)
        {
            PatchResult result = IrlPatchApplier.validate(Shaderpacks.packPath(packName), parsed);
            logResult("validate", result);
            setResult(result.ok, result.summary);
        }
        else
        {
            Path source = Shaderpacks.packPath(packName);
            Path output = Shaderpacks.dir().resolve(outputName(packName));
            PatchResult result = IrlPatchApplier.apply(source, output, parsed);
            logResult("patch", result);
            setResult(result.ok, result.summary);
            reload(); // a newly created patched pack should show up
        }
    }

    private String outputName(String packName)
    {
        String base = packName;
        if (base.toLowerCase().endsWith(".zip"))
        {
            base = base.substring(0, base.length() - 4);
        }
        base = base + "_IRLights";

        if (!newPackEachTime.get())
        {
            return base;
        }
        if (!Files.exists(Shaderpacks.dir().resolve(base)))
        {
            return base;
        }
        for (int i = 2; i < 1000; i++)
        {
            String candidate = base + "_" + i;
            if (!Files.exists(Shaderpacks.dir().resolve(candidate)))
            {
                return candidate;
            }
        }
        return base;
    }

    private static void logResult(String tag, PatchResult result)
    {
        for (String line : result.log)
        {
            LOG.info("[{}] {}", tag, line);
        }
    }

    private void setGuard(String key)
    {
        statusKey = key;
        statusText = null;
        statusOk = true;
    }

    private void setResult(boolean ok, String message)
    {
        statusText = message;
        statusOk = ok;
    }

    private void statusLine()
    {
        if (statusText != null)
        {
            Widgets.textColored(statusText, statusOk ? RGB_OK : RGB_ERR);
        }
        else
        {
            Widgets.textDisabled(Lang.t(statusKey));
        }
    }

    // ---- list of files -----------------------------------------------------

    /** A bordered, scrollable list of names; returns the (possibly updated) selected index.
     *  Magenta scrollbar + darker background to match the prototype. */
    private int fileList(String id, List<String> items, int selected)
    {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, COL_LIST_BG);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrab, COL_SCROLL);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrabHovered, COL_SCROLL);
        ImGui.pushStyleColor(ImGuiCol.ScrollbarGrabActive, COL_SCROLL);

        int result = selected;
        if (ImGui.beginChild("##list_" + id, 0f, LIST_H, true))
        {
            if (items.isEmpty())
            {
                Widgets.textDisabled(Lang.t("irl-redactor.patcher.empty"));
            }
            for (int i = 0; i < items.size(); i++)
            {
                if (Widgets.listItem(id + "_" + i, items.get(i), i == selected))
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

    private void headerRow(String title, Runnable onRefresh, Runnable onFolder)
    {
        Widgets.text(title);
        ImGui.sameLine();

        int n = onRefresh != null ? 2 : 1;
        float iconsW = n * ICON + (n - 1) * ICON_SP;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getContentRegionAvail().x - iconsW);

        if (onRefresh != null)
        {
            if (refreshIcon("ic_refresh_" + title))
            {
                onRefresh.run();
            }
            ImGui.sameLine(0f, ICON_SP);
        }
        if (folderIcon("ic_folder_" + title))
        {
            onFolder.run();
        }
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
