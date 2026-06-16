package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.MinecraftClient;
import org.qualet.irlredactor.editor.LightEditorScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the ImGui light editor on top of the finished frame, every frame —
 * independent of {@code currentScreen}. This is what lets the editor overlay a
 * foreign screen (Replay Mod's timeline {@code UserInputGuiScreen}); a host
 * {@link LightEditorScreen} is no longer required for the panel to render.
 *
 * <p>Injected after {@code Framebuffer.draw(int,int)} — the blit of Minecraft's
 * main framebuffer onto the window (FBO 0). At that point the whole frame (world +
 * HUD + any screen) is on FBO 0 and {@code Window.swapBuffers()} hasn't run yet, so
 * ImGui lands on top and is presented. No-op unless the editor is visible. (1.21.11
 * uses the same shape but the present method is named {@code blitToScreen}.)</p>
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientImGuiMixin
{
    @Inject(
        method = "render(Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gl/Framebuffer;draw(II)V",
            shift = At.Shift.AFTER
        )
    )
    private void irl$renderImGuiOverlay(boolean tick, CallbackInfo ci)
    {
        LightEditorScreen.renderActiveOverlay();
    }
}
