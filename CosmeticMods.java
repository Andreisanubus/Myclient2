package dev.myclient.module.modules;

import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.ModeSetting;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
import dev.myclient.render.Fx;
import dev.myclient.render.Proj;
import dev.myclient.util.Colors;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import java.util.*;

public final class CosmeticMods {
    private CosmeticMods() {}

    private static final Random RNG = new Random();

    /** Particule proprii: explozie la pop de totem, dara la proiectile si praf la pasi. */
    public static class Particles extends Module {
        private final Setting<Boolean> pops, projectiles, steps;
        private final ModeSetting popColor, trailColor;
        private final NumberSetting amount, size, life;
        private int t;

        public Particles() {
            super("Particles", Category.VISUALS);
            header("Events");
            pops = bool("Totem Pops", true);
            projectiles = bool("Projectile Trails", true);
            steps = bool("Footsteps", false);
            header("Look");
            popColor = color("Pop Color", "Yellow");
            trailColor = color("Trail Color", "Cyan");
            amount = num("Amount", 14, 2, 40, 1);
            size = num("Size", 3, 1, 8, 1);
            life = num("Life (ticks)", 25, 8, 80, 1);
            popColor.visible = () -> pops.value;
            trailColor.visible = () -> projectiles.value || steps.value;
            PopTracker.LISTENERS.add(p -> { if (isEnabled() && pops.value) burst(p); });
        }

        private void burst(PlayerEntity p) {
            int col = Colors.of(popColor.value);
            for (int i = 0; i < amount.asInt() * 2; i++) {
                double a = RNG.nextDouble() * Math.PI * 2, up = 0.04 + RNG.nextDouble() * 0.09, sp = 0.03 + RNG.nextDouble() * 0.08;
                Fx.add(p.getX(), p.getY() + 1.0, p.getZ(), Math.cos(a) * sp, up, Math.sin(a) * sp, col, life.asInt(), size.get(), 0.006);
            }
        }

        @Override public void onTick() {
            if (mc.world == null || mc.player == null) return;
            t++;
            int col = Colors.of(trailColor.value);
            if (projectiles.value) {
                int n = 0;
                for (Entity e : mc.world.getEntities()) {
                    if (!(e instanceof ProjectileEntity) || n++ > 40) continue;
                    if (e.getVelocity().lengthSquared() < 0.01) continue;
                    Fx.add(e.getX(), e.getY(), e.getZ(), 0, 0, 0, col, Math.max(6, life.asInt() / 2), size.get() * 0.7, 0);
                }
            }
            if (steps.value && t % 3 == 0 && mc.player.isOnGround() && mc.player.getVelocity().horizontalLengthSquared() > 0.003) {
                Fx.add(mc.player.getX(), mc.player.getY() + 0.05, mc.player.getZ(), (RNG.nextDouble() - 0.5) * 0.02, 0.02, (RNG.nextDouble() - 0.5) * 0.02, col, 14, size.get() * 0.6, 0.002);
            }
        }
    }

    /** Fantoma care se ridica din jucatorul al carui totem a facut pop. */
    public static class TotemSpirit extends Module {
        private final NumberSetting count, life, size;
        private final ModeSetting colorSel;
        private final Setting<Boolean> self, others;

        public TotemSpirit() {
            super("Totem Spirit", Category.VISUALS);
            header("Who");
            self = bool("Yourself", true);
            others = bool("Other Players", true);
            header("Look");
            count = num("Particles", 70, 20, 160, 5);
            life = num("Duration (ticks)", 45, 15, 120, 5);
            size = num("Size", 4, 1, 8, 1);
            colorSel = color("Color", "Cyan");
            PopTracker.LISTENERS.add(p -> {
                if (!isEnabled()) return;
                boolean me = p == mc.player;
                if ((me && !self.value) || (!me && !others.value)) return;
                spirit(p);
            });
        }

        private void spirit(PlayerEntity p) {
            int col = Colors.of(colorSel.value), white = 0xFFFFFFFF;
            for (int i = 0; i < count.asInt(); i++) {
                double h = RNG.nextDouble() * 1.8;
                double w = h > 1.4 ? 0.22 : 0.32;           // cap mai ingust, corp mai lat, ca o fantoma
                double a = RNG.nextDouble() * Math.PI * 2, r = RNG.nextDouble() * w;
                Fx.add(p.getX() + Math.cos(a) * r, p.getY() + h, p.getZ() + Math.sin(a) * r,
                        0, 0.03 + RNG.nextDouble() * 0.03, 0, i % 3 == 0 ? white : Colors.mix(col, white, 0.4), life.asInt() + RNG.nextInt(15), size.get(), -0.0008);
            }
        }
    }

    /** Coroana de faraon (nemes) desenata in lume pe cap, cu dungi aurii si albastre. Vizibila in persoana a 3-a si cu freecam. */
    public static class PharaohHeaddress extends Module {
        private final ModeSetting gold, stripe;
        private final NumberSetting sizeS, lappet;
        private final Setting<Boolean> others, firstPerson;

        public PharaohHeaddress() {
            super("Pharaoh Headdress", Category.VISUALS);
            header("Look");
            gold = color("Main Color", "Yellow");
            stripe = color("Stripe Color", "Blue");
            sizeS = num("Size", 1.0, 0.7, 1.5, 0.05);
            lappet = num("Lappet Length", 0.7, 0.3, 1.3, 0.05);
            header("Who");
            others = bool("Other Players", true);
            firstPerson = bool("Show In First Person", false);
        }

        private record Poly(Vec3d[] pts, int col, double depth) {}

        @Override public void onRender(DrawContext c, int sw, int sh) {
            boolean fp = mc.options.getPerspective().isFirstPerson() && mc.getCameraEntity() == mc.player;
            for (PlayerEntity p : mc.world.getPlayers()) {
                boolean me = p == mc.player;
                if (!me && !others.value) continue;
                if (me && fp && !firstPerson.value) continue;
                if (mc.player.distanceTo(p) > 48) continue;
                draw(c, p);
            }
        }

        private void draw(DrawContext c, PlayerEntity p) {
            int g = Colors.of(gold.value), s = Colors.of(stripe.value);
            double yaw = Math.toRadians(p.getYaw()), sz = sizeS.get(), R = 0.30 * sz;
            double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
            double hy = p.getY() + (p.isSneaking() ? 1.35 : 1.5), hx = p.getX(), hz = p.getZ();
            List<Poly> polys = new ArrayList<>();
            // banda in jurul capului
            for (int k = 0; k < 8; k++) {
                double a0 = k * Math.PI / 4, a1 = (k + 1) * Math.PI / 4;
                Vec3d p0 = at(hx, hz, rx, rz, fx, fz, Math.cos(a0) * R, Math.sin(a0) * R), p1 = at(hx, hz, rx, rz, fx, fz, Math.cos(a1) * R, Math.sin(a1) * R);
                Vec3d[] q = {new Vec3d(p0.x, hy + 0.12 * sz, p0.z), new Vec3d(p1.x, hy + 0.12 * sz, p1.z), new Vec3d(p1.x, hy + 0.30 * sz, p1.z), new Vec3d(p0.x, hy + 0.30 * sz, p0.z)};
                polys.add(new Poly(q, k % 2 == 0 ? g : s, 0));
            }
            // capac
            Vec3d[] cap = new Vec3d[8];
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                Vec3d w = at(hx, hz, rx, rz, fx, fz, Math.cos(a) * R * 0.95, Math.sin(a) * R * 0.95);
                cap[k] = new Vec3d(w.x, hy + 0.31 * sz, w.z);
            }
            polys.add(new Poly(cap, g, 0));
            // doua lappete care atarna pe lateral, in dungi
            double len = lappet.get();
            for (int side = -1; side <= 1; side += 2) {
                double lx = side * R * 1.02;
                for (int seg = 0; seg < 6; seg++) {
                    double y0 = hy + 0.20 * sz - len * seg / 6.0, y1 = hy + 0.20 * sz - len * (seg + 1) / 6.0;
                    Vec3d a = at(hx, hz, rx, rz, fx, fz, lx, -0.10 * sz), b = at(hx, hz, rx, rz, fx, fz, lx, 0.20 * sz);
                    Vec3d[] q = {new Vec3d(a.x, y0, a.z), new Vec3d(b.x, y0, b.z), new Vec3d(b.x, y1, b.z), new Vec3d(a.x, y1, a.z)};
                    polys.add(new Poly(q, seg % 2 == 0 ? g : s, 0));
                }
            }
            List<Poly> sorted = new ArrayList<>();
            for (Poly pl : polys) {
                double d = 0;
                for (Vec3d v : pl.pts()) d += Proj.depth(v);
                sorted.add(new Poly(pl.pts(), pl.col(), d / pl.pts().length));
            }
            sorted.sort((a, b) -> Double.compare(b.depth(), a.depth()));
            for (Poly pl : sorted) Proj.fillPoly(c, pl.pts(), pl.col());
        }

        private static Vec3d at(double hx, double hz, double rx, double rz, double fx, double fz, double lx, double lz) {
            return new Vec3d(hx + lx * rx + lz * fx, 0, hz + lx * rz + lz * fz);
        }
    }

    /**
     * Weapon Inspect: schimba (doar pe clientul tau) sabia cu un Karambit si ruleaza o animatie de inspect.
     * Animatia e o secventa de 28 de modele, fiecare cu alta pozitie/rotatie in mana, schimbate la fiecare tick.
     */
    public static class WeaponInspect extends Module {
        public static final String[] FINISHES = {"Fade", "Slaughter", "Doppler", "Steel", "Gold"};
        private static final int FRAMES = 28;
        private final ModeSetting finish, key, scope;
        private final NumberSetting speed;
        private final Setting<Boolean> hold;
        private int frame = -1;
        private double acc;
        private boolean wasDown;

        public WeaponInspect() {
            super("Weapon Inspect", Category.VISUALS);
            header("Knife");
            finish = mode("Finish", "Fade", FINISHES);
            scope = mode("Apply To", "All Swords", "All Swords", "Held Sword");
            header("Inspect");
            key = mode("Inspect Key", "V", dev.myclient.util.Keys.CHOICES);
            speed = num("Animation Speed", 1.0, 0.4, 2.0, 0.1);
            hold = bool("Hold Key To Keep Spinning", true);
        }

        private static boolean isSword(ItemStack st) {
            return !st.isEmpty() && Registries.ITEM.getId(st.getItem()).getPath().endsWith("_sword");
        }

        private void set(ItemStack st, Identifier id) {
            if (!id.equals(st.get(DataComponentTypes.ITEM_MODEL))) st.set(DataComponentTypes.ITEM_MODEL, id);
        }

        private Identifier id(int f) {
            return Identifier.of("myclient", "knife_" + finish.value.toLowerCase(Locale.ROOT) + "_f" + String.format("%02d", Math.max(0, f)));
        }

        @Override public void onTick() {
            var p = mc.player;
            if (p == null) return;
            ItemStack held = p.getMainHandStack();
            boolean down = mc.currentScreen == null && dev.myclient.util.Keys.down(key.value);
            if (down && !wasDown && isSword(held) && frame < 0) { frame = 0; acc = 0; }
            wasDown = down;
            if (frame >= 0) {
                acc += speed.get();
                while (acc >= 1) { acc -= 1; frame++; }
                if (frame >= FRAMES) frame = (hold.value && down) ? 0 : -1;
            }
            for (int i = 0; i < p.getInventory().size(); i++) {
                ItemStack st = p.getInventory().getStack(i);
                if (!isSword(st)) continue;
                boolean isHeld = st == held;
                if (scope.is("All Swords") || isHeld) set(st, id(isHeld ? frame : 0));
                else set(st, Registries.ITEM.getId(st.getItem()));
            }
        }

        @Override protected void onDisable() {
            frame = -1;
            if (mc.player == null) return;
            for (int i = 0; i < mc.player.getInventory().size(); i++) {
                ItemStack st = mc.player.getInventory().getStack(i);
                if (isSword(st)) set(st, Registries.ITEM.getId(st.getItem()));
            }
        }
    }
}
