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

            // chain Minecraft's existing GLFW callbacks. Routed through reflection for
            // the same reason as the GL3 init below: another mod's imgui-java may win
            // on the shared classpath and differ from ours only by return type.
            invokeBackend(implGlfw, "init", new Class<?>[]{ long.class, boolean.class }, handle, true);

            // ImGuiImplGl3.init's return type drifted across imgui-java versions:
            // boolean in our bundled 1.89.0, void in the older build Axiom ships
            // unrelocated. A direct call compiles to init()Z and throws
            // NoSuchMethodError against a loaded init()V. Reflection resolves a method
            // by name + parameter types and ignores the return type, so it links
            // against whichever imgui-java actually won on the classpath. Prefer the
            // (String) overload, falling back to the no-arg one if it is absent.
            if (!invokeBackend(implGl3, "init", new Class<?>[]{ String.class }, "#version 150"))
            {
                invokeBackend(implGl3, "init", new Class<?>[]{});
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

    /**
     * Invokes an imgui-java backend method ({@code ImGuiImplGl3} / {@code ImGuiImplGlfw})
     * reflectively, matching on name + parameter types only. Unlike a compiled
     * {@code invokevirtual}, this ignores the declared return type, so the call still
     * links when a foreign imgui-java (e.g. the unrelocated copy Axiom bundles) wins on
     * the shared classpath and the same method differs from ours only by return type.
     *
     * @return {@code true} if the method exists and was invoked; {@code false} if no
     *         such overload exists (so the caller can try a different one). The real
     *         failure of an existing method is rethrown, not swallowed.
     */
    private static boolean invokeBackend(Object backend, String name, Class<?>[] paramTypes, Object... args)
    {
        java.lang.reflect.Method method;
        try
        {
            method = backend.getClass().getMethod(name, paramTypes);
        }
        catch (NoSuchMethodException absent)
        {
            return false;
        }

        try
        {
            method.invoke(backend, args);
            return true;
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("imgui backend " + name + " is not accessible", e);
        }
        catch (java.lang.reflect.InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException("imgui backend " + name + " failed", cause);
        }
    }

    // Glyph ranges must stay reachable until the atlas is actually built (on the
    // first gl3.newFrame), or ImGui would read freed memory — hence a static field.
    private static short[] glyphRanges;

    /**
     * Loads the UI font: a smooth (non-pixel) Cyrillic-capable sans. Prefers a
     * bundled {@code inter.ttf} if present (drop one in to switch to Inter), then
     * the system Segoe UI (interim face), then the ImGui default. Light
     * oversampling + no pixel-snap give clean anti-aliased text.
     */
    private void loadFonts(ImGuiIO io)
    {
        ImFontAtlas fonts = io.getFonts();
        glyphRanges = fonts.getGlyphRangesCyrillic();

        // Vector-font rendering: horizontal oversampling, no pixel-snap.
        ImFontConfig cfg = new ImFontConfig();
        cfg.setOversampleH(2);
        cfg.setOversampleV(1);
        cfg.setPixelSnapH(false);

        // 1) bundled TTF override — drop an inter.ttf here to switch the face with
        //    no code change (the interim face is the system Segoe UI below).
        byte[] ttf = readResource("/assets/irl-redactor/fonts/inter.ttf");
        if (ttf != null)
        {
            fonts.addFontFromMemoryTTF(ttf, 16f, cfg, glyphRanges);
            return;
        }

        // 2) system Segoe UI — a clean Cyrillic-capable sans (the interim face).
        String windir = System.getenv("WINDIR");
        String segoe = windir == null ? null : windir + "\\Fonts\\segoeui.ttf";
        if (segoe != null && new File(segoe).isFile())
        {
            fonts.addFontFromFileTTF(segoe, 16f, cfg, glyphRanges);
            return;
        }

        // 3) last resort.
        fonts.addFontDefault();
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
