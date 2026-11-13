package dev.myclient.module.modules;

import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

public final class SoundDetector extends Module {
    private static final Deque<SoundAlert> ALERTS = new ArrayDeque<>();
    private final NumberSetting range = num("Range", 48, 8, 128, 4);
    private final NumberSetting displayTime = num("Display Time (s)", 4, 1, 12, 1);
    private final Setting<Boolean> coordinates = bool("Coordinates", true);
    private final Setting<Boolean> distance = bool("Distance", true);
    private final Setting<Boolean> includeAmbient = bool("Include Ambient", false);

    private record SoundAlert(String name, Vec3d pos, long time) {}

    public SoundDetector() { super("Sound Detector", Category.RENDER); }

    public static void soundPlayed(Identifier id, double x, double y, double z) {
        for (Module module : dev.myclient.module.ModuleManager.get().all()) {
            if (module instanceof SoundDetector detector && detector.isEnabled()) {
                detector.add(id, new Vec3d(x, y, z));
            }
        }
    }

    private void add(Identifier id, Vec3d pos) {
        if (mc.player == null || new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).squaredDistanceTo(pos) > range.get() * range.get()) return;
        String path = id.getPath().replace('_', ' ');
        if (!includeAmbient.value && (path.contains("ambient") || path.contains("weather"))) return;
        ALERTS.addFirst(new SoundAlert(path, pos, System.nanoTime()));
        while (ALERTS.size() > 8) ALERTS.removeLast();
    }

    @Override public void onRender(DrawContext c, int sw, int sh) {
        if (mc.player == null) return;
        long now = System.nanoTime();
        int y = 42;
        int shown = 0;
        for (SoundAlert alert : ALERTS) {
            if ((now - alert.time()) / 1_000_000_000.0 > displayTime.get()) continue;
            double d = Math.sqrt(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()).squaredDistanceTo(alert.pos()));
            String line = "Sound: " + alert.name();
            if (coordinates.value) line += " [" + (int) alert.pos().x + ", " + (int) alert.pos().y + ", " + (int) alert.pos().z + "]";
            if (distance.value) line += " " + Math.round(d) + "m";
            int w = mc.textRenderer.getWidth(line);
            c.fill(sw - w - 16, y - 3, sw - 5, y + 12, 0xB9000000);
            c.drawText(mc.textRenderer, line, sw - w - 10, y, 0xFF75D9FF, true);
            y += 19;
            if (++shown >= 5) break;
        }
    }

    @Override protected void onDisable() { ALERTS.clear(); }
}
