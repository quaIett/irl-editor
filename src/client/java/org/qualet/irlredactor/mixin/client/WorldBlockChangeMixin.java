package org.qualet.irlredactor.mixin.client;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.qualet.irl.light.shadow.BlockShadowCache;

/**
 * Keeps block shadows fresh when the world changes. Without this, placing or
 * breaking a slab next to a static lamp wouldn't update its shadow: a block
 * edit moves nothing, so a lamp whose only in-range change is terrain would
 * otherwise stay cached and reuse a stale depth map.
 *
 * Only a write that actually swaps the state may invalidate. The server
 * resyncs both interaction blocks after EVERY use — an empty-hand click on a
 * dumb block lands here as setBlockState with the state the client already
 * has. Vanilla discards that write (WorldChunk.setBlockState returns null on
 * identity match), and so do we; without the old==state gate every such click
 * re-collected and re-baked each lamp covering the block. States are interned,
 * so identity compare is exact. Real swaps go through
 * BlockShadowCache.invalidateChange, which additionally drops silhouette-
 * neutral churn (grass->dirt, fluid level ticks, a furnace lighting up)
 * before invalidating the lamps whose collection sphere covers the edit; the
 * next getOrCompute then returns a NEW list instance, which ShadowBaker
 * detects by reference and re-bakes precisely those lamps.
 *
 * Targets the base World method so the hook fires for every code path that
 * writes a block (vanilla placement, server-sync, BBS edits). Gated on
 * world.isClient so the integrated server's World instances (same JVM in
 * singleplayer) don't touch the render-thread state; the height gate mirrors
 * vanilla's own first reject, where the write also no-ops.
 */
@Mixin(World.class)
public class WorldBlockChangeMixin
{
    @Inject(
        method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
        at = @At("HEAD")
    )
    private void irlite$invalidateBlockShadows(
        BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
        CallbackInfoReturnable<Boolean> cir)
    {
        World self = (World) (Object) this;
        if (!self.isClient || self.isOutOfHeightLimit(pos))
        {
            return;
        }
        BlockState old = self.getBlockState(pos);
        if (old == state)
        {
            return;
        }
        BlockShadowCache.invalidateChange(self, pos, old, state);
        // (Auto block-lights need no signal here: their rolling scan picks up
        //  emitter placement/removal within a cycle — see AutoLightManager.)
    }
}
