package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.qualet.irl.light.FramePipeline;
import org.qualet.irl.light.iris.IrisShadersState;
import org.qualet.irlredactor.light.LightDriver;

@Mixin(GameRenderer.class)
public class GameRendererLightMixin
{
    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void irlite$collectLights(RenderTickCounter tickCounter, CallbackInfo ci)
    {
        // 1.21.1+: renderWorld(RenderTickCounter) — the old (tickDelta, limitTime,
        // MatrixStack) parameters are gone, so derive the partial tick here
        // (ignoreFreeze=true matches the previous always-advancing behaviour).
        float tickDelta = tickCounter.getTickDelta(true);

        // Depth textures are freed on the dormant transition; re-ramp auto-shadow
        // first-bakes when shaders return instead of baking every cube in one frame.
        FramePipeline.frame(tickDelta, IrisShadersState::shadersDisabled, LightDriver::collect,
            LightDriver::resetAutoShadowRamp);
    }
}
