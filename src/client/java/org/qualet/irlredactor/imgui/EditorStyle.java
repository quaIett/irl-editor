package org.qualet.irlredactor.imgui;

import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

/**
 * Applies the flat dark palette from the HTML prototype to an ImGui style.
 */
public final class EditorStyle
{
    private EditorStyle()
    {
    }

    public static void apply(ImGuiStyle s)
    {
        // Flat: no rounding anywhere, 1px borders (the prototype uses border-radius:0).
        s.setWindowRounding(0f);
        s.setChildRounding(0f);
        s.setFrameRounding(0f);
        s.setPopupRounding(0f);
        s.setScrollbarRounding(0f);
        s.setGrabRounding(0f);
        s.setTabRounding(0f);

        s.setWindowBorderSize(1f); // keep only the panel's edge line
        s.setFrameBorderSize(0f);  // no thin outline on elements

        // Crisp pixel edges, like Minecraft/BBS (no smoothing on shapes).
        s.setAntiAliasedLines(false);
        s.setAntiAliasedLinesUseTex(false);
        s.setAntiAliasedFill(false);

        s.setWindowPadding(12f, 10f);
        s.setFramePadding(8f, 6f);
        s.setItemSpacing(6f, 6f);
        s.setItemInnerSpacing(6f, 4f);

        col(s, ImGuiCol.WindowBg,        0x23, 0x23, 0x23);
        col(s, ImGuiCol.ChildBg,         0x26, 0x26, 0x26);
        col(s, ImGuiCol.PopupBg,         0x1b, 0x1b, 0x1b);
        col(s, ImGuiCol.Border,          0x11, 0x11, 0x11);

        col(s, ImGuiCol.FrameBg,         0x1c, 0x1c, 0x1c);
        col(s, ImGuiCol.FrameBgHovered,  0x4a, 0x1e, 0x38);
        col(s, ImGuiCol.FrameBgActive,   0x4a, 0x1e, 0x38);

        col(s, ImGuiCol.Text,            0xe2, 0xe2, 0xe2);
        col(s, ImGuiCol.TextDisabled,    0x8a, 0x8a, 0x8a);

        col(s, ImGuiCol.Button,          0x2a, 0x2a, 0x2a);
        col(s, ImGuiCol.ButtonHovered,   0x3a, 0x3a, 0x3a);
        col(s, ImGuiCol.ButtonActive,    0xe6, 0x2e, 0x8b);

        col(s, ImGuiCol.Header,          0x20, 0x20, 0x20);
        col(s, ImGuiCol.HeaderHovered,   0x2a, 0x2a, 0x2a);
        col(s, ImGuiCol.HeaderActive,    0x20, 0x20, 0x20);

        col(s, ImGuiCol.CheckMark,       0xe6, 0x2e, 0x8b);
        col(s, ImGuiCol.SliderGrab,      0xe6, 0x2e, 0x8b);
        col(s, ImGuiCol.SliderGrabActive, 0xe6, 0x2e, 0x8b);

        col(s, ImGuiCol.TitleBg,         0x1b, 0x1b, 0x1b);
        col(s, ImGuiCol.TitleBgActive,   0x1b, 0x1b, 0x1b);

        col(s, ImGuiCol.ScrollbarBg,     0x1a, 0x1a, 0x1a);
        col(s, ImGuiCol.ScrollbarGrab,   0x3a, 0x3a, 0x3a);
        col(s, ImGuiCol.ScrollbarGrabHovered, 0x4a, 0x4a, 0x4a);

        col(s, ImGuiCol.Separator,       0x00, 0x00, 0x00);
    }

    private static void col(ImGuiStyle s, int idx, int r, int g, int b)
    {
        s.setColor(idx, r / 255f, g / 255f, b / 255f, 1f);
    }
}
