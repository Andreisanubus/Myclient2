
 // SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz.
// See docs/spectre-base-finder-port.md.

package dev.myclient.chunkfinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.client.world.ClientWorld;

/**
 * Received-world evidence, distinct from older chunk heat inference.
 */
public final class SuspectObjects {

    private SuspectObjects() {
    }

    public enum Category {
        CONTAINERS("Containers", 0xFFFFCA54),
        ENDER_STORAGE("Ender chests", 0xFFB78AFF),
        HOPPERS("Hoppers", 0xFF51E5FF),
        MACHINES("Machines", 0xFFFF944D),
        REDSTONE("Redstone", 0xFFFF547D),
        SPAWNERS("Spawners", 0xFFFF63F1),
        DISPLAYS("Armor / displays", 0xFF70A6FF),
        GEODES("Amethyst", 0xFFD19BFF),
        GROWTH("Growth / bees", 0xFF91F56B),
        ANOMALIES("Light anomalies", 0xFFF2F57D);

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

    public record Found(BlockPos pos, BlockState state, Category category) {
    }

    /**
     * Some evidence blocks have no inventory item.
     * Never substitute their drops as evidence.
     */
    public record Icon(Item item, Identifier texture, String label) {

        public static Icon item(Item item) {
            return new Icon(
                    item,
                    null,
                    new ItemStack(item).getName().getString()
            );
        }
    }

    public static final Icon CAVE_VINES_ICON = new Icon(
            null,
            Identifier.ofVanilla("textures/block/cave_vines.png"),
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
        public Marker(
                Box bounds,
                List<Box> shapes,
                Category category,
                Map<Icon, Integer> icons,
                String label
        ) {
            this(bounds, shapes, category, icons, label, "");
        }
    }

    public record Focus(Marker target, List<Marker> nearby, double distance) {
    }

    private static String blockId(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).getPath();
    }

    private static boolean isShulkerBox(String id) {
        return id.equals("shulker_box") || id.endsWith("_shulker_box");
    }

    private static boolean isButton(String id) {
        return id.endsWith("_button");
    }

    private static boolean isPressurePlate(String id) {
        return id.endsWith("_pressure_plate");
    }

    public static Category category(BlockState state) {
        String id = blockId(state);

        if (id.equals("ender_chest")) {
            return Category.ENDER_STORAGE;
        }

        if (id.equals("chest")
                || id.equals("trapped_chest")
                || id.equals("barrel")
                || isShulkerBox(id)) {
            return Category.CONTAINERS;
        }

        if (id.equals("hopper")) {
            return Category.HOPPERS;
        }

        if (id.equals("piston")
                || id.equals("sticky_piston")
                || id.equals("furnace")
                || id.equals("blast_furnace")
                || id.equals("smoker")
                || id.equals("dispenser")
                || id.equals("dropper")
                || id.equals("observer")
                || id.equals("crafter")
                || id.equals("brewing_stand")) {
            return Category.MACHINES;
        }

        if (id.equals("redstone_wire")
                || id.equals("repeater")
                || id.equals("comparator")
                || id.equals("redstone_torch")
                || id.equals("redstone_wall_torch")
                || id.equals("lever")
                || isButton(id)
                || isPressurePlate(id)
                || id.equals("tripwire_hook")
                || id.equals("redstone_block")
                || id.equals("redstone_lamp")
                || id.equals("daylight_detector")
                || id.equals("target")) {
            return Category.REDSTONE;
        }

        if (id.equals("spawner") || id.equals("trial_spawner")) {
            return Category.SPAWNERS;
        }

        if (id.equals("amethyst_block")
                || id.equals("budding_amethyst")
                || ActivityClues.kind(state) == ActivityClues.Kind.AMETHYST) {
            return Category.GEODES;
        }

        if (ActivityClues.kind(state) != null) {
            return Category.GROWTH;
        }

        return null;
    }

    public static Icon icon(BlockState state) {
        if (state.isOf(Blocks.REDSTONE_WIRE)) {
            return Icon.item(Items.REDSTONE);
        }

        if (state.isOf(Blocks.CAVE_VINES)
                || state.isOf(Blocks.CAVE_VINES_PLANT)) {
            return CAVE_VINES_ICON;
        }

        if (state.isOf(Blocks.KELP_PLANT)) {
            return Icon.item(Items.KELP);
        }

        if (state.isOf(Blocks.COCOA)) {
            return Icon.item(Items.COCOA_BEANS);
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

    /**
     * Join amethyst across chunk borders.
     * Outline the formation, never its chunk.
     */
    public static List<Marker> geodes(
            Map<BlockPos, Found> cells,
            int minimumClusters,
            int minimumMaturity,
            boolean includeImmature
    ) {
        var remaining = new HashMap<>(cells);
        var result = new ArrayList<Marker>();
        var todo = new ArrayDeque<BlockPos>();

        while (!remaining.isEmpty()) {
            BlockPos start = remaining.keySet().iterator().next();

            todo.add(start);

            Box bounds = null;
            var counts = new HashMap<Icon, Integer>();

            int mature = 0;
            int small = 0;
            int medium = 0;
            int large = 0;
            int budding = 0;

            while (!todo.isEmpty()) {
                BlockPos pos = todo.removeFirst();
                Found cell = remaining.remove(pos);

                if (cell == null) {
                    continue;
                }

                BlockState state = cell.state();

                if (state.isOf(Blocks.AMETHYST_CLUSTER)) {
                    mature++;
                } else if (state.isOf(Blocks.SMALL_AMETHYST_BUD)) {
                    small++;
                } else if (state.isOf(Blocks.MEDIUM_AMETHYST_BUD)) {
                    medium++;
                } else if (state.isOf(Blocks.LARGE_AMETHYST_BUD)) {
                    large++;
                }

                if (state.isOf(Blocks.BUDDING_AMETHYST)) {
                    budding++;
                }

                Box cellBox = new Box(pos);

                bounds = bounds == null
                        ? cellBox
                        : bounds.union(cellBox);

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

            if (includeImmature
                    || budding > 0
                    && mature >= minimumClusters
                    && maturity >= minimumMaturity) {

                result.add(new Marker(
                        bounds,
                        List.of(bounds),
                        Category.GEODES,
                        Map.copyOf(counts),
                        "Amethyst formation",
                        mature + " mature / " + buds
                                + " buds - " + maturity + "% mature\n"
                                + small + " small / "
                                + medium + " medium / "
                                + large + " large"
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
        var result = new ArrayList<Marker>();

        for (var entry : cells.entrySet()) {
            ActivityClues.Kind kind = ActivityClues.kind(entry.getValue().state());

            if (kind == null) {
                continue;
            }

            BlockPos start = entry.getKey();
            Found below = cells.get(start.down());

            if (below != null && ActivityClues.kind(below.state()) == kind) {
                continue;
            }

            var counts = new HashMap<Icon, Integer>();

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

            int minimumLength = threshold.applyAsInt(kind);

            if (!includeShort && length < minimumLength) {
                continue;
            }

            Box bounds = new Box(start).union(new Box(cursor.down()));

            result.add(new Marker(
                    bounds,
                    List.of(bounds),
                    Category.GROWTH,
                    Map.copyOf(counts),
                    kind.label + " growth",
                    length + " blocks long - threshold " + minimumLength
            ));
        }

        return result;
    }

    private static double distanceSquared(Box box, Vec3d point) {
        double x = Math.max(box.minX, Math.min(point.x, box.maxX));
        double y = Math.max(box.minY, Math.min(point.y, box.maxY));
        double z = Math.max(box.minZ, Math.min(point.z, box.maxZ));

        return point.squaredDistanceTo(new Vec3d(x, y, z));
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

            if (distanceSquared(bounds, eye) > range * range) {
                continue;
            }

            var exact = bounds.raycast(eye, end);

            boolean hit = exact.isPresent() || bounds.contains(eye);

            var close = hit
                    ? exact
                    : bounds.expand(
                            Math.min(2, Math.sqrt(distanceSquared(bounds, eye)) * 0.035) + 0.15
                    ).raycast(eye, end);

            if (!hit && close.isEmpty()) {
                continue;
            }

            double distance = close
                    .map(eye::distanceTo)
                    .orElse(0.0);

            if (best == null
                    || hit && !exactBest
                    || hit == exactBest && distance < bestDistance) {

                best = marker;
                bestDistance = distance;
                exactBest = hit;
            }
        }

        if (best == null) {
            return null;
        }

        var nearby = new ArrayList<Marker>();
        Box neighborhood = best.bounds().expand(12);

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
}
