package org.qualet.irlredactor.client;

import java.util.Locale;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import org.qualet.irlredactor.editor.GuideOverlay;
import org.qualet.irlredactor.imgui.ImGuiInput;

/**
 * Client-side free-fly camera for the editor — a way to roam the scene while
 * placing lights, driven entirely from <em>inside</em> the open ImGui editor.
 *
 * <p>The editor screen frees the cursor for the panel, so vanilla keybinds are
 * dead behind it; this reads input raw instead:</p>
 * <ul>
 *   <li><b>Toggle</b> — the {@code free_camera} bind (default F), read raw in the
 *       editor context by {@code IRLRedactorClient}. Because it only acts while
 *       the editor is up (where the game's own F / swap-offhand can't fire), there
 *       is no keybind clash.</li>
 *   <li><b>Move</b> — WASD along the look direction, Space/Shift lift and drop,
 *       read raw from the movement binds (so a rebind is honoured); suppressed
 *       while an ImGui text field has focus.</li>
 *   <li><b>Look</b> — hold left mouse button over the world (not over a panel
 *       widget) to grab the cursor and aim with the mouse; release to hand the
 *       cursor back to the panel.</li>
 *   <li><b>Speed</b> — mouse wheel over the viewport ({@code FreeCamScrollMixin}),
 *       shown as a "Speed x.x" HUD indicator.</li>
 * </ul>
 *
 * <p>The real player is frozen in place — position pinned every tick
 * ({@link #tickFreeze()}) and movement input zeroed ({@code FreeCamInputMixin}) —
 * so only the local camera moves; the camera <em>position</em> is overridden in
 * {@code CameraFreeMixin} while rotation stays vanilla (driven by the look grab
 * above). Movement integrates per rendered frame with a real-time delta, so speed
 * is framerate-independent.</p>
 */
public final class FreeCamera
{
    private FreeCamera()
    {}

    /** Speed bounds and wheel step, in blocks per tick (1 tick = 1/20 s). */
    private static final double SPEED_MIN = 0.2;
    private static final double SPEED_MAX = 10.0;
    private static final double SPEED_STEP = 0.2;

    /** Clamp for the per-frame delta so an alt-tab / GC hitch can't teleport the
     *  camera across the world on the first frame back. */
    private static final double MAX_DT = 0.10;

    private static boolean active;

    /** Camera world position while flying. */
    private static double x;
    private static double y;
    private static double z;

    /** Whether {@link #x}/{@link #y}/{@link #z} hold a position to reuse. Kept
     *  across F off/on within one editor opening, reset when the editor closes
     *  ({@link #disable()}), so the next opening re-seeds from the eye. */
    private static boolean posValid;

    /** Fly speed in blocks per tick, wheel-adjustable. */
    private static double speed = 1.0;

    /** Player position captured on enable and pinned every tick while active. */
    private static double freezeX;
    private static double freezeY;
    private static double freezeZ;

    /** {@link System#nanoTime()} of the last {@link #advance()} for the frame delta. */
    private static long lastNanos;

    // ---- hold-LMB mouse-look --------------------------------------------------
    /** True while the left button is held over the viewport and the cursor is
     *  grabbed for aiming. */
    private static boolean lookActive;
    /** Previous raw cursor position (in the grabbed, unbounded space) for the delta. */
    private static double lookPrevX;
    private static double lookPrevY;
    /** Cursor position to restore when the look grab is released, so the panel
     *  cursor doesn't jump. */
    private static double savedCursorX;
    private static double savedCursorY;

    public static boolean isActive()
    {
        return active;
    }

    public static double x()
    {
        return x;
    }

    public static double y()
    {
        return y;
    }

    public static double z()
    {
        return z;
    }

    /**
     * Toggle the free camera. Enabling seeds the fly position from the current
     * camera eye and records the player's position to pin it to; requires a world
     * and player. Disabling releases any active look grab.
     */
    public static void toggle()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null)
        {
            active = false;
            return;
        }

        active = !active;
        if (active)
        {
            // Re-seed only when there's no saved position: F off/on within one
            // editor opening keeps where you left the camera; the position is
            // cleared when the editor closes (disable), so a fresh opening seeds
            // from the eye again.
            if (!posValid)
            {
                Camera cam = mc.gameRenderer.getCamera();
                Vec3d start = cam != null && cam.isReady() ? cam.getCameraPos() : player.getEyePos();
                x = start.x;
                y = start.y;
                z = start.z;
                posValid = true;
            }
            freezeX = player.getX();
            freezeY = player.getY();
            freezeZ = player.getZ();
            lastNanos = System.nanoTime();
        }
        else
        {
            endLook(mc);
        }
    }

    /** Force the camera off (e.g. when the editor closes or on disconnect),
     *  releasing any active look grab. */
    public static void disable()
    {
        if (active)
        {
            endLook(MinecraftClient.getInstance());
        }
        active = false;
        // Editor closed (or disconnected): forget the saved position so re-opening
        // the editor re-seeds from the eye.
        posValid = false;
    }

    /**
     * Mouse-wheel speed control (from {@code FreeCamScrollMixin}); wheel up
     * ({@code vertical > 0}) speeds up, snapped to the 0.2 step and clamped to
     * [0.2, 10.0].
     */
    public static void adjustSpeed(double vertical)
    {
        if (vertical == 0.0)
        {
            return;
        }
        double next = speed + Math.signum(vertical) * SPEED_STEP;
        // Snap to the step grid so the HUD always reads a clean "x.x".
        next = Math.round(next / SPEED_STEP) * SPEED_STEP;
        speed = MathHelper.clamp(next, SPEED_MIN, SPEED_MAX);
    }

    /**
     * Advance the fly position by one rendered frame. Called from
     * {@code CameraFreeMixin} at Camera.update TAIL (once per frame while the
     * world renders behind the editor). Updates the look grab first, then
     * integrates the movement keys against the (possibly just-rotated) look with a
     * real-time delta.
     */
    public static void advance()
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null)
        {
            return;
        }
        long handle = mc.getWindow().getHandle();

        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        if (dt <= 0.0)
        {
            return;
        }
        if (dt > MAX_DT)
        {
            dt = MAX_DT;
        }

        updateLook(mc, player, handle);

        // Read movement raw so it works while the editor screen holds the cursor;
        // gated off while an ImGui text field is focused (typing a light's name).
        boolean typing = ImGuiInput.wantsKeyboard();
        GameOptions o = mc.options;
        boolean fwd = !typing && isKeyDown(handle, o.forwardKey);
        boolean back = !typing && isKeyDown(handle, o.backKey);
        boolean left = !typing && isKeyDown(handle, o.leftKey);
        boolean right = !typing && isKeyDown(handle, o.rightKey);
        boolean up = !typing && isKeyDown(handle, o.jumpKey);
        boolean down = !typing && isKeyDown(handle, o.sneakKey);

        double yawRad = Math.toRadians(player.getYaw());
        double pitchRad = Math.toRadians(player.getPitch());
        double cosPitch = Math.cos(pitchRad);

        // Full look vector (matches Entity.getRotationVector) — fly where you look.
        double lookX = -Math.sin(yawRad) * cosPitch;
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * cosPitch;
        // Horizontal "right" perpendicular to the look direction.
        double rightX = -lookZ;
        double rightZ = lookX;
        double rightLen = Math.sqrt(rightX * rightX + rightZ * rightZ);
        if (rightLen > 1.0e-6)
        {
            rightX /= rightLen;
            rightZ /= rightLen;
        }

        double mx = 0.0;
        double my = 0.0;
        double mz = 0.0;
        if (fwd)
        {
            mx += lookX;
            my += lookY;
            mz += lookZ;
        }
        if (back)
        {
            mx -= lookX;
            my -= lookY;
            mz -= lookZ;
        }
        if (right)
        {
            mx += rightX;
            mz += rightZ;
        }
        if (left)
        {
            mx -= rightX;
            mz -= rightZ;
        }
        if (up)
        {
            my += 1.0;
        }
        if (down)
        {
            my -= 1.0;
        }

        double len = Math.sqrt(mx * mx + my * my + mz * mz);
        if (len > 1.0e-6)
        {
            // speed is blocks/tick; dt*20 = ticks elapsed this frame.
            double scale = speed * dt * 20.0 / len;
            x += mx * scale;
            y += my * scale;
            z += mz * scale;
        }
    }

    /**
     * Hold-LMB mouse-look. Grabs the cursor when the left button goes down over
     * the world (not over an ImGui widget), aims with the raw cursor delta while
     * held, and releases the cursor back to the panel when let go. Rotation is
     * applied to the player (the camera follows it), matching vanilla feel via
     * {@code changeLookDirection}.
     */
    private static void updateLook(MinecraftClient mc, ClientPlayerEntity player, long handle)
    {
        boolean lmb = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (!lookActive)
        {
            // Start only when the press lands on the viewport, not on a panel widget.
            if (lmb && !ImGuiInput.wantsMouse() && !GuideOverlay.isHandleActive())
            {
                lookActive = true;
                double[] cx = new double[1];
                double[] cy = new double[1];
                GLFW.glfwGetCursorPos(handle, cx, cy);
                savedCursorX = cx[0];
                savedCursorY = cy[0];
                GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                // Re-read the baseline in the grabbed space so the first frame has no jump.
                GLFW.glfwGetCursorPos(handle, cx, cy);
                lookPrevX = cx[0];
                lookPrevY = cy[0];
            }
            return;
        }

        if (!lmb)
        {
            endLook(mc);
            return;
        }

        double[] cx = new double[1];
        double[] cy = new double[1];
        GLFW.glfwGetCursorPos(handle, cx, cy);
        double dx = cx[0] - lookPrevX;
        double dy = cy[0] - lookPrevY;
        lookPrevX = cx[0];
        lookPrevY = cy[0];

        if (dx != 0.0 || dy != 0.0)
        {
            double sens = mc.options.getMouseSensitivity().getValue();
            double f = sens * 0.6 + 0.2;
            double g = f * f * f * 8.0;
            player.changeLookDirection(dx * g, dy * g);
        }
    }

    /** Release the look grab, restoring the free cursor at the saved position. */
    private static void endLook(MinecraftClient mc)
    {
        if (!lookActive)
        {
            return;
        }
        lookActive = false;
        long handle = mc.getWindow().getHandle();
        GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        GLFW.glfwSetCursorPos(handle, savedCursorX, savedCursorY);
    }

    /**
     * Raw down-state of whatever key/button a bind currently points at (honours a
     * rebind). Used for both the toggle and the movement keys so they work behind
     * the editor screen, where the vanilla keybind state is frozen.
     */
    public static boolean isKeyDown(long handle, KeyBinding bind)
    {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(bind);
        int code = key.getCode();
        switch (key.getCategory())
        {
            case KEYSYM:
                return code != GLFW.GLFW_KEY_UNKNOWN
                    && GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS;
            case MOUSE:
                return GLFW.glfwGetMouseButton(handle, code) == GLFW.GLFW_PRESS;
            default: // SCANCODE — no reliable raw poll.
                return false;
        }
    }

    /**
     * Pin the real player in place while flying. Called every client tick from
     * {@code IRLRedactorClient.onEndClientTick} (after the world tick), so the
     * frozen position is what the next frame renders — no model jitter. Together
     * with the movement suppression in {@code FreeCamInputMixin} this keeps the
     * player still without sending motion to the server.
     */
    public static void tickFreeze()
    {
        if (!active)
        {
            return;
        }
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null)
        {
            return;
        }
        player.setVelocity(0.0, 0.0, 0.0);
        player.setPosition(freezeX, freezeY, freezeZ);
        player.fallDistance = 0.0f;
    }

    /**
     * HUD indicator (registered as a {@code HudRenderCallback} in
     * {@code IRLRedactorClient}): "Free Camera" + "Speed x.x", centred just above
     * the hotbar. Draws nothing while inactive.
     */
    public static void renderHud(DrawContext ctx)
    {
        if (!active)
        {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.textRenderer == null)
        {
            return;
        }

        // "Speed x.x" only, tucked into the bottom-right corner.
        String speedText = String.format(Locale.ROOT, "Speed %.1f", speed);
        int w = mc.textRenderer.getWidth(speedText);
        int px = mc.getWindow().getScaledWidth() - w - 6;
        int py = mc.getWindow().getScaledHeight() - 14;
        ctx.drawText(mc.textRenderer, speedText, px, py, 0xFFFFFFFF, true);
    }
}
