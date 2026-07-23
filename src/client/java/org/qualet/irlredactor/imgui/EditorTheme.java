package org.qualet.irlredactor.imgui;

import imgui.ImColor;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

/**
 * Live editor-UI theme. The accent colour (buttons, sliders, toggles, tabs and
 * active states) is user-configurable from the "Interface" settings category;
 * every accent draw reads it each frame, so a change applies instantly. The
 * custom {@code Widgets} cache their accent ints from here (refreshed per frame)
 * and {@link #applyNative} re-pushes it onto the few native ImGui widgets.
 */
public final class EditorTheme
{
    private EditorTheme()
    {
    }

    /** Default accent = red #E42B25. */
    public static final float[] DEFAULT_ACCENT = { 0xE4 / 255f, 0x2B / 255f, 0x25 / 255f };

    /** Live accent RGB (0..1), edited in-place by the Interface colour picker. */
    public static final float[] accent = { DEFAULT_ACCENT[0], DEFAULT_ACCENT[1], DEFAULT_ACCENT[2] };

    /** Packed ABGR of the accent, for {@code ImDrawList} fills. */
    public static int accentU32()
    {
        return ImColor.rgba(b(accent[0]), b(accent[1]), b(accent[2]), 255);
    }

    /** A slightly brighter hover variant of the accent. */
    public static int accentHoverU32()
    {
        return ImColor.rgba(b(accent[0] + 0.12f), b(accent[1] + 0.06f), b(accent[2] + 0.05f), 255);
    }

    /** Re-applies the accent-dependent native style colours (per frame, so a live
     *  accent change reaches the native widgets that use it — checkmark, slider
     *  grab, active button, and the hovered/active input-frame tint). */
    public static void applyNative(ImGuiStyle s)
    {
        float r = accent[0], g = accent[1], bl = accent[2];
        s.setColor(ImGuiCol.ButtonActive, r, g, bl, 1f);
        s.setColor(ImGuiCol.CheckMark, r, g, bl, 1f);
        s.setColor(ImGuiCol.SliderGrab, r, g, bl, 1f);
        s.setColor(ImGuiCol.SliderGrabActive, r, g, bl, 1f);
        s.setColor(ImGuiCol.FrameBgHovered, r * 0.3f, g * 0.3f, bl * 0.3f, 1f);
        s.setColor(ImGuiCol.FrameBgActive, r * 0.3f, g * 0.3f, bl * 0.3f, 1f);
    }

    private static int b(float v)
    {
        int i = Math.round(v * 255f);
        return i < 0 ? 0 : Math.min(255, i);
    }
}
