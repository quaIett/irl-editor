package org.qualet.irlredactor.editor;

import org.qualet.irlredactor.light.LightConfig;

/**
 * Quality + Beam-style presets over the live volumetric / shadow knobs, mirroring
 * the BBS addon's {@code IrlitePresets} 1:1. Two independent axes; neither is
 * stored — the current index is derived from the live {@link LightConfig} values
 * every frame (no exact match → the trailing "Custom" slot), and picking a preset
 * writes its members. Values are byte-identical to the addon so the two products
 * agree.
 */
public final class SettingsPresets
{
    private SettingsPresets()
    {
    }

    /** Quality axis labels; the last ("Custom") is the derived no-match slot. */
    public static final String[] QUALITY_LABELS = {"Performance", "Balanced", "Quality", "Ultra", "Custom"};
    /** Beam-style axis labels; the last ("Custom") is the derived no-match slot. */
    public static final String[] STYLE_LABELS = {"Clean", "Dusty", "Smoky", "Custom"};

    // Quality members: (steps, maxDist, shadowStride, noiseStride, vlShadows, shadowQuality, blockShadows).
    private record Quality(int steps, float maxDist, int shadowStride, int noiseStride,
                           boolean vlShadows, int shadowQuality, boolean blockShadows) {}

    private static final Quality[] QUALITY = {
        new Quality(16, 48f, 4, 4, false, 0, false),   // Performance
        new Quality(48, 96f, 2, 2, true, 1, true),     // Balanced
        new Quality(56, 128f, 1, 2, true, 2, true),    // Quality
        new Quality(64, 192f, 1, 1, true, 2, true),    // Ultra
    };

    // Beam-style members: (noise, amount, scale, speed, tipBoost, tipRadius).
    private record Style(boolean noise, float amount, float scale, float speed,
                         float tipBoost, float tipRadius) {}

    private static final Style[] STYLE = {
        new Style(false, 0.6f, 2.0f, 0.25f, 1.0f, 1.5f), // Clean
        new Style(true, 0.6f, 2.0f, 0.25f, 1.5f, 1.5f),  // Dusty
        new Style(true, 1.0f, 0.5f, 3.0f, 2.0f, 2.0f),   // Smoky
    };

    /** Current Quality index (0..3), or {@code QUALITY.length} (=Custom) if the live
     *  values match no preset. */
    public static int quality()
    {
        for (int i = 0; i < QUALITY.length; i++)
        {
            Quality q = QUALITY[i];
            if (LightConfig.vlSteps == q.steps
                && LightConfig.vlMaxDist == q.maxDist
                && LightConfig.vlShadowStride == q.shadowStride
                && LightConfig.vlNoiseStride == q.noiseStride
                && LightConfig.vlShadows == q.vlShadows
                && LightConfig.shadowQuality == q.shadowQuality
                && LightConfig.shadowBlocks == q.blockShadows)
            {
                return i;
            }
        }
        return QUALITY.length;
    }

    public static void applyQuality(int i)
    {
        if (i < 0 || i >= QUALITY.length)
        {
            return;
        }
        Quality q = QUALITY[i];
        LightConfig.vlSteps = q.steps;
        LightConfig.vlMaxDist = q.maxDist;
        LightConfig.vlShadowStride = q.shadowStride;
        LightConfig.vlNoiseStride = q.noiseStride;
        LightConfig.vlShadows = q.vlShadows;
        LightConfig.shadowQuality = q.shadowQuality;
        LightConfig.shadowBlocks = q.blockShadows;
    }

    /** Current Beam-style index (0..2), or {@code STYLE.length} (=Custom). With noise
     *  off, only noise / tipBoost / tipRadius are compared (the shape values are
     *  don't-cares), matching the addon. */
    public static int style()
    {
        for (int i = 0; i < STYLE.length; i++)
        {
            Style s = STYLE[i];
            if (LightConfig.vlNoise != s.noise)
            {
                continue;
            }
            boolean shapeOk = !LightConfig.vlNoise
                || (LightConfig.vlNoiseAmount == s.amount
                    && LightConfig.vlNoiseScale == s.scale
                    && LightConfig.vlNoiseSpeed == s.speed);
            if (shapeOk
                && LightConfig.vlTipBoost == s.tipBoost
                && LightConfig.vlTipRadius == s.tipRadius)
            {
                return i;
            }
        }
        return STYLE.length;
    }

    public static void applyStyle(int i)
    {
        if (i < 0 || i >= STYLE.length)
        {
            return;
        }
        Style s = STYLE[i];
        LightConfig.vlNoise = s.noise;
        LightConfig.vlNoiseAmount = s.amount;
        LightConfig.vlNoiseScale = s.scale;
        LightConfig.vlNoiseSpeed = s.speed;
        LightConfig.vlTipBoost = s.tipBoost;
        LightConfig.vlTipRadius = s.tipRadius;
    }
}
