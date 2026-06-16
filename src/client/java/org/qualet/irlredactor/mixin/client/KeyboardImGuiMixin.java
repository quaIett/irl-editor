package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.Keyboard;
import org.qualet.irlredactor.imgui.ImGuiInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swallows key / char events when an ImGui text field is active (e.g. editing a
 * light's name), so typing doesn't also trigger Minecraft / Replay Mod keybinds.
 * Gated on WantCaptureKeyboard, which — with keyboard nav disabled (see
 * {@code ImGuiRuntime}) — is true only while a text input is focused.
 */
@Mixin(Keyboard.class)
public class KeyboardImGuiMixin
{
    @Inject(method = "onKey(JIIII)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci)
    {
        if (ImGuiInput.wantsKeyboard())
        {
            ci.cancel();
        }
    }

    @Inject(method = "onChar(JII)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureChar(long window, int codePoint, int modifiers, CallbackInfo ci)
    {
        if (ImGuiInput.wantsKeyboard())
        {
            ci.cancel();
        }
    }
}
