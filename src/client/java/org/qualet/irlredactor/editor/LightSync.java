package org.qualet.irlredactor.editor;

import org.qualet.irlredactor.light.PlacedLight;

/**
 * The single bridge between the ImGui scratch ({@link LightState}) and the
 * engine model ({@link PlacedLight}). Keeping the mapping in one place is what
 * lets {@code PlacedLight} stay ImGui-free.
 *
 * <ul>
 *   <li>{@link #pull} runs once when the selection changes (engine -&gt; UI).</li>
 *   <li>{@link #push} runs every frame while a light is selected (UI -&gt; engine),
 *       so edits are live with no "apply" step — the driver reads the scene each
 *       frame.</li>
 * </ul>
 *
 * Three fields are not 1:1 (see the wiring plan):
 * <ol>
 *   <li><b>Spot cone:</b> editor has {@code angle} (outer) + {@code soft} (penumbra
 *       width); engine wants {@code outerAngleDeg} + {@code innerAngleDeg}.
 *       outer = angle, inner = clamp(angle - soft, 1, angle).</li>
 *   <li><b>Volumetrics:</b> engine has no on/off flag, just {@code beamStrength};
 *       effective beamStrength = vol ? beam : 0. The UI's beam slider value is kept
 *       across vol toggles (only pulled back when the stored beam is non-zero).</li>
 *   <li><b>Direction:</b> copied straight through; the driver normalizes it.</li>
 * </ol>
 */
public final class LightSync
{
    private LightSync()
    {}

    /** engine -> UI: mirror a PlacedLight into the ImGui buffer (on selection). */
    public static void pull(PlacedLight l, LightState s)
    {
        s.name.set(l.name == null ? "" : l.name);
        s.type = l.type == PlacedLight.Type.SPOT ? LightState.Type.SPOT : LightState.Type.POINT;

        s.color[0] = l.r; s.color[1] = l.g; s.color[2] = l.b; s.color[3] = l.a;
        s.intensity[0] = l.intensity;
        s.radius[0] = l.radius;
        s.range[0] = l.range;

        s.angle[0] = l.outerAngleDeg;
        s.soft[0] = Math.max(0f, l.outerAngleDeg - l.innerAngleDeg);

        boolean volOn = l.beamStrength > 0f;
        s.vol.set(volOn);
        if (volOn)
        {
            s.beam[0] = l.beamStrength; // keep the UI's last beam when stored beam is 0
        }
        s.density[0] = l.vlDensity;
        s.aniso[0] = l.anisotropy;
        s.bulb[0] = l.bulbSize;

        s.shadows.set(l.shadows);
        s.entitiesOnly.set(l.entitiesOnly);
        s.blocksOnly.set(l.blocksOnly);

        s.cookie.set(l.cookie == null ? "" : l.cookie);
        s.cookieRotation[0] = (float) Math.toDegrees(l.cookieRotation);
        s.cookieScale[0] = l.cookieScale;
        s.cookieInvert.set(l.cookieInvert);

        s.pos[0] = (float) l.x; s.pos[1] = (float) l.y; s.pos[2] = (float) l.z;
        s.dir[0] = l.dirX; s.dir[1] = l.dirY; s.dir[2] = l.dirZ;
    }

    /** UI -> engine: commit the ImGui buffer into a PlacedLight (every frame). */
    public static void push(LightState s, PlacedLight l)
    {
        l.name = s.name.get();
        l.type = s.type == LightState.Type.SPOT ? PlacedLight.Type.SPOT : PlacedLight.Type.POINT;

        l.r = s.color[0]; l.g = s.color[1]; l.b = s.color[2]; l.a = s.color[3];
        l.intensity = s.intensity[0];
        l.radius = s.radius[0];
        l.range = s.range[0];

        float outer = s.angle[0];
        float inner = Math.max(1f, outer - s.soft[0]);
        l.outerAngleDeg = outer;
        l.innerAngleDeg = Math.min(inner, outer);

        l.beamStrength = s.vol.get() ? s.beam[0] : 0f;
        l.vlDensity = s.density[0];
        l.anisotropy = s.aniso[0];
        l.bulbSize = s.bulb[0];

        l.shadows = s.shadows.get();
        l.entitiesOnly = s.entitiesOnly.get();
        l.blocksOnly = s.blocksOnly.get();

        l.cookie = s.cookie.get();
        l.cookieRotation = (float) Math.toRadians(s.cookieRotation[0]);
        l.cookieScale = s.cookieScale[0];
        l.cookieInvert = s.cookieInvert.get();

        l.x = s.pos[0]; l.y = s.pos[1]; l.z = s.pos[2];
        l.dirX = s.dir[0]; l.dirY = s.dir[1]; l.dirZ = s.dir[2];
    }
}
