package org.qualet.irlredactor.mixin.client.iris;

import net.irisshaders.iris.gl.program.ProgramSamplers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.qualet.irl.light.iris.IrlSamplersBind;

/**
 * Binds IRLite shadow textures into every Iris-compiled program. Iris calls
 * ProgramSamplers.builder(...).build() for each program (gbuffers, composite,
 * deferred, final, shadow, ...), so injecting at build() HEAD covers all of
 * them. addDynamicSampler is a no-op (returns false) for programs that don't
 * declare the uniform — no texture unit wasted.
 *
 * <p>The sampler set (names, order, GL targets) lives in the shared
 * {@link org.qualet.irl.light.IrlSamplers} registry; this mixin only stays
 * per-mod because the addDynamicSampler arity is Iris-version specific.</p>
 */
@Mixin(value = ProgramSamplers.Builder.class, remap = false)
public class ProgramSamplersBuilderMixin
{
    @Inject(method = "build", at = @At("HEAD"))
    private void irlite$bindShadowSamplers(CallbackInfoReturnable<ProgramSamplers> cir)
    {
        IrlSamplersBind.bindAll((ProgramSamplers.Builder) (Object) this);
    }
}
