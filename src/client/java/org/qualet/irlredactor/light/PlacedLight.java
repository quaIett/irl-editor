package org.qualet.irlredactor.light;

/**
 * A single light source placed in the world, in pure world coordinates. This is
 * the BBS-free neutral data model that replaces IRLite's BBS PointLightForm /
 * SpotlightForm: the editor populates {@link LightScene} with these, and
 * {@link LightDriver} feeds each one into {@link LightRegistry} every frame.
 *
 * <p>Field set + units mirror the engine's {@code registerPoint}/{@code registerSpot}
 * contract (see {@link LightRegistry}). The {@link #id} is stable for the life of
 * the instance, which is what the shadow caches key on.</p>
 */
public class PlacedLight
{
    public enum Type
    {
        POINT, SPOT
    }

    private static long NEXT_ID = 1L;

    /** Stable per-light id (keys the shadow tile / dirty / block caches). */
    public final long id;

    public Type type = Type.POINT;

    /** Editor-facing display name (used by the source list). Engine ignores it. */
    public String name = "Источник";

    /** Absolute world position. */
    public double x, y, z;

    /** World-space direction the spotlight points (local +Z); normalized by the
     *  driver. Ignored for point lights. Defaults to straight down. */
    public float dirX = 0f, dirY = -1f, dirZ = 0f;

    /** Linear RGBA, 0..1. Alpha is carried for parity with the editor; the
     *  engine currently consumes RGB only. */
    public float r = 1f, g = 1f, b = 1f, a = 1f;

    public float intensity = 1f;        // 0..20
    public float radius = 6f;           // point reach in blocks (also point shadow far)
    public float range = 12f;           // spot reach in blocks (= spot shadow far)
    public float outerAngleDeg = 35f;   // spot OUTER cone full angle, degrees
    public float innerAngleDeg = 25f;   // spot INNER cone full angle, degrees (<= outer)
    public float beamStrength = 1f;     // 0..5    volumetric beam strength
    public float anisotropy = 0.4f;     // -0.95..0.95  Henyey-Greenstein g
    public float vlDensity = 0.05f;     // 0.005..0.5   volumetric density
    public float bulbSize = 0f;         // 0..2    shadow softness (0 = shader global)
    public boolean entitiesOnly = false;
    public boolean blocksOnly = false;
    public boolean shadows = true;
    /** Auto-light hint, only consulted by {@link LightDriver} on the auto-light
     *  path (manual lights leave it true and ignore it): whether this auto block-
     *  light may take one of the scarce shadow slots. Set false for ultra-weak
     *  sources like redstone dust so they never waste a slot / cube bake. */
    public boolean autoShadowEligible = true;

    public PlacedLight()
    {
        this.id = NEXT_ID++;
    }

    public static PlacedLight point()
    {
        PlacedLight l = new PlacedLight();
        l.type = Type.POINT;
        return l;
    }

    public static PlacedLight spot()
    {
        PlacedLight l = new PlacedLight();
        l.type = Type.SPOT;
        return l;
    }

    /** Deep copy of every field except {@link #id}, which gets a fresh stable id
     *  (the shadow caches key on identity, so a duplicate must not share it). */
    public static PlacedLight copyOf(PlacedLight s)
    {
        PlacedLight l = new PlacedLight();
        l.type = s.type;
        l.name = s.name;
        l.x = s.x; l.y = s.y; l.z = s.z;
        l.dirX = s.dirX; l.dirY = s.dirY; l.dirZ = s.dirZ;
        l.r = s.r; l.g = s.g; l.b = s.b; l.a = s.a;
        l.intensity = s.intensity;
        l.radius = s.radius;
        l.range = s.range;
        l.outerAngleDeg = s.outerAngleDeg;
        l.innerAngleDeg = s.innerAngleDeg;
        l.beamStrength = s.beamStrength;
        l.anisotropy = s.anisotropy;
        l.vlDensity = s.vlDensity;
        l.bulbSize = s.bulbSize;
        l.entitiesOnly = s.entitiesOnly;
        l.blocksOnly = s.blocksOnly;
        l.shadows = s.shadows;
        return l;
    }
}
