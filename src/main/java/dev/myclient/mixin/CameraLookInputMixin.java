package dev.myclient.mixin;

import dev.myclient.module.modules.Freecam;
import dev.myclient.module.modules.Freelook;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes vanilla mouse-look input to the active detached camera entity.
 * The local player keeps its own yaw/pitch, so movement direction is unchanged
 * while Freelook is enabled and the player remains fixed while Freecam is enabled.
 */
@Mixin(Entity.class)
public abstract class CameraLookInputMixin {
    @Inject(method = "changeLookDirection(DD)V", at = @At("HEAD"), cancellable = true)
    private void myclient$routeMouseLookToCamera(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player == null || self != client.player || (!Freecam.active && !Freelook.active)) {
            return;
        }

        Entity camera = client.getCameraEntity();
        if (camera == null || camera == client.player) {
            return;
        }

        camera.changeLookDirection(cursorDeltaX, cursorDeltaY);
        ci.cancel();
    }
}
