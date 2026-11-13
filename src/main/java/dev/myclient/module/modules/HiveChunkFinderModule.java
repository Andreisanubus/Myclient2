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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Sober-style Chunk Finder port for Yarn 1.21.11.
 *
 * Detects loaded chunks containing a fully honey-filled bee nest or beehive,
 * waits for chunks to settle before checking them, merges nearby hits, and
 * renders animated world-space markers.
 */
public final class HiveChunkFinderModule extends Module {
    private static final int SETTLE_TICKS = 200;
    private static final int CHUNKS_PER_TICK = 8;
    private static final int BASE_Y = 55;
    private static final long REVEAL_NANOS = 350_000_000L;

    private static final Set<HiveChunkFinderModule> INSTANCES =
            new CopyOnWriteArraySet<>();

    private final NumberSetting mergeRadius =
            num("Merge Radius", 4, 1, 8, 1);
    private final Setting<Boolean> notifications =
            bool("Notifications", true);
    private final Setting<Boolean> beam =
            bool("Beam", false);
    private final ModeSetting markerColor =
            color("Color", "Orange");

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

    private record HitChunk(int x, int z, long foundAtNanos) {}
    private record Cluster(int minX, int maxX, int minZ, int maxZ,
                           double centerX, double centerZ, long createdAtNanos) {}

    public HiveChunkFinderModule() {
        super("Chunk Finder", Category.BASE_RAID);
        header("Detection");
        add(mergeRadius);
        add(notifications);
        header("Marker");
        add(markerColor);
        add(beam);
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

    /** A block update invalidates the settled scan for its containing chunk. */
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
        int viewDistance = Math.max(2, mc.options.getViewDistance().getValue());

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
            if (hasFullHoneyHive(chunk)) {
                hits.put(key, new HitChunk(chunkX, chunkZ, System.nanoTime()));
                changed = true;
                if (notifications.value) {
                    mc.player.sendMessage(Text.literal(
                            "[Chunk Finder] Full honey hive detected in chunk "
                                    + chunkX + ", " + chunkZ
                    ), false);
                }
            }
        }

        if (changed) rebuildClusters();
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

    private static boolean hasFullHoneyHive(WorldChunk chunk) {
        for (ChunkSection section : chunk.getSectionArray()) {
            if (section == null || section.isEmpty()) continue;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if ((state.isOf(Blocks.BEE_NEST) || state.isOf(Blocks.BEEHIVE))
                                && state.get(BeehiveBlock.HONEY_LEVEL) == 5) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
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
                        centerX, centerZ, System.nanoTime()));
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
            double progress = Math.max(0.0, Math.min(1.0,
                    (now - cluster.createdAtNanos()) / (double) REVEAL_NANOS));
            double eased = 1.0 - Math.pow(1.0 - progress, 3.0);
            double pulse = 0.92 + 0.08 * Math.sin((now - cluster.createdAtNanos()) / 420_000_000.0);

            double x0 = cluster.minX() * 16.0;
            double x1 = (cluster.maxX() + 1) * 16.0;
            double z0 = cluster.minZ() * 16.0;
            double z1 = (cluster.maxZ() + 1) * 16.0;
            int topY = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE,
                    (int) Math.floor(cluster.centerX()), (int) Math.floor(cluster.centerZ()));
            double y = Math.max(BASE_Y, topY) + 0.025;

            Vec3d a = new Vec3d(x0, y, z0);
            Vec3d b = new Vec3d(x1, y, z0);
            Vec3d c = new Vec3d(x1, y, z1);
            Vec3d d = new Vec3d(x0, y, z1);

            Proj.fillPoly(context, new Vec3d[]{a, b, c, d},
                    Proj.alpha(rgb, (int) (24 * eased * pulse)));

            int border = Proj.alpha(rgb, (int) (220 * eased));
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
