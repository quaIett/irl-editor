package org.qualet.irlredactor.mixin.client;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.qualet.irlredactor.light.shadow.BlockShadowCache;

/**
 * Keeps block shadows fresh when the world changes. Without this, placing or
 * breaking a slab next to a static lamp wouldn't update its shadow: a block
 * edit moves nothing, so a lamp whose only in-range change is terrain would
 * otherwise stay cached and reuse a stale depth map.
 *
 * BlockShadowCache.invalidateAt(pos) drops the cached block lists of the lamps
 * whose collection sphere covers the edit (a far-away edit invalidates nothing).
 * That is sufficient on its own now that the bake is per-light: the next
 * getOrCompute for an invalidated lamp returns a NEW list instance, which
 * ShadowBaker detects by reference and re-bakes precisely that lamp. (The old
 * global position-only scene hash couldn't see a block edit, so it also needed
 * ShadowBaker.markBlocksDirty() to force a whole-scene GL re-render — no longer
 * the case.)
 *
 * Targets the base World method so the hook fires for every code path that
 * writes a block (vanilla placement, server-sync, BBS edits). Gated on
 * world.isClient so the integrated server's World instances (same JVM in
 * singleplayer) don't touch the render-thread state.
 */
@Mixin(World.class)
public class WorldBlockChangeMixin
{
    @Inject(
        method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z",
        at = @At("HEAD")
    )
    private void irlite$invalidateBlockShadows(
        BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
        CallbackInfoReturnable<Boolean> cir)
    {
        World self = (World) (Object) this;
        if (self.isClient)
        {
            // Marks the affected lamps' block lists stale; ShadowBaker picks up
            // the new list instance by reference next frame and re-bakes them.
            BlockShadowCache.invalidateAt(pos);
            // (Auto block-lights need no signal here: their rolling scan picks up
            //  emitter placement/removal within a cycle — see AutoLightManager.)
        }
    }
}
