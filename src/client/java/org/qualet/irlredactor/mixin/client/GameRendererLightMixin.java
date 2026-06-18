package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.qualet.irlredactor.light.IrisShadersState;
import org.qualet.irl.light.LightBuffer;
import org.qualet.irlredactor.light.LightDriver;
import org.qualet.irl.light.LightRegistry;
import org.qualet.irlredactor.light.shadow.ShadowBaker;

@Mixin(GameRenderer.class)
public class GameRendererLightMixin
{
    /** True while the light pipeline is parked because shaders are off; the
     *  GPU-side release (SSBO zero + shadow textures/caches) runs once, on
     *  the transition into that state. */
    @Unique
    private static boolean irlite$dormant;

    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void irlite$collectLights(RenderTickCounter tickCounter, CallbackInfo ci)
    {
        // 1.21.1: renderWorld(RenderTickCounter) — the old (tickDelta, limitTime,
        // MatrixStack) parameters are gone, so derive the partial tick here
        // (ignoreFreeze=true matches the previous always-advancing behaviour).
        // NB: 1.21.1 still names this getTickDelta(boolean); the getTickProgress
        // rename only lands later (and is what the 1.21.11 line uses).
        float tickDelta = tickCounter.getTickDelta(true);

        // Shaders off -> nothing consumes the SSBO or the shadow maps, so the
        // whole collect/bake/upload pipeline would be wasted work (the shadow
        // bake being the expensive part). Drop the render-path registrations
        // that still arrive each frame, release the GPU-side state once, and
        // stay dormant until a shaderpack returns.
        if (IrisShadersState.shadersDisabled())
        {
            LightRegistry.clear();

            if (!irlite$dormant)
            {
                irlite$dormant = true;
                LightBuffer.uploadEmpty();
                ShadowBaker.onShadersDisabled();
                // Depth textures are freed here; re-ramp auto-shadow first-bakes
                // when shaders return instead of baking every cube in one frame.
                LightDriver.resetAutoShadowRamp();
            }

            return;
        }

        irlite$dormant = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cameraPos = camera != null ? camera.getPos() : Vec3d.ZERO;
        // Forward look vector for the shadow baker's behind-camera light cull.
        Vec3d cameraForward = camera != null ? Vec3d.fromPolar(camera.getPitch(), camera.getYaw()) : null;

        // Register every placed light up front in pure world coordinates. (IRLite
        // also had a render-path for live-actor pose; our lights already know
        // their world transform, so the scanner path alone covers everything.)
        LightDriver.collect(world, cameraPos, tickDelta);

        // Bake spotlight shadow depth maps BEFORE the SSBO upload (sets each
        // spot's shadow tile index) and before Iris activates (vanilla entity
        // rendering writes into our depth FBO).
        if (world != null && camera != null)
        {
            mc.getEntityRenderDispatcher().configure(world, camera, mc.getCameraEntity());
        }
        ShadowBaker.bake(world, cameraPos, cameraForward, tickDelta);

        LightRegistry.flush();
    }
}
