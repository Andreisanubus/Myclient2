package dev.myclient.module.modules;

import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Alerts for nearby server-originated positional sound events. */
public final class SoundDetectorModule extends Module {
    private static SoundDetectorModule INSTANCE;

    private final NumberSetting range = num("Range", 64, 8, 128, 4);
    private final NumberSetting minimumVolume = num("Minimum Volume", 0.05, 0, 1, 0.05);
    private final NumberSetting cooldown = num("Sound Cooldown (ms)", 600, 100, 5000, 100);
    private final Setting<Boolean> showScreen = bool("On-screen Alerts", true);
    private final Setting<Boolean> chatAlerts = bool("Chat Alerts", false);
    private final Setting<Boolean> showCoordinates = bool("Show Coordinates", true);
    private final Setting<Boolean> filterCommon = bool("Filter Common Ambient Sounds", true);

    private final ArrayDeque<SoundAlert> alerts = new ArrayDeque<>();
    private final Map<String, Long> lastBySound = new HashMap<>();

    private record SoundAlert(String id, Vec3d pos, double distance, long time) {}

    public SoundDetectorModule() {
        super("Sound Detector", Category.RENDER);
        INSTANCE = this;
        header("Detection");
        add(range);
        add(minimumVolume);
        add(cooldown);
        add(filterCommon);
        header("Alerts");
        add(showScreen);
        add(chatAlerts);
        add(showCoordinates);
    }

    public static void soundPlayed(RegistryEntry<SoundEvent> sound,
                                   double x, double y, double z, float volume) {
        SoundDetectorModule module = INSTANCE;
        if (module == null || !module.isEnabled() || sound == null) return;
        module.accept(sound, x, y, z, volume);
    }

    private void accept(RegistryEntry<SoundEvent> sound,
                        double x, double y, double z, float volume) {
        if (mc.player == null || mc.world == null || volume < minimumVolume.get()) return;
        Vec3d pos = new Vec3d(x, y, z);
        double distance = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).distanceTo(pos);
        if (distance > range.get()) return;

        Identifier id = sound.getKey().map(key -> key.getValue())
                .orElse(Identifier.of("minecraft", "unknown"));
        String soundId = id.toString();
        if (filterCommon.value && isCommonAmbient(soundId)) return;

        long now = System.currentTimeMillis();
        long previous = lastBySound.getOrDefault(soundId, 0L);
        if (now - previous < cooldown.get()) return;
        lastBySound.put(soundId, now);

        alerts.addFirst(new SoundAlert(soundId, pos, distance, now));
        while (alerts.size() > 5) alerts.removeLast();

        if (chatAlerts.value) {
            String coords = showCoordinates.value
                    ? " at " + (int) x + " " + (int) y + " " + (int) z : "";
            mc.player.sendMessage(Text.literal("[Sound Detector] " + soundId
                    + " (" + (int) distance + "m)" + coords), false);
        }
    }

    private static boolean isCommonAmbient(String id) {
        return id.startsWith("minecraft:ambient.")
                || id.startsWith("minecraft:weather.")
                || id.startsWith("minecraft:music.");
    }

    @Override
    protected void onDisable() {
        alerts.clear();
        lastBySound.clear();
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        while (!alerts.isEmpty() && now - alerts.peekLast().time() > 3500L) {
            alerts.removeLast();
        }
        if (lastBySound.size() > 512) {
            long cutoff = now - (long) Math.max(5000.0, cooldown.get() * 4.0);
            lastBySound.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }

    @Override
    public void onRender(DrawContext context, int width, int height) {
        if (!showScreen.value || alerts.isEmpty()) return;
        long now = System.currentTimeMillis();
        int x = 10;
        int y = 112;
        int row = 13;
        int shown = 0;
        for (SoundAlert alert : alerts) {
            long age = now - alert.time();
            if (age > 3500L || shown >= 5) continue;
            int alpha = (int) (220 * Math.min(1.0, (3500L - age) / 450.0));
            context.fill(x, y + shown * row, x + 250, y + shown * row + 12,
                    (alpha << 24) | 0x1A1D24);
            String label = alert.id() + "  " + (int) alert.distance() + "m";
            if (showCoordinates.value) {
                label += "  " + (int) alert.pos().x + " " + (int) alert.pos().y
                        + " " + (int) alert.pos().z;
            }
            context.drawTextWithShadow(mc.textRenderer, Text.literal(label),
                    x + 5, y + shown * row + 2, (alpha << 24) | 0x70D8FF);
            shown++;
        }
    }
}
