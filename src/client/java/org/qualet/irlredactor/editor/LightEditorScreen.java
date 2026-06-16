package org.qualet.irlredactor.editor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.opengl.GL30;
import org.qualet.irlredactor.client.IRLRedactorClient;
import org.qualet.irlredactor.imgui.ImGuiRuntime;

/**
 * Empty Minecraft screen that hosts the ImGui editor. Opening a screen frees the
 * cursor and stops camera/player input, while the world keeps rendering behind
 * it (the prototype's "viewport"). ESC closes it via the default Screen handling.
 *
 * <p>1.21.11: the ImGui overlay is NOT drawn from {@link #render} anymore. The
 * GUI pipeline is deferred (a {@code DrawContext} records commands that the
 * {@code GuiRenderer} flushes to the framebuffer AFTER {@code render} returns), so
 * raw-GL ImGui drawn here was immediately overwritten by Minecraft's own GUI (the
 * "screen dims but the panel is invisible" bug). Instead {@code MinecraftClient}
 * is mixed into (see {@code MinecraftClientImGuiMixin}) to call
 * {@link #renderActiveOverlay()} right after {@code Framebuffer.blitToScreen()} —
 * the frame is on the default framebuffer by then, so ImGui lands on top.</p>
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
        // The host screen draws nothing itself (the dim background comes from the
        // framework's renderBackground). The ImGui panel is overlaid post-blit via
        // renderActiveOverlay() — see the class note.
    }

    @Override
    protected void applyBlur(DrawContext context)
    {
        // 1.21.x blurs the world behind an open screen; skip it so the light editor
        // keeps a clear view of the scene it is editing.
    }

    @Override
    protected void renderDarkening(DrawContext context)
    {
        // Skip the in-world dark overlay too (Screen#renderBackground tiles
        // INWORLD_MENU_BACKGROUND_TEXTURE over the frame for any open screen). The
        // editor edits the lit scene, so it must see it undimmed — same reason as
        // applyBlur above. renderBackground reaches this via its else branch
        // (deferSubtitles() is false), so no-op'ing it leaves a clean world view.
    }

    @Override
    public void removed()
    {
        // Persist edits as soon as the editor is closed (not only on disconnect).
        IRLRedactorClient.saveCurrentWorld();
    }

    /**
     * Render the active editor's ImGui panel onto the default framebuffer. Called
     * from {@code MinecraftClientImGuiMixin} after the frame has been blitted to
     * the screen, so ImGui overlays Minecraft's already-flushed GUI. No-op unless
     * a {@link LightEditorScreen} is the current screen.
     */
    public static void renderActiveOverlay()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof LightEditorScreen screen)
        {
            // Target framebuffer 0 (the window) — MC's frame is already there.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            ImGuiRuntime.get().frame(screen.panel::draw);
        }
    }
}
