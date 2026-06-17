package org.qualet.irlredactor.patcher;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.util.Util;
import org.qualet.irl.patcher.PatcherHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link PatcherHost} for the standalone editor: the Minecraft game dir, Iris's
 * shaderpacks directory + listing, the OS folder-open, and the 6 .irlights bundled
 * under {@code assets/irl-redactor/patches/}. Installed at client init via
 * {@code Patcher.install(new RedactorPatcherHost())}.
 */
public final class RedactorPatcherHost implements PatcherHost
{
    private static final Logger LOG = LoggerFactory.getLogger("irl-redactor");

    private static final List<String> BUNDLED = List.of(
        "bliss.irlights",
        "bsl.irlights",
        "complementaryreimagined.irlights",
        "iterationrp.irlights",
        "photon.irlights",
        "solas.irlights"
    );

    @Override
    public Path gameDir()
    {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path shaderpacksDir()
    {
        try
        {
            return Iris.getShaderpacksDirectory();
        }
        catch (Throwable t)
        {
            LOG.warn("Iris.getShaderpacksDirectory failed: {}", t.toString());
            return null;
        }
    }

    @Override
    public List<String> listShaderpacks()
    {
        try
        {
            return List.copyOf(Iris.getShaderpacksDirectoryManager().enumerate());
        }
        catch (Throwable t)
        {
            LOG.warn("Iris enumerate failed: {}", t.toString());
            return List.of();
        }
    }

    @Override
    public void openFolder(Path dir)
    {
        Util.getOperatingSystem().open(dir.toFile());
    }

    @Override
    public String patchesDirName()
    {
        return "irl-redactor";
    }

    @Override
    public List<String> bundledPatches()
    {
        return BUNDLED;
    }

    @Override
    public InputStream openBundledPatch(String name)
    {
        return RedactorPatcherHost.class.getResourceAsStream("/assets/irl-redactor/patches/" + name);
    }
}
