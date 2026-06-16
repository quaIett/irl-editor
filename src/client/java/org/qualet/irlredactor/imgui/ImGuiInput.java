package org.qualet.irlredactor.imgui;

import imgui.ImGui;
import org.qualet.irlredactor.editor.LightEditorScreen;

/**
 * Decides when the ImGui editor should swallow input so it doesn't leak to the
 * game / a foreign screen (e.g. Replay Mod's timeline). Safe to call before ImGui
 * is initialized — {@link #active()} guards {@code ImGui.getIO()} from asserting.
 */
public final class ImGuiInput
{
    private ImGuiInput()
    {
    }

    public static boolean wantsMouse()
    {
        return active() && ImGui.getIO().getWantCaptureMouse();
    }

    public static boolean wantsKeyboard()
    {
        return active() && ImGui.getIO().getWantCaptureKeyboard();
    }

    /** Overlay drawing this frame AND ImGui ready — guards getIO() from asserting. */
    private static boolean active()
    {
        return LightEditorScreen.isOverlayActive() && ImGuiRuntime.get().isInitialized();
    }
}
