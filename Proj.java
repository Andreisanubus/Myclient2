package dev.myclient.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

/**
 * Proiectie lume -> ecran, desenata pe HUD. Foloseste camera reala a jocului (pozitie interpolata, F5, freecam),
 * deci obiectele raman prinse de lume cand te uiti in jur. Fiindca se deseneaza pe HUD, se vad prin pereti si prin pamant.
 * FOV-ul se blocheaza la valoarea din setari (fara efectul de sprint) si view bobbing se opreste cat timp e activ un modul de randare.
 */
public final class Proj {
    private static double cx, cy, cz, fx, fy, fz, rx, rz, ux, uy, uz, scale;
    public static int sw, sh;
    private static int budget;
    private static boolean locked;
    private static double oldScale;
    private static boolean oldBob;

    private Proj() {}

    public static void lock(MinecraftClient mc, boolean on) {
        if (on) {
            if (!locked) { oldScale = mc.options.getFovEffectScale().getValue(); oldBob = mc.options.getBobView().getValue(); locked = true; }
            mc.options.getFovEffectScale().setValue(0.0);
            mc.options.getBobView().setValue(false);
        } else if (locked) {
            mc.options.getFovEffectScale().setValue(oldScale);
            mc.options.getBobView().setValue(oldBob);
            locked = false;
        }
    }

    public static void begin(MinecraftClient mc, int w, int h) {
        Camera cam = mc.gameRenderer.getCamera();
        Vec3d e = cam.getCameraPos();
        cx = e.x; cy = e.y; cz = e.z;
        double yaw = Math.toRadians(cam.getYaw()), pit = Math.toRadians(cam.getPitch());
        fx = -Math.sin(yaw) * Math.cos(pit); fy = -Math.sin(pit); fz = Math.cos(yaw) * Math.cos(pit);
        rx = -Math.cos(yaw); rz = -Math.sin(yaw);
        ux = -rz * fy; uy = rz * fx - rx * fz; uz = rx * fy;
        sw = w; sh = h;
        scale = (h / 2.0) / Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2.0);
        budget = 20000;
    }

    public static Vec3d camPos() { return new Vec3d(cx, cy, cz); }
    public static Vec3d forward(double d) { return new Vec3d(cx + fx * d, cy + fy * d, cz + fz * d); }
    public static Vec3d below(double fwd, double down) { return new Vec3d(cx + fx * fwd - ux * down, cy + fy * fwd - uy * down, cz + fz * fwd - uz * down); }

    public static int alpha(int col, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (col & 0xFFFFFF); }

    private static int shade(int col, double k) {
        int r = (int) (((col >> 16) & 255) * k), g = (int) (((col >> 8) & 255) * k), b = (int) ((col & 255) * k);
        return (r << 16) | (g << 8) | b;
    }

    public static double depth(Vec3d v) { return (v.x - cx) * fx + (v.y - cy) * fy + (v.z - cz) * fz; }

    /** {x, y, adancime} sau null daca e in spatele camerei. */
    public static double[] to(double x, double y, double z) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        double zc = dx * fx + dy * fy + dz * fz;
        if (zc < 0.05) return null;
        double xc = dx * rx + dz * rz;
        double yc = dx * ux + dy * uy + dz * uz;
        return new double[]{sw / 2.0 + xc / zc * scale, sh / 2.0 - yc / zc * scale, zc};
    }

    public static double[] boxRect(Box b) {
        double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
        for (int i = 0; i < 8; i++) {
            double[] p = to((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
            if (p == null) return null;
            x0 = Math.min(x0, p[0]); y0 = Math.min(y0, p[1]); x1 = Math.max(x1, p[0]); y1 = Math.max(y1, p[1]);
        }
        if (x1 - x0 > sw * 4 || y1 - y0 > sh * 4) return null;
        return new double[]{x0, y0, x1, y1};
    }

    public static void outline(DrawContext c, double x0, double y0, double x1, double y1, int col, boolean corners) {
        int a = (int) x0, b = (int) y0, d = (int) x1, e = (int) y1;
        if (corners) {
            int l = Math.max(2, Math.min(8, (d - a) / 3)), m = Math.max(2, Math.min(8, (e - b) / 3));
            c.fill(a, b, a + l, b + 1, col); c.fill(a, b, a + 1, b + m, col);
            c.fill(d - l, b, d, b + 1, col); c.fill(d - 1, b, d, b + m, col);
            c.fill(a, e - 1, a + l, e, col); c.fill(a, e - m, a + 1, e, col);
            c.fill(d - l, e - 1, d, e, col); c.fill(d - 1, e - m, d, e, col);
        } else {
            c.fill(a, b, d, b + 1, col); c.fill(a, e - 1, d, e, col);
            c.fill(a, b, a + 1, e, col); c.fill(d - 1, b, d, e, col);
        }
    }

    public static double[] box(DrawContext c, Box b, int col, boolean corners) {
        double[] r = boxRect(b);
        if (r == null) return null;
        outline(c, r[0], r[1], r[2], r[3], col, corners);
        return r;
    }

    // ------------------------------------------------------------ umplere
    private static void fillConvex(DrawContext c, double[] xs, double[] ys, int n, int col) {
        double minY = 1e9, maxY = -1e9;
        for (int i = 0; i < n; i++) { minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]); }
        int y0 = (int) Math.max(0, Math.floor(minY)), y1 = (int) Math.min(sh, Math.ceil(maxY));
        if (y1 <= y0) return;
        int step = (y1 - y0) > 220 ? 3 : (y1 - y0) > 90 ? 2 : 1;
        for (int y = y0; y < y1; y += step) {
            double yc = y + step * 0.5, lo = 1e9, hi = -1e9;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double ya = ys[i], yb = ys[j];
                if ((ya <= yc && yb > yc) || (yb <= yc && ya > yc)) {
                    double x = xs[i] + (yc - ya) / (yb - ya) * (xs[j] - xs[i]);
                    lo = Math.min(lo, x); hi = Math.max(hi, x);
                }
            }
            if (hi > lo) {
                int a = (int) Math.max(0, lo), b = (int) Math.min(sw, hi);
                if (b > a) { c.fill(a, y, b, Math.min(y + step, y1), col); if (--budget <= 0) return; }
            }
        }
    }

    /** Poligon convex in lume, cu taiere la planul din fata camerei. */
    public static void fillPoly(DrawContext c, Vec3d[] pts, int argb) {
        if (budget <= 0) return;
        double near = 0.1;
        List<Vec3d> out = new ArrayList<>();
        for (int i = 0; i < pts.length; i++) {
            Vec3d a = pts[i], b = pts[(i + 1) % pts.length];
            double da = depth(a) - near, db = depth(b) - near;
            if (da >= 0) out.add(a);
            if ((da >= 0) != (db >= 0)) out.add(a.add(b.subtract(a).multiply(da / (da - db))));
        }
        if (out.size() < 3) return;
        double[] xs = new double[out.size()], ys = new double[out.size()];
        for (int i = 0; i < out.size(); i++) {
            Vec3d v = out.get(i);
            double[] p = to(v.x, v.y, v.z);
            if (p == null) return;
            xs[i] = p[0]; ys[i] = p[1];
        }
        fillConvex(c, xs, ys, out.size(), argb);
    }

    private static Vec3d corner(Box b, int i) {
        return new Vec3d((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
    }

    /** Bloc umplut 3D: se deseneaza fetele vazute, cu umbrire diferita, ca sa arate ca un cub. */
    public static void fillBox3D(DrawContext c, Box b, int rgb, int alpha) {
        if (budget <= 0) return;
        int[][] faces = {{0, 2, 6, 4}, {1, 5, 7, 3}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 1, 3, 2}, {4, 6, 7, 5}};
        boolean[] vis = {cx < b.minX, cx > b.maxX, cy < b.minY, cy > b.maxY, cz < b.minZ, cz > b.maxZ};
        double[] k = {0.82, 0.82, 0.55, 1.0, 0.68, 0.68};
        for (int f = 0; f < 6; f++) {
            if (!vis[f]) continue;
            Vec3d[] q = new Vec3d[4];
            for (int i = 0; i < 4; i++) q[i] = corner(b, faces[f][i]);
            fillPoly(c, q, alpha(shade(rgb, k[f]), alpha));
        }
    }

    public static void boxEdges(DrawContext c, Box b, int argb) {
        int[][] e = {{0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {3, 7}, {2, 6}};
        for (int[] p : e) line3(c, corner(b, p[0]), corner(b, p[1]), argb);
    }

    /** Linie subtire de 1 px, desenata pe segmente continue (arata curat si costa putin). */
    public static void line2(DrawContext c, double x0, double y0, double x1, double y1, int col) {
        double dx = x1 - x0, dy = y1 - y0;
        if (Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6) return;
        if (Math.abs(dx) >= Math.abs(dy)) {
            if (x1 < x0) { double t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; dx = -dx; dy = -dy; }
            double slope = dy / dx;
            int xs = (int) Math.max(Math.floor(x0), -1), xe = (int) Math.min(Math.floor(x1), sw);
            int runStart = xs, runY = (int) Math.round(y0 + (xs + 0.5 - x0) * slope);
            for (int x = xs; x <= xe; x++) {
                int y = (int) Math.round(y0 + (x + 0.5 - x0) * slope);
                if (y != runY) { hrun(c, runStart, x - 1, runY, col); runStart = x; runY = y; }
            }
            hrun(c, runStart, xe, runY, col);
        } else {
            if (y1 < y0) { double t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; dx = -dx; dy = -dy; }
            double slope = dx / dy;
            int ys = (int) Math.max(Math.floor(y0), -1), ye = (int) Math.min(Math.floor(y1), sh);
            int runStart = ys, runX = (int) Math.round(x0 + (ys + 0.5 - y0) * slope);
            for (int y = ys; y <= ye; y++) {
                int x = (int) Math.round(x0 + (y + 0.5 - y0) * slope);
                if (x != runX) { vrun(c, runStart, y - 1, runX, col); runStart = y; runX = x; }
            }
            vrun(c, runStart, ye, runX, col);
        }
    }

    private static void hrun(DrawContext c, int xa, int xb, int y, int col) {
        if (y < 0 || y >= sh) return;
        xa = Math.max(0, xa); xb = Math.min(sw - 1, xb);
        if (xb >= xa) c.fill(xa, y, xb + 1, y + 1, col);
    }

    private static void vrun(DrawContext c, int ya, int yb, int x, int col) {
        if (x < 0 || x >= sw) return;
        ya = Math.max(0, ya); yb = Math.min(sh - 1, yb);
        if (yb >= ya) c.fill(x, ya, x + 1, yb + 1, col);
    }

    public static void line3(DrawContext c, Vec3d a, Vec3d b, int col) {
        double za = depth(a), zb = depth(b), n = 0.1;
        if (za < n && zb < n) return;
        Vec3d p = a, q = b;
        if (za < n) p = a.add(b.subtract(a).multiply((n - za) / (zb - za)));
        else if (zb < n) q = a.add(b.subtract(a).multiply((n - za) / (zb - za)));
        double[] s = to(p.x, p.y, p.z), e = to(q.x, q.y, q.z);
        if (s == null || e == null) return;
        line2(c, s[0], s[1], e[0], e[1], col);
    }
}
