package org.qualet.irlredactor.imgui;

import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import net.minecraft.client.MinecraftClient;
import org.qualet.irlredactor.IRLRedactorMod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Owns the ImGui context and the GLFW/GL3 backends. ImGui is rendered on top of
 * Minecraft's frame from a hosting {@code Screen}. Initialization is lazy (on the
 * first frame) so it runs on the render thread with a valid GL context, after
 * Minecraft has finished setting up its window and GLFW callbacks.
 */
public final class ImGuiRuntime
{
    private static ImGuiRuntime instance;

    private final ImGuiImplGlfw implGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 implGl3 = new ImGuiImplGl3();
    private boolean initialized;

    /**
     * Set when the ImGui backend is found unusable — typically because another mod
     * (e.g. Axiom) bundles an <em>unrelocated</em> {@code imgui-java} that shadows
     * our {@code imgui.gl3.ImGuiImplGl3} on the shared classpath and lacks methods
     * we call. Once disabled the overlay is a no-op, so a backend conflict degrades
     * gracefully instead of crashing Minecraft.
     */
    private boolean disabled;

    private ImGuiRuntime()
    {
    }

    public static ImGuiRuntime get()
    {
        if (instance == null)
        {
            instance = new ImGuiRuntime();
        }

        return instance;
    }

    private void ensureInit()
    {
        if (initialized)
        {
            return;
        }

        try
        {
            long handle = MinecraftClient.getInstance().getWindow().getHandle();

            ImGui.createContext();

            ImGuiIO io = ImGui.getIO();
            io.setIniFilename(null); // don't litter the run dir with imgui.ini

            EditorStyle.apply(ImGui.getStyle());
            loadFonts(io);

            // chain Minecraft's existing GLFW callbacks
            implGlfw.init(handle, true);

            // ImGuiImplGl3.init(String) is missing from some imgui-java builds that
            // other mods ship unrelocated (e.g. Axiom), which can shadow our class
            // on the shared classpath. Fall back to the no-arg init present in every
            // imgui-java version.
            try
            {
                implGl3.init("#version 150");
            }
            catch (NoSuchMethodError missingOverload)
            {
                implGl3.init();
            }

            initialized = true;
        }
        catch (Throwable t)
        {
            // A conflicting imgui-java on the classpath makes the backend unusable.
            // Disable the editor overlay rather than crashing Minecraft.
            disabled = true;
            IRLRedactorMod.LOGGER.warn(
                "ImGui backend init failed - another mod likely bundles a conflicting "
                + "imgui-java (e.g. Axiom). The IRL light-editor overlay is disabled.", t);
        }
    }

    // Glyph ranges must stay reachable until the atlas is actually built (on the
    // first gl3.newFrame), or ImGui would read freed memory — hence a static field.
    private static short[] glyphRanges;

    /**
     * Loads the bundled Minecraft default font (with Cyrillic glyphs) so the UI
     * matches BBS. Oversampling is disabled + pixel-snap on, for crisp pixel edges.
     * Falls back to a Cyrillic-capable system font, then to the ImGui default.
     */
    private void loadFonts(ImGuiIO io)
    {
        ImFontAtlas fonts = io.getFonts();
        glyphRanges = fonts.getGlyphRangesCyrillic();

        ImFontConfig cfg = new ImFontConfig();
        cfg.setOversampleH(1);
        cfg.setOversampleV(1);
        cfg.setPixelSnapH(true);

        byte[] ttf = readResource("/assets/irl-redactor/fonts/minecraft.ttf");
        if (ttf != null)
        {
            fonts.addFontFromMemoryTTF(ttf, 16f, cfg, glyphRanges);
        }
        else
        {
            String windir = System.getenv("WINDIR");
            String segoe = windir == null ? null : windir + "\\Fonts\\segoeui.ttf";
            if (segoe != null && new File(segoe).isFile())
            {
                fonts.addFontFromFileTTF(segoe, 16f, glyphRanges);
            }
            else
            {
                fonts.addFontDefault();
            }
        }
    }

    private static byte[] readResource(String path)
    {
        try (InputStream in = ImGuiRuntime.class.getResourceAsStream(path))
        {
            return in == null ? null : in.readAllBytes();
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** Renders a single ImGui frame. {@code ui} describes the UI in immediate mode. */
    public void frame(Runnable ui)
    {
        if (disabled)
        {
            return;
        }

        ensureInit();

        if (!initialized)
        {
            return; // init just failed and disabled the overlay
        }

        try
        {
            // gl3.newFrame() lazily builds device objects + the font atlas texture on
            // the first call; it must run before ImGui.newFrame() or ImGui asserts
            // "Font Atlas not built".
            implGl3.newFrame();
            implGlfw.newFrame();
            ImGui.newFrame();

            ui.run();

            ImGui.render();
            implGl3.renderDrawData(ImGui.getDrawData());
        }
        catch (LinkageError e)
        {
            // Same conflicting-imgui-java symptom surfacing during rendering (a
            // shadowed class missing a method/field we call). Disable the overlay
            // instead of crashing. Our own UI bugs are RuntimeExceptions, not
            // LinkageErrors, so they still surface normally.
            disabled = true;
            IRLRedactorMod.LOGGER.warn(
                "ImGui backend failed during rendering - conflicting imgui-java on the "
                + "classpath (e.g. Axiom). Disabling the IRL light-editor overlay.", e);
        }
    }

    public void dispose()
    {
        if (!initialized)
        {
            return;
        }

        implGl3.shutdown();
        implGlfw.shutdown();
        ImGui.destroyContext();
        initialized = false;
    }

    /** Whether the ImGui context + backends have been created (first frame ran). */
    public boolean isInitialized()
    {
        return initialized;
    }

    /** True when the ImGui backend was found unusable (e.g. a conflicting imgui-java
     *  shipped unrelocated by another mod) and the editor overlay has been disabled
     *  to avoid crashing Minecraft. */
    public boolean isDisabled()
    {
        return disabled;
    }
}
