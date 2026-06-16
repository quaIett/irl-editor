package org.qualet.irlredactor.replay;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Soft (reflection-based) bridge to the standalone Replay Mod (johni0702). Replay
 * Mod is NOT a compile/runtime dependency: everything is gated behind
 * {@link #isPresent()} ({@code FabricLoader.isModLoaded("replaymod")}) and reached
 * by reflection, so the mod loads and runs identically with or without it.
 *
 * <p>"In a replay" == {@code ReplayModReplay.instance.getReplayHandler() != null}
 * (a .mcpr is being played back — timeline or fly-camera). Verified against
 * replaymod-1.20.4-2.6.23: {@code public static ReplayModReplay instance} +
 * {@code public ReplayHandler getReplayHandler()}.</p>
 */
public final class ReplayCompat
{
    private static final boolean PRESENT =
        FabricLoader.getInstance().isModLoaded("replaymod");

    private static boolean resolved;
    private static boolean failed;
    private static Field instanceField;     // static ReplayModReplay.instance
    private static Method getReplayHandler;  // ReplayModReplay#getReplayHandler()

    private ReplayCompat()
    {
    }

    public static boolean isPresent()
    {
        return PRESENT;
    }

    /** True only while a replay playback session is active. Never throws. */
    public static boolean inReplay()
    {
        if (!PRESENT || !resolve())
        {
            return false;
        }
        try
        {
            Object replayMod = instanceField.get(null);
            return replayMod != null && getReplayHandler.invoke(replayMod) != null;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static boolean resolve()
    {
        if (resolved)
        {
            return !failed;
        }
        resolved = true;
        try
        {
            Class<?> c = Class.forName("com.replaymod.replay.ReplayModReplay");
            instanceField = c.getField("instance");
            getReplayHandler = c.getMethod("getReplayHandler");
        }
        catch (Throwable t)
        {
            failed = true;
            // Replay Mod is present but its API moved — log once so it's diagnosable;
            // detection then stays disabled (inReplay() returns false).
            org.slf4j.LoggerFactory.getLogger("irl-redactor")
                .warn("ReplayCompat: reflection to com.replaymod.replay.ReplayModReplay failed", t);
        }
        return !failed;
    }
}
