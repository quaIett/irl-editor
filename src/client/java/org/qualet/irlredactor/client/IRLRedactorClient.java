package org.qualet.irlredactor.client;

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
import org.qualet.irlredactor.editor.LightEditorScreen;
import org.qualet.irlredactor.light.LightGuideRenderer;
import org.qualet.irlredactor.light.LightScene;
import org.qualet.irlredactor.light.LightStore;

import java.nio.file.Path;

/**
 * Client entrypoint: registers the keybind (L) that opens the light editor
 * screen and wires per-world persistence — the {@link LightScene} is loaded when
 * a world is joined and saved when it is left (and whenever the editor closes),
 * so a lighting setup is bound to the world it was made in.
 */
public class IRLRedactorClient implements ClientModInitializer
{
    private static KeyBinding openEditor;

    /** Key of the world currently joined (folder name SP / address MP), or null. */
    private static String currentWorldKey;

    @Override
    public void onInitializeClient()
    {
        openEditor = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.irl-redactor.open_editor",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            "category.irl-redactor"
        ));

        // In-world light guides (gated by LightConfig.showGuides).
        LightGuideRenderer.register();

        ClientTickEvents.END_CLIENT_TICK.register(client ->
        {
            while (openEditor.wasPressed())
            {
                if (client.currentScreen == null)
                {
                    client.setScreen(new LightEditorScreen());
                }
            }
        });

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
            currentWorldKey = null;
        });
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
