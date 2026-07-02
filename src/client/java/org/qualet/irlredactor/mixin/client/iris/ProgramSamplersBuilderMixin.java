package org.qualet.irlredactor.mixin.client.iris;

import net.irisshaders.iris.gl.program.ProgramSamplers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.qualet.irlredactor.light.cookie.CookieArray;
import org.qualet.irl.light.shadow.PointShadowArray;
import org.qualet.irl.light.shadow.PointShadowEvsm;
import org.qualet.irl.light.shadow.PointShadowPyramid;
import org.qualet.irl.light.shadow.SpotShadowEvsm;
import org.qualet.irl.light.shadow.SpotShadowPyramid;
import org.qualet.irl.light.shadow.SpotlightDepthAtlas;

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
        // F1a: min/max mip pyramid of the spot atlas (plain 2D, no target rebind needed).
        // Added in lockstep with the addon BEFORE any patch regen/sync: the .irlights
        // patches are shared copies — a pack with IRLITE_SHADOW_PYRAMID and no binding
        // here would sample whatever sits on unit 0.
        self.addDynamicSampler(SpotShadowPyramid::getGlTextureId, "irl_spotShadowPyramid");
        // F1b: face-major point pyramid — registered as 2D, rebound to GL_TEXTURE_2D_ARRAY
        // by SamplerBindingCubeArrayMixin (like the cookie array).
        self.addDynamicSampler(PointShadowPyramid::getGlTextureId, "irl_pointShadowPyramid");
        // F2a: EVSM prefilter of the spot atlas (plain 2D + mips, no target rebind needed)
        self.addDynamicSampler(SpotShadowEvsm::getGlTextureId, "irl_spotEvsm");
        // F2b: face-major point EVSM - registered 2D, rebound to GL_TEXTURE_2D_ARRAY by SamplerBindingCubeArrayMixin
        self.addDynamicSampler(PointShadowEvsm::getGlTextureId, "irl_pointEvsm");
        // Gobo/cookie mask array — registered as 2D, rebound to GL_TEXTURE_2D_ARRAY
        // by SamplerBindingCubeArrayMixin (like the point cube array).
        self.addDynamicSampler(CookieArray::getGlTextureId, "irl_cookieArray");
    }
}
