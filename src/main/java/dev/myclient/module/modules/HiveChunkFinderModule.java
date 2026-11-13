package dev.myclient.module.modules;

import dev.myclient.module.Category;
import dev.myclient.module.Module;
import dev.myclient.module.ModeSetting;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
import dev.myclient.render.Proj;
import dev.myclient.util.Colors;

import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Multi-category loaded-chunk finder for Fabric 1.21.11.
 * It only examines chunks already delivered to the client by the server.
 */
public final class HiveChunkFinderModule extends Module {
    private static final int SETTLE_TICKS = 200;
    private static final int CHUNKS_PER_TICK = 8;
    private static final int BASE_Y = 55;
    private static final long REVEAL_NANOS = 350_000_000L;

    private static final Set<HiveChunkFinderModule> INSTANCES =
            new CopyOnWriteArraySet<>();

    private final NumberSetting mergeRadius = num("Merge Radius", 4, 1, 8, 1);
    private final NumberSetting scanRadius = num("Scan Radius", 12, 2, 32, 1);
    private final NumberSetting minimumHoney = num("Minimum Honey Level", 5, 1, 5, 1);

    private final Setting<Boolean> detectNests = bool("Detect Bee Nests", true);
    private final Setting<Boolean> detectHives = bool("Detect Beehives", true);
    private final NumberSetting minContainers = num("Min Containers", 3, 1, 64, 1);
    private final Setting<Boolean> detectContainers = bool("Detect Chests / Barrels / Shulkers", true);
    private final NumberSetting minHoppers = num("Min Hoppers", 4, 1, 64, 1);
    private final Setting<Boolean> detectHoppers = bool("Detect Hopper Clusters", true);
    private final NumberSetting minMachines = num("Min Machines", 3, 1, 64, 1);
    private final Setting<Boolean> detectMachines = bool("Detect Machines", true);
    private final NumberSetting minRedstone = num("Min Redstone Components", 8, 1, 128, 1);
    private final Setting<Boolean> detectRedstone = bool("Detect Redstone Networks", true);
    private final NumberSetting minSpawners = num("Min Spawners", 1, 1, 16, 1);
    private final Setting<Boolean> detectSpawners = bool("Detect Spawners", true);
    private final NumberSetting minPlants = num("Min Growth Blocks", 8, 1, 128, 1);
    private final Setting<Boolean> detectKelp = bool("Detect Mature Kelp", true);
    private final Setting<Boolean> detectVines = bool("Detect Vines", true);
    private final Setting<Boolean> detectCaveVines = bool("Detect Cave Vines", true);
    private final Setting<Boolean> detectBamboo = bool("Detect Bamboo", true);
    private final Setting<Boolean> detectCocoa = bool("Detect Cocoa Pods", true);
    private final NumberSetting minAmethyst = num("Min Budding Amethyst", 4, 1, 64, 1);
    private final Setting<Boolean> detectAmethyst = bool("Detect Amethyst Geodes", true);
    private final Setting<Boolean> notifications = bool("Notifications", true);

    private final ModeSetting markerColor = color("Color", "Orange");
    private final Setting<Boolean> beam = bool("Beam", false);
    private final Setting<Boolean> animatedMarker = bool("Animated Marker", true);
    private final NumberSetting fillOpacity = num("Fill Opacity", 10, 0, 60, 5);
    private final NumberSetting outlineOpacity = num("Outline Opacity", 90, 10, 100, 5);
    private final NumberSetting markerHeight = num("Marker Height Offset", 0.025, 0.0, 2.0, 0.025);

    private final Map<Long, Long> firstSeenTick = new HashMap<>();
    private final Set<Long> checked = new HashSet<>();
    private final Map<Long, HitChunk> hits = new HashMap<>();
    private final List<Long> scanOrder = new ArrayList<>();
    private List<Cluster> clusters = List.of();

    private ClientWorld activeWorld;
    private int centerChunkX = Integer.MIN_VALUE;
    private int centerChunkZ = Integer.MIN_VALUE;
    private int activeViewDistance = -1;
    private int activeMergeRadius = -1;
    private int scanCursor;
    private long tick;

    private record HitChunk(int x, int z, long foundAtNanos, String details) {}
    private record Cluster(int minX, int maxX, int minZ, int maxZ,
                           double centerX, double centerZ,
                           long createdAtNanos, String details) {}

    public HiveChunkFinderModule() {
        super("Chunk Finder", Category.BASE_RAID);
        header("Scan");
        add(mergeRadius);
        add(scanRadius);
        add(notifications);

        header("Bee Structures");
        add(detectNests);
        add(detectHives);
        add(minimumHoney);

        header("Storage And Utility");
        add(detectContainers);
        add(minContainers);
        add(detectHoppers);
        add(minHoppers);
        add(detectMachines);
        add(minMachines);

        header("Redstone And Spawners");
        add(detectRedstone);
        add(minRedstone);
        add(detectSpawners);
        add(minSpawners);

        header("Natural Structures");
        add(detectKelp);
        add(detectVines);
        add(detectCaveVines);
        add(detectBamboo);
        add(detectCocoa);
        add(minPlants);
        add(detectAmethyst);
        add(minAmethyst);

        header("Marker");
        add(markerColor);
        add(beam);
        add(animatedMarker);
        add(fillOpacity);
        add(outlineOpacity);
        add(markerHeight);
        INSTANCES.add(this);
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        firstSeenTick.clear();
        checked.clear();
        hits.clear();
        scanOrder.clear();
        clusters = List.of();
        activeWorld = null;
        centerChunkX = Integer.MIN_VALUE;
        centerChunkZ = Integer.MIN_VALUE;
        activeViewDistance = -1;
        activeMergeRadius = -1;
        scanCursor = 0;
        tick = 0;
    }

    public static void blockUpdated(ClientWorld world, net.minecraft.util.math.BlockPos pos) {
        if (world == null || pos == null) return;
        long key = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        for (HiveChunkFinderModule module : INSTANCES) {
            if (!module.isEnabled() || module.activeWorld != world) continue;
            module.checked.remove(key);
            module.firstSeenTick.put(key, module.tick);
        }
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) {
            if (activeWorld != null) reset();
            return;
        }

        if (activeWorld != mc.world) {
            reset();
            activeWorld = mc.world;
        }

        tick++;
        int playerChunkX = mc.player.getChunkPos().x;
        int playerChunkZ = mc.player.getChunkPos().z;
        int viewDistance = Math.max(2, Math.min(
                scanRadius.asInt(),
                mc.options.getViewDistance().getValue()
        ));

        if (playerChunkX != centerChunkX || playerChunkZ != centerChunkZ
                || viewDistance != activeViewDistance) {
            rebuildScanOrder(playerChunkX, playerChunkZ, viewDistance);
        }

        int radius = mergeRadius.asInt();
        if (radius != activeMergeRadius) {
            activeMergeRadius = radius;
            rebuildClusters();
        }

        if (scanOrder.isEmpty()) return;

        boolean changed = false;
        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            if (scanCursor >= scanOrder.size()) scanCursor = 0;
            long key = scanOrder.get(scanCursor++);
            int chunkX = ChunkPos.getPackedX(key);
            int chunkZ = ChunkPos.getPackedZ(key);

            if (!mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) continue;

            long first = firstSeenTick.computeIfAbsent(key, ignored -> tick);
            if (tick - first < SETTLE_TICKS || checked.contains(key)) continue;

            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkX, chunkZ);
            if (chunk == null) continue;

            checked.add(key);
            List<String> detections = scanChunk(chunk);
            if (!detections.isEmpty()) {
                String details = String.join(", ", detections);
                hits.put(key, new HitChunk(chunkX, chunkZ, System.nanoTime(), details));
                changed = true;
                if (notifications.value) {
                    mc.player.sendMessage(Text.literal(
                            "[Chunk Finder] " + details + " in chunk " + chunkX + ", " + chunkZ
                    ), false);
                }
            }
        }

        if (changed) rebuildClusters();
    }

    private List<String> scanChunk(WorldChunk chunk) {
        int containers = 0;
        int hoppers = 0;
        int machines = 0;
        int redstone = 0;
        int spawners = 0;
        int matureKelp = 0;
        int vines = 0;
        int caveVines = 0;
        int bamboo = 0;
        int cocoa = 0;
        int buddingAmethyst = 0;
        boolean fullNest = false;
        boolean fullHive = false;

        for (ChunkSection section : chunk.getSectionArray()) {
            if (section == null || section.isEmpty()) continue;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;

                        if (state.isOf(Blocks.BEE_NEST)
                                && state.get(BeehiveBlock.HONEY_LEVEL) >= minimumHoney.asInt()) {
                            fullNest = true;
                        }
                        if (state.isOf(Blocks.BEEHIVE)
                                && state.get(BeehiveBlock.HONEY_LEVEL) >= minimumHoney.asInt()) {
                            fullHive = true;
                        }

                        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)
                                || state.isOf(Blocks.BARREL) || state.isOf(Blocks.SHULKER_BOX)
                                || state.isOf(Blocks.ENDER_CHEST)) containers++;

                        if (state.isOf(Blocks.HOPPER)) hoppers++;

                        if (state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE)
                                || state.isOf(Blocks.SMOKER) || state.isOf(Blocks.DISPENSER)
                                || state.isOf(Blocks.DROPPER) || state.isOf(Blocks.OBSERVER)
                                || state.isOf(Blocks.PISTON) || state.isOf(Blocks.STICKY_PISTON)
                                || state.isOf(Blocks.CRAFTER) || state.isOf(Blocks.BREWING_STAND)) {
                            machines++;
                        }

                        if (state.isOf(Blocks.REDSTONE_WIRE) || state.isOf(Blocks.REPEATER)
                                || state.isOf(Blocks.COMPARATOR) || state.isOf(Blocks.REDSTONE_TORCH)
                                || state.isOf(Blocks.REDSTONE_WALL_TORCH) || state.isOf(Blocks.LEVER)
                                || state.isOf(Blocks.STONE_BUTTON) || state.isOf(Blocks.OAK_BUTTON)
                                || state.isOf(Blocks.STONE_PRESSURE_PLATE)
                                || state.isOf(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE)
                                || state.isOf(Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE)
                                || state.isOf(Blocks.REDSTONE_LAMP) || state.isOf(Blocks.DAYLIGHT_DETECTOR)
                                || state.isOf(Blocks.TARGET) || state.isOf(Blocks.TRIPWIRE_HOOK)) {
                            redstone++;
                        }

                        if (state.isOf(Blocks.SPAWNER) || state.isOf(Blocks.TRIAL_SPAWNER)) spawners++;
                        if (state.isOf(Blocks.KELP) && state.get(Properties.AGE_25) == 25) matureKelp++;
                        if (state.isOf(Blocks.VINE)) vines++;
                        if (state.isOf(Blocks.CAVE_VINES) || state.isOf(Blocks.CAVE_VINES_PLANT)) caveVines++;
                        if (state.isOf(Blocks.BAMBOO)) bamboo++;
                        if (state.isOf(Blocks.COCOA)) cocoa++;
                        if (state.isOf(Blocks.BUDDING_AMETHYST)) buddingAmethyst++;
                    }
                }
            }
        }

        List<String> found = new ArrayList<>();
        if (detectNests.value && fullNest) found.add("Full Bee Nest");
        if (detectHives.value && fullHive) found.add("Full Beehive");
        if (detectContainers.value && containers >= minContainers.asInt()) found.add("Storage x" + containers);
        if (detectHoppers.value && hoppers >= minHoppers.asInt()) found.add("Hoppers x" + hoppers);
        if (detectMachines.value && machines >= minMachines.asInt()) found.add("Machines x" + machines);
        if (detectRedstone.value && redstone >= minRedstone.asInt()) found.add("Redstone x" + redstone);
        if (detectSpawners.value && spawners >= minSpawners.asInt()) found.add("Spawners x" + spawners);

        int plantTotal = 0;
        if (detectKelp.value) plantTotal += matureKelp;
        if (detectVines.value) plantTotal += vines;
        if (detectCaveVines.value) plantTotal += caveVines;
        if (detectBamboo.value) plantTotal += bamboo;
        if (detectCocoa.value) plantTotal += cocoa;
        if (plantTotal >= minPlants.asInt()) {
            List<String> plants = new ArrayList<>();
            if (detectKelp.value && matureKelp > 0) plants.add("Kelp " + matureKelp);
            if (detectVines.value && vines > 0) plants.add("Vines " + vines);
            if (detectCaveVines.value && caveVines > 0) plants.add("Cave Vines " + caveVines);
            if (detectBamboo.value && bamboo > 0) plants.add("Bamboo " + bamboo);
            if (detectCocoa.value && cocoa > 0) plants.add("Cocoa " + cocoa);
            found.add("Growth: " + String.join("/", plants));
        }

        if (detectAmethyst.value && buddingAmethyst >= minAmethyst.asInt()) {
            found.add("Amethyst Geode (" + buddingAmethyst + " budding blocks)");
        }

        return found;
    }

    private void rebuildScanOrder(int centerX, int centerZ, int viewDistance) {
        centerChunkX = centerX;
        centerChunkZ = centerZ;
        activeViewDistance = viewDistance;
        scanCursor = 0;
        scanOrder.clear();

        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                scanOrder.add(ChunkPos.toLong(centerX + dx, centerZ + dz));
            }
        }

        scanOrder.sort(Comparator.comparingLong(key -> {
            long dx = (long) ChunkPos.getPackedX(key) - centerX;
            long dz = (long) ChunkPos.getPackedZ(key) - centerZ;
            return dx * dx + dz * dz;
        }));

        firstSeenTick.keySet().removeIf(key ->
                Math.abs(ChunkPos.getPackedX(key) - centerX) > viewDistance
                        || Math.abs(ChunkPos.getPackedZ(key) - centerZ) > viewDistance);
        checked.removeIf(key ->
                Math.abs(ChunkPos.getPackedX(key) - centerX) > viewDistance
                        || Math.abs(ChunkPos.getPackedZ(key) - centerZ) > viewDistance);

        boolean removed = hits.keySet().removeIf(key ->
                Math.abs(ChunkPos.getPackedX(key) - centerX) > viewDistance
                        || Math.abs(ChunkPos.getPackedZ(key) - centerZ) > viewDistance);
        if (removed) rebuildClusters();
    }

    private void rebuildClusters() {
        if (hits.isEmpty()) {
            clusters = List.of();
            return;
        }

        int radius = Math.max(1, Math.min(8, mergeRadius.asInt()));
        Set<Long> remaining = new HashSet<>(hits.keySet());
        ArrayDeque<Long> queue = new ArrayDeque<>();
        List<Cluster> rebuilt = new ArrayList<>();

        while (!remaining.isEmpty()) {
            long start = remaining.iterator().next();
            remaining.remove(start);
            queue.addLast(start);

            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            long sumX = 0;
            long sumZ = 0;
            int count = 0;
            LinkedHashSet<String> details = new LinkedHashSet<>();

            while (!queue.isEmpty()) {
                long current = queue.removeFirst();
                HitChunk hit = hits.get(current);
                if (hit == null) continue;

                minX = Math.min(minX, hit.x());
                maxX = Math.max(maxX, hit.x());
                minZ = Math.min(minZ, hit.z());
                maxZ = Math.max(maxZ, hit.z());
                sumX += hit.x();
                sumZ += hit.z();
                count++;
                details.addAll(List.of(hit.details().split(", ")));

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        long neighbor = ChunkPos.toLong(hit.x() + dx, hit.z() + dz);
                        if (remaining.remove(neighbor)) queue.addLast(neighbor);
                    }
                }
            }

            if (count > 0) {
                double centerX = (sumX / (double) count + 0.5) * 16.0;
                double centerZ = (sumZ / (double) count + 0.5) * 16.0;
                rebuilt.add(new Cluster(minX, maxX, minZ, maxZ,
                        centerX, centerZ, System.nanoTime(),
                        String.join(", ", details)));
            }
        }

        clusters = List.copyOf(rebuilt);
    }

    @Override
    public void onRender(DrawContext context, int width, int height) {
        if (mc.world == null || mc.player == null || clusters.isEmpty()) return;

        long now = System.nanoTime();
        int rgb = Colors.of(markerColor.value) & 0xFFFFFF;

        for (Cluster cluster : clusters) {
            double progress = animatedMarker.value
                    ? Math.max(0.0, Math.min(1.0,
                        (now - cluster.createdAtNanos()) / (double) REVEAL_NANOS))
                    : 1.0;
            double eased = 1.0 - Math.pow(1.0 - progress, 3.0);
            double pulse = animatedMarker.value
                    ? 0.92 + 0.08 * Math.sin((now - cluster.createdAtNanos()) / 420_000_000.0)
                    : 1.0;

            double x0 = cluster.minX() * 16.0;
            double x1 = (cluster.maxX() + 1) * 16.0;
            double z0 = cluster.minZ() * 16.0;
            double z1 = (cluster.maxZ() + 1) * 16.0;
            int topY = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE,
                    (int) Math.floor(cluster.centerX()), (int) Math.floor(cluster.centerZ()));
            double y = Math.max(BASE_Y, topY) + markerHeight.get();

            Vec3d a = new Vec3d(x0, y, z0);
            Vec3d b = new Vec3d(x1, y, z0);
            Vec3d c = new Vec3d(x1, y, z1);
            Vec3d d = new Vec3d(x0, y, z1);

            Proj.fillPoly(context, new Vec3d[]{a, b, c, d},
                    Proj.alpha(rgb, (int) (fillOpacity.get() * 2.55 * eased * pulse)));

            int border = Proj.alpha(rgb, (int) (outlineOpacity.get() * 2.55 * eased));
            drawLine(context, a, b, border);
            drawLine(context, b, c, border);
            drawLine(context, c, d, border);
            drawLine(context, d, a, border);

            double bracket = Math.min(5.0, Math.min(x1 - x0, z1 - z0) * 0.12);
            drawBracket(context, x0, z0, y + 0.012, bracket, 1, 1, border);
            drawBracket(context, x1, z0, y + 0.012, bracket, -1, 1, border);
            drawBracket(context, x1, z1, y + 0.012, bracket, -1, -1, border);
            drawBracket(context, x0, z1, y + 0.012, bracket, 1, -1, border);

            double rotation = now / 2_600_000_000.0;
            double radius = Math.max(2.5, Math.min(x1 - x0, z1 - z0) * 0.12) * pulse;
            drawHexagon(context, cluster.centerX(), y + 0.035, cluster.centerZ(),
                    radius, rotation, Proj.alpha(rgb, (int) (190 * eased)));
            drawHexagon(context, cluster.centerX(), y + 0.045, cluster.centerZ(),
                    radius * 0.62, -rotation * 1.35,
                    Proj.alpha(0xFFFFFFFF, (int) (150 * eased)));

            if (beam.value) {
                Proj.line3(context,
                        new Vec3d(cluster.centerX(), y + 0.05, cluster.centerZ()),
                        new Vec3d(cluster.centerX(), y + 80.0, cluster.centerZ()),
                        Proj.alpha(rgb, (int) (125 * eased)));
            }
        }
    }

    private static void drawBracket(DrawContext context, double x, double z, double y,
                                    double length, int sx, int sz, int color) {
        Proj.line3(context, new Vec3d(x, y, z),
                new Vec3d(x + sx * length, y, z), color);
        Proj.line3(context, new Vec3d(x, y, z),
                new Vec3d(x, y, z + sz * length), color);
    }

    private static void drawHexagon(DrawContext context, double x, double y, double z,
                                    double radius, double rotation, int color) {
        Vec3d[] points = new Vec3d[6];
        for (int i = 0; i < 6; i++) {
            double angle = rotation + Math.PI * 2.0 * i / 6.0;
            points[i] = new Vec3d(x + Math.cos(angle) * radius, y,
                    z + Math.sin(angle) * radius);
        }
        for (int i = 0; i < 6; i++) {
            Proj.line3(context, points[i], points[(i + 1) % 6], color);
        }
    }

    private static void drawLine(DrawContext context, Vec3d a, Vec3d b, int color) {
        Proj.line3(context, a, b, color);
    }
}
