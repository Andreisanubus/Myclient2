package dev.myclient.module.modules;

import dev.myclient.config.StaffManager;
import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
import dev.myclient.util.Names;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Beta client-side staff watcher. It reports observable anomalies only;
 * it cannot prove that a staff member is spectating the player.
 */
public final class StaffDetector extends Module {
    private record Signal(String label, int points, int tick) {}

    private static StaffDetector instance;

    private final Setting<Boolean> tabSensor = bool("Staff Vanish (TAB)", true);
    private final Setting<Boolean> invisibleSensor = bool("Invisible Staff", true);
    private final Setting<Boolean> positionSensor = bool("Position Jerk", true);
    private final Setting<Boolean> soundSensor = bool("Suspicious Sounds", true);
    private final Setting<Boolean> chatAlert = bool("Chat Alert", true);
    private final Setting<Boolean> hudAlert = bool("Critical HUD Alert", true);
    private final NumberSetting scoreThreshold = num("Alert Score", 3, 2, 8, 1);
    private final NumberSetting scoreWindow = num("Score Window (seconds)", 12, 4, 30, 1);
    private final NumberSetting soundRange = num("Sound Range", 3.0, 1.0, 8.0, 0.5);

    private final Set<String> previousTab = new HashSet<>();
    private final Map<String, Integer> tabMissingSince = new HashMap<>();
    private final Deque<Signal> signals = new ArrayDeque<>();
    private int tick;
    private Object lastWorld;
    private Vec3d lastPosition;
    private long lastAlertAt;
    private String alertText = "";
    private long alertUntil;

    public StaffDetector() {
        super("Staff Detector", Category.DONUT);
        instance = this;
        header("Sensors (BETA)");
        header("Scoring");
        header("Alerts");
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
        alertText = "";
        alertUntil = 0L;
    }

    private void reset() {
        previousTab.clear();
        tabMissingSince.clear();
        signals.clear();
        tick = 0;
        lastWorld = mc.world;
        lastPosition = mc.player == null ? null : new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    private static boolean isKnownStaff(String name) {
        return name != null && StaffManager.isStaff(name);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        tick++;

        if (lastWorld != mc.world) {
            reset();
            return;
        }

        scanTab();
        scanInvisibleStaff();
        scanPositionJerk();

        trimSignals();
        int score = currentScore();
        if (score >= scoreThreshold.asInt() && System.currentTimeMillis() - lastAlertAt > 5000L) {
            String evidence = evidenceText();
            criticalAlert("STAFF IS WATCHING YOU  [" + score + "]  " + evidence);
        }
    }

    private void scanTab() {
        if (!tabSensor.value) {
            previousTab.clear();
            tabMissingSince.clear();
            return;
        }

        Set<String> current = Names.tab(mc);
        if (!previousTab.isEmpty()) {
            for (String name : previousTab) {
                if (current.contains(name) || !isKnownStaff(name)) continue;
                if (!tabMissingSince.containsKey(name)) {
                    tabMissingSince.put(name, tick);
                    addSignal("Known staff vanished from TAB: " + name, 5);
                    criticalAlert("STAFF IS WATCHING YOU  [TAB]  " + name);
                }
            }
        }

        for (Iterator<Map.Entry<String, Integer>> it = tabMissingSince.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Integer> entry = it.next();
            if (current.contains(entry.getKey())) it.remove();
        }

        previousTab.clear();
        previousTab.addAll(current);
    }

    private void scanInvisibleStaff() {
        if (!invisibleSensor.value) return;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isInvisible()) continue;
            String name = player.getName().getString();
            if (isKnownStaff(name)) {
                addSignal("Invisible staff entity nearby: " + name, 2);
            }
        }
    }

    private void scanPositionJerk() {
        Vec3d now = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (lastPosition != null && positionSensor.value) {
            double moved = now.distanceTo(lastPosition);
            boolean noMovementInput = !mc.options.forwardKey.isPressed()
                    && !mc.options.backKey.isPressed()
                    && !mc.options.leftKey.isPressed()
                    && !mc.options.rightKey.isPressed()
                    && !mc.options.jumpKey.isPressed()
                    && !mc.options.sneakKey.isPressed();
            if (noMovementInput && mc.player.isOnGround() && moved > 2.5) {
                addSignal("Unexpected position correction while stationary", 1);
            }
        }
        lastPosition = now;
    }

    /**
     * Called by the sound packet mixin. A sound at almost the player's exact
     * position with no visible nearby player is weak evidence, not proof.
     */
    public static void soundPlayed(Identifier id, double x, double y, double z) {
        StaffDetector detector = instance;
        if (detector == null || !detector.isEnabled() || !detector.soundSensor.value
                || detector.mc.player == null || detector.mc.world == null) return;

        String path = id.getPath().toLowerCase(Locale.ROOT);
        boolean suspicious = path.contains("step") || path.contains("chest")
                || path.contains("break") || path.contains("crit")
                || path.contains("hit") || path.contains("smoke");
        if (!suspicious) return;

        Vec3d soundPos = new Vec3d(x, y, z);
        if (new Vec3d(detector.mc.player.getX(), detector.mc.player.getY(), detector.mc.player.getZ()).squaredDistanceTo(soundPos)
                > detector.soundRange.get() * detector.soundRange.get()) return;

        boolean visiblePlayerNearby = detector.mc.world.getPlayers().stream()
                .anyMatch(p -> p != detector.mc.player && p.isAlive()
                        && new Vec3d(p.getX(), p.getY(), p.getZ()).squaredDistanceTo(soundPos) < 64.0);
        if (!visiblePlayerNearby) detector.addSignal("Unattributed nearby sound: " + path, 1);
    }

    private void addSignal(String label, int points) {
        signals.addLast(new Signal(label, points, tick));
        while (signals.size() > 40) signals.removeFirst();
    }

    private void trimSignals() {
        int maxAge = (int) (scoreWindow.get() * 20.0);
        while (!signals.isEmpty() && tick - signals.peekFirst().tick() > maxAge) {
            signals.removeFirst();
        }
    }

    private int currentScore() {
        int total = 0;
        for (Signal signal : signals) total += signal.points();
        return total;
    }

    private String evidenceText() {
        StringBuilder out = new StringBuilder();
        for (Signal signal : signals) {
            if (out.length() > 0) out.append(" | ");
            out.append(signal.label());
            if (out.length() > 180) break;
        }
        return out.toString();
    }

    private void criticalAlert(String message) {
        long now = System.currentTimeMillis();
        if (now - lastAlertAt < 1800L && alertText.equals(message)) return;
        lastAlertAt = now;
        alertText = message;
        alertUntil = now + 7000L;
        if (chatAlert.value && mc.player != null) {
            mc.player.sendMessage(Text.literal("[Staff Detector] " + message), false);
        }
    }

    @Override
    public void onRender(DrawContext context, int sw, int sh) {
        if (!hudAlert.value || System.currentTimeMillis() > alertUntil || alertText.isEmpty()) return;
        int width = Math.min(sw - 16, mc.textRenderer.getWidth(alertText) + 24);
        int left = (sw - width) / 2;
        context.fill(left - 1, 39, left + width + 1, 61, 0xFFFF3333);
        context.fill(left, 40, left + width, 60, 0xE0101018);
        context.drawText(mc.textRenderer, mc.textRenderer.trimToWidth(alertText, width - 12),
                left + 6, 47, 0xFFFFFFFF, true);
    }
}
