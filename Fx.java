package dev.myclient.render;

import net.minecraft.client.gui.DrawContext;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Particule proprii, ancorate in lume (se deseneaza prin Proj, deci raman pe loc cand rotesti camera). */
public final class Fx {
    private static final class P { double x, y, z, vx, vy, vz, size, grav; int col, life, max; }
    private static final List<P> list = new ArrayList<>();

    private Fx() {}

    public static void add(double x, double y, double z, double vx, double vy, double vz, int col, int life, double size, double grav) {
        if (list.size() > 1500) return;
        P p = new P();
        p.x = x; p.y = y; p.z = z; p.vx = vx; p.vy = vy; p.vz = vz; p.col = col; p.life = p.max = Math.max(1, life); p.size = size; p.grav = grav;
        list.add(p);
    }

    public static void clear() { list.clear(); }

    public static void tick() {
        for (Iterator<P> it = list.iterator(); it.hasNext(); ) {
            P p = it.next();
            p.x += p.vx; p.y += p.vy; p.z += p.vz;
            p.vy -= p.grav; p.vx *= 0.97; p.vz *= 0.97;
            if (--p.life <= 0) it.remove();
        }
    }

    public static void render(DrawContext c) {
        for (P p : list) {
            double[] s = Proj.to(p.x, p.y, p.z);
            if (s == null) continue;
            double k = (double) p.life / p.max;
            int sz = (int) Math.max(1, Math.min(12, p.size * 5 / s[2]));
            int x = (int) s[0] - sz / 2, y = (int) s[1] - sz / 2;
            c.fill(x, y, x + sz, y + sz, Proj.alpha(p.col, (int) (255 * Math.min(1, k * 1.4))));
        }
    }
}
