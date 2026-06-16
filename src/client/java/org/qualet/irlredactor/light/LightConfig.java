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
}
