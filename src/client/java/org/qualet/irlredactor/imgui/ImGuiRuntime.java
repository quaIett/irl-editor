package org.qualet.irlredactor.imgui;

import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import net.minecraft.client.MinecraftClient;

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

        long handle = MinecraftClient.getInstance().getWindow().getHandle();

        ImGui.createContext();

        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(null); // don't litter the run dir with imgui.ini
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);

        EditorStyle.apply(ImGui.getStyle());
        loadFonts(io);

        // chain Minecraft's existing GLFW callbacks
        implGlfw.init(handle, true);
        implGl3.init("#version 150");

        initialized = true;
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
        ensureInit();

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
}
