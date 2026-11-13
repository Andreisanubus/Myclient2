package dev.myclient.module.modules;

import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ActivityDetector extends Module {
    private static final Deque<Event> EVENTS = new ArrayDeque<>();
    private static long lastAlertNanos;
    private final NumberSetting range = num("Alert Range", 128, 8, 512, 8);
    private final NumberSetting displayTime = num("Display Time (s)", 5, 1, 15, 1);
    private final Setting<Boolean> showCoordinates = bool("Show Coordinates", true);
    private final Setting<Boolean> showDistance = bool("Show Distance", true);
    private final Setting<Boolean> onlyChanges = bool("Ignore Same State", true);

    private record Event(String text, BlockPos pos, long time) {}

    public ActivityDetector() {
        super("Activity Detector", Category.RENDER);
        header("Notifications");
    }

    public static void blockChanged(BlockPos pos, boolean wasAir, boolean isAir) {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player == null || pos == null) return;
        for (Module module : dev.myclient.module.ModuleManager.get().all()) {
            if (module instanceof ActivityDetector detector && detector.isEnabled()) {
                detector.addEvent(pos, wasAir, isAir);
            }
        }
    }

    private void addEvent(BlockPos pos, boolean wasAir, boolean isAir) {
        if (mc.player == null || mc.player.squaredDistanceTo(pos.toCenterPos()) > range.get() * range.get()) return;
        String kind = isAir ? "Block removed" : (wasAir ? "Block placed/updated" : "Block changed");
        EVENTS.addFirst(new Event(kind, pos.toImmutable(), System.nanoTime()));
        while (EVENTS.size() > 8) EVENTS.removeLast();
        lastAlertNanos = System.nanoTime();
    }

    @Override public void onRender(DrawContext c, int sw, int sh) {
        long now = System.nanoTime();
        int y = 42;
        int shown = 0;
        for (Event event : EVENTS) {
            double age = (now - event.time()) / 1_000_000_000.0;
            if (age > displayTime.get()) continue;
            double distance = mc.player == null ? 0 : Math.sqrt(mc.player.squaredDistanceTo(event.pos().toCenterPos()));
            String line = event.text();
            if (showCoordinates.value) line += " [" + event.pos().getX() + ", " + event.pos().getY() + ", " + event.pos().getZ() + "]";
            if (showDistance.value) line += " " + Math.round(distance) + "m";
            int w = mc.textRenderer.getWidth(line);
            c.fill(sw - w - 16, y - 3, sw - 5, y + 12, 0xB9000000);
            c.drawText(mc.textRenderer, line, sw - w - 10, y, 0xFFFFD36A, true);
            y += 19;
            if (++shown >= 5) break;
        }
    }

    @Override protected void onDisable() { EVENTS.clear(); }
}
