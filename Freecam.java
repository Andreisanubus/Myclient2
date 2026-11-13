package dev.myclient.module.modules;

import dev.myclient.mixinmarker.MixinMarker;
import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
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
 * Freecam in stil spectator: camera ta, acelasi HUD, zbori prin blocuri. Jucatorul tau local se misca, dar pachetele de miscare
 * nu mai ajung la server (un mixin le opreste), deci pentru server stai pe loc. La oprire revii exact unde erai.
 * Daca mixin-ul nu e activ, modulul refuza sa porneasca, ca sa nu te mute pe server.
 */
public class Freecam extends Module {
    public static volatile boolean active;
    private static volatile boolean sawPacket, blockInteract = true;

    public static boolean blockPacket(Packet<?> p) {
        if (!active) return false;
        if (p instanceof PlayerMoveC2SPacket || p instanceof PlayerInputC2SPacket || p instanceof VehicleMoveC2SPacket) { sawPacket = true; return true; }
        return blockInteract && (p instanceof PlayerActionC2SPacket || p instanceof PlayerInteractBlockC2SPacket
                || p instanceof PlayerInteractEntityC2SPacket || p instanceof PlayerInteractItemC2SPacket || p instanceof HandSwingC2SPacket);
    }

    private final NumberSetting speed, vSpeed;
    private final Setting<Boolean> smooth, interact;
    private Vec3d origin;
    private int ticks;
    private double vx, vy, vz;

    public Freecam() {
        super("Freecam", Category.RENDER);
        header("Movement");
        speed = num("Speed (blocks/tick)", 0.6, 0.1, 3.0, 0.1);
        vSpeed = num("Vertical Speed", 0.5, 0.1, 2.0, 0.1);
        smooth = bool("Smooth Acceleration", true);
        header("Safety");
        interact = bool("Block Interactions", true);
    }

    private void warn(String s) { if (mc.player != null) mc.player.sendMessage(Text.literal("[Freecam] " + s), false); }

    @Override protected void onEnable() {
        var p = mc.player;
        if (p == null || mc.getNetworkHandler() == null) { setEnabled(false); return; }
        if (!(mc.getNetworkHandler() instanceof MixinMarker)) {
            warn("blocarea pachetelor nu e activa (mixin neaplicat), Freecam ramane oprit");
            setEnabled(false);
            return;
        }
        origin = new Vec3d(p.getX(), p.getY(), p.getZ());
        ticks = 0; vx = vy = vz = 0;
        sawPacket = false;
        blockInteract = interact.value;
        active = true;
    }

    @Override protected void onDisable() {
        active = false;
        var p = mc.player;
        if (p == null) return;
        p.noClip = false;
        p.setNoGravity(false);
        if (origin != null) { p.setPosition(origin.x, origin.y, origin.z); p.setVelocity(Vec3d.ZERO); }
        origin = null;
    }

    @Override public void onTick() {
        var p = mc.player;
        if (p == null) { setEnabled(false); return; }
        blockInteract = interact.value;
        ticks++;
        p.noClip = true;
        p.setNoGravity(true);
        if (ticks == 1) p.setYaw(p.getYaw() + 0.02f);      // forteaza un pachet de rotatie, ca sa verificam ca blocarea merge
        if (ticks <= 5) {
            p.setVelocity(Vec3d.ZERO);
            if (ticks == 5 && !sawPacket) { warn("pachetele nu sunt blocate, Freecam oprit"); setEnabled(false); }
            return;
        }
        var o = mc.options;
        double f = (o.forwardKey.isPressed() ? 1 : 0) - (o.backKey.isPressed() ? 1 : 0);
        double s = (o.leftKey.isPressed() ? 1 : 0) - (o.rightKey.isPressed() ? 1 : 0);
        double v = (o.jumpKey.isPressed() ? 1 : 0) - (o.sneakKey.isPressed() ? 1 : 0);
        double yaw = Math.toRadians(p.getYaw()), pit = Math.toRadians(p.getPitch());
        double tx = (-Math.sin(yaw) * Math.cos(pit) * f + Math.cos(yaw) * s) * speed.get();
        double ty = -Math.sin(pit) * f * speed.get() + v * vSpeed.get();
        double tz = (Math.cos(yaw) * Math.cos(pit) * f + Math.sin(yaw) * s) * speed.get();
        double k = smooth.value ? 0.35 : 1.0;
        vx += (tx - vx) * k; vy += (ty - vy) * k; vz += (tz - vz) * k;
        p.setVelocity(vx, vy, vz);
        o.forwardKey.setPressed(false); o.backKey.setPressed(false);
        o.leftKey.setPressed(false); o.rightKey.setPressed(false);
        o.jumpKey.setPressed(false); o.sneakKey.setPressed(false);
    }
}
