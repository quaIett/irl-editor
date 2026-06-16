package org.qualet.irlredactor.patcher;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** The folder where users drop .irlights files: {@code <gameDir>/irl-redactor/patches}. */
public final class PatchLibrary
{
    public static final String EXTENSION = ".irlights";

    private static final Logger LOG = LoggerFactory.getLogger("irl-redactor");

    /** Patches shipped inside the mod jar (under {@code assets/irl-redactor/patches/}). On
     *  first use they are unpacked into {@link #dir()} so the patcher works out of the box;
     *  a file the user already placed (or edited) is never overwritten. */
    private static final String[] BUNDLED = {
        "bliss.irlights",
        "bsl.irlights",
        "complementaryreimagined.irlights",
        "iterationrp.irlights",
        "photon.irlights",
        "solas.irlights",
    };

    private static volatile boolean extracted;

    private PatchLibrary()
    {}

    public static Path dir()
    {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("irl-redactor").resolve("patches");
        try
        {
            Files.createDirectories(dir);
        }
        catch (IOException ignored)
        {}
        extractBundled(dir);
        return dir;
    }

    public static List<Path> list()
    {
        List<Path> patches = new ArrayList<>();
        Path dir = dir();

        try (Stream<Path> stream = Files.list(dir))
        {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(EXTENSION))
                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                .forEach(patches::add);
        }
        catch (IOException ignored)
        {}

        return patches;
    }

    public static void openFolder()
    {
        Util.getOperatingSystem().open(dir().toFile());
    }

    /** Unpacks any bundled patch missing from {@code dir}. Runs at most once per session. */
    private static void extractBundled(Path dir)
    {
        if (extracted)
        {
            return;
        }
        extracted = true;

        for (String name : BUNDLED)
        {
            Path target = dir.resolve(name);
            if (Files.exists(target))
            {
                continue;
            }
            try (InputStream in = PatchLibrary.class.getResourceAsStream("/assets/irl-redactor/patches/" + name))
            {
                if (in == null)
                {
                    continue;
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Unpacked bundled patch: {}", name);
            }
            catch (IOException e)
            {
                LOG.warn("Could not unpack bundled patch {}: {}", name, e.toString());
            }
        }
    }
}
