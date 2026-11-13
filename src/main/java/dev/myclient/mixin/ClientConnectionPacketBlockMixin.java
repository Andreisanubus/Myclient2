package dev.myclient.mixin;

import dev.myclient.module.modules.Freecam;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionPacketBlockMixin {
    @Inject(method = "sendImmediately", at = @At("HEAD"), cancellable = true)
    private void myclient$blockFreecamPackets(Packet<?> packet, PacketCallbacks callbacks,
                                              boolean flush, CallbackInfo ci) {
        if (Freecam.blockPacket(packet)) ci.cancel();
    }
}
