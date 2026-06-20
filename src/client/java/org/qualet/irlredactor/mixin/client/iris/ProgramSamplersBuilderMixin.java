package org.qualet.irlredactor.mixin.client.iris;

import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.texture.TextureType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.qualet.irlredactor.light.cookie.CookieArray;
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
        // 1.21.11 / Iris 1.10.7: the old 2-arg addDynamicSampler(IntSupplier, String)
        // is gone. Register with an explicit TextureType (2D — Iris has no
        // CUBE_MAP_ARRAY type, so the point cube-array is rebound to its real GL
        // target by SamplerBindingCubeArrayMixin) and a null GlSampler supplier.
        self.addDynamicSampler(TextureType.TEXTURE_2D, SpotlightDepthAtlas::getGlTextureId, () -> null, "irl_spotShadowAtlas");
        self.addDynamicSampler(TextureType.TEXTURE_2D, PointShadowArray::getGlTextureId, () -> null, "irl_pointShadowArray");
        // Gobo/cookie mask array — like the point cube array, registered as 2D and
        // rebound to its real GL_TEXTURE_2D_ARRAY target by SamplerBindingCubeArrayMixin.
        self.addDynamicSampler(TextureType.TEXTURE_2D, CookieArray::getGlTextureId, () -> null, "irl_cookieArray");
    }
}
