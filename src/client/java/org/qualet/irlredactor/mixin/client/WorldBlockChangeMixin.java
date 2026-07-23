package org.qualet.irlredactor.mixin.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.qualet.irl.light.shadow.BlockShadowCache;

/**
 * Keeps block shadows fresh when the world changes. Without this, placing or
 * breaking a slab next to a static lamp wouldn't update its shadow: a block
 * edit moves nothing, so a lamp whose only in-range change is terrain would
 * otherwise stay cached and reuse a stale depth map.
 *
 * Hooks ClientWorld.updateListeners (the client's block-change notifier)
 * rather than the base World.setBlockState. Two reasons:
 *
 *  1. Sinytra Connector compatibility. setBlockState is overloaded on the base
 *     class (flags, and flags+maxUpdateDepth). Loom bakes the @Inject target
 *     down to a bare intermediary name with no descriptor; on Fabric that name
 *     is unique per overload, but Connector remaps it to Mojmap where both
 *     overloads share the name "setBlock", so Mixin binds the injector to the
 *     wrong arity and aborts with an InvalidInjectionException. updateListeners
 *     is not overloaded, so the bare-name remap stays unambiguous.
 *  2. It hands us old and new state directly, and only fires for real,
 *     client-visible writes: vanilla discards an identity write upstream
 *     (WorldChunk.setBlockState returns null on identity match), so a no-op
 *     resync — e.g. the server re-sending both interaction blocks after an
 *     empty-hand click on a dumb block — never reaches here. The oldState ==
 *     newState guard below is a cheap belt-and-braces check (states are
 *     interned, so identity compare is exact).
 *
 * Real swaps go through BlockShadowCache.invalidateChange, which drops
 * silhouette-neutral churn (grass->dirt, fluid level ticks, a furnace lighting
 * up) before invalidating the lamps whose collection sphere covers the edit;
 * the next getOrCompute then returns a NEW list instance, which ShadowBaker
 * detects by reference and re-bakes precisely those lamps.
 *
 * ClientWorld is client-only by construction, so no isClient gate is needed —
 * the integrated server runs on ServerWorld instances and never lands here.
 */
@Mixin(ClientWorld.class)
public class WorldBlockChangeMixin
{
    @Inject(
        method = "updateListeners(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/BlockState;I)V",
        at = @At("HEAD")
    )
    private void irlite$invalidateBlockShadows(
        BlockPos pos, BlockState oldState, BlockState newState, int flags,
        CallbackInfo ci)
    {
        if (oldState == newState)
        {
            return;
        }
        BlockShadowCache.invalidateChange((ClientWorld) (Object) this, pos, oldState, newState);
        // (Auto block-lights need no signal here: their rolling scan picks up
        //  emitter placement/removal within a cycle — see AutoLightManager.)
    }
}
