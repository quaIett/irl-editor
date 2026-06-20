package org.qualet.irlredactor.light.cookie;

import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBImageResize;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.qualet.irlredactor.IRLRedactorMod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
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
 * blocked (the "slide projector" look).</p>
 */
public final class CookieArray
{
    /** Per-layer square resolution; loaded images are resampled to this. */
    public static final int RES = 512;
    /** Hard cap on simultaneously loaded distinct cookies (array depth). */
    public static final int MAX_LAYERS = 16;

    private static int glTextureId = 0;
    private static boolean initialized = false;

    /** file name -> array layer (or -1 cached for a known-bad file, so a broken
     *  image isn't re-decoded every frame). */
    private static final Map<String, Integer> nameToLayer = new HashMap<>();
    private static int nextLayer = 0;

    private CookieArray()
    {}

    /** {@code config/irl-redactor/cookies/} — where the user drops mask images. */
    public static Path dir()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("irl-redactor").resolve("cookies");
    }

    /** Lazy — 0 until the first cookie is uploaded (no VRAM if unused). */
    public static int getGlTextureId()
    {
        return glTextureId;
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
     *  a full array. The result (incl. -1) is cached per name. */
    public static int resolve(String name)
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
        int layer = (nextLayer < MAX_LAYERS) ? load(name) : -1;
        nameToLayer.put(name, layer);
        return layer;
    }

    private static int load(String name)
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

        ByteBuffer rawBuf = MemoryUtil.memAlloc(raw.length);
        rawBuf.put(raw).flip();

        ByteBuffer img = null;
        ByteBuffer resized = null;
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);

            img = STBImage.stbi_load_from_memory(rawBuf, w, h, c, 1);   // force 1 channel (grayscale)
            if (img == null)
            {
                IRLRedactorMod.LOGGER.warn("Cookie decode failed: {} ({})", name, STBImage.stbi_failure_reason());
                return -1;
            }

            resized = MemoryUtil.memAlloc(RES * RES);
            STBImageResize.stbir_resize_uint8(img, w.get(0), h.get(0), 0, resized, RES, RES, 0, 1);

            int layer = upload(resized);
            IRLRedactorMod.LOGGER.info("Cookie loaded '{}' -> layer {}", name, layer);
            return layer;
        }
        finally
        {
            if (img != null)
            {
                STBImage.stbi_image_free(img);
            }
            if (resized != null)
            {
                MemoryUtil.memFree(resized);
            }
            MemoryUtil.memFree(rawBuf);
        }
    }

    /** Upload one RES*RES grayscale buffer into the next free layer, allocating the
     *  array on first use. Returns the layer index. Restores the prior array bind. */
    private static int upload(ByteBuffer pixels)
    {
        int prev = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        if (!initialized)
        {
            init();
        }
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, glTextureId);

        int layer = nextLayer++;
        GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, RES, RES, 1,
            GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, pixels);

        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, prev);
        return layer;
    }

    private static void init()
    {
        int prev = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        glTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, glTextureId);

        GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_R8, RES, RES, MAX_LAYERS, 0,
            GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            FloatBuffer border = stack.floats(0f, 0f, 0f, 0f);   // outside the image = black = blocked
            GL11.glTexParameterfv(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_BORDER_COLOR, border);
        }

        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, prev);
        initialized = true;
    }

    /** Forget all loaded cookies and free the GL texture, so a next {@link #resolve}
     *  reloads from disk (the editor's "refresh" button — picks up edited images). */
    public static void reload()
    {
        nameToLayer.clear();
        nextLayer = 0;
        delete();
    }

    public static void delete()
    {
        if (initialized)
        {
            GL11.glDeleteTextures(glTextureId);
            glTextureId = 0;
            initialized = false;
        }
    }
}
