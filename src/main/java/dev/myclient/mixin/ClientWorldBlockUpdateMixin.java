
package dev.myclient.mixin;

import dev.myclient.chunkfinder.ChunkScanner;
import dev.myclient.module.modules.HiveChunkFinderModule;
import dev.myclient.module.modules.ActivityDetector;
import org.spongepowered.asm.mixin.Unique;
import dev.myclient.module.modules.ActivityDetector;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
public abstract class ClientWorldBlockUpdateMixin {

    @Unique private BlockState myclient$oldState;

    @Unique private BlockState myclient$oldState;

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("HEAD"))
    private void myclient$captureOldState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        myclient$oldState = ((ClientWorld) (Object) this).getBlockState(pos);
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("HEAD"))
    private void myclient$captureOldState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        myclient$oldState = ((ClientWorld) (Object) this).getBlockState(pos);
    }

    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("TAIL")
    )
    private void myclient$onBlockUpdate(
            BlockPos pos,
            BlockState state,
            int flags,
            int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValue()) {
            return;
        }

        BlockState oldState = myclient$oldState;
        myclient$oldState = null;
        ActivityDetector.blockChanged(pos, oldState != null && oldState.isAir(), state.isAir());

        BlockState oldState = myclient$oldState;
        myclient$oldState = null;
        ActivityDetector.blockChanged(pos, oldState != null && oldState.isAir(), state.isAir());

        ChunkScanner.blockUpdated(
                (ClientWorld) (Object) this,
                pos,
                state
        );
        HiveChunkFinderModule.blockUpdated((ClientWorld) (Object) this, pos);
    }
}
