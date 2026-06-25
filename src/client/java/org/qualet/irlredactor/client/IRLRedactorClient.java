package org.qualet.irlredactor.client;

import imgui.ImGui;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.lwjgl.glfw.GLFW;
import org.qualet.irl.light.shadow.RedactorEntityCasterSource;
import org.qualet.irl.light.shadow.ShadowEngine;
import org.qualet.irl.patcher.Patcher;
import org.qualet.irlredactor.editor.LightEditorScreen;
import org.qualet.irlredactor.imgui.ImGuiRuntime;
import org.qualet.irlredactor.patcher.RedactorPatcherHost;
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

    /** Key of the world currently joined (folder name SP / address MP), or null. */
    private static String currentWorldKey;

    /** Previous raw (GLFW) state of J, for front-edge detection in a replay. */
    private static boolean rawToggleDown;

    @Override
    public void onInitializeClient()
    {
        // Install the patcher host so the shared irl-core patcher can reach the game
        // dir / Iris shaderpacks dir / bundled .irlights (matches the IRLite wiring).
        Patcher.install(new RedactorPatcherHost());

        // Install the shadow caster source (vanilla entity dispatcher) + config so the
        // shared irl-core shadow orchestration can reach this mod's per-mod pieces.
        ShadowEngine.install(new RedactorEntityCasterSource(), LightConfig.SHADOW);

        openEditor = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.irl-redactor.open_editor",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
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
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
        {
            saveCurrentWorld();
            LightScene.clear();
            AutoLightManager.clear();
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

        // Drain the keybind queue every tick so a press made during a replay (fly-
        // camera mode, where the keybind does fire) can't leak out as a stale open.
        boolean keybindPressed = false;
        while (openEditor.wasPressed())
        {
            keybindPressed = true;
        }

        // Raw front-edge detect for the physical J key (default bind).
        long handle = client.getWindow().getHandle();
        boolean down = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_J) == GLFW.GLFW_PRESS;
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
