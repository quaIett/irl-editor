package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.qualet.irlredactor.client.FreeCamera;

/**
 * Free-fly camera: overrides the camera position (only) with {@link FreeCamera}'s
 * fly position while it's active. Injected at the TAIL of {@code Camera.update} so
 * it runs after vanilla has set the position from the focused entity, and the
 * light SSBO upload (which reads the camera a moment later in {@code renderWorld}
 * — see {@code GameRendererLightMixin}) sees the free-camera eye. Rotation is left
 * vanilla, so the mouse still aims the view as usual.
 */
@Mixin(Camera.class)
public abstract class CameraFreeMixin
{
    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Inject(
        method = "update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V",
        at = @At("TAIL"))
    private void irl$freeCamera(BlockView area, Entity focusedEntity, boolean thirdPerson,
                                boolean inverseView, float tickDelta, CallbackInfo ci)
    {
        if (FreeCamera.isActive())
        {
            FreeCamera.advance();
            this.setPos(FreeCamera.x(), FreeCamera.y(), FreeCamera.z());
        }
    }
}
