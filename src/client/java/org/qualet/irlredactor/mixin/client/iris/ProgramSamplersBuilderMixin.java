package org.qualet.irlredactor.mixin.client.iris;

import net.irisshaders.iris.gl.program.ProgramSamplers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.qualet.irlredactor.light.shadow.PointShadowArray;
import org.qualet.irlredactor.light.shadow.SpotlightDepthAtlas;

/**
 * Binds IRLite shadow textures into every Iris-compiled program. Iris calls
 * ProgramSamplers.builder(...).build() for each program (gbuffers, composite,
 * deferred, final, shadow, ...), so injecting at build() HEAD covers all of
 * them. addDynamicSampler is a no-op (returns false) for programs that don't
 * declare the uniform — no texture unit wasted.
 */
@Mixin(value = ProgramSamplers.Builder.class, remap = false)
public class ProgramSamplersBuilderMixin
{
    @Inject(method = "build", at = @At("HEAD"))
    private void irlite$bindShadowSamplers(CallbackInfoReturnable<ProgramSamplers> cir)
    {
        ProgramSamplers.Builder self = (ProgramSamplers.Builder) (Object) this;
        self.addDynamicSampler(SpotlightDepthAtlas::getGlTextureId, "irl_spotShadowAtlas");
        self.addDynamicSampler(PointShadowArray::getGlTextureId, "irl_pointShadowArray");
    }
}
