package org.qualet.irlredactor.mixin.client.iris;

import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.sampler.SamplerBinding;
import org.lwjgl.opengl.GL40;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.qualet.irlredactor.light.shadow.PointShadowArray;

import java.util.function.IntSupplier;

/**
 * Iris's TextureType has no CUBE_MAP_ARRAY, so our cube-array sampler is
 * registered as TEXTURE_2D (addDynamicSampler 2-arg). When Iris is about to
 * bind it and the id matches our cube-array texture, rebind it to
 * GL_TEXTURE_CUBE_MAP_ARRAY on the same unit and cancel the 2D bind.
 */
@Mixin(value = SamplerBinding.class, remap = false)
public abstract class SamplerBindingCubeArrayMixin
{
    @Shadow
    @Final
    private int textureUnit;

    @Shadow
    @Final
    private IntSupplier texture;

    @Inject(method = "updateSampler", at = @At("HEAD"), cancellable = true)
    private void irlite$bindCubeArrayInsteadOf2D(CallbackInfo ci)
    {
        int id = this.texture.getAsInt();
        if (id == 0)
        {
            return;
        }

        int cubeArrayId = PointShadowArray.getGlTextureId();
        if (cubeArrayId != 0 && id == cubeArrayId)
        {
            IrisRenderSystem.bindTextureToUnit(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, this.textureUnit, id);
            ci.cancel();
        }
    }
}
