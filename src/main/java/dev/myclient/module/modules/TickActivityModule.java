package dev.myclient.module.modules;

import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;

import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Reports nearby remote block changes. This is a client-side activity indicator,
 * not proof that another player caused the change.
 */
public final class TickActivityModule extends Module {
    private static TickActivityModule INSTANCE;

    private final NumberSetting range = num("Range", 48, 8, 128, 4);
    private final NumberSetting minDepth = num("Minimum Depth Below Player", 4, 0, 32, 1);
    private final NumberSetting cooldown = num("Position Cooldown (ms)", 800, 100, 5000, 100);
    private final Setting<Boolean> undergroundOnly = bool("Underground Only", true);
    private final Setting<Boolean> showScreen = bool("On-screen Alerts", true);
    private final Setting<Boolean> chatAlerts = bool("Chat Alerts", false);
    private final Setting<Boolean> showCoordinates = bool("Show Coordinates", true);

    private final ArrayDeque<Activity> alerts = new ArrayDeque<>();
    private final java.util.HashMap<Long, Long> lastByPosition = new java.util.HashMap<>();

    private record Activity(String label, BlockPos pos, long time) {}

    public TickActivityModule() {
        super("Tick Activity", Category.RENDER);
        INSTANCE = this;
        header("Detection");
        add(range);
        add(minDepth);
        add(cooldown);
        add(undergroundOnly);
        header("Alerts");
        add(showScreen);
        add(chatAlerts);
        add(showCoordinates);
    }

    public static void blockChanged(ClientWorld world, BlockPos pos,
                                    BlockState oldState, BlockState newState) {
        TickActivityModule module = INSTANCE;
        if (module == null || !module.isEnabled() || world == null || pos == null
                || oldState == null || newState == null || oldState.equals(newState)) return;
        module.accept(world, pos.toImmutable(), oldState, newState);
    }

    private void accept(ClientWorld world, BlockPos pos,
                        BlockState oldState, BlockState newState) {
        if (mc.world != world || mc.player == null) return;

        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dy = pos.getY() + 0.5 - mc.player.getY();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        double distanceSq = dx * dx + dy * dy + dz * dz;
        double maxRange = range.get();
        if (distanceSq > maxRange * maxRange) return;

        // Ignore the immediate player interaction area to reduce self-mining noise.
        if (distanceSq < 25.0) return;
        if (undergroundOnly.value && pos.getY() > mc.player.getY() - minDepth.get()) return;

        long key = pos.asLong();
        long now = System.currentTimeMillis();
        long previous = lastByPosition.getOrDefault(key, 0L);
        if (now - previous < cooldown.get()) return;
        lastByPosition.put(key, now);

        String label = newState.isAir() ? "Block broken" : oldState.isAir()
                ? "Block placed" : "Block changed";
        Activity activity = new Activity(label, pos, now);
        alerts.addFirst(activity);
        while (alerts.size() > 6) alerts.removeLast();

        if (chatAlerts.value && mc.player != null) {
            String coords = showCoordinates.value
                    ? " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() : "";
            mc.player.sendMessage(Text.literal("[Tick Activity] " + label + coords), false);
        }
    }

    @Override
    protected void onDisable() {
        alerts.clear();
        lastByPosition.clear();
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        while (!alerts.isEmpty() && now - alerts.peekLast().time() > 3500L) {
            alerts.removeLast();
        }
        if (lastByPosition.size() > 4096) {
            long cutoff = now - Math.max(5000L, cooldown.get() * 4L);
            lastByPosition.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
    }

    @Override
    public void onRender(DrawContext context, int width, int height) {
        if (!showScreen.value || alerts.isEmpty()) return;
        long now = System.currentTimeMillis();
        int x = 10;
        int y = 42;
        int row = 13;
        int shown = 0;
        for (Activity activity : alerts) {
            long age = now - activity.time();
            if (age > 3500L || shown >= 5) continue;
            int alpha = (int) (220 * Math.min(1.0, (3500L - age) / 450.0));
            int color = (alpha << 24) | 0x1A1D24;
            context.fill(x, y + shown * row, x + 218, y + shown * row + 12, color);
            String text = activity.label();
            if (showCoordinates.value) {
                BlockPos p = activity.pos();
                text += "  " + p.getX() + " " + p.getY() + " " + p.getZ();
            }
            context.drawTextWithShadow(mc.textRenderer, Text.literal(text),
                    x + 5, y + shown * row + 2, (alpha << 24) | 0xFFFFFF);
            shown++;
        }
    }
}
