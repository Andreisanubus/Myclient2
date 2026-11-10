package dev.myclient.module.modules;

import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.ModeSetting;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
import dev.myclient.render.Proj;
import dev.myclient.util.Colors;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.Random;

/**
 * Teme de cer desenate in directii fixe din lume (raman pe loc cand rotesti camera). Se vad doar unde cerul nu e acoperit de blocuri.
 * Nu inlocuiesc cerul real al jocului: sunt desenate peste imagine, deci arata cel mai bine noaptea sau cu cer liber.
 */
public class Atmosphere extends Module {
    private static final double D = 300;
    private static final int NSTARS = 240;

    private final ModeSetting theme;
    private final NumberSetting intensity, size, azimuth, elevation, speed;
    private final Setting<Boolean> stars;
    private final double[][] star = new double[NSTARS][];
    private final boolean[] vis = new boolean[NSTARS];
    private int upd;

    public Atmosphere() {
        super("Atmosphere", Category.VISUALS);
        header("Theme");
        theme = mode("Theme", "Space", "Space", "Northern Lights", "Ringed Planet", "Black Hole");
        intensity = num("Intensity %", 70, 10, 100, 5);
        size = num("Size", 1.0, 0.5, 2.5, 0.1);
        speed = num("Animation Speed", 1.0, 0.2, 3.0, 0.1);
        header("Position");
        azimuth = num("Direction (deg)", 40, 0, 360, 5);
        elevation = num("Height (deg)", 35, 8, 80, 1);
        header("Sky");
        stars = bool("Stars", true);
        Random r = new Random(1234);
        for (int i = 0; i < NSTARS; i++) star[i] = new double[]{r.nextDouble() * 360, Math.toDegrees(Math.asin(r.nextDouble())), 0.4 + r.nextDouble() * 0.6};
    }

    private static Vec3d dir(double azDeg, double elDeg) {
        double a = Math.toRadians(azDeg), e = Math.toRadians(elDeg);
        return new Vec3d(-Math.sin(a) * Math.cos(e), Math.sin(e), Math.cos(a) * Math.cos(e));
    }

    private boolean clear(Vec3d d) {
        Vec3d cam = Proj.camPos();
        for (int i = 1; i <= 24; i++) {
            Vec3d p = cam.add(d.multiply(i * 5.0));
            if (!mc.world.getBlockState(BlockPos.ofFloored(p)).isAir()) return false;
        }
        return true;
    }

    private static Vec3d[] basis(Vec3d d) {
        Vec3d right = new Vec3d(0, 1, 0).crossProduct(d).normalize();
        Vec3d up = d.crossProduct(right).normalize();
        return new Vec3d[]{right, up};
    }

    private int a(double k) { return (int) (255 * intensity.get() / 100.0 * k); }

    private static Vec3d on(Vec3d ctr, Vec3d r, Vec3d u, double x, double y) { return ctr.add(r.multiply(x)).add(u.multiply(y)); }

    private void disc(DrawContext c, Vec3d ctr, Vec3d r, Vec3d u, double rad, double ox, double oy, int argb) {
        Vec3d[] pts = new Vec3d[28];
        for (int i = 0; i < 28; i++) {
            double t = i * Math.PI * 2 / 28;
            pts[i] = on(ctr, r, u, ox + Math.cos(t) * rad, oy + Math.sin(t) * rad);
        }
        Proj.fillPoly(c, pts, argb);
    }

    /** Inel (elipsa) in benzi de culoare. front=true deseneaza jumatatea din fata, false pe cea din spate. */
    private void ring(DrawContext c, Vec3d ctr, Vec3d r, Vec3d u, double r1, double r2, double tilt, double sq, boolean front, int[] bands) {
        double ct = Math.cos(tilt), st = Math.sin(tilt);
        int seg = 48, nb = bands.length;
        for (int j = 0; j < seg; j++) {
            double t0 = j * Math.PI * 2 / seg, t1 = (j + 1) * Math.PI * 2 / seg;
            if (front != (Math.sin((t0 + t1) / 2) < 0)) continue;
            for (int b = 0; b < nb; b++) {
                double ra = r1 + (r2 - r1) * b / nb, rb = r1 + (r2 - r1) * (b + 1) / nb;
                Vec3d[] q = {pt(ctr, r, u, t0, ra, ct, st, sq), pt(ctr, r, u, t1, ra, ct, st, sq), pt(ctr, r, u, t1, rb, ct, st, sq), pt(ctr, r, u, t0, rb, ct, st, sq)};
                Proj.fillPoly(c, q, bands[b]);
            }
        }
    }

    private static Vec3d pt(Vec3d ctr, Vec3d r, Vec3d u, double t, double R, double ct, double st, double sq) {
        double x = (Math.cos(t) * ct - Math.sin(t) * sq * st) * R, y = (Math.cos(t) * st + Math.sin(t) * sq * ct) * R;
        return on(ctr, r, u, x, y);
    }

    @Override public void onRender(DrawContext c, int sw, int sh) {
        if (mc.world == null) return;
        double t = System.currentTimeMillis() / 1000.0 * speed.get();
        Vec3d cam = Proj.camPos();
        if (stars.value || theme.is("Space")) drawStars(c, cam, t);
        switch (theme.value) {
            case "Space" -> space(c, cam, t);
            case "Northern Lights" -> aurora(c, cam, t);
            case "Ringed Planet" -> ringed(c, cam);
            default -> blackHole(c, cam, t);
        }
    }

    private void drawStars(DrawContext c, Vec3d cam, double t) {
        for (int k = 0; k < 20; k++) { int i = (upd++) % NSTARS; vis[i] = clear(dir(star[i][0], star[i][1])); }
        for (int i = 0; i < NSTARS; i++) {
            if (!vis[i]) continue;
            Vec3d p = cam.add(dir(star[i][0], star[i][1]).multiply(D));
            double[] s = Proj.to(p.x, p.y, p.z);
            if (s == null) continue;
            double tw = 0.55 + 0.45 * Math.sin(t * 1.5 + i);
            int sz = star[i][2] > 0.8 ? 2 : 1, x = (int) s[0], y = (int) s[1];
            c.fill(x, y, x + sz, y + sz, Proj.alpha(0xFFFFFFFF, a(tw * star[i][2])));
        }
    }

    private void space(DrawContext c, Vec3d cam, double t) {
        double[][] neb = {{60, 30, 0xFF7C3AED}, {200, 45, 0xFF2563EB}, {320, 25, 0xFFDB2777}};
        for (double[] n : neb) {
            Vec3d d = dir(n[0], n[1]);
            if (!clear(d)) continue;
            Vec3d ctr = cam.add(d.multiply(D)), r[] = basis(d);
            double rad = D * Math.tan(Math.toRadians(14 * size.get()));
            disc(c, ctr, r[0], r[1], rad, 0, 0, Proj.alpha((int) n[2], a(0.10)));
            disc(c, ctr, r[0], r[1], rad * 0.65, 0.03 * rad, 0.02 * rad, Proj.alpha((int) n[2], a(0.12)));
            disc(c, ctr, r[0], r[1], rad * 0.3, 0, 0, Proj.alpha(0xFFFFFFFF, a(0.08)));
        }
        long slot = (long) (t / 4);                               // o stea cazatoare la fiecare ~4 secunde
        double prog = (t % 4) / 0.8;
        if (prog < 1) {
            Random rg = new Random(slot);
            double az = rg.nextDouble() * 360, el = 25 + rg.nextDouble() * 40, daz = (rg.nextBoolean() ? 1 : -1) * (20 + rg.nextDouble() * 20), del = -(8 + rg.nextDouble() * 10);
            if (clear(dir(az, el))) {
                for (int i = 0; i < 10; i++) {
                    double u = Math.max(0, prog - i * 0.025);
                    Vec3d p = cam.add(dir(az + daz * u, el + del * u).multiply(D));
                    double[] s = Proj.to(p.x, p.y, p.z);
                    if (s != null) c.fill((int) s[0], (int) s[1], (int) s[0] + 2, (int) s[1] + 2, Proj.alpha(0xFFFFFFFF, a((1 - i / 10.0) * (1 - prog * 0.5))));
                }
            }
        }
    }

    private void ringed(DrawContext c, Vec3d cam) {
        Vec3d d = dir(azimuth.get(), elevation.get());
        if (!clear(d)) return;
        Vec3d ctr = cam.add(d.multiply(D)), r[] = basis(d);
        double rad = D * Math.tan(Math.toRadians(6 * size.get()));
        int[] bands = {Proj.alpha(0xFFFDE68A, a(0.55)), Proj.alpha(0xFFD6B36A, a(0.65)), Proj.alpha(0xFF9C7B45, a(0.5)), Proj.alpha(0xFFE9D9A8, a(0.6))};
        double tilt = Math.toRadians(18);
        ring(c, ctr, r[0], r[1], rad * 1.25, rad * 2.15, tilt, 0.26, false, bands);
        disc(c, ctr, r[0], r[1], rad, 0, 0, Proj.alpha(0xFFB45309, a(1.0)));
        disc(c, ctr, r[0], r[1], rad * 0.82, -0.1 * rad, 0.1 * rad, Proj.alpha(0xFFF59E0B, a(0.9)));
        for (double h : new double[]{-0.4, 0.0, 0.4}) {
            double w = Math.sqrt(Math.max(0, 1 - h * h)) * rad, hy = h * rad, th = 0.08 * rad;
            Proj.fillPoly(c, new Vec3d[]{on(ctr, r[0], r[1], -w, hy - th), on(ctr, r[0], r[1], w, hy - th), on(ctr, r[0], r[1], w, hy + th), on(ctr, r[0], r[1], -w, hy + th)}, Proj.alpha(0xFF7C2D12, a(0.35)));
        }
        ring(c, ctr, r[0], r[1], rad * 1.25, rad * 2.15, tilt, 0.26, true, bands);
    }

    private void blackHole(DrawContext c, Vec3d cam, double t) {
        Vec3d d = dir(azimuth.get(), elevation.get());
        if (!clear(d)) return;
        Vec3d ctr = cam.add(d.multiply(D)), r[] = basis(d);
        double rad = D * Math.tan(Math.toRadians(5 * size.get()));
        double pulse = 0.9 + 0.1 * Math.sin(t * 2);
        int[] glow = {Proj.alpha(0xFFFB923C, a(0.10)), Proj.alpha(0xFFF97316, a(0.16))};
        int[] disk = {Proj.alpha(0xFFFFF7ED, a(0.85 * pulse)), Proj.alpha(0xFFFDBA74, a(0.8)), Proj.alpha(0xFFF97316, a(0.65)), Proj.alpha(0xFFB91C1C, a(0.45))};
        double tilt = Math.toRadians(12);
        ring(c, ctr, r[0], r[1], rad * 1.0, rad * 2.8, tilt, 0.22, false, disk);
        disc(c, ctr, r[0], r[1], rad * 1.5, 0, 0, glow[0]);
        disc(c, ctr, r[0], r[1], rad * 1.2, 0, 0, glow[1]);
        disc(c, ctr, r[0], r[1], rad, 0, 0, 0xFF000000);
        ring(c, ctr, r[0], r[1], rad * 1.0, rad * 2.8, tilt, 0.22, true, disk);
        ring(c, ctr, r[0], r[1], rad * 1.02, rad * 1.18, 0, 1.0, true, new int[]{Proj.alpha(0xFFFFEDD5, a(0.8 * pulse))});
        ring(c, ctr, r[0], r[1], rad * 1.02, rad * 1.18, 0, 1.0, false, new int[]{Proj.alpha(0xFFFFEDD5, a(0.8 * pulse))});
    }

    private void aurora(DrawContext c, Vec3d cam, double t) {
        int[] low = {Colors.of("Green"), 0xFF34D399, 0xFF4ADE80}, high = {0xFF22D3EE, 0xFFA78BFA, 0xFF2DD4BF};
        for (int k = 0; k < 3; k++) {
            double az0 = azimuth.get() - 35 + (k - 1) * 18, span = 70, e0 = elevation.get() * 0.55 + 5 * k, hgt = 22 * size.get();
            int steps = 24;
            for (int i = 0; i < steps; i++) {
                double u0 = (double) i / steps, u1 = (double) (i + 1) / steps;
                double w0 = Math.sin(t * 0.8 + u0 * 6 + k * 2) * 5, w1 = Math.sin(t * 0.8 + u1 * 6 + k * 2) * 5;
                double h0 = 1 + 0.3 * Math.sin(t * 0.6 + u0 * 9 + k), h1 = 1 + 0.3 * Math.sin(t * 0.6 + u1 * 9 + k);
                Vec3d mid = dir(az0 + span * (u0 + u1) / 2, e0 + hgt * 0.5);
                if (!clear(mid)) continue;
                Vec3d b0 = cam.add(dir(az0 + span * u0 + w0, e0 + w0 * 0.4).multiply(D)), b1 = cam.add(dir(az0 + span * u1 + w1, e0 + w1 * 0.4).multiply(D));
                Vec3d m0 = cam.add(dir(az0 + span * u0 + w0, e0 + hgt * 0.5 * h0).multiply(D)), m1 = cam.add(dir(az0 + span * u1 + w1, e0 + hgt * 0.5 * h1).multiply(D));
                Vec3d t0 = cam.add(dir(az0 + span * u0 + w0, e0 + hgt * h0).multiply(D)), t1 = cam.add(dir(az0 + span * u1 + w1, e0 + hgt * h1).multiply(D));
                double edge = Math.sin(Math.PI * (u0 + u1) / 2);       // marginile benzii se estompeaza
                Proj.fillPoly(c, new Vec3d[]{b0, b1, m1, m0}, Proj.alpha(low[k], a(0.42 * edge)));
                Proj.fillPoly(c, new Vec3d[]{m0, m1, t1, t0}, Proj.alpha(high[k], a(0.20 * edge)));
            }
        }
    }
}
