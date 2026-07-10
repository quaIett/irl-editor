package org.qualet.irlredactor.editor;

import imgui.type.ImBoolean;
import imgui.type.ImString;

/**
 * In-memory state of a single light source. Pure prototype data — not wired to
 * any renderer or to IRLite. Trackpad values are kept in single-element float[]
 * arrays so they can be passed directly to ImGui widgets.
 */
public class LightState
{
    public enum Type
    {
        POINT, SPOT
    }

    public final ImString name = new ImString("Light 1", 64);
    public Type type = Type.POINT;

    /** RGBA, 0..1. Set via the color picker. */
    public final float[] color = {1f, 1f, 1f, 1f};

    public final float[] intensity = {1f};      // 0..20
    public final float[] radius    = {6f};      // 0.1..64   (point)
    public final float[] range     = {12f};     // 0.1..128  (spot)
    public final float[] angle     = {35f};     // 1..179    (spot)
    public final float[] soft      = {10f};     // 0..60     (spot)
    public final float[] beam      = {1f};      // 0..5
    public final float[] density   = {0.05f};   // 0.005..0.5
    public final float[] aniso     = {0.4f};    // -0.95..0.95
    public final float[] bulb      = {0f};      // 0..2

    /** Absolute world position (X/Y/Z). Driven by the placement group / LightSync.
     *  Double, not float: at large world coordinates (~1e5) a float quantizes to a
     *  ~8mm lattice, and the gizmo's per-frame write-back would re-snap the light
     *  every frame of a drag (visible stutter). Kept exact here; narrowing happens
     *  only in the camera-relative differences fed to rendering. */
    public final double[] pos = {0.0, 0.0, 0.0};
    /** Spot world-space direction (local +Z); normalized by the driver. Default down. */
    public final float[] dir = {0f, -1f, 0f};

    public final ImBoolean vol          = new ImBoolean(true);
    public final ImBoolean shadows      = new ImBoolean(true);
    public final ImBoolean entitiesOnly = new ImBoolean(false);
    public final ImBoolean blocksOnly   = new ImBoolean(false);

    /** Spot gobo/cookie (spot-only). {@link #cookie} = selected file name ("" = none).
     *  {@link #cookieRotation} is in DEGREES here (UI-friendly); the engine stores
     *  radians — converted in {@link LightSync}. */
    public final ImString  cookie         = new ImString("", 96);
    public final float[]   cookieRotation = {0f};   // 0..360 degrees
    public final float[]   cookieScale    = {1f};   // 0.1..4
    public final ImBoolean cookieInvert   = new ImBoolean(false);

    /** Resets the visual parameters to defaults. Identity (name/type) and
     *  placement (pos/dir) are preserved — this is the per-light "Сброс". */
    public void reset()
    {
        color[0] = 1f; color[1] = 1f; color[2] = 1f; color[3] = 1f;
        intensity[0] = 1f;
        radius[0] = 6f;
        range[0] = 12f;
        angle[0] = 35f;
        soft[0] = 10f;
        beam[0] = 1f;
        density[0] = 0.05f;
        aniso[0] = 0.4f;
        bulb[0] = 0f;
        vol.set(true);
        shadows.set(true);
        entitiesOnly.set(false);
        blocksOnly.set(false);
        cookie.set("");
        cookieRotation[0] = 0f;
        cookieScale[0] = 1f;
        cookieInvert.set(false);
    }
}
