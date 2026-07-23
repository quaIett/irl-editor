package org.qualet.irlredactor.mixin.client.iris;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.qualet.irlredactor.client.diag.VlProfiler;

import java.util.function.Supplier;

/**
 * Dev GPU profiler (editor "perf" section): GL_TIME_ELAPSED brackets around
 * every Iris fullscreen pass — the redactor port of the IRLite addon's mixin
 * of the same name (keep in sync). All five CompositeRenderer instances
 * (begin, prepare, deferred, composite, shadowcomp) share renderAll, and
 * pass-name prefixes already disambiguate the stage, so one mixin covers all.
 *
 * <p>The Pass object carries no name at renderAll time, so the name is captured
 * at pipeline construction: createProgram RETURN pairs source.getName() with
 * the built Program (ALWAYS recorded — construction usually precedes the
 * runtime toggle), and the renderAll brackets look it up by Program identity.
 * The bracket spans program.use() .. renderQuad(); everything no-ops while
 * collection is off.</p>
 *
 * <p>require = 0 on the redirects: these are the mod's only instruction-level
 * anchors into an Iris method body — if a future Iris reshapes renderAll they
 * must degrade to an inert profiler, not a mixin-apply crash in normal play
 * (the begin/end guards tolerate unpaired brackets).</p>
 */
@Mixin(value = CompositeRenderer.class, remap = false)
public class CompositeRendererTimerMixin
{
    @Inject(method = "createProgram", at = @At("RETURN"), remap = false)
    private void irlite$recordPassName(ProgramSource source, ImmutableSet<Integer> flipped,
                                       ImmutableSet<Integer> flippedAtLeastOnceSnapshot,
                                       Supplier<?> shadowTargetsSupplier,
                                       CallbackInfoReturnable<Program> cir)
    {
        VlProfiler.registerPassName(cir.getReturnValue(), source.getName());
    }

    @Redirect(method = "renderAll",
              at = @At(value = "INVOKE",
                       target = "Lnet/irisshaders/iris/gl/program/Program;use()V"),
              require = 0, expect = 1,
              remap = false)
    private void irlite$beginTimedPass(Program program)
    {
        // 1.21.11 / Iris 1.10.7: FullScreenQuadRenderer.renderQuad() was removed (the
        // fullscreen quad now draws through a GpuBuffer), so there is no per-pass END
        // anchor left to redirect. Bracket each pass from its program.use() to the
        // NEXT pass's use(); the frame's trailing pass is closed by the bake bracket's
        // VlProfiler.frameTick() next frame. VlProfiler tolerates the rolling /
        // unpaired brackets. Dev-only; inert while profiling is off.
        VlProfiler.endPass();
        VlProfiler.beginPass(VlProfiler.irisPassName(program));
        program.use();
    }
}
