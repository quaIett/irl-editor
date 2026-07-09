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

/**
 * Per-frame entry point for the light + shadow pipeline. Thin delegate to the
 * shared {@link FramePipeline} (main structure); the only per-mod concern here is
 * the 1.21.11 inject target: {@code renderWorld(RenderTickCounter)} — the old
 * {@code (tickDelta, limitTime, MatrixStack)} signature is gone.
 */
@Mixin(GameRenderer.class)
public class GameRendererLightMixin
{
    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void irlite$collectLights(RenderTickCounter tickCounter, CallbackInfo ci)
    {
        // 1.21.11: renderWorld(RenderTickCounter) — the old (tickDelta, limitTime,
        // MatrixStack) parameters are gone, so derive the partial tick here
        // (ignoreFreeze=true matches the previous always-advancing behaviour).
        float tickDelta = tickCounter.getTickProgress(true);

        // Depth textures are freed on the dormant transition; re-ramp auto-shadow
        // first-bakes when shaders return instead of baking every cube in one frame.
        FramePipeline.frame(tickDelta, IrisShadersState::shadersDisabled, LightDriver::collect,
            LightDriver::resetAutoShadowRamp);
    }

    /**
     * Deferred SSBO upload — the light buffer is made relative to the current-frame eye
     * that the shaderpack reconstructs fragments against, not a stale camera.
     *
     * <p>Main-line (1.20.x) calls {@code Camera.update} inside {@code renderWorld} after HEAD,
     * so it injects right after that call. 1.21.11 moved the camera update out: {@code render}
     * invokes {@code updateCamera(RenderTickCounter)} (→ {@code Camera.update}) BEFORE it calls
     * {@code renderWorld}, so the camera is already this frame's by the time HEAD runs. We still
     * defer the upload to a strictly-later bytecode point than the HEAD collect — right after
     * {@code updateCameraState(F)} (offset 42, the call that settles this frame's camera-derived
     * render state) — so collect+bake at HEAD always runs first, and the upload lands well before
     * {@code WorldRenderer.render} / Iris activation (offset ~502).</p>
     */
    @Inject(method = "renderWorld",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/GameRenderer;updateCameraState(F)V",
                     shift = At.Shift.AFTER,
                     ordinal = 0),
            require = 1)
    private void irlite$uploadLights(RenderTickCounter tickCounter, CallbackInfo ci)
    {
        FramePipeline.uploadIfPending();
    }
}
