package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.qualet.irlredactor.client.FreeCamera;
import org.qualet.irlredactor.imgui.ImGuiInput;

/**
 * Free-fly camera: routes the mouse wheel to the fly speed while the free camera
 * is active, and swallows the event so it doesn't also scroll the hotbar / zoom.
 * Skipped when ImGui wants the mouse (the editor panel is being scrolled), which
 * {@code MouseImGuiMixin} already cancels independently.
 */
@Mixin(Mouse.class)
public class FreeCamScrollMixin
{
    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void irl$freeCamSpeed(long window, double horizontal, double vertical, CallbackInfo ci)
    {
        if (FreeCamera.isActive() && !ImGuiInput.wantsMouse())
        {
            FreeCamera.adjustSpeed(vertical);
            ci.cancel();
        }
    }
}
