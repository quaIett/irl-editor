package org.qualet.irlredactor.editor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.opengl.GL30;
import org.qualet.irlredactor.client.IRLRedactorClient;
import org.qualet.irlredactor.imgui.ImGuiRuntime;

/**
 * Hosts the ImGui light editor. Two ways in:
 * <ul>
 *   <li><b>Normal world</b> — opened as a real {@link Screen} (frees the cursor,
 *       swallows game input; the world keeps rendering behind it).</li>
 *   <li><b>In a replay</b> — there is no host screen (Replay Mod owns
 *       {@code currentScreen}); the panel is shown as a detached overlay toggled by
 *       {@link #editorVisible}.</li>
 * </ul>
 *
 * <p>Either way the panel is drawn post-blit from {@code MinecraftClientImGuiMixin}
 * via {@link #renderActiveOverlay()}, never from {@link #render} — so it can also
 * sit on top of a foreign screen.</p>
 */
public class LightEditorScreen extends Screen
{
    /**
     * Single shared panel backing BOTH entry paths (host screen and detached
     * overlay), so editor state (scene selection, gizmo) is identical regardless of
     * how it was opened.
     */
    private static final LightEditorPanel PANEL = new LightEditorPanel();

    /**
     * Detached-overlay visibility (the replay path). Toggled from a raw GLFW read in
     * {@link IRLRedactorClient}. Read on the render thread, written on the client
     * thread — volatile keeps the flip visible promptly.
     */
    private static volatile boolean editorVisible;

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
        // Nothing here: the panel is overlaid post-blit via renderActiveOverlay()
        // (see MinecraftClientImGuiMixin). The host screen exists only to free the
        // cursor / swallow game input in the normal-world case.
    }

    @Override
    public void removed()
    {
        // Persist edits as soon as the editor is closed (not only on disconnect).
        IRLRedactorClient.saveCurrentWorld();
    }

    // ---- detached overlay (replay path) -----------------------------------

    public static boolean isVisible()
    {
        return editorVisible;
    }

    /** True when the panel is being drawn this frame (detached overlay or host
     *  screen) — used by the input-capture mixins to gate when to swallow input. */
    public static boolean isOverlayActive()
    {
        return editorVisible || MinecraftClient.getInstance().currentScreen instanceof LightEditorScreen;
    }

    /** Toggles the detached overlay; saves the scene when hiding. */
    public static void toggleVisible()
    {
        setVisible(!editorVisible);
    }

    public static void setVisible(boolean visible)
    {
        if (editorVisible && !visible)
        {
            IRLRedactorClient.saveCurrentWorld();
        }
        editorVisible = visible;
    }

    // ---- render hook ------------------------------------------------------

    /**
     * Draws the editor panel onto the window framebuffer (FBO 0). Called every frame
     * from {@code MinecraftClientImGuiMixin}, after the frame is blitted to the
     * screen. Renders when the detached overlay is visible OR a host
     * {@link LightEditorScreen} is open; a no-op otherwise.
     */
    public static void renderActiveOverlay()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!editorVisible && !(mc.currentScreen instanceof LightEditorScreen))
        {
            return;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        ImGuiRuntime.get().frame(PANEL::draw);
    }
}
