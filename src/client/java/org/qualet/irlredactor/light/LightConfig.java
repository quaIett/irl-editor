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
}
