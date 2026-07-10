package org.qualet.irlredactor.editor;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiMouseCursor;
import imgui.type.ImBoolean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom immediate-mode widgets drawn directly via {@link ImDrawList} for
 * pixel-level control, matching the BBS / vanilla-Minecraft look: no element
 * outlines, and text with a vanilla-style drop shadow (offset +1,+1 in a darker
 * tone). Drawing text ourselves is what lets every label carry the MC shadow —
 * ImGui's built-in widget text cannot, so buttons and headers are custom too.
 */
public final class Widgets
{
    private Widgets()
    {
    }

    private static final float SHADOW = 1f;
    private static final float TRACKPAD_HEIGHT = 30f;
    private static final float DRAG_RANGE_PX = 300f;

    private static final int COL_BG        = ImColor.rgba(0x1c, 0x1c, 0x1c, 0xff);
    private static final int COL_FILL      = ImColor.rgba(0xe6, 0x2e, 0x8b, 0xff);

    private static final int COL_LABEL     = ImColor.rgba(0xe2, 0xe2, 0xe2, 0xff);
    private static final int COL_LABEL_SH  = ImColor.rgba(0x38, 0x38, 0x38, 0xff);
    private static final int COL_VALUE     = ImColor.rgba(0xff, 0xff, 0xff, 0xff);
    private static final int COL_VALUE_SH  = ImColor.rgba(0x3f, 0x3f, 0x3f, 0xff);
    private static final int COL_DIM       = ImColor.rgba(0x8a, 0x8a, 0x8a, 0xff);
    private static final int COL_DIM_SH    = ImColor.rgba(0x22, 0x22, 0x22, 0xff);

    private static final int COL_ACCENT    = ImColor.rgba(0xe6, 0x2e, 0x8b, 0xff);
    private static final int COL_TRACK     = ImColor.rgba(0x3a, 0x3a, 0x3a, 0xff);
    private static final int COL_KNOB_OFF  = ImColor.rgba(0x77, 0x77, 0x77, 0xff);
    private static final int COL_KNOB_ON   = ImColor.rgba(0xff, 0xff, 0xff, 0xff);

    private static final int COL_BTN       = ImColor.rgba(0x2a, 0x2a, 0x2a, 0xff);
    private static final int COL_BTN_HOVER = ImColor.rgba(0x3a, 0x3a, 0x3a, 0xff);
    private static final int COL_BTN_TEXT  = ImColor.rgba(0xc4, 0xc4, 0xc4, 0xff);
    private static final int COL_BTN_TX_SH = ImColor.rgba(0x30, 0x30, 0x30, 0xff);

    // Magenta "primary" call-to-action button (Validate / Patch in the patcher),
    // matching the prototype: solid accent fill with dark text.
    private static final int COL_PRIMARY      = ImColor.rgba(0xe6, 0x2e, 0x8b, 0xff);
    private static final int COL_PRIMARY_HOV  = ImColor.rgba(0xf0, 0x4a, 0x9e, 0xff);
    private static final int COL_PRIMARY_TEXT = ImColor.rgba(0x1c, 0x1c, 0x1c, 0xff);

    // Plain list row (no per-row box) for the patcher's file lists.
    private static final int COL_ROW_HOVER = ImColor.rgba(0x2e, 0x2e, 0x2e, 0xff);

    private static final int COL_HEADER    = ImColor.rgba(0x20, 0x20, 0x20, 0xff);
    private static final int COL_TRI       = ImColor.rgba(0x6f, 0x6f, 0x6f, 0xff);

    private static final Map<String, Float> DRAG_START_VAL = new HashMap<>();
    private static final Map<String, Double> DRAG_START_VAL_D = new HashMap<>();
    private static final Map<String, Float> DRAG_START_X = new HashMap<>();
    private static final Map<String, Boolean> OPEN = new HashMap<>();

    // ---- text with a vanilla-Minecraft drop shadow -------------------------

    private static void shadowText(ImDrawList dl, float x, float y, int color, int shadow, String text)
    {
        dl.addText(x + SHADOW, y + SHADOW, shadow, text);
        dl.addText(x, y, color, text);
    }

    /** Shadowed label that advances the cursor like {@code ImGui.text}. */
    public static void text(String s)
    {
        emitText(s, COL_LABEL, COL_LABEL_SH);
    }

    /** Shadowed dimmed label (section captions, footer). */
    public static void textDisabled(String s)
    {
        emitText(s, COL_DIM, COL_DIM_SH);
    }

    /** Shadowed label in an explicit {@code 0xRRGGBB} colour (patcher meta / status lines). */
    public static void textColored(String s, int rgb)
    {
        int color = ImColor.rgba((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 0xFF);
        emitText(s, color, COL_DIM_SH);
    }

    private static void emitText(String s, int color, int shadow)
    {
        ImVec2 pos = ImGui.getCursorScreenPos();
        shadowText(ImGui.getWindowDrawList(), pos.x, pos.y, color, shadow, s);
        ImVec2 size = ImGui.calcTextSize(s);
        ImGui.dummy(size.x, size.y);
    }

    /** Word-wrapped variant of {@link #textColored} — wraps to the available content
     *  width so long patcher meta / status lines don't overflow the fixed-width window. */
    public static void textColoredWrapped(String s, int rgb)
    {
        int color = ImColor.rgba((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 0xFF);
        emitWrapped(s, color, COL_DIM_SH);
    }

    /** Word-wrapped variant of {@link #textDisabled}. */
    public static void textDisabledWrapped(String s)
    {
        emitWrapped(s, COL_DIM, COL_DIM_SH);
    }

    private static void emitWrapped(String s, int color, int shadow)
    {
        float maxW = ImGui.getContentRegionAvail().x;
        if (maxW <= 1f)
        {
            emitText(s, color, shadow);
            return;
        }
        for (String line : wrapToWidth(s, maxW))
        {
            emitText(line, color, shadow);
        }
    }

    /** Greedy word-wrap of {@code s} into lines no wider than {@code maxW} pixels
     *  (measured with the current font). Honours explicit {@code \n} breaks; a single
     *  word wider than {@code maxW} is left on its own (over-long) line. */
    private static List<String> wrapToWidth(String s, float maxW)
    {
        List<String> out = new ArrayList<>();
        for (String paragraph : s.split("\n", -1))
        {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" "))
            {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && ImGui.calcTextSize(candidate).x > maxW)
                {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                }
                else
                {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    // ---- button ------------------------------------------------------------

    public static boolean button(String id, String label, float width, boolean active)
    {
        float height = ImGui.getTextLineHeight() + 12f;
        ImVec2 pos = ImGui.getCursorScreenPos();

        ImGui.invisibleButton("##" + id, width, height);
        boolean clicked = ImGui.isItemClicked();

        int bg = active ? COL_ACCENT : (ImGui.isItemHovered() ? COL_BTN_HOVER : COL_BTN);
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(pos.x, pos.y, pos.x + width, pos.y + height, bg);

        ImVec2 ts = ImGui.calcTextSize(label);
        float tx = pos.x + (width - ts.x) * 0.5f;
        float ty = pos.y + (height - ImGui.getTextLineHeight()) * 0.5f;
        if (active)
        {
            shadowText(dl, tx, ty, COL_VALUE, COL_VALUE_SH, label);
        }
        else
        {
            shadowText(dl, tx, ty, COL_BTN_TEXT, COL_BTN_TX_SH, label);
        }

        return clicked;
    }

    /** Solid-accent call-to-action button with dark text (patcher Validate / Patch). */
    public static boolean primaryButton(String id, String label, float width)
    {
        float height = ImGui.getTextLineHeight() + 12f;
        ImVec2 pos = ImGui.getCursorScreenPos();

        ImGui.invisibleButton("##" + id, width, height);
        boolean clicked = ImGui.isItemClicked();

        int bg = ImGui.isItemHovered() ? COL_PRIMARY_HOV : COL_PRIMARY;
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(pos.x, pos.y, pos.x + width, pos.y + height, bg);

        ImVec2 ts = ImGui.calcTextSize(label);
        float tx = pos.x + (width - ts.x) * 0.5f;
        float ty = pos.y + (height - ImGui.getTextLineHeight()) * 0.5f;
        dl.addText(tx, ty, COL_PRIMARY_TEXT, label);

        return clicked;
    }

    /** Plain list entry: transparent at rest, subtle box on hover, accent fill when
     *  selected (the patcher's file rows — no per-row box like {@link #selectable}). */
    public static boolean listItem(String id, String label, boolean selected)
    {
        float width = ImGui.getContentRegionAvail().x;
        float height = 22f;
        ImVec2 pos = ImGui.getCursorScreenPos();

        ImGui.invisibleButton("##" + id, width, height);
        boolean clicked = ImGui.isItemClicked();

        ImDrawList dl = ImGui.getWindowDrawList();
        if (selected)
        {
            dl.addRectFilled(pos.x, pos.y, pos.x + width, pos.y + height, COL_ACCENT);
        }
        else if (ImGui.isItemHovered())
        {
            dl.addRectFilled(pos.x, pos.y, pos.x + width, pos.y + height, COL_ROW_HOVER);
        }

        float textY = pos.y + (height - ImGui.getTextLineHeight()) * 0.5f;
        shadowText(dl, pos.x + 6f, textY,
            selected ? COL_VALUE : COL_LABEL,
            selected ? COL_VALUE_SH : COL_LABEL_SH, label);

        return clicked;
    }

    // ---- list row (selectable) ---------------------------------------------

    /** Full-width selectable row: {@code label} on the left, optional {@code right}
     *  caption on the right, accent fill when {@code selected}. Returns true on click. */
    public static boolean selectable(String id, String label, String right, boolean selected)
    {
        float width = ImGui.getContentRegionAvail().x;
        float height = 22f;
        ImVec2 pos = ImGui.getCursorScreenPos();

        ImGui.invisibleButton("##" + id, width, height);
        boolean clicked = ImGui.isItemClicked();

        int bg = selected ? COL_ACCENT : (ImGui.isItemHovered() ? COL_BTN_HOVER : COL_BTN);
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(pos.x, pos.y, pos.x + width, pos.y + height, bg);

        float textY = pos.y + (height - ImGui.getTextLineHeight()) * 0.5f;
        shadowText(dl, pos.x + 9f, textY,
            selected ? COL_VALUE : COL_BTN_TEXT,
            selected ? COL_VALUE_SH : COL_BTN_TX_SH, label);

        if (right != null && !right.isEmpty())
        {
            float rw = ImGui.calcTextSize(right).x;
            shadowText(dl, pos.x + width - 9f - rw, textY,
                selected ? COL_VALUE : COL_DIM,
                selected ? COL_VALUE_SH : COL_DIM_SH, right);
        }

        return clicked;
    }

    // ---- unbounded relative drag (absolute coords) -------------------------

    /** Trackpad-styled field with no min/max: relative horizontal drag adds
     *  {@code speed} units per pixel to {@code v[idx]}. For world positions where a
     *  bounded fill bar makes no sense. Returns true when the value changed. */
    public static boolean dragValue(String id, String label, float[] v, int idx, float speed, String fmt)
    {
        float width = ImGui.getContentRegionAvail().x;
        ImVec2 pos = ImGui.getCursorScreenPos();
        float height = 24f;

        ImGui.invisibleButton("##" + id, width, height);
        boolean changed = false;

        if (ImGui.isItemActivated())
        {
            DRAG_START_VAL.put(id, v[idx]);
            DRAG_START_X.put(id, ImGui.getMousePos().x);
        }

        if (ImGui.isItemActive())
        {
            float startX = DRAG_START_X.getOrDefault(id, ImGui.getMousePos().x);
            float startVal = DRAG_START_VAL.getOrDefault(id, v[idx]);
            float nv = startVal + (ImGui.getMousePos().x - startX) * speed;
            if (nv != v[idx])
            {
                v[idx] = nv;
                changed = true;
            }
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
        }
        else if (ImGui.isItemHovered())
        {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
        }

        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(pos.x, pos.y, pos.x + width, pos.y + height, COL_BG);

        float textY = pos.y + (height - ImGui.getTextLineHeight()) * 0.5f;
        shadowText(dl, pos.x + 9f, textY, COL_LABEL, COL_LABEL_SH, label);

        String value = String.format(Locale.ROOT, fmt, v[idx]);
        float vw = ImGui.calcTextSize(value).x;
        shadowText(dl, pos.x + width - 9f - vw, textY, COL_VALUE, COL_VALUE_SH, value);

        return changed;
    }

    /** Double variant of {@link #dragValue(String, String, float[], int, float, String)}
     *  for absolute world positions: the drag delta is applied to the double start
     *  value, so a value like 100000.00 does not get re-snapped to the float lattice
     *  (~8mm at 1e5) on every frame of a drag. */
    public static boolean dragValue(String id, String label, double[] v, int idx, float speed, String fmt)
    {
        float width = ImGui.getContentRegionAvail().x;
        ImVec2 pos = ImGui.getCursorScreenPos();
        float height = 24f;

        ImGui.invisibleButton("##" + id, width, height);
        boolean changed = false;

        if (ImGui.isItemActivated())
        {
            DRAG_START_VAL_D.put(id, v[idx]);
            DRAG_START_X.put(id, ImGui.getMousePos().x);
        }

        if (ImGui.isItemActive())
        {
            float startX = DRAG_START_X.getOrDefault(id, ImGui.getMousePos().x);
            double startVal = DRAG_START_VAL_D.getOrDefault(id, v[idx]);
            double nv = startVal + (double) ((ImGui.getMousePos().x - startX) * speed);
            if (nv != v[idx])
            {
                v[idx] = nv;
                changed = true;
            }
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
        }
        else if (ImGui.isItemHovered())
        {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
        }

        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(pos.x, pos.y, pos.x + width, pos.y + height, COL_BG);

        float textY = pos.y + (height - ImGui.getTextLineHeight()) * 0.5f;
        shadowText(dl, pos.x + 9f, textY, COL_LABEL, COL_LABEL_SH, label);

        String value = String.format(Locale.ROOT, fmt, v[idx]);
        float vw = ImGui.calcTextSize(value).x;
        shadowText(dl, pos.x + width - 9f - vw, textY, COL_VALUE, COL_VALUE_SH, value);

        return changed;
    }

    // ---- collapsible section header ----------------------------------------

    public static boolean collapsingHeader(String id, String label, boolean defaultOpen)
    {
        boolean open = OPEN.getOrDefault(id, defaultOpen);

        float width = ImGui.getContentRegionAvail().x;
        float height = 22f;
        ImVec2 pos = ImGui.getCursorScreenPos();

        ImGui.invisibleButton("##h_" + id, width, height);
        if (ImGui.isItemClicked())
        {
            open = !open;
        }
        OPEN.put(id, open);

        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(pos.x, pos.y, pos.x + width, pos.y + height, COL_HEADER);

        float cx = pos.x + 11f;
        float cy = pos.y + height * 0.5f;
        if (open)
        {
            dl.addTriangleFilled(cx - 4f, cy - 2f, cx + 4f, cy - 2f, cx, cy + 3f, COL_TRI);
        }
        else
        {
            dl.addTriangleFilled(cx - 2f, cy - 4f, cx - 2f, cy + 4f, cx + 3f, cy, COL_TRI);
        }

        float ty = pos.y + (height - ImGui.getTextLineHeight()) * 0.5f;
        shadowText(dl, pos.x + 22f, ty, COL_LABEL, COL_LABEL_SH, label);

        return open;
    }

    // ---- trackpad ----------------------------------------------------------

    public static boolean trackpad(String id, String label, float[] v, float min, float max, String fmt)
    {
        float width = ImGui.getContentRegionAvail().x;
        ImVec2 pos = ImGui.getCursorScreenPos();

        ImGui.invisibleButton("##" + id, width, TRACKPAD_HEIGHT);
        boolean changed = false;

        if (ImGui.isItemActivated())
        {
            DRAG_START_VAL.put(id, v[0]);
            DRAG_START_X.put(id, ImGui.getMousePos().x);
        }

        if (ImGui.isItemActive())
        {
            float per = (max - min) / DRAG_RANGE_PX;
            float startX = DRAG_START_X.getOrDefault(id, ImGui.getMousePos().x);
            float startVal = DRAG_START_VAL.getOrDefault(id, v[0]);
            float nv = clamp(startVal + (ImGui.getMousePos().x - startX) * per, min, max);

            if (nv != v[0])
            {
                v[0] = nv;
                changed = true;
            }

            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
        }
        else if (ImGui.isItemHovered())
        {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
        }

        ImDrawList dl = ImGui.getWindowDrawList();
        float x0 = pos.x;
        float y0 = pos.y;
        float x1 = pos.x + width;
        float y1 = pos.y + TRACKPAD_HEIGHT;

        dl.addRectFilled(x0, y0, x1, y1, COL_BG);
        float pct = clamp((v[0] - min) / (max - min), 0f, 1f);
        dl.addRectFilled(x0, y0, x0 + width * pct, y1, COL_FILL);

        float textY = y0 + (TRACKPAD_HEIGHT - ImGui.getTextLineHeight()) * 0.5f;
        shadowText(dl, x0 + 9f, textY, COL_LABEL, COL_LABEL_SH, label);

        String value = String.format(Locale.ROOT, fmt, v[0]);
        float vw = ImGui.calcTextSize(value).x;
        shadowText(dl, x1 - 9f - vw, textY, COL_VALUE, COL_VALUE_SH, value);

        return changed;
    }

    // ---- toggle row --------------------------------------------------------

    public static boolean toggleRow(String id, String label, ImBoolean value)
    {
        float rowHeight = 16f;
        float toggleW = 34f;

        ImVec2 start = ImGui.getCursorScreenPos();
        float textY = start.y + (rowHeight - ImGui.getTextLineHeight()) * 0.5f;
        shadowText(ImGui.getWindowDrawList(), start.x, textY, COL_LABEL, COL_LABEL_SH, label);

        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getContentRegionAvail().x - toggleW);
        return toggle(id, value, toggleW, rowHeight);
    }

    private static boolean toggle(String id, ImBoolean value, float w, float h)
    {
        ImVec2 pos = ImGui.getCursorScreenPos();
        ImGui.invisibleButton("##" + id, w, h);

        boolean changed = false;
        if (ImGui.isItemClicked())
        {
            value.set(!value.get());
            changed = true;
        }

        boolean on = value.get();
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(pos.x, pos.y, pos.x + w, pos.y + h, on ? COL_ACCENT : COL_TRACK);

        float knobW = 14f;
        float knobH = 12f;
        float kx = on ? pos.x + w - 1f - knobW : pos.x + 1f;
        float ky = pos.y + (h - knobH) * 0.5f;
        dl.addRectFilled(kx, ky, kx + knobW, ky + knobH, on ? COL_KNOB_ON : COL_KNOB_OFF);

        return changed;
    }

    private static float clamp(float v, float lo, float hi)
    {
        return Math.max(lo, Math.min(hi, v));
    }
}
