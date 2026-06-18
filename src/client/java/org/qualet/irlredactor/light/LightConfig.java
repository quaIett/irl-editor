package org.qualet.irlredactor.light;

/**
 * Plain configuration for the lighting engine — the BBS-free replacement for
 * IRLite's {@code IrliteConfig} (which was backed by BBS settings {@code Value*}).
 * Fields are static and mutable so the editor can drive them later; the getters
 * keep the same names and defaults the engine code expects.
 */
public final class LightConfig
{
    /** Draw in-world wireframe gizmos for placed lights (default off). */
    public static boolean showGuides = false;
    /** Shadow resolution preset ordinal (0 LOW .. 3 ULTRA), default 1 (MEDIUM). */
    public static int shadowQuality = 1;
    /** When on, shadow maps are only re-baked when the scene changes (default on). */
    public static boolean shadowCache = true;
    /** When on, world blocks cast shadows by their real shape (default on). */
    public static boolean shadowBlocks = true;
    /** Block-shadow collection radius in blocks (default 24). */
    public static int shadowBlockRadius = 24;
    /** Max full STATIC shadow re-bakes allowed per frame; deferred lamps keep
     *  their sticky tile's previous (valid) map and retry on a later frame, so
     *  a mass invalidation (block edit in a shared section, quality change, a
     *  camera pan across a row of lamps) is spread out instead of spiking. A
     *  light that has never baked, or just moved tiles, is never deferred.
     *  {@code <= 0} means unlimited. Default 4. */
    public static int shadowBakeBudget = 4;

    // --- Auto block-lights ----------------------------------------------------
    // Automatically place a point light on every light-emitting vanilla block
    // (torch, glowstone, lantern, lava, ...) within range, with hardcoded
    // colour/radius per block type. See AutoLightManager + BlockLightDefs.

    /** Master toggle for auto block-lights. Default OFF — it's an experimental,
     *  potentially heavy mode the user opts into. */
    public static boolean autoLights = false;
    /** Whether auto block-lights cast shadows. Default OFF: shadows are by far the
     *  heaviest part — the shaderpack does ~28 PCSS texture taps PER shadowed light
     *  PER lit pixel (capped at 16 lights), plus per-light cube bakes. Unshadowed
     *  lights early-out in the shader (no taps) and skip the bake entirely, so the
     *  default stays smooth even with a high source count. Turn on for shadows (the
     *  nearest ones win the 16 cube slots) at a real FPS cost. */
    public static boolean autoLightShadows = false;
    /** Global brightness multiplier applied to every auto block-light's
     *  hardcoded table intensity (default 1.0 = the table value as-is). */
    public static float autoLightIntensity = 1.0f;
    /** Global reach multiplier applied to every auto block-light's hardcoded
     *  table radius — how far each source shines (default 1.0). Distinct from
     *  {@link #autoLightRadius}, which is the scan radius. */
    public static float autoLightReach = 1.0f;
    /** Radius in blocks around the camera within which emissive blocks are
     *  scanned for auto-lighting (default 48). Larger = more lights found. */
    public static int autoLightRadius = 48;
    /** Hard cap on how many auto block-lights are fed to the engine, nearest
     *  first (default 200). Keeps the total light count under
     *  {@code LightBuffer.MAX_LIGHTS} (256) with headroom for manual lights. */
    public static int autoLightMax = 200;

    private LightConfig()
    {}

    public static boolean showGuides()
    {
        return showGuides;
    }

    public static boolean shadowCache()
    {
        return shadowCache;
    }

    public static boolean shadowBlocks()
    {
        return shadowBlocks;
    }

    public static int shadowQuality()
    {
        return shadowQuality;
    }

    public static int shadowBlockRadius()
    {
        return shadowBlockRadius;
    }

    public static int shadowBakeBudget()
    {
        return shadowBakeBudget;
    }

    public static boolean autoLights()
    {
        return autoLights;
    }

    public static boolean autoLightShadows()
    {
        return autoLightShadows;
    }

    public static float autoLightIntensity()
    {
        return autoLightIntensity;
    }

    public static float autoLightReach()
    {
        return autoLightReach;
    }

    public static int autoLightRadius()
    {
        return autoLightRadius;
    }

    public static int autoLightMax()
    {
        return autoLightMax;
    }
}
