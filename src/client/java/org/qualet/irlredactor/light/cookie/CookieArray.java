package org.qualet.irlredactor.light.cookie;

import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;
import org.qualet.irl.light.CookieArrayBase;
import org.qualet.irlredactor.IRLRedactorMod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A {@code GL_TEXTURE_2D_ARRAY} of grayscale gobo/cookie masks — one layer per
 * loaded image — bound into every Iris program as {@code irl_cookieArray} (see
 * {@code ProgramSamplersBuilderMixin} + {@code SamplerBindingCubeArrayMixin}).
 *
 * <p>The spot shader projects a fragment into the light's frustum and multiplies
 * the light by the sampled luminance (white = pass, black = block) — a projected
 * mask, NOT a shadow: no depth, no bake, one texture tap.</p>
 *
 * <p>Images load from {@code config/irl-redactor/cookies/} on demand
 * ({@link #resolve}), resampled to a fixed {@link #RES} square, single channel
 * (R8). {@code CLAMP_TO_BORDER} black so everything outside the image area is
 * blocked (the "slide projector" look). The array texture, the guarded upload
 * and the STB decode/resample live in {@link CookieArrayBase}; this subclass keeps
 * the config-folder source and the load cache.</p>
 */
public final class CookieArray extends CookieArrayBase
{
    /** Per-layer square resolution; loaded images are resampled to this. */
    public static final int RES = CookieArrayBase.RES;
    /** Hard cap on simultaneously loaded distinct cookies (array depth). */
    public static final int MAX_LAYERS = 16;

    private static final CookieArray INSTANCE = new CookieArray();

    /** file name -> array layer (or -1 cached for a known-bad file, so a broken
     *  image isn't re-decoded every frame). A full-array miss is NOT cached, so a
     *  cookie first requested while full can still load after {@link #reload}. */
    private final Map<String, Integer> nameToLayer = new HashMap<>();
    private int nextLayer = 0;

    private CookieArray()
    {
        super(MAX_LAYERS);
    }

    /** {@code config/irl-redactor/cookies/} — where the user drops mask images. */
    public static Path dir()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("irl-redactor").resolve("cookies");
    }

    /** Lazy — 0 until the first cookie is uploaded (no VRAM if unused). */
    public static int getGlTextureId()
    {
        return INSTANCE.textureId();
    }

    /** Image file names in the cookies folder, sorted. Pure IO, no GL — safe from
     *  any thread; creates the folder if missing. */
    public static List<String> available()
    {
        List<String> out = new ArrayList<>();
        Path d = dir();
        try
        {
            if (!Files.isDirectory(d))
            {
                Files.createDirectories(d);
                return out;
            }
            try (Stream<Path> s = Files.list(d))
            {
                s.filter(Files::isRegularFile)
                 .map(p -> p.getFileName().toString())
                 .filter(CookieArray::isImage)
                 .sorted(String.CASE_INSENSITIVE_ORDER)
                 .forEach(out::add);
            }
        }
        catch (IOException e)
        {
            IRLRedactorMod.LOGGER.warn("Cookie folder list failed", e);
        }
        return out;
    }

    private static boolean isImage(String n)
    {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg")
            || l.endsWith(".tga") || l.endsWith(".bmp");
    }

    /** Resolve a cookie file name to its array layer, loading on first use. Render
     *  thread only (uploads to GL). Returns -1 for an empty name, a failed load, or
     *  a full array. A read/decode failure is cached per name (a broken image isn't
     *  re-decoded every frame); a full-array miss is transient and NOT cached. */
    public static int resolve(String name)
    {
        return INSTANCE.resolve0(name);
    }

    private int resolve0(String name)
    {
        if (name == null || name.isEmpty())
        {
            return -1;
        }
        Integer cached = nameToLayer.get(name);
        if (cached != null)
        {
            return cached;
        }
        if (nextLayer >= MAX_LAYERS)
        {
            return -1;
        }
        int layer = load(name);
        nameToLayer.put(name, layer);
        return layer;
    }

    private int load(String name)
    {
        byte[] raw;
        try
        {
            raw = Files.readAllBytes(dir().resolve(name));
        }
        catch (IOException e)
        {
            IRLRedactorMod.LOGGER.warn("Cookie read failed: {}", name, e);
            return -1;
        }

        ByteBuffer pixels = CookieArrayBase.decode(raw);
        if (pixels == null)
        {
            IRLRedactorMod.LOGGER.warn("Cookie decode failed: {} ({})", name, STBImage.stbi_failure_reason());
            return -1;
        }
        try
        {
            int layer = nextLayer++;
            uploadLayer(pixels, layer);
            IRLRedactorMod.LOGGER.info("Cookie loaded '{}' -> layer {}", name, layer);
            return layer;
        }
        finally
        {
            MemoryUtil.memFree(pixels);
        }
    }

    /** Forget all loaded cookies and free the GL texture, so a next {@link #resolve}
     *  reloads from disk (the editor's "refresh" button — picks up edited images). */
    public static void reload()
    {
        INSTANCE.reload0();
    }

    private void reload0()
    {
        nameToLayer.clear();
        nextLayer = 0;
        deleteTexture();
    }

    public static void delete()
    {
        INSTANCE.deleteTexture();
    }
}
