package org.qualet.irlredactor.patcher;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Thin wrapper over Iris's shaderpack directory + listing, with a direct-scan fallback. */
public final class Shaderpacks
{
    private static final Logger LOG = LoggerFactory.getLogger("irl-redactor");

    private Shaderpacks()
    {}

    public static Path dir()
    {
        try
        {
            Path p = Iris.getShaderpacksDirectory();
            if (p != null)
            {
                return p;
            }
        }
        catch (Throwable t)
        {
            LOG.warn("Iris.getShaderpacksDirectory failed: {}", t.toString());
        }

        return FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
    }

    public static List<String> list()
    {
        Set<String> names = new LinkedHashSet<>();

        try
        {
            names.addAll(Iris.getShaderpacksDirectoryManager().enumerate());
        }
        catch (Throwable t)
        {
            LOG.warn("Iris enumerate failed: {}", t.toString());
        }

        if (names.isEmpty())
        {
            Path dir = dir();
            try (Stream<Path> stream = Files.list(dir))
            {
                stream.forEach(p ->
                {
                    String name = p.getFileName().toString();
                    if (Files.isDirectory(p) || name.toLowerCase().endsWith(".zip"))
                    {
                        names.add(name);
                    }
                });
            }
            catch (Throwable t)
            {
                LOG.warn("Shaderpack dir scan failed for {}: {}", dir, t.toString());
            }
        }

        LOG.info("Shaderpacks: dir={} count={}", dir(), names.size());
        return new ArrayList<>(names);
    }

    public static Path packPath(String name)
    {
        return dir().resolve(name);
    }

    public static void openFolder()
    {
        Util.getOperatingSystem().open(dir().toFile());
    }
}
