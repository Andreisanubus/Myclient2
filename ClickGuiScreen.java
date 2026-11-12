package dev.myclient.gui;

import dev.myclient.config.ConfigManager;
import dev.myclient.config.FriendManager;
import dev.myclient.config.StaffManager;
import dev.myclient.config.Theme;
import dev.myclient.hud.Hud;
import dev.myclient.module.Category;
import dev.myclient.module.HeaderSetting;
import dev.myclient.module.HudModule;
import dev.myclient.module.ModeSetting;
import dev.myclient.module.Module;
import dev.myclient.module.ModuleManager;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
import dev.myclient.module.StringSetting;
import dev.myclient.util.Colors;
import dev.myclient.util.Keys;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class ClickGuiScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable l, Runnable r) {}
    private record Slider(int x, int y, int w, NumberSetting s) {}
    private static class Box { String t = ""; }

    private static final String[] TABS = {"Modules", "HUD", "Friends", "Staff", "Theme", "Config"};
    private static final int GRAY = 0xFF8A8A8A, WHITE = 0xFFFFFFFF, DIM = 0xFF5A5A5A;

    private final List<Hit> hits = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();
    private final Set<Category> closed = new HashSet<>();
    private final Set<String> pick = new LinkedHashSet<>(ConfigManager.SECTIONS);
    private final Box search = new Box(), friendBox = new Box(), staffBox = new Box(), cfgName = new Box(), cfgCode = new Box();
    private final Box editBox = new Box();
    private StringSetting editing;
    private Box focus;
    private Module binding, winMod;
    private int winSec, winScroll, winContentH, wx, wy, ww, wh;
    private Slider dragging;
    private HudModule dragHud;
    private int dragDX, dragDY;
    private int tab, scroll, contentH;
    private String status = "";
    private long statusUntil;

    public ClickGuiScreen() { super(Text.literal("MyClient")); }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() {
        ConfigManager.save("default", new LinkedHashSet<>(ConfigManager.SECTIONS));
        super.close();
    }

    // ------------------------------------------------------------ helpers
    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
    private void hit(int x, int y, int w, int h, Runnable l, Runnable r) { hits.add(new Hit(x, y, w, h, l, r)); }
    private void say(String s) { status = s; statusUntil = System.currentTimeMillis() + 2500; }
    private void txt(DrawContext c, String s, int x, int y, int color) { c.drawText(textRenderer, s, x, y, color, false); }
    private int tw(String s) { return textRenderer.getWidth(s); }

    private void box(DrawContext c, int x, int y, int w, int h, String title) {
        RenderUtil.roundedRect(c, x, y, w, h, Theme.radius, Theme.panel());
        txt(c, title, x + 8, y + 7, WHITE);
    }

    private void button(DrawContext c, int x, int y, int w, int h, String label, int mx, int my, Runnable l) {
        boolean hov = in(mx, my, x, y, w, h);
        RenderUtil.roundedRect(c, x, y, w, h, Math.max(2, Theme.radius / 2), hov ? Theme.accent : 0x33FFFFFF);
        txt(c, label, x + (w - tw(label)) / 2, y + (h - 8) / 2 + 1, WHITE);
        hit(x, y, w, h, l, null);
    }

    private void simpleRow(DrawContext c, int x, int y, int w, String label, boolean on, int mx, int my, Runnable l) {
        int r = Math.max(1, Theme.radius / 2);
        if (on) RenderUtil.roundedRect(c, x, y, w, 15, r, Theme.accent);
        else if (in(mx, my, x, y, w, 15)) RenderUtil.roundedRect(c, x, y, w, 15, r, 0x22FFFFFF);
        txt(c, textRenderer.trimToWidth(label, w - 8), x + 4, y + 4, on ? WHITE : GRAY);
        hit(x, y, w, 15, l, null);
    }

    private void field(DrawContext c, int x, int y, int w, Box b, String placeholder) {
        boolean f = focus == b;
        RenderUtil.roundedRect(c, x, y, w, 14, 3, f ? 0x44FFFFFF : 0x22FFFFFF);
        boolean ph = b.t.isEmpty() && !f;
        String s = ph ? placeholder : b.t + (f && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        txt(c, textRenderer.trimToWidth(s, w - 8, f), x + 4, y + 3, ph ? 0xFF777777 : WHITE);
        hit(x, y, w, 14, () -> focus = b, null);
    }

    // ------------------------------------------------------------ render
    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        sliders.clear();
        if (tab != 1) c.fill(0, 0, width, height, 0x99000000);
        switch (tab) {
            case 0 -> modulesTab(c, mx, my);
            case 1 -> hudTab(c, mx, my);
            case 2 -> friendsTab(c, mx, my);
            case 3 -> staffTab(c, mx, my);
            case 4 -> themeTab(c, mx, my);
            default -> configTab(c, mx, my);
        }
        tabBar(c, mx, my);
        if (winMod != null) { hits.clear(); sliders.clear(); drawWindow(c, mx, my); }
        if (System.currentTimeMillis() < statusUntil) txt(c, status, (width - tw(status)) / 2, height - 40, WHITE);
    }

    // ------------------------------------------------------------ MODULES
    private List<Module> results() {
        String q = search.t.toLowerCase();
        if (q.isEmpty()) return List.of();
        return ModuleManager.get().all().stream().filter(m -> m.name.toLowerCase().contains(q)).limit(6).toList();
    }

    private int panelHeight(Category cat) {
        int h = 22;
        if (!closed.contains(cat)) { for (Module m : ModuleManager.get().in(cat)) h += blockHeight(m); h += 4; }
        return h;
    }

    private int searchHeight() {
        int h = 44;
        for (Module m : results()) h += blockHeight(m);
        return h;
    }

    private void modulesTab(DrawContext c, int mx, int my) {
        List<Category> cats = Arrays.stream(Category.values()).filter(k -> !ModuleManager.get().in(k).isEmpty()).toList();
        int gap = 6, margin = 8, pw = 112, x = margin, y = 10 - scroll, rowH = 0, maxB = 0, total = cats.size() + 1;
        for (int i = 0; i < total; i++) {
            boolean isSearch = i == cats.size();
            int h = isSearch ? searchHeight() : panelHeight(cats.get(i));
            if (x + pw > width - margin && x > margin) { x = margin; y += rowH + gap; rowH = 0; }
            if (isSearch) searchPanel(c, x, y, pw, mx, my); else categoryPanel(c, cats.get(i), x, y, pw, mx, my);
            rowH = Math.max(rowH, h);
            maxB = Math.max(maxB, y + h + scroll);
            x += pw + gap;
        }
        contentH = maxB;
        txt(c, "* = modul inregistrat, logica nu e scrisa inca", 10, height - 56, DIM);
    }

    private void categoryPanel(DrawContext c, Category cat, int x, int y, int w, int mx, int my) {
        boolean open = !closed.contains(cat);
        RenderUtil.roundedRect(c, x, y, w, panelHeight(cat), Theme.radius, Theme.panel());
        txt(c, cat.label, x + 8, y + 7, WHITE);
        txt(c, open ? "v" : ">", x + w - 12, y + 7, GRAY);
        hit(x, y, w, 22, () -> { if (!closed.remove(cat)) closed.add(cat); }, null);
        if (!open) return;
        int ry = y + 22;
        for (Module m : ModuleManager.get().in(cat)) ry += moduleBlock(c, m, x + 4, ry, w - 8, mx, my);
    }

    private void searchPanel(DrawContext c, int x, int y, int w, int mx, int my) {
        RenderUtil.roundedRect(c, x, y, w, searchHeight(), Theme.radius, Theme.panel());
        txt(c, "Search", x + 8, y + 7, WHITE);
        field(c, x + 6, y + 22, w - 12, search, "cauta...");
        int ry = y + 42;
        for (Module m : results()) ry += moduleBlock(c, m, x + 4, ry, w - 8, mx, my);
    }

    // ---- randul unui modul: click stanga = porneste/opreste, click dreapta sau "..." = fereastra de setari
    private int blockHeight(Module m) { return 16; }

    private boolean hasSettings(Module m) {
        for (Setting<?> s : m.settings) if (!(s instanceof HeaderSetting)) return true;
        return false;
    }

    private void openWin(Module m) { winMod = m; winSec = 0; winScroll = 0; focus = null; editing = null; }

    private int moduleBlock(DrawContext c, Module m, int x, int y, int w, int mx, int my) {
        boolean on = m.isEnabled(), has = hasSettings(m);
        int r = Math.max(1, Theme.radius / 2);
        if (on) RenderUtil.roundedRect(c, x, y, w, 15, r, Theme.accent);
        else if (in(mx, my, x, y, w, 15)) RenderUtil.roundedRect(c, x, y, w, 15, r, 0x22FFFFFF);
        String label = m.implemented() ? m.name : m.name + "*";
        txt(c, textRenderer.trimToWidth(label, w - (has ? 20 : 8)), x + 4, y + 4, on ? WHITE : (m.implemented() ? GRAY : DIM));
        if (has) {
            int dx = x + w - 14;
            txt(c, "...", x + w - 13, y + 3, in(mx, my, dx, y, 14, 15) ? WHITE : (on ? 0xFFDDEBFF : DIM));
            hit(dx, y, 14, 15, () -> openWin(m), () -> openWin(m));
        }
        hit(x, y, w, 15, m::toggle, () -> openWin(m));
        return 16;
    }

    // ------------------------------------------------------------ FEREASTRA DE SETARI
    private record Sec(String title, List<Setting<?>> items) {}

    private List<Sec> sections(Module m) {
        List<Sec> out = new ArrayList<>();
        Sec cur = null;
        for (Setting<?> s : m.settings) {
            if (s instanceof HeaderSetting h) { cur = new Sec(h.value, new ArrayList<>()); out.add(cur); continue; }
            if (!s.visible.getAsBoolean()) continue;
            if (cur == null) { cur = new Sec("General", new ArrayList<>()); out.add(cur); }
            cur.items().add(s);
        }
        out.removeIf(x -> x.items().isEmpty());
        return out;
    }

    private static boolean isColor(ModeSetting ms) {
        return ms.modes.size() == Colors.NAMES.length && ms.modes.get(0).equals(Colors.NAMES[0]);
    }

    @SuppressWarnings("unchecked")
    private void resetModule(Module m) {
        for (Setting<?> s : m.settings) if (!(s instanceof HeaderSetting)) ((Setting<Object>) s).value = s.def;
        say("Resetat: " + m.name);
    }

    private int viewTop, viewBot;

    /** Hit taiat la zona vizibila a continutului (ca sa nu se apese sub titlu sau sub butoane). */
    private void chit(int x, int y, int w, int h, Runnable l, Runnable r) {
        int y0 = Math.max(y, viewTop), y1 = Math.min(y + h, viewBot);
        if (y1 > y0) hit(x, y0, w, y1 - y0, l, r);
    }

    private void drawWindow(DrawContext c, int mx, int my) {
        Module m = winMod;
        c.fill(0, 0, width, height, 0x88000000);
        ww = Math.min(width - 20, 420);
        wh = Math.min(height - 46, 270);
        wx = (width - ww) / 2;
        wy = Math.max(6, (height - 34 - wh) / 2);
        RenderUtil.roundedRect(c, wx, wy, ww, wh, Theme.radius, 0xF20C0C0E);

        // titlu: nume, Activ, Bind, inchidere
        txt(c, m.name + (m.implemented() ? "" : "  (fara logica inca)"), wx + 10, wy + 9, WHITE);
        int xr = wx + ww - 20;
        button(c, xr, wy + 5, 14, 14, "x", mx, my, () -> { winMod = null; binding = null; });
        String bl = "Bind: " + (binding == m ? "..." : Keys.name(m.key));
        int bw = tw(bl) + 12, bx = xr - bw - 6;
        button(c, bx, wy + 5, bw, 14, bl, mx, my, () -> binding = m);
        hit(bx, wy + 5, bw, 14, null, () -> m.key = -1);
        int sx = bx - 32;
        boolean on = m.isEnabled();
        RenderUtil.roundedRect(c, sx, wy + 7, 22, 10, 5, on ? Theme.accent : 0x33FFFFFF);
        RenderUtil.roundedRect(c, on ? sx + 13 : sx + 1, wy + 8, 8, 8, 4, WHITE);
        txt(c, "Activ", sx - tw("Activ") - 6, wy + 9, GRAY);
        hit(sx - tw("Activ") - 8, wy + 4, tw("Activ") + 40, 16, m::toggle, null);
        c.fill(wx + 1, wy + 24, wx + ww - 1, wy + 25, 0x22FFFFFF);

        // subsol: Copy / Paste / Reset
        int fy = wy + wh - 22;
        c.fill(wx + 1, fy - 2, wx + ww - 1, fy - 1, 0x22FFFFFF);
        int bx2 = wx + 8;
        button(c, bx2, fy + 3, 70, 14, "Copy Config", mx, my, () -> { Keys.setClipboard(ConfigManager.exportModule(m)); say("Config copiat: " + m.name); });
        button(c, bx2 + 74, fy + 3, 74, 14, "Paste Config", mx, my,
                () -> say(ConfigManager.importModule(m, Keys.getClipboard()) ? "Config lipit: " + m.name : "Cod invalid pentru " + m.name));
        button(c, bx2 + 152, fy + 3, 44, 14, "Reset", mx, my, () -> resetModule(m));
        String hint = "doar setarile acestui modul";
        if (ww > 420 - 10) txt(c, hint, wx + ww - tw(hint) - 8, fy + 7, DIM);

        // bara din stanga: sectiunile
        List<Sec> secs = sections(m);
        if (winSec >= secs.size()) winSec = 0;
        int sy = wy + 30;
        for (int i = 0; i < secs.size(); i++) {
            int idx = i;
            if (i == winSec) RenderUtil.roundedRect(c, wx + 6, sy, 92, 15, Math.max(2, Theme.radius / 2), Theme.accent);
            else if (in(mx, my, wx + 6, sy, 92, 15)) RenderUtil.roundedRect(c, wx + 6, sy, 92, 15, Math.max(2, Theme.radius / 2), 0x22FFFFFF);
            txt(c, textRenderer.trimToWidth(secs.get(i).title(), 82), wx + 12, sy + 4, i == winSec ? WHITE : GRAY);
            hit(wx + 6, sy, 92, 15, () -> { winSec = idx; winScroll = 0; focus = null; editing = null; }, null);
            sy += 17;
        }
        if (secs.isEmpty()) txt(c, "Fara setari", wx + 12, wy + 34, DIM);
        c.fill(wx + 102, wy + 28, wx + 103, fy - 4, 0x22FFFFFF);

        // continutul sectiunii alese, cu derulare
        int cx = wx + 110, cw = ww - 120;
        viewTop = wy + 28;
        viewBot = fy - 3;
        if (secs.isEmpty()) {
            txt(c, "Acest modul nu are setari.", cx, viewTop + 8, DIM);
            return;
        }
        c.enableScissor(cx - 2, viewTop, cx + cw + 4, viewBot);
        int y = viewTop + 2 - winScroll, total = 0;
        for (Setting<?> st : secs.get(winSec).items()) {
            int h = control(c, st, cx, y, cw, mx, my);
            y += h; total += h;
        }
        c.disableScissor();
        winContentH = total + 4;
        winScroll = Math.max(0, Math.min(Math.max(0, winContentH - (viewBot - viewTop)), winScroll));
    }

    @SuppressWarnings("unchecked")
    private int control(DrawContext c, Setting<?> s, int x, int y, int w, int mx, int my) {
        int h;
        if (s instanceof NumberSetting n) {
            h = 25;
            String v = String.format(n.step >= 1 ? "%.0f" : "%.2f", n.value);
            txt(c, textRenderer.trimToWidth(s.name, w - tw(v) - 10), x, y + 3, GRAY);
            txt(c, v, x + w - tw(v), y + 3, WHITE);
            int ty = y + 15;
            RenderUtil.roundedRect(c, x, ty, w, 4, 2, 0x33FFFFFF);
            double t = n.max > n.min ? (n.value - n.min) / (n.max - n.min) : 0;
            RenderUtil.roundedRect(c, x, ty, Math.max(4, (int) (w * t)), 4, 2, Theme.accent);
            if (ty >= viewTop && ty + 4 <= viewBot) sliders.add(new Slider(x, ty, w, n));
        } else if (s instanceof ModeSetting ms && isColor(ms)) {
            h = 19;
            txt(c, textRenderer.trimToWidth(s.name, w - Colors.NAMES.length * 13 - 6), x, y + 5, GRAY);
            int sx = x + w - Colors.NAMES.length * 13;
            for (int i = 0; i < Colors.NAMES.length; i++) {
                String nm = Colors.NAMES[i];
                if (nm.equals(ms.value)) RenderUtil.roundedRect(c, sx + i * 13 - 1, y + 3, 12, 12, 3, WHITE);
                RenderUtil.roundedRect(c, sx + i * 13, y + 4, 10, 10, 3, Colors.of(nm));
                chit(sx + i * 13, y + 3, 12, 12, () -> ms.value = nm, null);
            }
        } else if (s instanceof ModeSetting ms) {
            int lw = w * 4 / 10;
            txt(c, textRenderer.trimToWidth(s.name, lw - 6), x, y + 5, GRAY);
            int cx0 = x + lw, cx = cx0, cy = y + 2, lines = 1;
            for (String o : ms.modes) {
                int chw = tw(o) + 10;
                if (cx + chw > x + w && cx > cx0) { cx = cx0; cy += 15; lines++; }
                boolean sel = o.equals(ms.value);
                RenderUtil.roundedRect(c, cx, cy, chw, 13, 3, sel ? Theme.accent : 0x22FFFFFF);
                txt(c, o, cx + 5, cy + 3, sel ? WHITE : GRAY);
                chit(cx, cy, chw, 13, () -> ms.value = o, () -> ms.value = o);
                cx += chw + 3;
            }
            h = Math.max(19, lines * 15 + 4);
        } else if (s instanceof StringSetting ss) {
            h = 19;
            int lw = w * 4 / 10, fx = x + lw, fw = w - lw;
            txt(c, textRenderer.trimToWidth(s.name, lw - 6), x, y + 5, GRAY);
            boolean ed = editing == ss;
            RenderUtil.roundedRect(c, fx, y + 2, fw, 14, 3, ed ? 0x44FFFFFF : 0x22FFFFFF);
            String v = ed ? ss.value + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "") : (ss.value.isEmpty() ? "(click)" : ss.value);
            txt(c, textRenderer.trimToWidth(v, fw - 8, ed), fx + 4, y + 5, ed ? WHITE : (ss.value.isEmpty() ? DIM : 0xFF9CA3AF));
            chit(fx, y + 2, fw, 14, () -> { editing = ss; editBox.t = ss.value; focus = editBox; }, null);
        } else if (s.value instanceof Boolean b) {
            h = 19;
            txt(c, textRenderer.trimToWidth(s.name, w - 34), x, y + 5, GRAY);
            int sx = x + w - 24;
            RenderUtil.roundedRect(c, sx, y + 4, 22, 10, 5, b ? Theme.accent : 0x33FFFFFF);
            RenderUtil.roundedRect(c, b ? sx + 13 : sx + 1, y + 5, 8, 8, 4, WHITE);
            chit(x, y, w, 18, () -> ((Setting<Boolean>) s).value = !b, null);
        } else {
            return 0;
        }
        c.fill(x, y + h - 1, x + w, y + h, 0x14FFFFFF);
        return h;
    }

    private void syncEdit() { if (focus == editBox && editing != null) editing.value = editBox.t; }

    private void updateSlider(double mx) {
        if (dragging == null) return;
        double t = Math.max(0, Math.min(1, (mx - dragging.x()) / (double) dragging.w()));
        NumberSetting n = dragging.s();
        n.set(n.min + t * (n.max - n.min));
    }

    // ------------------------------------------------------------ HUD
    private List<HudModule> hudMods() {
        List<HudModule> l = new ArrayList<>();
        for (Module m : ModuleManager.get().all()) if (m instanceof HudModule hm) l.add(hm);
        return l;
    }

    private void hudTab(DrawContext c, int mx, int my) {
        List<HudModule> hm = hudMods();
        int w = 170, x = 10, y = 10;
        box(c, x, y, w, 36 + (Hud.toggles.size() + hm.size()) * 17, "HUD");
        int ry = y + 22;
        for (String key : new ArrayList<>(Hud.toggles.keySet())) {
            simpleRow(c, x + 6, ry, w - 12, key, Hud.toggles.get(key), mx, my, () -> Hud.toggles.compute(key, (k, v) -> !v));
            ry += 17;
        }
        for (HudModule m : hm) {
            simpleRow(c, x + 6, ry, w - 12, m.name, m.isEnabled(), mx, my, m::toggle);
            hit(x + 6, ry, w - 12, 15, null, () -> openWin(m));
            ry += 17;
        }
        txt(c, "Trage elementele de pe ecran ca sa le muti", x + 6, ry + 3, DIM);
        for (HudModule m : hm) {
            if (!m.isEnabled()) continue;
            int a = m.lastX - 2, b = m.lastY - 2, d = m.lastX + m.w + 2, e = m.lastY + m.h + 2;
            c.fill(a, b, d, b + 1, Theme.accent); c.fill(a, e - 1, d, e, Theme.accent);
            c.fill(a, b, a + 1, e, Theme.accent); c.fill(d - 1, b, d, e, Theme.accent);
            txt(c, m.name, a, b - 10, WHITE);
        }
    }

    // ------------------------------------------------------------ FRIENDS
    private void addFriend() { FriendManager.add(friendBox.t); friendBox.t = ""; }

    private void friendsTab(DrawContext c, int mx, int my) {
        int w = 220, x = (width - w) / 2, y = 30;
        List<String> list = new ArrayList<>(FriendManager.friends);
        box(c, x, y, w, 46 + list.size() * 17, "Friends");
        field(c, x + 6, y + 22, w - 60, friendBox, "nume (Bedrock: .nume)");
        button(c, x + w - 48, y + 22, 42, 14, "Add", mx, my, this::addFriend);
        int ry = y + 42;
        for (String name : list) {
            RenderUtil.roundedRect(c, x + 6, ry, w - 12, 15, 3, 0x22FFFFFF);
            txt(c, name, x + 10, ry + 4, WHITE);
            button(c, x + w - 26, ry + 1, 16, 13, "x", mx, my, () -> FriendManager.friends.remove(name));
            ry += 17;
        }
    }

    // ------------------------------------------------------------ STAFF
    private void addStaff() { StaffManager.add(staffBox.t); staffBox.t = ""; }

    private void staffTab(DrawContext c, int mx, int my) {
        int w = 220, x = (width - w) / 2, y = 30;
        List<String> list = new ArrayList<>(StaffManager.staff);
        box(c, x, y, w, 62 + list.size() * 17, "Staff");
        field(c, x + 6, y + 22, w - 60, staffBox, "nume staff");
        button(c, x + w - 48, y + 22, 42, 14, "Add", mx, my, this::addStaff);
        int ry = y + 42;
        for (String name : list) {
            RenderUtil.roundedRect(c, x + 6, ry, w - 12, 15, 3, 0x22FFFFFF);
            txt(c, name, x + 10, ry + 4, WHITE);
            button(c, x + w - 26, ry + 1, 16, 13, "x", mx, my, () -> StaffManager.staff.remove(name));
            ry += 17;
        }
        txt(c, "Apar in Staff List (modulul din Donut) cand sunt online", x + 6, ry + 3, DIM);
    }

    // ------------------------------------------------------------ THEME
    private void themeTab(DrawContext c, int mx, int my) {
        int w = 220, x = (width - w) / 2, y = 30;
        box(c, x, y, w, 116, "Theme");
        txt(c, "Accent", x + 8, y + 24, GRAY);
        for (int i = 0; i < Theme.PRESETS.length; i++) {
            int col = Theme.PRESETS[i], sx = x + 8 + i * 22, sy = y + 36;
            if (col == Theme.accent) RenderUtil.roundedRect(c, sx - 2, sy - 2, 20, 20, 5, WHITE);
            RenderUtil.roundedRect(c, sx, sy, 16, 16, 4, col);
            hit(sx, sy, 16, 16, () -> Theme.accent = col, null);
        }
        txt(c, "Opacity: " + Theme.opacity + "%", x + 8, y + 62, GRAY);
        button(c, x + w - 52, y + 59, 20, 14, "-", mx, my, () -> Theme.opacity = Math.max(30, Theme.opacity - 5));
        button(c, x + w - 28, y + 59, 20, 14, "+", mx, my, () -> Theme.opacity = Math.min(100, Theme.opacity + 5));
        txt(c, "Corner radius: " + Theme.radius, x + 8, y + 82, GRAY);
        button(c, x + w - 52, y + 79, 20, 14, "-", mx, my, () -> Theme.radius = Math.max(0, Theme.radius - 1));
        button(c, x + w - 28, y + 79, 20, 14, "+", mx, my, () -> Theme.radius = Math.min(20, Theme.radius + 1));
        button(c, x + 8, y + 98, 70, 13, "Reset theme", mx, my, Theme::reset);
    }

    // ------------------------------------------------------------ CONFIG
    private void saveProfile() {
        String n = ConfigManager.safe(cfgName.t);
        if (n.isEmpty()) { say("Scrie un nume (litere/cifre)"); return; }
        say(ConfigManager.save(n, pick) ? "Salvat: " + n : "Eroare la salvare");
        cfgName.t = "";
    }

    private void configTab(DrawContext c, int mx, int my) {
        List<String> profiles = ConfigManager.list();
        int w = 270, x = (width - w) / 2, y = 30, sec = ConfigManager.SECTIONS.size();
        box(c, x, y, w, 42 + sec * 17 + 14 + profiles.size() * 17 + 4 + 62, "Config");
        field(c, x + 6, y + 22, w - 60, cfgName, "nume profil");
        button(c, x + w - 48, y + 22, 42, 14, "Save", mx, my, this::saveProfile);

        int ry = y + 42;
        for (String s : ConfigManager.SECTIONS) {
            simpleRow(c, x + 6, ry, w - 12, s, pick.contains(s), mx, my, () -> { if (!pick.remove(s)) pick.add(s); });
            ry += 17;
        }
        txt(c, "Profiles", x + 8, ry + 2, GRAY);
        ry += 14;
        for (String p : profiles) {
            RenderUtil.roundedRect(c, x + 6, ry, w - 12, 15, 3, 0x22FFFFFF);
            txt(c, p, x + 10, ry + 4, WHITE);
            button(c, x + w - 66, ry + 1, 36, 13, "Load", mx, my, () -> say(ConfigManager.load(p) ? "Incarcat: " + p : "Eroare"));
            button(c, x + w - 26, ry + 1, 16, 13, "x", mx, my, () -> ConfigManager.delete(p));
            ry += 17;
        }
        ry += 4;
        txt(c, "Share code", x + 8, ry, GRAY);
        field(c, x + 6, ry + 10, w - 12, cfgCode, "cod (Ctrl+V ca sa lipesti)");
        int bw = (w - 12 - 6) / 3, by = ry + 28;
        button(c, x + 6, by, bw, 14, "Export", mx, my, () -> {
            cfgCode.t = ConfigManager.export(pick);
            Keys.setClipboard(cfgCode.t);
            say("Cod exportat si copiat");
        });
        button(c, x + 6 + bw + 3, by, bw, 14, "Copy", mx, my, () -> { Keys.setClipboard(cfgCode.t); say("Copiat"); });
        button(c, x + 6 + 2 * (bw + 3), by, bw, 14, "Import", mx, my, () -> {
            String code = cfgCode.t.isEmpty() ? Keys.getClipboard() : cfgCode.t;
            say(ConfigManager.importCode(code) ? "Importat" : "Cod invalid");
        });
    }

    // ------------------------------------------------------------ TAB BAR
    private void tabBar(DrawContext c, int mx, int my) {
        int pad = 10, gap = 4, total = -gap;
        for (String t : TABS) total += tw(t) + 2 * pad + gap;
        int bx = (width - total) / 2 - 5, by = height - 26;
        RenderUtil.roundedRect(c, bx, by, total + 10, 20, 8, 0xF00C0C0E);
        int x = bx + 5;
        for (int i = 0; i < TABS.length; i++) {
            int w = tw(TABS[i]) + 2 * pad, idx = i;
            if (i == tab) RenderUtil.roundedRect(c, x, by + 3, w, 14, 6, Theme.accent);
            txt(c, TABS[i], x + pad, by + 6, i == tab ? WHITE : GRAY);
            hit(x, by + 3, w, 14, () -> { tab = idx; focus = null; binding = null; }, null);
            x += w + gap;
        }
    }

    // ------------------------------------------------------------ input (API 1.21.9+: Click / KeyInput / CharInput)
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int button = click.button();
        focus = null;
        editing = null;
        binding = null;
        if (button == 0) {
            for (Slider s : sliders)
                if (in(mx, my, s.x(), s.y() - 3, s.w(), 10)) { dragging = s; updateSlider(mx); return true; }
        }
        for (Hit h : hits) {
            if (!in(mx, my, h.x(), h.y(), h.w(), h.h())) continue;
            Runnable r = button == 0 ? h.l() : (button == 1 ? h.r() : null);
            if (r != null) { r.run(); return true; }
        }
        if (tab == 1 && button == 0) {
            for (HudModule m : hudMods()) {
                if (m.isEnabled() && in(mx, my, m.lastX - 2, m.lastY - 2, m.w + 4, m.h + 4)) {
                    dragHud = m; dragDX = (int) mx - m.lastX; dragDY = (int) my - m.lastY;
                    return true;
                }
            }
        }
        if (winMod != null) {
            if (!in(mx, my, wx, wy, ww, wh)) { winMod = null; binding = null; }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragHud != null) { dragHud.moveTo((int) click.x() - dragDX, (int) click.y() - dragDY, width, height); return true; }
        if (dragging != null) { updateSlider(click.x()); return true; }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = null;
        dragHud = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (winMod != null) { winScroll = Math.max(0, winScroll - (int) (vAmount * 14)); return true; }
        if (tab == 0) scroll = Math.max(0, Math.min(Math.max(0, contentH - (height - 44)), scroll - (int) (vAmount * 20)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (binding != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { /* anuleaza */ }
            else if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) binding.key = -1;
            else binding.key = key;
            binding = null;
            return true;
        }
        if (focus != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { focus = null; return true; }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!focus.t.isEmpty()) focus.t = focus.t.substring(0, focus.t.length() - 1);
                syncEdit();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (focus == editBox) { focus = null; editing = null; }
                else if (focus == friendBox) addFriend();
                else if (focus == staffBox) addStaff();
                else if (focus == cfgName) saveProfile();
                return true;
            }
            if (key == GLFW.GLFW_KEY_V && input.hasCtrl()) {
                focus.t += Keys.getClipboard().replaceAll("[\\r\\n]", "");
                syncEdit();
                return true;
            }
            return true;
        }
        if (winMod != null && key == GLFW.GLFW_KEY_ESCAPE) { winMod = null; return true; }
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (focus != null && input.isValidChar() && focus.t.length() < 4000) {
            focus.t += input.asString();
            syncEdit();
            return true;
        }
        return super.charTyped(input);
    }
}
