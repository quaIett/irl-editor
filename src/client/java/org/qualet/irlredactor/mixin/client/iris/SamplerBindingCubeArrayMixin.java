package org.qualet.irlredactor.mixin.client.iris;

import net.irisshaders.iris.gl.sampler.SamplerBinding;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.qualet.irl.light.iris.IrlSamplersBind;

import java.util.function.IntSupplier;

/**
 * Iris's TextureType has neither CUBE_MAP_ARRAY nor 2D_ARRAY, so our point cube
 * shadow array and the gobo/cookie 2D array are both registered as TEXTURE_2D
 * (addDynamicSampler). When Iris is about to bind one of them and the id matches,
 * rebind it to its real GL target on the same unit and cancel the 2D bind. The
 * id→target set lives in the shared {@link org.qualet.irl.light.IrlSamplers}
 * registry ({@link IrlSamplersBind#tryRebind}).
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
        if (IrlSamplersBind.tryRebind(this.texture.getAsInt(), this.textureUnit))
        {
            ci.cancel();
        }
    }
}
