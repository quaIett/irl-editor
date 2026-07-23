package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.qualet.irlredactor.client.FreeCamera;

/**
 * Free-fly camera: zeroes the player's movement input while the free camera is
 * active, so WASD/Space/Shift fly the camera (read separately in
 * {@link FreeCamera#advance()}) instead of walking the frozen player. Runs after
 * {@code KeyboardInput.tick} has filled the fields from the keybinds, so
 * {@code KeyBinding.isPressed()} still reports the true state for the fly logic.
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
        self.movementForward = 0.0f;
        self.movementSideways = 0.0f;
        // 1.21.2+ folded the six boolean pressing/jump/sneak fields into the
        // PlayerInput record (KeyboardInput.tick() is also no-arg now). DEFAULT
        // is the all-false no-input state, so this zeroes movement as before.
        self.playerInput = PlayerInput.DEFAULT;
    }
}
