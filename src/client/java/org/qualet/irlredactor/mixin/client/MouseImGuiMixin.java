package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.Mouse;
import org.qualet.irlredactor.imgui.ImGuiInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swallows mouse button / scroll events before Minecraft (and any open screen,
 * e.g. Replay Mod's timeline) handles them, whenever the ImGui editor wants the
 * mouse. ImGui has already seen the event via its own chained GLFW callback, so the
 * panel still works; this just stops the click from also scrubbing the timeline.
 */
@Mixin(Mouse.class)
public class MouseImGuiMixin
{
    @Inject(method = "onMouseButton(JIII)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureMouseButton(long window, int button, int action, int mods, CallbackInfo ci)
    {
        if (ImGuiInput.wantsMouse())
        {
            ci.cancel();
        }
    }

    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci)
    {
        if (ImGuiInput.wantsMouse())
        {
            ci.cancel();
        }
    }
}
