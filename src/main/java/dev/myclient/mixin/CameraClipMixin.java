package dev.myclient.mixin;

import dev.myclient.module.modules.Freecam;
import dev.myclient.module.modules.Freelook;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraClipMixin {
    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void myclient$disableFreeCameraCollision(float distance, CallbackInfoReturnable<Float> cir) {
        if (Freecam.active || Freelook.active) cir.setReturnValue(distance);
    }
}
