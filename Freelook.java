package dev.myclient.module.modules;

import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.util.math.Vec3d;

/** Independent third-person orbit camera. Mouse movement rotates only this camera entity. */
public final class Freelook extends Module {
    public static volatile boolean active;

    private final NumberSetting distance;
    private Entity camera;
    private Perspective previousPerspective;

    public Freelook() {
        super("Freelook", Category.RENDER);
        header("Orbit Camera");
        distance = num("Camera Distance", 4.0, 1.0, 12.0, 0.25);
        header("Controls");
        // Mouse rotates the camera. Movement keys continue to control the player.
    }

    @Override
    protected void onEnable() {
        if (Freecam.active || mc.player == null || mc.world == null) {
            setEnabled(false);
            return;
        }

        Entity orbit = EntityType.MARKER.create(mc.world, SpawnReason.COMMAND);
        if (orbit == null) {
            setEnabled(false);
            return;
        }

        camera = orbit;
        camera.setYaw(mc.player.getYaw());
        camera.setPitch(mc.player.getPitch());
        camera.noClip = true;
        camera.setNoGravity(true);

        previousPerspective = mc.options.getPerspective();
        mc.options.setPerspective(Perspective.FIRST_PERSON);
        active = true;
        updateCameraPosition();
        mc.setCameraEntity(camera);
    }

    @Override
    protected void onDisable() {
        active = false;
        Entity old = camera;
        camera = null;
        if (old != null) {
            if (mc.player != null && mc.getCameraEntity() == old) mc.setCameraEntity(mc.player);
            old.discard();
        }
        if (previousPerspective != null) mc.options.setPerspective(previousPerspective);
        previousPerspective = null;
    }

    @Override
    public void onTick() {
        if (!isEnabled()) return;
        if (mc.player == null || mc.world == null || camera == null) {
            setEnabled(false);
            return;
        }
        camera.noClip = true;
        camera.setNoGravity(true);
        updateCameraPosition();
    }

    private void updateCameraPosition() {
        if (mc.player == null || camera == null) return;
        double yaw = Math.toRadians(camera.getYaw());
        double pitch = Math.toRadians(camera.getPitch());
        double d = distance.get();
        double horizontal = Math.cos(pitch) * d;

        double x = mc.player.getX() + Math.sin(yaw) * horizontal;
        double y = mc.player.getEyeY() + Math.sin(pitch) * d;
        double z = mc.player.getZ() - Math.cos(yaw) * horizontal;

        camera.setPosition(x, y, z);
        camera.setVelocity(Vec3d.ZERO);
    }
}
