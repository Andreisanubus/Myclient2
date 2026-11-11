
// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz.
// See docs/spectre-base-finder-port.md.

package dev.myclient.chunkfinder;

import java.util.*;
import java.util.function.ToIntFunction;

import net.minecraft.block.*;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

/**
 * Received-world evidence, distinct from the older chunk heat inference.
 * Each detection type has its own category and default ESP color.
 */
public final class SuspectObjects {

    private SuspectObjects() {}

    public enum Category {
        CONTAINERS("Containers", 0xFFFFCA54),
        ENDER_STORAGE("Ender chests", 0xFFB78AFF),
        HOPPERS("Hoppers", 0xFF51E5FF),
        MACHINES("Machines", 0xFFFF944D),
        REDSTONE("Redstone", 0xFFFF547D),
        SPAWNERS("Spawners", 0xFFFF63F1),
        DISPLAYS("Armor / displays", 0xFF70A6FF),

        KELP("Kelp", 0xFF35E66B),
        AMETHYST("Amethyst / Geodes", 0xFFB66BFF),
        BEE_NESTS("Bee nests / Beehives", 0xFFFFD34E),
        CAVE_VINES("Cave vines", 0xFF35C9D0),
        VINES("Vines", 0xFF7ED957),
        BAMBOO("Bamboo", 0xFF91F56B),
        COCOA("Cocoa", 0xFFB9824B),

        ANOMALIES("Light anomalies", 0xFFF2F57D),

        // Legacy categories kept for compatibility with older scanner code.
        GEODES("Geodes (legacy)", 0xFFD19BFF),
        GROWTH("Growth (legacy)", 0xFF91F56B);

        public final String label;
        public final int color;

        Category(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    public record Found(BlockPos pos, BlockState state, Category category) {}

    public record Icon(Item item, Identifier texture, String label) {
        public static Icon item(Item item) {
            return new Icon(item, null, new ItemStack(item).getName().getString());
        }
    }

    public static final Icon CAVE_VINES_ICON = new Icon(
            null,
            Identifier.of("minecraft", "textures/block/cave_vines.png"),
            "Cave vines"
    );

    public record Marker(
            Box bounds,
            List<Box> shapes,
            Category category,
            Map<Icon, Integer> icons,
            String label,
            String detail
    ) {
        public Marker(Box bounds, List<Box> shapes, Category category,
                      Map<Icon, Integer> icons, String label) {
            this(bounds, shapes, category, icons, label, "");
        }
    }

    public record Focus(Marker target, List<Marker> nearby, double distance) {}

    public static Category category(BlockState state) {
        Block block = state.getBlock();

        if (block instanceof EnderChestBlock) return Category.ENDER_STORAGE;

        if (block instanceof AbstractChestBlock<?>
                || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock) {
            return Category.CONTAINERS;
        }

        if (block instanceof HopperBlock) return Category.HOPPERS;

        if (block instanceof PistonBlock
                || block instanceof AbstractFurnaceBlock
                || block instanceof DispenserBlock
                || block instanceof ObserverBlock
                || block instanceof CrafterBlock
                || block instanceof BrewingStandBlock) {
            return Category.MACHINES;
        }

        if (block instanceof RedstoneWireBlock
                || block instanceof AbstractRedstoneGateBlock
                || block instanceof RedstoneTorchBlock
                || block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof AbstractPressurePlateBlock
                || block instanceof TripwireHookBlock
                || block == Blocks.REDSTONE_BLOCK
                || block == Blocks.REDSTONE_LAMP
                || block == Blocks.DAYLIGHT_DETECTOR
                || block == Blocks.TARGET) {
            return Category.REDSTONE;
        }

        if (block instanceof SpawnerBlock
                || block instanceof TrialSpawnerBlock) {
            return Category.SPAWNERS;
        }

        ActivityClues.Kind kind = ActivityClues.kind(state);
        if (kind != null) return category(kind);

        if (block == Blocks.AMETHYST_BLOCK
                || block == Blocks.BUDDING_AMETHYST) {
            return Category.AMETHYST;
        }

        return null;
    }

    public static Category category(ActivityClues.Kind kind) {
        if (kind == null) return null;

        return switch (kind) {
            case KELP -> Category.KELP;
            case CAVE_VINES -> Category.CAVE_VINES;
            case VINES -> Category.VINES;
            case AMETHYST -> Category.AMETHYST;
            case BAMBOO -> Category.BAMBOO;
            case COCOA -> Category.COCOA;
            case BEES -> Category.BEE_NESTS;
        };
    }

    public static Icon icon(BlockState state) {
        if (state.isOf(Blocks.REDSTONE_WIRE)) {
            return Icon.item(Items.REDSTONE);
        }

        if (state.isOf(Blocks.CAVE_VINES)
                || state.isOf(Blocks.CAVE_VINES_PLANT)) {
            return CAVE_VINES_ICON;
        }

        if (state.isOf(Blocks.KELP) || state.isOf(Blocks.KELP_PLANT)) {
            return Icon.item(Items.KELP);
        }

        if (state.isOf(Blocks.COCOA)) {
            return Icon.item(Items.COCOA_BEANS);
        }

        if (state.isOf(Blocks.BAMBOO) || state.isOf(Blocks.BAMBOO_SAPLING)) {
            return Icon.item(Items.BAMBOO);
        }

        Item item = state.getBlock().asItem();
        return Icon.item(item == Items.AIR ? Items.AMETHYST_SHARD : item);
    }

    public static Marker block(Found found, ClientWorld world) {
        VoxelShape shape = found.state().getOutlineShape(world, found.pos());

        List<Box> boxes = shape.isEmpty()
                ? List.of(new Box(found.pos()))
                : shape.getBoundingBoxes().stream()
                    .map(box -> box.offset(found.pos()))
                    .toList();

        Box bounds = boxes.getFirst();
        for (Box box : boxes) {
            bounds = bounds.union(box);
        }

        Icon icon = icon(found.state());

        return new Marker(
                bounds,
                List.of(bounds),
                found.category(),
                Map.of(icon, 1),
                icon.label()
        );
    }

    public static List<Marker> geodes(
            Map<BlockPos, Found> cells,
            int minimumClusters,
            int minimumMaturity,
            boolean includeImmature
    ) {
        Map<BlockPos, Found> remaining = new HashMap<>(cells);
        List<Marker> result = new ArrayList<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();

        while (!remaining.isEmpty()) {
            BlockPos start = remaining.keySet().iterator().next();
            todo.add(start);

            Box bounds = null;
            Map<Icon, Integer> counts = new HashMap<>();

            int mature = 0;
            int small = 0;
            int medium = 0;
            int large = 0;
            int budding = 0;

            while (!todo.isEmpty()) {
                BlockPos pos = todo.removeFirst();
                Found cell = remaining.remove(pos);
                if (cell == null) continue;

                BlockState state = cell.state();

                if (state.isOf(Blocks.AMETHYST_CLUSTER)) mature++;
                else if (state.isOf(Blocks.SMALL_AMETHYST_BUD)) small++;
                else if (state.isOf(Blocks.MEDIUM_AMETHYST_BUD)) medium++;
                else if (state.isOf(Blocks.LARGE_AMETHYST_BUD)) large++;

                if (state.isOf(Blocks.BUDDING_AMETHYST)) budding++;

                Box cellBox = new Box(pos);
                bounds = bounds == null ? cellBox : bounds.union(cellBox);
                counts.merge(icon(state), 1, Integer::sum);

                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = pos.offset(direction);
                    if (remaining.containsKey(neighbor)) {
                        todo.addLast(neighbor);
                    }
                }
            }

            int buds = small + medium + large;
            int maturity = mature + buds == 0
                    ? 0
                    : 100 * mature / (mature + buds);

            if (includeImmature || (budding > 0
                    && mature >= minimumClusters
                    && maturity >= minimumMaturity)) {
                result.add(new Marker(
                        bounds,
                        List.of(bounds),
                        Category.AMETHYST,
                        Map.copyOf(counts),
                        "Amethyst formation",
                        mature + " mature / " + buds + " buds - " + maturity
                                + "% mature\n" + small + " small / "
                                + medium + " medium / " + large + " large"
                ));
            }
        }

        return result;
    }

    public static List<Marker> plants(
            Map<BlockPos, Found> cells,
            ToIntFunction<ActivityClues.Kind> threshold,
            boolean includeShort
    ) {
        List<Marker> result = new ArrayList<>();

        for (Map.Entry<BlockPos, Found> entry : cells.entrySet()) {
            BlockPos start = entry.getKey();
            ActivityClues.Kind kind = ActivityClues.kind(entry.getValue().state());

            if (kind == null || kind == ActivityClues.Kind.AMETHYST
                    || kind == ActivityClues.Kind.BEES) {
                continue;
            }

            Found below = cells.get(start.down());
            if (below != null && ActivityClues.kind(below.state()) == kind) {
                continue;
            }

            Map<Icon, Integer> counts = new HashMap<>();
            int length = 0;
            BlockPos cursor = start;

            while (true) {
                Found cell = cells.get(cursor);
                if (cell == null || ActivityClues.kind(cell.state()) != kind) {
                    break;
                }

                counts.merge(icon(cell.state()), 1, Integer::sum);
                length++;
                cursor = cursor.up();
            }

            int required = threshold.applyAsInt(kind);
            if (!includeShort && length < required) continue;

            Box bounds = new Box(start).union(new Box(cursor.down()));

            result.add(new Marker(
                    bounds,
                    List.of(bounds),
                    category(kind),
                    Map.copyOf(counts),
                    kind.label + " growth",
                    length + " blocks long - threshold " + required
            ));
        }

        return result;
    }

    public static Focus focus(
            List<Marker> markers,
            Vec3d eye,
            Vec3d direction,
            double range
    ) {
        Marker best = null;
        double bestDistance = Double.MAX_VALUE;
        boolean exactBest = false;

        Vec3d end = eye.add(direction.multiply(range));

        for (Marker marker : markers) {
            Box bounds = marker.bounds();

            if (distanceSquared(bounds, eye) > range * range) continue;

            boolean hit = contains(bounds, eye)
                    || intersectsSegment(bounds, eye, end);

            double distance = Math.sqrt(distanceSquared(bounds, eye));

            if (!hit) {
                Box expanded = expand(
                        bounds,
                        Math.min(2.0, distance * 0.035) + 0.15
                );

                if (!intersectsSegment(expanded, eye, end)) continue;
            }

            if (best == null || (hit && !exactBest)
                    || (hit == exactBest && distance < bestDistance)) {
                best = marker;
                bestDistance = distance;
                exactBest = hit;
            }
        }

        if (best == null) return null;

        List<Marker> nearby = new ArrayList<>();
        Box neighborhood = expand(best.bounds(), 12.0);

        for (Marker marker : markers) {
            if (marker.bounds().intersects(neighborhood)
                    && distanceSquared(marker.bounds(), eye) <= range * range) {
                nearby.add(marker);
            }
        }

        return new Focus(
                best,
                List.copyOf(nearby),
                Math.sqrt(distanceSquared(best.bounds(), eye))
        );
    }

    private static Box expand(Box box, double amount) {
        return new Box(
                box.minX - amount,
                box.minY - amount,
                box.minZ - amount,
                box.maxX + amount,
                box.maxY + amount,
                box.maxZ + amount
        );
    }

    private static boolean contains(Box box, Vec3d point) {
        return point.x >= box.minX && point.x <= box.maxX
                && point.y >= box.minY && point.y <= box.maxY
                && point.z >= box.minZ && point.z <= box.maxZ;
    }

    private static double distanceSquared(Box box, Vec3d point) {
        double dx = Math.max(
                Math.max(box.minX - point.x, 0.0),
                point.x - box.maxX
        );
        double dy = Math.max(
                Math.max(box.minY - point.y, 0.0),
                point.y - box.maxY
        );
        double dz = Math.max(
                Math.max(box.minZ - point.z, 0.0),
                point.z - box.maxZ
        );

        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean intersectsSegment(Box box, Vec3d start, Vec3d end) {
        double tMin = 0.0;
        double tMax = 1.0;

        double[] origins = {start.x, start.y, start.z};
        double[] deltas = {
                end.x - start.x,
                end.y - start.y,
                end.z - start.z
        };
        double[] minimums = {box.minX, box.minY, box.minZ};
        double[] maximums = {box.maxX, box.maxY, box.maxZ};

        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(deltas[axis]) < 1.0E-9) {
                if (origins[axis] < minimums[axis]
                        || origins[axis] > maximums[axis]) {
                    return false;
                }
                continue;
            }

            double inverse = 1.0 / deltas[axis];
            double first = (minimums[axis] - origins[axis]) * inverse;
            double second = (maximums[axis] - origins[axis]) * inverse;

            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }

            tMin = Math.max(tMin, first);
            tMax = Math.min(tMax, second);

            if (tMin > tMax) return false;
        }

        return true;
    }
}
