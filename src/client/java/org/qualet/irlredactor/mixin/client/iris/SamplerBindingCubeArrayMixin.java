package org.qualet.irlredactor.mixin.client.iris;

import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.sampler.SamplerBinding;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.qualet.irlredactor.light.cookie.CookieArray;
import org.qualet.irl.light.shadow.PointShadowArray;
import org.qualet.irl.light.shadow.PointShadowEvsm;
import org.qualet.irl.light.shadow.PointShadowPyramid;

import java.util.function.IntSupplier;

/**
 * Iris's TextureType has neither CUBE_MAP_ARRAY nor 2D_ARRAY, so our point cube
 * shadow array and the gobo/cookie 2D array are both registered as TEXTURE_2D
 * (addDynamicSampler). When Iris is about to bind one of them and the id matches,
 * rebind it to its real GL target on the same unit and cancel the 2D bind.
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
            return;
        }

        int cookieArrayId = CookieArray.getGlTextureId();
        if (cookieArrayId != 0 && id == cookieArrayId)
        {
            IrisRenderSystem.bindTextureToUnit(GL30.GL_TEXTURE_2D_ARRAY, this.textureUnit, id);
            ci.cancel();
            return;
        }

        // F1b: face-major point shadow pyramid is a 2D array too
        int pointPyramidId = PointShadowPyramid.getGlTextureId();
        if (pointPyramidId != 0 && id == pointPyramidId)
        {
            IrisRenderSystem.bindTextureToUnit(GL30.GL_TEXTURE_2D_ARRAY, this.textureUnit, id);
            ci.cancel();
            return;
        }

        // F2b: face-major point EVSM is a 2D array too
        int pointEvsmId = PointShadowEvsm.getGlTextureId();
        if (pointEvsmId != 0 && id == pointEvsmId)
        {
            IrisRenderSystem.bindTextureToUnit(GL30.GL_TEXTURE_2D_ARRAY, this.textureUnit, id);
            ci.cancel();
        }
    }
}
