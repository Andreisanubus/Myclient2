package dev.myclient.mixin;

import net.minecraft.client.network.ClientCommonNetworkHandler;
import dev.myclient.mixinmarker.MixinMarker;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientCommonNetworkHandlerMarkerMixin implements MixinMarker {
}
