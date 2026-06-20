package org.qualet.irlredactor.light;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.qualet.irlredactor.IRLRedactorMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 */
public final class LightStore
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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

        List<Dto> dtos = new ArrayList<>(lights.size());
        for (PlacedLight l : lights)
        {
            if (l != null)
            {
                dtos.add(Dto.from(l));
            }
        }

        try
        {
            Files.createDirectories(dir());
            try (Writer w = Files.newBufferedWriter(file(key)))
            {
                GSON.toJson(dtos, w);
            }
            IRLRedactorMod.LOGGER.info("Saved {} lights for world '{}'", dtos.size(), key);
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
            List<Dto> dtos = GSON.fromJson(r, new TypeToken<List<Dto>>() {}.getType());
            if (dtos == null)
            {
                return;
            }
            for (Dto d : dtos)
            {
                if (d != null)
                {
                    LightScene.add(d.to());
                }
            }
            IRLRedactorMod.LOGGER.info("Loaded {} lights for world '{}'", LightScene.count(), key);
        }
        catch (Exception e)
        {
            IRLRedactorMod.LOGGER.error("Failed to load lights for world '{}'", key, e);
        }
    }

    /** Plain serialization shape — decoupled from {@link PlacedLight} so the id /
     *  static counter aren't persisted (a fresh id is minted on load). */
    private static final class Dto
    {
        String type;
        String name;
        double x, y, z;
        float dirX, dirY, dirZ;
        float r, g, b, a;
        float intensity, radius, range, outerAngleDeg, innerAngleDeg;
        float beamStrength, anisotropy, vlDensity, bulbSize;
        boolean entitiesOnly, blocksOnly, shadows;
        String cookie;
        float cookieRotation, cookieScale;
        boolean cookieInvert;

        static Dto from(PlacedLight l)
        {
            Dto d = new Dto();
            d.type = l.type.name();
            d.name = l.name;
            d.x = l.x; d.y = l.y; d.z = l.z;
            d.dirX = l.dirX; d.dirY = l.dirY; d.dirZ = l.dirZ;
            d.r = l.r; d.g = l.g; d.b = l.b; d.a = l.a;
            d.intensity = l.intensity; d.radius = l.radius; d.range = l.range;
            d.outerAngleDeg = l.outerAngleDeg; d.innerAngleDeg = l.innerAngleDeg;
            d.beamStrength = l.beamStrength; d.anisotropy = l.anisotropy;
            d.vlDensity = l.vlDensity; d.bulbSize = l.bulbSize;
            d.entitiesOnly = l.entitiesOnly; d.blocksOnly = l.blocksOnly; d.shadows = l.shadows;
            d.cookie = l.cookie; d.cookieRotation = l.cookieRotation;
            d.cookieScale = l.cookieScale; d.cookieInvert = l.cookieInvert;
            return d;
        }

        PlacedLight to()
        {
            PlacedLight l = new PlacedLight(); // fresh stable id (advances counter -> no collisions)
            l.type = "SPOT".equals(type) ? PlacedLight.Type.SPOT : PlacedLight.Type.POINT;
            l.name = name == null ? "Источник" : name;
            l.x = x; l.y = y; l.z = z;
            l.dirX = dirX; l.dirY = dirY; l.dirZ = dirZ;
            l.r = r; l.g = g; l.b = b; l.a = a;
            l.intensity = intensity; l.radius = radius; l.range = range;
            l.outerAngleDeg = outerAngleDeg; l.innerAngleDeg = innerAngleDeg;
            l.beamStrength = beamStrength; l.anisotropy = anisotropy;
            l.vlDensity = vlDensity; l.bulbSize = bulbSize;
            l.entitiesOnly = entitiesOnly; l.blocksOnly = blocksOnly; l.shadows = shadows;
            l.cookie = cookie == null ? "" : cookie;
            l.cookieRotation = cookieRotation;
            l.cookieScale = cookieScale == 0f ? 1f : cookieScale;   // legacy/no-cookie default
            l.cookieInvert = cookieInvert;
            return l;
        }
    }
}
