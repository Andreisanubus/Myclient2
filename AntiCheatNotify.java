package dev.myclient.module.modules;

import dev.myclient.config.Theme;
import dev.myclient.gui.RenderUtil;
import dev.myclient.module.Category;
import dev.myclient.module.HudModule;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
import dev.myclient.module.StringSetting;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Arata daca anti-cheat-ul pare ON sau OFF si anunta pe ecran cand se schimba.
 * Un client nu poate sti sigur starea anti-cheat-ului din server. Se folosesc doua semnale:
 * 1) cuvinte-cheie in chat (editabile) si 2) "setback-uri": cand serverul te muta inapoi (pozitia sare fata de ce era asteptat).
 * Rezultatul e o estimare, afisata cu motivul.
 */
public class AntiCheatNotify extends HudModule {
    private enum State { UNKNOWN, ON, OFF }
    private record Toast(String text, int color, long until) {}

    private final StringSetting onWords, offWords;
    private final NumberSetting jump, window, flagAt, bg;
    private final Setting<Boolean> detect, screen, chat, sound;

    private State state = State.UNKNOWN;
    private String reason = "fara date inca";
    private final Deque<Long> setbacks = new ArrayDeque<>();
    private final Deque<Toast> toasts = new ArrayDeque<>();
    private Vec3d lastPos, lastVel;
    private Object lastWorld;
    private long onSince, lastSetback;

    public AntiCheatNotify() {
        super("Anti Cheat Notify", Category.HUD, 40, 1);
        header("Chat Keywords (separate prin virgula)");
        onWords = text("ON keywords", "anticheat enabled,anti-cheat enabled,anticheat on,anti cheat on");
        offWords = text("OFF keywords", "anticheat disabled,anti-cheat disabled,anticheat off,anti cheat off");
        header("Setback Detection");
        detect = bool("Detect Setbacks", true);
        jump = num("Jump Size (blocks)", 3, 1.5, 10, 0.5);
        window = num("Window (s)", 30, 10, 120, 5);
        flagAt = num("Setbacks To Flag ON", 3, 1, 10, 1);
        header("Alerts");
        screen = bool("Screen Alert", true);
        chat = bool("Chat Alert", false);
        sound = bool("Sound", true);
        bg = num("Background Alpha %", 75, 10, 100, 5);
        jump.visible = () -> detect.value;
        window.visible = () -> detect.value;
        flagAt.visible = () -> detect.value;
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!isEnabled()) return;
            String t = msg.getString().toLowerCase(Locale.ROOT);
            for (String w : onWords.value.toLowerCase(Locale.ROOT).split(",")) if (!w.isBlank() && t.contains(w.trim())) { set(State.ON, "chat: " + w.trim()); return; }
            for (String w : offWords.value.toLowerCase(Locale.ROOT).split(",")) if (!w.isBlank() && t.contains(w.trim())) { set(State.OFF, "chat: " + w.trim()); return; }
        });
    }

    @Override protected void onEnable() { state = State.UNKNOWN; reason = "fara date inca"; setbacks.clear(); lastPos = null; }

    private void set(State s, String why) {
        if (s == state) { reason = why; return; }
        state = s;
        reason = why;
        onSince = System.currentTimeMillis();
        String txt = switch (s) {
            case ON -> "ANTI-CHEAT: ON  (" + why + ")";
            case OFF -> "ANTI-CHEAT: OFF  (" + why + ")";
            default -> "ANTI-CHEAT: neclar  (" + why + ")";
        };
        int col = s == State.ON ? 0xFFEF4444 : s == State.OFF ? 0xFF22C55E : 0xFF9CA3AF;
        if (screen.value) { toasts.addLast(new Toast(txt, col, System.currentTimeMillis() + 5000)); while (toasts.size() > 3) toasts.removeFirst(); }
        if (chat.value && mc.player != null) mc.player.sendMessage(Text.literal("[Anti Cheat Notify] " + txt), false);
        if (sound.value && mc.player != null) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, s == State.ON ? 0.5f : 1.4f);
    }

    @Override public void onTick() {
        var p = mc.player;
        if (p == null || mc.world == null) return;
        Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ()), vel = p.getVelocity();
        long now = System.currentTimeMillis();
        if (lastWorld != mc.world) { lastWorld = mc.world; lastPos = null; setbacks.clear(); }
        if (detect.value && lastPos != null && p.age > 80 && !p.hasVehicle() && lastVel.length() < 1.2) {
            double err = pos.distanceTo(lastPos.add(lastVel));
            if (err > jump.get()) { setbacks.addLast(now); lastSetback = now; }
        }
        lastPos = pos;
        lastVel = vel;
        long w = (long) (window.get() * 1000);
        while (!setbacks.isEmpty() && setbacks.peekFirst() < now - w) setbacks.pollFirst();
        if (detect.value) {
            if (setbacks.size() >= flagAt.asInt() && state != State.ON) set(State.ON, setbacks.size() + " setback-uri in " + window.asInt() + "s");
            else if (state == State.ON && reason.contains("setback") && now - lastSetback > 2 * w) set(State.UNKNOWN, "calm, fara setback-uri");
        }
    }

    @Override protected int[] draw(DrawContext c, int x, int y, int sw, int sh) {
        long now = System.currentTimeMillis();
        toasts.removeIf(t -> t.until() < now);
        int ty = 64;
        for (Toast t : toasts) {
            int w = mc.textRenderer.getWidth(t.text()) + 18;
            c.fill(sw / 2 - w / 2, ty, sw / 2 + w / 2, ty + 16, 0xD0101014);
            c.fill(sw / 2 - w / 2, ty, sw / 2 - w / 2 + 3, ty + 16, t.color());
            c.drawText(mc.textRenderer, t.text(), sw / 2 - w / 2 + 9, ty + 4, 0xFFFFFFFF, true);
            ty += 18;
        }
        int col = state == State.ON ? 0xFFEF4444 : state == State.OFF ? 0xFF22C55E : 0xFF9CA3AF;
        String s = state == State.ON ? "ON" : state == State.OFF ? "OFF" : "?";
        String sub = mc.textRenderer.trimToWidth(reason, 150);
        int w = Math.max(96, Math.max(mc.textRenderer.getWidth(sub) + 16, 90)), h = 34;
        RenderUtil.roundedRect(c, x, y, w, h, Theme.radius, ((int) (bg.get() * 2.55) << 24) | 0x0C0C0E);
        c.fill(x + 3, y + 3, x + 5, y + h - 3, col);
        c.drawText(mc.textRenderer, "Anti-Cheat", x + 9, y + 5, 0xFFFFFFFF, true);
        c.drawText(mc.textRenderer, s, x + w - mc.textRenderer.getWidth(s) - 6, y + 5, col, true);
        c.drawText(mc.textRenderer, sub, x + 9, y + 20, 0xFF9CA3AF, false);
        return new int[]{w, h};
    }
}
