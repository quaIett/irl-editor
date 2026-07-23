package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.qualet.irlredactor.client.FreeCamera;

/**
 * Free-fly camera: zeroes the player's movement input while the free camera is
 * active, so WASD/Space/Shift fly the camera (read separately in
 * {@link FreeCamera#advance()}) instead of walking the frozen player. Runs after
 * {@code KeyboardInput.tick} has filled the input from the keybinds, so
 * {@code KeyBinding.isPressed()} still reports the true state for the fly logic.
 *
 * <p>1.21.11: the 1.20.x {@code movementForward}/{@code movementSideways} float
 * pair AND the {@code pressing*}/{@code jumping}/{@code sneaking} booleans are
 * gone — analog movement is a single {@code Vec2f movementVector} and the digital
 * state is the {@code PlayerInput} record — so we zero both, and {@code tick()} is
 * no-arg here (the old {@code tick(ZF)V} signature was removed by the 1.21.2 input
 * rework). {@code playerInput} is public (set through the cast); {@code
 * movementVector} is protected on the base {@code Input} class, so it is zeroed
 * through {@link InputAccessor} — a subclass {@code @Shadow} of the inherited
 * field fails to attach when the mixin targets {@code KeyboardInput}.</p>
 */
@Mixin(KeyboardInput.class)
public class FreeCamInputMixin
{
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void irl$suppressMovement(CallbackInfo ci)
    {
        if (!FreeCamera.isActive())
        {
            return;
        }
        Input self = (Input) (Object) this;
        self.playerInput = PlayerInput.DEFAULT;
        ((InputAccessor) self).setMovementVector(Vec2f.ZERO);
    }
}
