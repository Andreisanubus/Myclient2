package dev.myclient.module.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.myclient.config.Theme;
import dev.myclient.gui.RenderUtil;
import dev.myclient.module.Category;
import dev.myclient.module.HudModule;
import dev.myclient.module.ModeSetting;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.StringSetting;
import net.minecraft.client.gui.DrawContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * HUD care citeste un URL la intervale (in timp real sau cu delay) si arata continutul.
 * Accepta text pe linii sau JSON (lista de nume / obiecte cu "name"). Doar http/https. URL-ul NU intra in codurile de share.
 */
public class StaffLiveHud extends HudModule {
    private record Snap(long ts, List<String> lines) {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final StringSetting url;
    private final NumberSetting refresh, delay, maxLines, bg;
    private final ModeSetting format;
    private final ConcurrentLinkedDeque<Snap> snaps = new ConcurrentLinkedDeque<>();
    private volatile long lastOk;
    private volatile boolean inflight;
    private long lastFetch;

    public StaffLiveHud() {
        super("Staff Live", Category.DONUT, 2, 55);
        header("Source");
        url = text("Live URL (http/https)", "");
        refresh = num("Refresh (s)", 3, 0.5, 60, 0.5);
        delay = num("Delay (s)", 0, 0, 120, 1);
        format = mode("Format", "Auto", "Auto", "Text", "JSON");
        header("Display");
        maxLines = num("Max Lines", 8, 2, 20, 1);
        bg = num("Background Alpha %", 75, 10, 100, 5);
    }

    @Override protected void onDisable() { snaps.clear(); }

    @Override public void onTick() {
        String u = url.value.trim();
        long now = System.currentTimeMillis();
        if (inflight || now - lastFetch < refresh.get() * 1000 || !(u.startsWith("http://") || u.startsWith("https://"))) return;
        lastFetch = now;
        inflight = true;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(6)).header("User-Agent", "MyClient").GET().build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
                inflight = false;
                if (err != null || resp.statusCode() / 100 != 2) return;
                long t = System.currentTimeMillis();
                lastOk = t;
                snaps.addLast(new Snap(t, parse(resp.body())));
                while (snaps.size() > 1 && snaps.peekFirst().ts() < t - (long) (delay.get() * 1000) - 60000) snaps.pollFirst();
            });
        } catch (Exception e) {
            inflight = false;
        }
    }

    private static String clean(String l) {
        StringBuilder sb = new StringBuilder();
        for (char ch : l.toCharArray()) if (ch >= 32 && ch != 127 && ch < 0x2500) sb.append(ch);
        String t = sb.toString().trim();
        return t.length() > 80 ? t.substring(0, 77) + "..." : t;
    }

    private List<String> parse(String body) {
        String b = (body.length() > 20000 ? body.substring(0, 20000) : body).trim();
        List<String> out = new ArrayList<>();
        String low = b.toLowerCase();
        if (low.startsWith("<!doctype") || low.startsWith("<html") || low.contains("<head") || low.contains("<body")) {
            var m = java.util.regex.Pattern.compile("<title[^>]*>(.*?)</title>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE).matcher(b);
            out.add("Pagina web, nu date");
            if (m.find()) out.add(clean(m.group(1)));
            out.add("Foloseste un URL care da text/JSON");
            return out;
        }
        if (!format.is("Text") && (b.startsWith("[") || b.startsWith("{"))) {
            try {
                collect(JsonParser.parseString(b), out, 0);
                List<String> cl = new ArrayList<>();
                for (String x : out) { String c2 = clean(x); if (!c2.isEmpty()) cl.add(c2); }
                if (!cl.isEmpty()) return cl;
                out.clear();
            } catch (Exception ignored) {}
        }
        for (String ln : b.split("\\R")) {
            String c2 = clean(ln);
            if (c2.isEmpty()) continue;
            if (c2.length() > 40 && c2.indexOf(' ') < 0) c2 = c2.substring(0, 37) + "...";   // sir lung fara spatii = cod/token
            out.add(c2);
        }
        return out;
    }

    private void collect(JsonElement e, List<String> out, int depth) {
        if (out.size() > 60 || depth > 4) return;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x.isJsonPrimitive()) out.add(x.getAsString());
                else collect(x, out, depth + 1);
            }
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            for (String k : new String[]{"name", "username", "player", "staff"}) {
                if (o.has(k) && o.get(k).isJsonPrimitive()) { out.add(o.get(k).getAsString()); return; }
            }
            for (var en : o.entrySet()) {
                JsonElement v = en.getValue();
                if (v.isJsonArray() || v.isJsonObject()) collect(v, out, depth + 1);
                else if (depth == 0) out.add(en.getKey() + ": " + v.getAsString());
            }
        }
    }

    @Override protected int[] draw(DrawContext c, int x, int y, int sw, int sh) {
        long now = System.currentTimeMillis();
        boolean hasUrl = url.value.trim().startsWith("http");
        boolean editing = mc.currentScreen != null;
        if (!hasUrl && !editing) return new int[]{80, 14};

        List<String> lines = new ArrayList<>();
        Snap pick = null;
        long cutoff = now - (long) (delay.get() * 1000);
        for (Snap s : snaps) if (s.ts() <= cutoff) pick = s;
        if (!hasUrl) lines.add("Pune URL-ul in setari");
        else if (pick == null) lines.add(snaps.isEmpty() ? "se incarca..." : "asteapta delay...");
        else for (String l : pick.lines()) { if (lines.size() >= maxLines.asInt()) break; lines.add(l); }

        boolean live = hasUrl && lastOk > 0 && now - lastOk < refresh.get() * 2000 + 4000;
        String head = "Staff Live" + (delay.get() > 0 ? "  (delay " + delay.asInt() + "s)" : "");
        int w = mc.textRenderer.getWidth(head) + 26;
        for (String l : lines) w = Math.max(w, mc.textRenderer.getWidth(l) + 18);
        w = Math.min(w, 260);
        int h = 18 + lines.size() * 11 + 4;
        RenderUtil.roundedRect(c, x, y, w, h, Theme.radius, ((int) (bg.get() * 2.55) << 24) | 0x0C0C0E);
        c.fill(x + 3, y + 3, x + 5, y + h - 3, Theme.accent);
        int dot = live ? (((now / 500) % 2 == 0) ? 0xFF22C55E : 0xFF15803D) : 0xFFEF4444;
        c.fill(x + 10, y + 7, x + 15, y + 12, dot);
        c.drawText(mc.textRenderer, head, x + 20, y + 5, 0xFFFFFFFF, true);
        c.drawText(mc.textRenderer, live ? "LIVE" : "OFF", x + w - mc.textRenderer.getWidth(live ? "LIVE" : "OFF") - 5, y + 5, live ? 0xFF86EFAC : 0xFFFCA5A5, false);
        int ty = y + 18;
        for (String l : lines) {
            c.drawText(mc.textRenderer, mc.textRenderer.trimToWidth(l, w - 16), x + 10, ty, 0xFFD1D5DB, false);
            ty += 11;
        }
        return new int[]{w, h};
    }
}
