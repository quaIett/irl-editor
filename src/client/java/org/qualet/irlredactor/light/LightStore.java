package org.qualet.irlredactor.light;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;
import com.google.gson.JsonDeserializer;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.qualet.irlredactor.IRLRedactorMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Per-world persistence for the {@link LightScene}. The scene is saved as JSON
 * under {@code config/irl-redactor/lights/<worldKey>.json}, so a placed lighting
 * setup survives a relog and is bound to the world it was made in (the key is the
 * save-folder name for singleplayer / the server address for multiplayer — see
 * {@code IRLRedactorClient}).
 *
 * <p>Stored in the config dir rather than inside the world save so it works
 * uniformly for SP and MP without needing write access to a remote world.</p>
 *
 * <p>{@link PlacedLight} is serialized directly (no separate DTO) — its
 * {@code transient} fields ({@code id}, {@code autoShadowEligible}) are skipped
 * by Gson automatically. The {@link InstanceCreator} below routes deserialization
 * through {@code new PlacedLight()} instead of Gson's default unsafe-allocate, so
 * every loaded light still mints a fresh stable id (advances the counter, exactly
 * like the old {@code Dto.to()} did) rather than coming back as {@code id == 0}.</p>
 *
 * <p>{@link PlacedLight.Type} gets its own lenient deserializer instead of Gson's
 * native enum handling: the old {@code Dto} stored the type as a {@code String}
 * and read it back with {@code "SPOT".equals(type) ? SPOT : POINT}, so any
 * unknown/corrupt value silently fell back to {@code POINT} and the rest of the
 * file still loaded. Gson's default enum adapter instead throws on an unknown
 * constant, which fails the whole {@code List<PlacedLight>} parse and (via the
 * catch in {@link #load}) drops every light in the file. The adapter below
 * reproduces the exact old (case-sensitive) semantics; writing is untouched
 * (still Gson's default {@code name()} serialization), so the on-disk format is
 * unchanged.</p>
 */
public final class LightStore
{
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(PlacedLight.class, (InstanceCreator<PlacedLight>) (Type t) -> new PlacedLight())
        .registerTypeAdapter(PlacedLight.Type.class, (JsonDeserializer<PlacedLight.Type>) (json, t, ctx) ->
            "SPOT".equals(json.isJsonPrimitive() ? json.getAsString() : null)
                ? PlacedLight.Type.SPOT
                : PlacedLight.Type.POINT)
        .create();

    private LightStore()
    {}

    private static Path dir()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("irl-redactor").resolve("lights");
    }

    private static Path file(String key)
    {
        return dir().resolve(key + ".json");
    }

    /** Writes the current scene for {@code key}. A null/empty key is a no-op. */
    public static void save(String key, List<PlacedLight> lights)
    {
        if (key == null || key.isEmpty())
        {
            return;
        }

        try
        {
            Files.createDirectories(dir());
            try (Writer w = Files.newBufferedWriter(file(key)))
            {
                GSON.toJson(lights, new TypeToken<List<PlacedLight>>() {}.getType(), w);
            }
            IRLRedactorMod.LOGGER.info("Saved {} lights for world '{}'", lights.size(), key);
        }
        catch (IOException e)
        {
            IRLRedactorMod.LOGGER.error("Failed to save lights for world '{}'", key, e);
        }
    }

    /** Replaces the scene with the saved set for {@code key}. Clears it if there
     *  is no saved file (or the key is null) so lights never bleed across worlds. */
    public static void load(String key)
    {
        LightScene.clear();
        if (key == null || key.isEmpty())
        {
            return;
        }

        Path f = file(key);
        if (!Files.isRegularFile(f))
        {
            return;
        }

        try (Reader r = Files.newBufferedReader(f))
        {
            List<PlacedLight> lights = GSON.fromJson(r, new TypeToken<List<PlacedLight>>() {}.getType());
            if (lights == null)
            {
                return;
            }
            for (PlacedLight l : lights)
            {
                if (l == null)
                {
                    continue;
                }
                // Gson leaves a field missing from the JSON at its construction
                // default, so a pre-cookie save already comes back sane
                // (cookie="" / cookieScale=1f). These guards only cover an
                // explicit null/0 written by some older or hand-edited file.
                if (l.name == null)
                {
                    l.name = "Источник";
                }
                if (l.cookie == null)
                {
                    l.cookie = "";
                }
                if (l.cookieScale == 0f)
                {
                    l.cookieScale = 1f;
                }
                LightScene.add(l);
            }
            IRLRedactorMod.LOGGER.info("Loaded {} lights for world '{}'", LightScene.count(), key);
        }
        catch (Exception e)
        {
            IRLRedactorMod.LOGGER.error("Failed to load lights for world '{}'", key, e);
        }
    }
}
