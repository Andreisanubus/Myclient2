package dev.myclient.module.modules;

import dev.myclient.mixinmarker.MixinMarker;
import dev.myclient.module.Category;
import dev.myclient.module.Module;
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

/** Client-side detached camera with spectator-style 3D movement. */
public final class Freecam extends Module {
    public static volatile boolean active;
    private static volatile boolean blockInteract = true;

    private final NumberSetting speed;
    private final NumberSetting verticalSpeed;
    private final Setting<Boolean> smooth;
    private final Setting<Boolean> blockInteractions;

    private Entity camera;
    private double velocityX, velocityY, velocityZ;
    private double anchorX, anchorY, anchorZ;
    private float anchorYaw, anchorPitch;

    public Freecam() {
        super("Freecam", Category.RENDER);
        header("Spectator Camera");
        speed = num("Move Speed", 0.65, 0.1, 3.0, 0.05);
        verticalSpeed = num("Vertical Speed", 0.65, 0.1, 3.0, 0.05);
        smooth = bool("Smooth Movement", false);
        header("Protection");
        blockInteractions = bool("Block Interactions", true);
    }

    private void warn(String message) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[Freecam] " + message), false);
    }

    public static boolean blockPacket(Packet<?> packet) {
        if (!active || packet == null) return false;
        if (packet instanceof PlayerMoveC2SPacket
                || packet instanceof PlayerInputC2SPacket
                || packet instanceof VehicleMoveC2SPacket) return true;
        return blockInteract && (packet instanceof PlayerActionC2SPacket
                || packet instanceof PlayerInteractBlockC2SPacket
                || packet instanceof PlayerInteractEntityC2SPacket
                || packet instanceof PlayerInteractItemC2SPacket
                || packet instanceof HandSwingC2SPacket);
    }

    @Override
    protected void onEnable() {
        if (Freelook.active) {
            warn("Opreste Freelook inainte sa activezi Freecam.");
            setEnabled(false);
            return;
        }
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            setEnabled(false);
            return;
        }
        if (!(mc.getNetworkHandler() instanceof MixinMarker)) {
            warn("Packet blocker-ul nu este activ; Freecam a fost oprit.");
            setEnabled(false);
            return;
        }

        Entity detached = EntityType.MARKER.create(mc.world, SpawnReason.COMMAND);
        if (detached == null) {
            warn("Nu am putut crea camera client-side.");
            setEnabled(false);
            return;
        }

        anchorX = mc.player.getX();
        anchorY = mc.player.getY();
        anchorZ = mc.player.getZ();
        anchorYaw = mc.player.getYaw();
        anchorPitch = mc.player.getPitch();

        camera = detached;
        camera.setPosition(anchorX, mc.player.getEyeY(), anchorZ);
        camera.setYaw(anchorYaw);
        camera.setPitch(anchorPitch);
        camera.noClip = true;
        camera.setNoGravity(true);

        velocityX = velocityY = velocityZ = 0.0;
        blockInteract = blockInteractions.value;
        active = true;
        mc.setCameraEntity(camera);
    }

    @Override
    protected void onDisable() {
        active = false;
        Entity old = camera;
        camera = null;
        if (mc.player != null) mc.setCameraEntity(mc.player);
        if (old != null) old.discard();
        velocityX = velocityY = velocityZ = 0.0;
    }

    @Override
    public void onTick() {
        if (!isEnabled()) return;
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null || camera == null) {
            setEnabled(false);
            return;
        }

        blockInteract = blockInteractions.value;

        // Freeze the real player at the activation point, like spectator camera mode.
        mc.player.setPosition(anchorX, anchorY, anchorZ);
        mc.player.setVelocity(Vec3d.ZERO);
        mc.player.setYaw(anchorYaw);
        mc.player.setPitch(anchorPitch);

        camera.noClip = true;
        camera.setNoGravity(true);

        var options = mc.options;
        double forward = (options.forwardKey.isPressed() ? 1.0 : 0.0)
                - (options.backKey.isPressed() ? 1.0 : 0.0);
        double sideways = (options.leftKey.isPressed() ? 1.0 : 0.0)
                - (options.rightKey.isPressed() ? 1.0 : 0.0);
        double vertical = (options.jumpKey.isPressed() ? 1.0 : 0.0)
                - (options.sneakKey.isPressed() ? 1.0 : 0.0);

        double yaw = Math.toRadians(camera.getYaw());
        double pitch = Math.toRadians(camera.getPitch());
        double cp = Math.cos(pitch);

        double targetX = (-Math.sin(yaw) * cp * forward + Math.cos(yaw) * sideways) * speed.get();
        double targetY = (-Math.sin(pitch) * forward * speed.get()) + vertical * verticalSpeed.get();
        double targetZ = (Math.cos(yaw) * cp * forward + Math.sin(yaw) * sideways) * speed.get();

        double length = Math.sqrt(targetX * targetX + targetY * targetY + targetZ * targetZ);
        double max = Math.max(speed.get(), verticalSpeed.get());
        if (length > max && length > 0.0) {
            double scale = max / length;
            targetX *= scale;
            targetY *= scale;
            targetZ *= scale;
        }

        double factor = smooth.value ? 0.30 : 1.0;
        velocityX += (targetX - velocityX) * factor;
        velocityY += (targetY - velocityY) * factor;
        velocityZ += (targetZ - velocityZ) * factor;

        camera.setPosition(camera.getX() + velocityX, camera.getY() + velocityY, camera.getZ() + velocityZ);
        camera.setVelocity(Vec3d.ZERO);
    }
}
