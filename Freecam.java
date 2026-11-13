package dev.myclient.module.modules;

import dev.myclient.mixinmarker.MixinMarker;
import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.ModeSetting;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Detached camera Freecam for Fabric 1.21.11.
 *
 * The camera is a client-only marker entity. The real player remains at the
 * server position and keeps its own yaw/pitch. In Freelook mode the marker
 * follows the player from a no-clip third-person camera position.
 */
public final class Freecam extends Module {
    public static volatile boolean active;
    private static volatile boolean blockInteract = true;
    private static volatile boolean freelookNoClip;

    public static boolean shouldIgnoreCameraCollision() {
        return active && freelookNoClip;
    }

    private final ModeSetting cameraMode;
    private final NumberSetting speed;
    private final NumberSetting verticalSpeed;
    private final NumberSetting freelookDistance;
    private final Setting<Boolean> smooth;
    private final Setting<Boolean> freezePlayerInput;
    private final Setting<Boolean> blockInteractions;

    private Entity camera;
    private double velocityX;
    private double velocityY;
    private double velocityZ;

    public Freecam() {
        super("Freecam", Category.RENDER);

        header("Camera");
        cameraMode = mode("Mode", "Detached", "Detached", "Freelook");
        freelookDistance = num("Freelook Distance", 4.0, 1.0, 12.0, 0.5);

        header("Movement");
        speed = num("Speed (blocks/tick)", 0.6, 0.1, 3.0, 0.1);
        verticalSpeed = num("Vertical Speed", 0.5, 0.1, 2.0, 0.1);
        smooth = bool("Smooth Acceleration", true);
        freezePlayerInput = bool("Freeze Player Input", true);

        header("Safety");
        blockInteractions = bool("Block Interactions", true);
    }

    private void warn(String message) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[Freecam] " + message), false);
        }
    }

    public static boolean blockPacket(Packet<?> packet) {
        if (!active || packet == null) {
            return false;
        }

        if (packet instanceof PlayerMoveC2SPacket
                || packet instanceof PlayerInputC2SPacket
                || packet instanceof VehicleMoveC2SPacket) {
            return true;
        }

        return blockInteract && (
                packet instanceof PlayerActionC2SPacket
                || packet instanceof PlayerInteractBlockC2SPacket
                || packet instanceof PlayerInteractEntityC2SPacket
                || packet instanceof PlayerInteractItemC2SPacket
                || packet instanceof HandSwingC2SPacket
        );
    }

    @Override
    protected void onEnable() {
        if (mc.player == null || mc.world == null
                || mc.getNetworkHandler() == null) {
            setEnabled(false);
            return;
        }

        if (!(mc.getNetworkHandler() instanceof MixinMarker)) {
            warn("Mixin-ul de blocare nu este activ. Modul a fost oprit.");
            setEnabled(false);
            return;
        }

        Entity detachedCamera = EntityType.MARKER.create(mc.world, SpawnReason.COMMAND);
        if (detachedCamera == null) {
            warn("Nu am putut crea camera client-side.");
            setEnabled(false);
            return;
        }

        camera = detachedCamera;
        camera.setPosition(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
        camera.setYaw(mc.player.getYaw());
        camera.setPitch(mc.player.getPitch());
        camera.noClip = true;
        camera.setNoGravity(true);

        velocityX = 0.0;
        velocityY = 0.0;
        velocityZ = 0.0;
        blockInteract = blockInteractions.value;
        freelookNoClip = cameraMode.is("Freelook");
        active = true;

        // The camera entity is never added to the world or sent to the server.
        mc.setCameraEntity(camera);
    }

    @Override
    protected void onDisable() {
        active = false;
        freelookNoClip = false;

        Entity oldCamera = camera;
        camera = null;

        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }

        if (oldCamera != null) {
            oldCamera.discard();
        }

        velocityX = 0.0;
        velocityY = 0.0;
        velocityZ = 0.0;
    }

    @Override
    public void onTick() {
        if (!isEnabled()) {
            return;
        }

        if (mc.player == null || mc.world == null
                || mc.getNetworkHandler() == null || camera == null) {
            setEnabled(false);
            return;
        }

        blockInteract = blockInteractions.value;
        freelookNoClip = cameraMode.is("Freelook");
        camera.noClip = true;
        camera.setNoGravity(true);

        if (cameraMode.is("Freelook")) {
            updateFreelookCamera();
        } else {
            updateDetachedCamera();
        }
    }

    private void updateFreelookCamera() {
        var player = mc.player;

        // Keep the view behind the player, while mouse movement changes only
        // the camera entity's rotation, not the real player's rotation.
        double yaw = Math.toRadians(camera.getYaw());
        double distance = freelookDistance.get();

        double x = player.getX() + Math.sin(yaw) * distance;
        double z = player.getZ() - Math.cos(yaw) * distance;

        camera.setPosition(x, player.getEyeY(), z);
        camera.setVelocity(Vec3d.ZERO);
        velocityX = 0.0;
        velocityY = 0.0;
        velocityZ = 0.0;
    }

    private void updateDetachedCamera() {
        var options = mc.options;

        double forward = (options.forwardKey.isPressed() ? 1.0 : 0.0)
                - (options.backKey.isPressed() ? 1.0 : 0.0);
        double sideways = (options.leftKey.isPressed() ? 1.0 : 0.0)
                - (options.rightKey.isPressed() ? 1.0 : 0.0);
        double vertical = (options.jumpKey.isPressed() ? 1.0 : 0.0)
                - (options.sneakKey.isPressed() ? 1.0 : 0.0);

        double yaw = Math.toRadians(camera.getYaw());
        double pitch = Math.toRadians(camera.getPitch());

        double cosPitch = Math.cos(pitch);
        double targetX = (
                -Math.sin(yaw) * cosPitch * forward
                + Math.cos(yaw) * sideways
        ) * speed.get();

        double targetY = (
                -Math.sin(pitch) * forward * speed.get()
                + vertical * verticalSpeed.get()
        );

        double targetZ = (
                Math.cos(yaw) * cosPitch * forward
                + Math.sin(yaw) * sideways
        ) * speed.get();

        // Normalize diagonal movement so it is not faster than straight movement.
        double length = Math.sqrt(
                targetX * targetX + targetY * targetY + targetZ * targetZ
        );
        double maxLength = Math.max(speed.get(), verticalSpeed.get());
        if (length > maxLength && length > 0.0) {
            double scale = maxLength / length;
            targetX *= scale;
            targetY *= scale;
            targetZ *= scale;
        }

        double acceleration = smooth.value ? 0.35 : 1.0;
        velocityX += (targetX - velocityX) * acceleration;
        velocityY += (targetY - velocityY) * acceleration;
        velocityZ += (targetZ - velocityZ) * acceleration;

        camera.setPosition(
                camera.getX() + velocityX,
                camera.getY() + velocityY,
                camera.getZ() + velocityZ
        );
        camera.setVelocity(Vec3d.ZERO);

        if (freezePlayerInput.value) {
            // Do not let normal movement keys move the player while the camera
            // is detached. Key states are restored naturally by the next input poll.
            options.forwardKey.setPressed(false);
            options.backKey.setPressed(false);
            options.leftKey.setPressed(false);
            options.rightKey.setPressed(false);
            options.jumpKey.setPressed(false);
            options.sneakKey.setPressed(false);
        }
    }
}
