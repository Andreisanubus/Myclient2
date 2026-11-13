package dev.myclient.mixin;

import dev.myclient.module.modules.SoundDetectorModule;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerSoundMixin {
    @Inject(method = "onPlaySound", at = @At("TAIL"))
    private void myclient$onSound(PlaySoundS2CPacket packet, CallbackInfo ci) {
        SoundDetectorModule.soundPlayed(
                packet.getSound(),
                packet.getX(),
                packet.getY(),
                packet.getZ(),
                packet.getVolume()
        );
    }
}
