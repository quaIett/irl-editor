package org.qualet.irlredactor.light;

import java.util.ArrayList;
import java.util.List;

/**
 * The set of lights currently placed in the world — the BBS-free replacement for
 * IRLite's world scan of BBS ModelBlock form trees. The editor adds/removes
 * {@link PlacedLight}s here; {@link LightDriver} reads this list each frame.
 *
 * <p>Accessed only from the client main thread (client tick + world render run
 * on the same thread), so no synchronization.</p>
 */
public final class LightScene
{
    private static final List<PlacedLight> LIGHTS = new ArrayList<>();

    private LightScene()
    {}

    /** Live backing list — iterate to drive the engine; do not hold across frames. */
    public static List<PlacedLight> all()
    {
        return LIGHTS;
    }

    public static void add(PlacedLight light)
    {
        if (light != null)
        {
            LIGHTS.add(light);
        }
    }

    public static void remove(PlacedLight light)
    {
        LIGHTS.remove(light);
    }

    public static void clear()
    {
        LIGHTS.clear();
    }

    public static int count()
    {
        return LIGHTS.size();
    }
}
