package org.qualet.irlredactor.mixin.client;

import net.minecraft.world.World;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(World.class)
public interface WorldBlockEntityTickersAccessor
{
    @Accessor("blockEntityTickers")
    List<BlockEntityTickInvoker> irlite$getBlockEntityTickers();
}
