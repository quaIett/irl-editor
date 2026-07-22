package org.qualet.irlredactor.client;

import imgui.ImGui;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL30;
import org.qualet.irl.light.IrlSamplers;
import org.qualet.irl.light.shadow.RedactorEntityCasterSource;
import org.qualet.irl.light.shadow.ShadowBakeProbe;
import org.qualet.irl.light.shadow.ShadowEngine;
import org.qualet.irl.patcher.Patcher;
import org.qualet.irlredactor.client.diag.VlProfiler;
import org.qualet.irlredactor.editor.LightEditorScreen;
import org.qualet.irlredactor.imgui.ImGuiRuntime;
import org.qualet.irlredactor.patcher.RedactorPatcherHost;
import org.qualet.irlredactor.light.cookie.CookieArray;
import org.qualet.irlredactor.light.LightConfig;
import org.qualet.irlredactor.light.LightGuideRenderer;
import org.qualet.irlredactor.light.LightScene;
import org.qualet.irlredactor.light.LightStore;
import org.qualet.irlredactor.light.auto.AutoLightManager;
import org.qualet.irlredactor.replay.ReplayCompat;

import java.nio.file.Path;

/**
 * Client entrypoint: registers the editor key (J) and wires per-world persistence —
 * the {@link LightScene} is loaded when a world is joined and saved when it is left
 * (and whenever the editor closes), so a lighting setup is bound to its world.
 *
 * <p>The J key has two behaviours: in a normal world it opens the host
 * {@link LightEditorScreen}; inside a replay (Replay Mod) it toggles the editor as a
 * detached overlay via a raw GLFW read — see {@link #onEndClientTick}.</p>
 */
public class IRLRedactorClient implements ClientModInitializer
{
    private static KeyBinding openEditor;
    private static KeyBinding freeCamera;

    /** Key of the world currently joined (folder name SP / address MP), or null. */
    private static String currentWorldKey;

    /** Previous raw (GLFW) state of J, for front-edge detection in a replay. */
    private static boolean rawToggleDown;

    /** Previous raw (GLFW) state of the free-camera bind, for front-edge detection. */
    private static boolean fcKeyWasDown;

    /** Previous editor-open state, to enable the free camera the moment it opens. */
    private static boolean fcEditorWasOpen;

    @Override
    public void onInitializeClient()
    {
        // Install the patcher host so the shared irl-core patcher can reach the game
        // dir / Iris shaderpacks dir / bundled .irlights (matches the IRLite wiring).
        Patcher.install(new RedactorPatcherHost());

        // Dev GPU profiler (editor "perf" section): wire the core-side bake probe
        // and the HUD overlay unconditionally — every call is a cheap no-op until
        // the user flips the runtime toggle (VlProfiler.setCollecting).
        HudRenderCallback.EVENT.register((ctx, tickDelta) -> VlProfiler.renderHud(ctx));
        // Free-fly camera "Speed x.x" indicator (no-op while the camera is off).
        HudRenderCallback.EVENT.register((ctx, tickDelta) -> FreeCamera.renderHud(ctx));
        ShadowEngine.installBakeProbe(new ShadowBakeProbe()
        {
            @Override
            public void section(String name)
            {
                VlProfiler.switchPass(name);
            }

            @Override
            public void counter(String key, int amount)
            {
                VlProfiler.counter(key, amount);
            }
        });

        // Install the shadow caster source (vanilla entity dispatcher) + config so the
        // shared irl-core shadow orchestration can reach this mod's per-mod pieces.
        ShadowEngine.install(new RedactorEntityCasterSource(), LightConfig.SHADOW);

        // Register the per-mod gobo/cookie mask array into the shared sampler registry;
        // rebound from its 2D registration to GL_TEXTURE_2D_ARRAY at bind time.
        IrlSamplers.register("irl_cookieArray", CookieArray::getGlTextureId, GL30.GL_TEXTURE_2D_ARRAY);

        openEditor = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.irl-redactor.open_editor",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "category.irl-redactor"
        ));

        freeCamera = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.irl-redactor.free_camera",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F,
            "category.irl-redactor"
        ));

        // In-world light guides (gated by LightConfig.showGuides).
        LightGuideRenderer.register();

        ClientTickEvents.END_CLIENT_TICK.register(IRLRedactorClient::onEndClientTick);

        // Per-world persistence: load on join, save + clear on leave.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
        {
            currentWorldKey = worldKey(client);
            LightStore.load(currentWorldKey);
            // Deferred initial bake: entering a world must not fire the cold-start
            // bake by itself — lights render shadowless until the editor's
            // "bake now" button clears the hold (perf section; see LightConfig).
            LightConfig.holdBake = LightConfig.holdBakeOnJoin;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
        {
            saveCurrentWorld();
            LightScene.clear();
            AutoLightManager.clear();
            FreeCamera.disable();
            currentWorldKey = null;
        });
    }

    /**
     * Editor toggle. The J key has two paths:
     * <ul>
     *   <li><b>In a replay</b> — a raw GLFW read of J toggles the detached overlay,
     *       bypassing Minecraft's KeyBinding routing (fires only with no screen
     *       open) and Replay Mod's contextual-keybind remapping, both of which
     *       swallow J while the timeline screen is up.</li>
     *   <li><b>Otherwise</b> — the vanilla KeyBinding opens the host
     *       {@link LightEditorScreen}, only when nothing else is open (unchanged).</li>
     * </ul>
     */
    private static void onEndClientTick(MinecraftClient client)
    {
        // Auto block-lights: rescan the emissive blocks around the player when due
        // (throttled inside; no-op when the feature is off or there's no world).
        if (client.world != null && client.player != null)
        {
            AutoLightManager.tick(client.world,
                client.player.getX(), client.player.getEyeY(), client.player.getZ());
        }

        // Free-fly camera lives inside the editor: it's on by default the moment
        // the editor opens, and the bound key (default F, read raw — the vanilla
        // keybind can't fire behind a screen, so no clash with swap-offhand)
        // toggles it off/on within the session. Auto-off + position reset on close.
        long fcHandle = client.getWindow().getHandle();
        boolean fcDown = FreeCamera.isKeyDown(fcHandle, freeCamera);
        boolean fcEdge = fcDown && !fcKeyWasDown;
        fcKeyWasDown = fcDown;
        boolean editorOpen = LightEditorScreen.isOverlayActive();
        if (editorOpen)
        {
            // Enable by default the first tick the editor is open.
            if (!fcEditorWasOpen && !FreeCamera.isActive())
            {
                FreeCamera.toggle();
            }
            if (fcEdge && !imguiWantsKeyboard())
            {
                FreeCamera.toggle();
            }
        }
        else
        {
            // Editor closed: turn the camera off and forget the saved position
            // (disable() is idempotent, so calling it every tick is fine).
            FreeCamera.disable();
        }
        fcEditorWasOpen = editorOpen;
        // Drain the vanilla queue so an in-editor F press never leaks out later.
        while (freeCamera.wasPressed())
        {
        }
        FreeCamera.tickFreeze();

        // Drain the keybind queue every tick so a press made during a replay (fly-
        // camera mode, where the keybind does fire) can't leak out as a stale open.
        boolean keybindPressed = false;
        while (openEditor.wasPressed())
        {
            keybindPressed = true;
        }

        // Raw front-edge detect for the editor's currently-bound key. Reads whatever
        // key/button the open-editor bind points at, so a rebind in Options → Controls
        // also drives the replay toggle — not the hard-coded default. See isBoundKeyDown.
        long handle = client.getWindow().getHandle();
        boolean down = isBoundKeyDown(handle);
        boolean rawPressed = down && !rawToggleDown;
        rawToggleDown = down;

        boolean inReplay = ReplayCompat.inReplay();

        if (inReplay)
        {
            // Toggle on EITHER signal:
            //   - keybindPressed fires in fly-camera mode (currentScreen == null, so
            //     the vanilla KeyBinding is live) — and unlike the raw poll it never
            //     misses a quick tap (it's edge-driven from the GLFW key callback);
            //   - rawPressed covers timeline mode, where Replay Mod's screen is open
            //     and swallows the KeyBinding, so the raw GLFW read is the only signal.
            // Ignore J while ImGui has keyboard focus (typing a light's name).
            if ((rawPressed || keybindPressed) && !imguiWantsKeyboard())
            {
                LightEditorScreen.toggleVisible();
            }
        }
        else
        {
            // Left a replay with the overlay still up: drop it (normal world uses
            // the host screen).
            if (LightEditorScreen.isVisible())
            {
                LightEditorScreen.setVisible(false);
            }
            if (keybindPressed && client.currentScreen == null)
            {
                client.setScreen(new LightEditorScreen());
            }
        }
    }

    /**
     * Raw GLFW read of whatever key/button the {@link #openEditor} bind currently
     * points at, so a rebind in Options → Controls also drives the replay-overlay
     * toggle (the vanilla {@code wasPressed()} queue is dead while Replay Mod's
     * timeline screen is up). Returns false for an unbound key or a scancode bind —
     * nothing to raw-poll there; the keybind queue still covers fly-camera mode.
     */
    private static boolean isBoundKeyDown(long handle)
    {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(openEditor);
        int code = key.getCode();
        switch (key.getCategory())
        {
            case KEYSYM:
                return code != GLFW.GLFW_KEY_UNKNOWN
                    && GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS;
            case MOUSE:
                return GLFW.glfwGetMouseButton(handle, code) == GLFW.GLFW_PRESS;
            default: // SCANCODE — no reliable raw poll; rely on the keybind queue.
                return false;
        }
    }

    /** True if ImGui is up and currently capturing keyboard input. */
    private static boolean imguiWantsKeyboard()
    {
        return ImGuiRuntime.get().isInitialized() && ImGui.getIO().getWantCaptureKeyboard();
    }

    /** Persists the current scene under the joined world's key (no-op if no world). */
    public static void saveCurrentWorld()
    {
        if (currentWorldKey != null)
        {
            LightStore.save(currentWorldKey, LightScene.all());
        }
    }

    /** Folder name for singleplayer, server address for multiplayer; null if neither. */
    private static String worldKey(MinecraftClient client)
    {
        MinecraftServer server = client.getServer();
        if (server != null)
        {
            try
            {
                Path root = server.getSavePath(WorldSavePath.ROOT);
                return "sp-" + sanitize(root.getFileName().toString());
            }
            catch (Exception e)
            {
                return null;
            }
        }

        ServerInfo info = client.getCurrentServerEntry();
        if (info != null && info.address != null && !info.address.isEmpty())
        {
            return "mp-" + sanitize(info.address);
        }
        return null;
    }

    private static String sanitize(String s)
    {
        return s.replaceAll("[^a-zA-Z0-9-_]", "_");
    }
}
