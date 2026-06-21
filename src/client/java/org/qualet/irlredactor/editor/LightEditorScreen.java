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
 * <p>1.21.11: the ImGui overlay is NOT drawn from {@link #render}. The GUI pipeline
 * is deferred (a {@code DrawContext} records commands that the {@code GuiRenderer}
 * flushes to the framebuffer AFTER {@code render} returns), so raw-GL ImGui drawn
 * there was immediately overwritten by Minecraft's own GUI (the "screen dims but
 * the panel is invisible" bug). Instead {@code MinecraftClient} is mixed into (see
 * {@code MinecraftClientImGuiMixin}) to call {@link #renderActiveOverlay()} right
 * after {@code Framebuffer.blitToScreen()} — the frame is on the default
 * framebuffer by then, so ImGui lands on top of any open screen.</p>
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
     * Render the editor's ImGui panel onto the default framebuffer. Called from
     * {@code MinecraftClientImGuiMixin} after the frame has been blitted to the
     * screen, so ImGui overlays Minecraft's already-flushed GUI. Renders when the
     * detached overlay is visible OR a host {@link LightEditorScreen} is open; a
     * no-op otherwise.
     */
    public static void renderActiveOverlay()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!editorVisible && !(mc.currentScreen instanceof LightEditorScreen))
        {
            return;
        }
        ImGuiRuntime runtime = ImGuiRuntime.get();
        if (runtime.isDisabled())
        {
            // Backend already known unusable (conflicting imgui-java, e.g. Axiom) —
            // don't leave the cursor/input trapped behind an overlay that can't draw.
            forceClose(mc);
            return;
        }

        // Target framebuffer 0 (the window) — MC's frame is already there.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        runtime.frame(PANEL::draw);

        // The first frame may have just discovered the backend is unusable.
        if (runtime.isDisabled())
        {
            forceClose(mc);
        }
    }

    /** Force the editor closed on both entry paths when the ImGui backend is
     *  disabled, so the user isn't stuck with a freed cursor / swallowed input
     *  behind an overlay that renders nothing. */
    private static void forceClose(MinecraftClient mc)
    {
        editorVisible = false;
        if (mc.currentScreen instanceof LightEditorScreen)
        {
            mc.setScreen(null);
        }
    }
}
