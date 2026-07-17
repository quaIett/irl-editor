package org.qualet.irlredactor.light;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.qualet.irl.light.LightBuffer;
import org.qualet.irl.light.LightMath;
import org.qualet.irl.light.LightRegistry;
import org.qualet.irl.light.VlGlobalsBuffer;
import org.qualet.irlredactor.light.auto.AutoLightManager;
import org.qualet.irlredactor.light.cookie.CookieArray;
import org.qualet.irl.light.shadow.PointDepthAtlas;

/**
 * Feeds the {@link LightScene} into the {@link LightRegistry} each frame — the
 * BBS-free replacement for IRLite's {@code LightCollector} (which walked BBS
 * ModelBlock form trees + dashboard replays). Because every {@link PlacedLight}
 * already carries an absolute world transform, the whole render-path renderer
 * machinery IRLite needed for live-actor pose is unnecessary: we register every
 * light up front in world coordinates, exactly like IRLite's scanner path did.
 *
 * <p>Called at {@code renderWorld} HEAD by {@code GameRendererLightMixin}, before
 * the shadow bake and the SSBO flush.</p>
 */
public final class LightDriver
{
    /** Max NEW auto-light shadow first-bakes to introduce per active frame, so a
     *  freshly-lit area ramps its cube bakes over several frames instead of one
     *  spike (a per-light first bake bypasses the shadow bake budget by design —
     *  it can't show a blank map). Reset to 0 whenever the pipeline goes dormant
     *  (shaders off frees the depth textures), so each shaders-on re-ramps. */
    private static final int AUTO_SHADOW_RAMP_STEP = 2;
    private static int autoShadowRamp;

    private LightDriver()
    {}

    /** Pipeline went dormant (shaders off): the depth textures were freed, so the
     *  next shaders-on frame first-bakes from scratch — ramp the auto-shadows in
     *  over several frames again instead of baking all of them at once. */
    public static void resetAutoShadowRamp()
    {
        autoShadowRamp = 0;
    }

    public static void collect(ClientWorld world, Vec3d cameraPos, float tickDelta)
    {
        // Track the global-VL knobs each frame: they land in the globals UBO
        // (binding 7) on the SSBO upload, so UBO-era shader patches read every
        // VL number and flag live without a recompile (edited in the panel's
        // "Global volumetrics" group, see LightEditorPanel.vlGroup).
        VlGlobalsBuffer.set(
            LightConfig.vlIntensity(),
            LightConfig.vlMaxDist(),
            LightConfig.vlTipBoost(),
            LightConfig.vlTipRadius(),
            LightConfig.vlNoiseAmount(),
            LightConfig.vlNoiseScale(),
            LightConfig.vlNoiseSpeed(),
            LightConfig.vlSteps(),
            LightConfig.vlShadowStride(),
            LightConfig.vlNoiseStride(),
            (LightConfig.vlShadows() ? 1 : 0) | (LightConfig.vlNoise() ? 2 : 0)
                | (LightConfig.vlBlueNoise() ? 4 : 0) | (LightConfig.vlDitherTemporal() ? 8 : 0));

        if (world == null || cameraPos == null)
        {
            return;
        }

        // Manual lights first, so they keep priority on the limited shadow slots.
        // Count manual POINT lights that cast shadows: those compete for the same
        // cube-shadow slots the auto-lights would use, so their share is reserved.
        int manualShadowPoints = 0;
        java.util.List<PlacedLight> lights = LightScene.all();
        for (int i = 0, n = lights.size(); i < n; i++)
        {
            PlacedLight l = lights.get(i);
            if (l == null)
            {
                continue;
            }
            if (l.type == PlacedLight.Type.SPOT)
            {
                emitSpot(l);
            }
            else
            {
                emitPoint(l);
                if (l.shadows)
                {
                    manualShadowPoints++;
                }
            }
        }

        // Auto block-lights (torch / glowstone / ...), all point lights. Fed AFTER
        // the manual lights and capped to the SSBO headroom so a manual light is
        // never dropped at the 256-light limit. Only the nearest few cast shadows:
        //   - reserve the atlas blocks manual point lights need (PointDepthAtlas
        //     totals the tiers' blocks), so an auto-light never starves a manual
        //     one of a slot;
        //   - ramp the count up over frames so a freshly-lit area doesn't first-bake
        //     every cube in a single frame.
        // Nearest-first feed means the closest emitters win the shadow grants.
        if (LightConfig.autoLights())
        {
            int headroom = Math.max(0, LightBuffer.MAX_LIGHTS - LightRegistry.getCount());
            int feedMax = Math.min(LightConfig.autoLightMax(), headroom);
            java.util.List<PlacedLight> autos = AutoLightManager.nearest(cameraPos, feedMax);

            autoShadowRamp = Math.min(PointDepthAtlas.blockCount(), autoShadowRamp + AUTO_SHADOW_RAMP_STEP);
            int shadowBudget = LightConfig.autoLightShadows()
                ? Math.min(autoShadowRamp, Math.max(0, PointDepthAtlas.blockCount() - manualShadowPoints))
                : 0;

            // Grant shadows to the nearest ELIGIBLE auto-lights up to the budget;
            // ineligible ones (e.g. redstone dust) never consume a slot.
            int granted = 0;
            for (int i = 0, n = autos.size(); i < n; i++)
            {
                PlacedLight l = autos.get(i);
                if (l == null)
                {
                    continue;
                }
                boolean wantShadow = l.autoShadowEligible && granted < shadowBudget;
                l.shadows = wantShadow;
                if (wantShadow)
                {
                    granted++;
                }
                emitPoint(l);
            }
        }
    }

    private static void emitPoint(PlacedLight l)
    {
        LightRegistry.registerPoint(
            l.x, l.y, l.z,
            l.r, l.g, l.b,
            l.intensity, l.radius,
            l.entitiesOnly, l.blocksOnly,
            l.anisotropy, l.vlDensity, l.beamStrength, l.bulbSize,
            l.shadows, l.id);
    }

    private static void emitSpot(PlacedLight l)
    {
        // Normalize the world-space direction defensively (matches LightCollector).
        float[] dir = LightMath.normalizeDir(l.dirX, l.dirY, l.dirZ, 0f, 0f, 1f, new float[3]);
        float dx = dir[0], dy = dir[1], dz = dir[2];

        // Full cone angles (degrees) -> half-angle cosines, exactly as IRLite did.
        LightMath.Cone cone = LightMath.cone(l.outerAngleDeg, l.innerAngleDeg);
        float cosOuter = cone.cosOuter();
        float cosInner = cone.cosInner();

        // Resolve the gobo image to its texture-array layer (loads on first use,
        // cached after); -1 = no mask. Cheap per-frame: a name->layer map lookup.
        int cookieLayer = (l.cookie != null && !l.cookie.isEmpty()) ? CookieArray.resolve(l.cookie) : -1;
        float cookieFlags = l.cookieInvert ? 1F : 0F;

        LightRegistry.registerSpot(
            l.x, l.y, l.z,
            dx, dy, dz,
            l.r, l.g, l.b,
            l.intensity, l.range, cosOuter, cosInner,
            l.entitiesOnly, l.blocksOnly,
            l.anisotropy, l.vlDensity, l.beamStrength, l.bulbSize,
            l.shadows,
            (float) cookieLayer, l.cookieRotation, l.cookieScale, cookieFlags,
            l.id);
    }
}
