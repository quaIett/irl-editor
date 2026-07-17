package org.qualet.irlredactor.light;

import org.qualet.irl.light.shadow.ShadowConfig;

/**
 * Plain configuration for the lighting engine — the BBS-free replacement for
 * IRLite's {@code IrliteConfig} (which was backed by BBS settings {@code Value*}).
 * Fields are static and mutable so the editor can drive them later; the getters
 * keep the same names and defaults the engine code expects.
 */
public final class LightConfig
{
    /** Adapter handed to {@link org.qualet.irl.light.shadow.ShadowEngine} at client
     *  init: the shadow-relevant subset of this config (the five getters
     *  {@link ShadowConfig} exposes), delegating to the static getters below. */
    public static final ShadowConfig SHADOW = ShadowConfig.builder()
            .shadowQuality(LightConfig::shadowQuality)
            .shadowCache(LightConfig::shadowCache)
            .shadowBakeBudget(LightConfig::shadowBakeBudget)
            .shadowBlocks(LightConfig::shadowBlocks)
            .shadowBlockRadius(LightConfig::shadowBlockRadius)
            .build();

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
    /** Max full static shadow bakes started per frame before the rest are
     *  deferred to a later frame (default 4). Spreads a mass invalidation (a
     *  block edit near a cluster of lamps) across frames instead of one spike;
     *  the deferred lamps keep their existing (slightly stale) map until baked.
     *  &lt;= 0 disables throttling (bake everything every frame). First bakes and
     *  tile-reassign bakes are never deferred (they would sample a blank or
     *  foreign map); dynamic overlays and static-&gt;live copies are never
     *  budgeted (they must run every frame). */
    public static int shadowBakeBudget = 4;

    // --- Auto block-lights ----------------------------------------------------
    // Automatically place a point light on every light-emitting vanilla block
    // (torch, glowstone, lantern, lava, ...) within range, with hardcoded
    // colour/radius per block type. See AutoLightManager + BlockLightDefs.

    /** Master toggle for auto block-lights. Default OFF — it's an experimental,
     *  potentially heavy mode the user opts into. */
    public static boolean autoLights = false;
    /** Surface culling for auto block-lights (default ON). When on, an emissive
     *  block only becomes a light if it is EXPOSED — i.e. at least one of its six
     *  face-neighbours is an opening (air / glass / any non-opaque, non-emitter
     *  cell). Blocks fully buried inside opaque terrain, or in the interior of a
     *  solid cluster of emitters, emit nothing: they're invisible and their light
     *  can't escape, so they'd only waste light slots (and are redundant with the
     *  cluster's outer shell, which stays lit). Turn off to light every emissive
     *  block in range regardless of exposure. */
    public static boolean autoLightCulling = true;
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

    // --- Global volumetrics (live) ---------------------------------------------
    // Runtime VL globals, pushed into the binding-7 globals UBO every frame by
    // LightDriver.collect so UBO-era shader patches read them live without a
    // recompile. Defaults mirror the Complementary pack's compiled #define
    // values (IRLITE_VL_*), so an untouched editor looks identical to the pack.

    /** Global volumetric intensity multiplier (default 1.0 = pack default). */
    public static float vlIntensity = 1.0f;
    /** Ray-march steps per light in the volumetric pass (default 48). Higher is
     *  smoother but costs performance on every beam-covered pixel. */
    public static int vlSteps = 48;
    /** Maximum volumetric ray distance in blocks (default 96). */
    public static float vlMaxDist = 96f;
    /** Per-step shadowing of the VL beams (default on) — UBO flags bit 0. */
    public static boolean vlShadows = true;
    /** Tap the shadow maps every Nth march step, reusing the result in between
     *  (default 2; 1 = every step). Halves the VL shadow cost at 2. */
    public static int vlShadowStride = 2;
    /** Extra volumetric glow near the light source itself (default 1.5). */
    public static float vlTipBoost = 1.5f;
    /** Radius of the tip glow around the source, in blocks (default 1.5). */
    public static float vlTipRadius = 1.5f;
    /** Animated density noise breaking the beams into drifting puffs
     *  (default on) — UBO flags bit 1. */
    public static boolean vlNoise = true;
    /** How strongly the noise modulates the beam, 0.2..1 (default 0.6). */
    public static float vlNoiseAmount = 0.6f;
    /** Approximate size of the noise puffs, in blocks (default 2.0). */
    public static float vlNoiseScale = 2.0f;
    /** Noise drift speed (default 0.25). Kept a multiple of 0.25 — the shader's
     *  wind is whole field-periods per its 3600 s time wrap, so in-between values
     *  would make the fog pop on the wrap (the core setter quantizes too). */
    public static float vlNoiseSpeed = 0.25f;
    /** Sample the density noise every Nth march step (default 2; 1 = every step). */
    public static int vlNoiseStride = 2;
    /** Blue-noise dither of the VL march start instead of the pack's white-ish
     *  hash (default on) — UBO flags bit 2. */
    public static boolean vlBlueNoise = true;
    /** Rotate the dither pattern every frame (default on) — UBO flags bit 3.
     *  If recorded footage shimmers on moving lamps without TAA, switch off per shot. */
    public static boolean vlDitherTemporal = true;

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

    public static boolean autoLightCulling()
    {
        return autoLightCulling;
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

    public static float vlIntensity()
    {
        return vlIntensity;
    }

    public static int vlSteps()
    {
        return vlSteps;
    }

    public static float vlMaxDist()
    {
        return vlMaxDist;
    }

    public static boolean vlShadows()
    {
        return vlShadows;
    }

    public static int vlShadowStride()
    {
        return vlShadowStride;
    }

    public static float vlTipBoost()
    {
        return vlTipBoost;
    }

    public static float vlTipRadius()
    {
        return vlTipRadius;
    }

    public static boolean vlNoise()
    {
        return vlNoise;
    }

    public static float vlNoiseAmount()
    {
        return vlNoiseAmount;
    }

    public static float vlNoiseScale()
    {
        return vlNoiseScale;
    }

    public static float vlNoiseSpeed()
    {
        return vlNoiseSpeed;
    }

    public static int vlNoiseStride()
    {
        return vlNoiseStride;
    }

    public static boolean vlBlueNoise()
    {
        return vlBlueNoise;
    }

    public static boolean vlDitherTemporal()
    {
        return vlDitherTemporal;
    }
}
