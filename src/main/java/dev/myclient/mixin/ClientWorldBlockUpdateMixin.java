
package dev.myclient.mixin;

import dev.myclient.chunkfinder.ChunkScanner;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
public abstract class ClientWorldBlockUpdateMixin {

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

        ChunkScanner.blockUpdated(
                (ClientWorld) (Object) this,
                pos,
                state
        );
    }
}
