package org.qualet.irlredactor.light;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

import org.qualet.irl.light.LightRegistry;

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
    private LightDriver()
    {}

    public static void collect(ClientWorld world, Vec3d cameraPos, float tickDelta)
    {
        if (world == null || cameraPos == null)
        {
            return;
        }

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
            }
        }
    }

    private static void emitPoint(PlacedLight l)
    {
        LightRegistry.registerPoint(
            (float) l.x, (float) l.y, (float) l.z,
            l.r, l.g, l.b,
            l.intensity, l.radius,
            l.entitiesOnly, l.blocksOnly,
            l.anisotropy, l.vlDensity, l.beamStrength, l.bulbSize,
            l.shadows, l.id);
    }

    private static void emitSpot(PlacedLight l)
    {
        // Normalize the world-space direction defensively (matches LightCollector).
        float dx = l.dirX, dy = l.dirY, dz = l.dirZ;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1e-4f)
        {
            dx /= len;
            dy /= len;
            dz /= len;
        }
        else
        {
            dx = 0f;
            dy = 0f;
            dz = 1f;
        }

        // Full cone angles (degrees) -> half-angle cosines, exactly as IRLite did.
        float outer = l.outerAngleDeg;
        float inner = Math.min(l.innerAngleDeg, outer);
        float cosOuter = (float) Math.cos(Math.toRadians(outer * 0.5F));
        float cosInner = (float) Math.cos(Math.toRadians(inner * 0.5F));

        LightRegistry.registerSpot(
            (float) l.x, (float) l.y, (float) l.z,
            dx, dy, dz,
            l.r, l.g, l.b,
            l.intensity, l.range, cosOuter, cosInner,
            l.entitiesOnly, l.blocksOnly,
            l.anisotropy, l.vlDensity, l.beamStrength, l.bulbSize,
            l.shadows, l.id);
    }
}
