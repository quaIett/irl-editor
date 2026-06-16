package org.qualet.irlredactor.editor;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.qualet.irlredactor.client.IRLRedactorClient;
import org.qualet.irlredactor.imgui.ImGuiRuntime;

/**
 * Empty Minecraft screen that hosts the ImGui editor. Opening a screen frees the
 * cursor and stops camera/player input, while the world keeps rendering behind
 * it (the prototype's "viewport"). ESC closes it via the default Screen handling.
 */
public class LightEditorScreen extends Screen
{
    private final LightEditorPanel panel = new LightEditorPanel();

    public LightEditorScreen()
    {
        super(Text.literal("IRLite Light Editor"));
    }

    @Override
    public boolean shouldPause()
    {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta)
    {
        // 1.21.11 GUI rendering is deferred (DrawContext records draw commands
        // that the engine flushes later), so the old context.draw() flush is
        // gone. ImGui still overlays the frame via raw GL here. NOTE: verify the
        // draw ordering at runtime — if ImGui ends up under MC's GUI, this may
        // need a later hook (e.g. a HUD/after-frame event) instead of Screen#render.
        ImGuiRuntime.get().frame(panel::draw);
    }

    @Override
    public void removed()
    {
        // Persist edits as soon as the editor is closed (not only on disconnect).
        IRLRedactorClient.saveCurrentWorld();
    }
}
