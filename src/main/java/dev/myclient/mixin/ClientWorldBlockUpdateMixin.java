package dev.myclient.mixin;

import dev.myclient.chunkfinder.ChunkScanner;
import dev.myclient.module.modules.HiveChunkFinderModule;
import dev.myclient.module.modules.TickActivityModule;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
public abstract class ClientWorldBlockUpdateMixin {

    @Unique private BlockState myclient$oldState;

    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD")
    )
    private void myclient$onBlockUpdateHead(
            BlockPos pos,
            BlockState state,
            int flags,
            int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> cir
    ) {
        ClientWorld world = (ClientWorld) (Object) this;
        myclient$oldState = world.getBlockState(pos);
    }

    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("TAIL")
    )
    private void myclient$onBlockUpdateTail(
            BlockPos pos,
            BlockState state,
            int flags,
            int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValue()) return;
        ClientWorld world = (ClientWorld) (Object) this;
        TickActivityModule.blockChanged(world, pos, myclient$oldState, state);
        myclient$oldState = null;
        ChunkScanner.blockUpdated(world, pos, state);
        HiveChunkFinderModule.blockUpdated(world, pos);
    }
}
