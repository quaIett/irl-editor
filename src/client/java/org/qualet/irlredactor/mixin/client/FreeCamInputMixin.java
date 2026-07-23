package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
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
    @Inject(method = "tick(ZF)V", at = @At("TAIL"))
    private void irl$suppressMovement(boolean slowDown, float ticks, CallbackInfo ci)
    {
        if (!FreeCamera.isActive())
        {
            return;
        }
        Input self = (Input) (Object) this;
        self.movementForward = 0.0f;
        self.movementSideways = 0.0f;
        self.pressingForward = false;
        self.pressingBack = false;
        self.pressingLeft = false;
        self.pressingRight = false;
        self.jumping = false;
        self.sneaking = false;
    }
}
