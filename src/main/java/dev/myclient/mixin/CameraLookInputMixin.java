package dev.myclient.mixin;

import dev.myclient.module.modules.Freecam;
import dev.myclient.module.modules.Freelook;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Intercepts the exact vanilla mouse-look call inside Mouse.updateMouse.
 * This avoids changing the player's rotation while a detached camera is active.
 */
@Mixin(Mouse.class)
public abstract class CameraLookInputMixin {
    @Redirect(
            method = "updateMouse(D)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"
            ),
            require = 0
    )
    private void myclient$routeMouseLookToCamera(ClientPlayerEntity player, double cursorDeltaX, double cursorDeltaY) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player != null && player == client.player && (Freecam.active || Freelook.active)) {
            Entity camera = client.getCameraEntity();
            if (camera != null && camera != client.player) {
                camera.changeLookDirection(cursorDeltaX, cursorDeltaY);
                return;
            }
        }

        player.changeLookDirection(cursorDeltaX, cursorDeltaY);
    }
}
