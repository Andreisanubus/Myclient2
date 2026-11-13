
package dev.myclient.chunkfinder;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongPredicate;

public final class ChunkScanner {

    private static volatile int stepBlocks = 1024;
    private static volatile long tickBudgetNanos = 1_500_000L;
    private static final int MAX_QUEUED_CHUNKS = 4096;

    private static final MinecraftClient MC = MinecraftClient.getInstance();

    private static final List<Consumer> CONSUMERS =
            new CopyOnWriteArrayList<>();

    private static final ArrayDeque<Long> QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();

    private static ClientWorld currentWorld;
    private static ScanCursor cursor;
    private static boolean initialized;

    private ChunkScanner() {
    }

    public static void configure(int blocksPerStep, long budgetNanos) {
        stepBlocks = Math.max(0, Math.min(4096, blocksPerStep));
        tickBudgetNanos = Math.max(0L, Math.min(4_000_000L, budgetNanos));
    }

    public interface Consumer {

        boolean wants(BlockState state);

        void found(long packedPos, BlockState state);

        void removed(long packedPos);

        void reset();

        default void chunkScanned(
                long chunkKey,
                LongPredicate stillThere
        ) {
        }
    }

    public static void init() {
        if (initialized) {
            return;
        }

        initialized = true;

        ClientTickEvents.END_CLIENT_TICK.register(
                ChunkScanner::tick
        );

        ClientChunkEvents.CHUNK_LOAD.register(
                (world, chunk) -> {
                    if (world == MC.world && !CONSUMERS.isEmpty()) {
                        enqueue(chunk);
                    }
                }
        );
    }

    public static void register(Consumer consumer) {
        if (consumer == null || CONSUMERS.contains(consumer)) {
            return;
        }

        init();
        CONSUMERS.add(consumer);

        consumer.reset();
        restart();
    }

    public static void unregister(Consumer consumer) {
        if (consumer == null) {
            return;
        }

        CONSUMERS.remove(consumer);

        if (CONSUMERS.isEmpty()) {
            clearQueue();
            cursor = null;
        } else {
            restart();
        }
    }

    public static void rescan() {
        restart();
    }

    public static void clear() {
        clearQueue();
        cursor = null;

        for (Consumer consumer : CONSUMERS) {
            safeReset(consumer);
        }
    }

    private static void restart() {
        clearQueue();
        cursor = null;

        if (MC.world == null || MC.player == null) {
            return;
        }

        currentWorld = MC.world;

        for (Consumer consumer : CONSUMERS) {
            safeReset(consumer);
        }

        queueLoadedChunks();
    }

    private static void tick(MinecraftClient client) {
        if (CONSUMERS.isEmpty()
                || client.world == null
                || client.player == null) {
            return;
        }

        if (currentWorld != client.world) {
            restart();
            return;
        }

        if (stepBlocks <= 0 || tickBudgetNanos <= 0) return;
        long deadline = System.nanoTime() + tickBudgetNanos;

        while (System.nanoTime() < deadline) {
            if (cursor == null) {
                cursor = nextCursor();

                if (cursor == null) {
                    break;
                }
            }

            boolean finished = cursor.step(
                    stepBlocks,
                    deadline
            );

            if (finished) {
                cursor.finish();
                cursor = null;
            } else {
                break;
            }
        }
    }

    private static void queueLoadedChunks() {
        if (MC.world == null || MC.player == null) {
            return;
        }

        int centerX = MC.player.getChunkPos().x;
        int centerZ = MC.player.getChunkPos().z;

        int distance = Math.max(
                2,
                MC.options.getViewDistance().getValue()
        );

        List<WorldChunk> chunks = new ArrayList<>();

        for (int x = centerX - distance; x <= centerX + distance; x++) {
            for (int z = centerZ - distance; z <= centerZ + distance; z++) {

                WorldChunk chunk = MC.world.getChunkManager()
                        .getWorldChunk(x, z);

                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
        }

        chunks.sort(Comparator.comparingLong(chunk -> {
            long dx = (long) chunk.getPos().x - centerX;
            long dz = (long) chunk.getPos().z - centerZ;
            return dx * dx + dz * dz;
        }));

        for (WorldChunk chunk : chunks) {
            enqueue(chunk);
        }
    }

    private static void enqueue(WorldChunk chunk) {
        if (chunk == null || QUEUE.size() >= MAX_QUEUED_CHUNKS) {
            return;
        }

        if (MC.player == null) {
            return;
        }

        ChunkPos pos = chunk.getPos();

        long key = ChunkPos.toLong(pos.x, pos.z);

        if (QUEUED.add(key)) {
            QUEUE.addLast(key);
        }
    }

    private static ScanCursor nextCursor() {
        if (MC.world == null) {
            return null;
        }

        while (!QUEUE.isEmpty()) {
            long key = QUEUE.removeFirst();
            QUEUED.remove(key);

            int x = ChunkPos.getPackedX(key);
            int z = ChunkPos.getPackedZ(key);

            WorldChunk chunk = MC.world.getChunkManager()
                    .getWorldChunk(x, z);

            if (chunk == null) {
                continue;
            }

            return new ScanCursor(
                    MC.world,
                    chunk,
                    List.copyOf(CONSUMERS)
            );
        }

        return null;
    }

    /**
     * Poate fi apelată ulterior de un mixin la actualizarea unui bloc.
     */
    public static void blockUpdated(
            ClientWorld world,
            BlockPos pos,
            BlockState newState
    ) {
        if (world == null || pos == null || newState == null
                || world != MC.world) {
            return;
        }

        long packed = pos.asLong();

        for (Consumer consumer : CONSUMERS) {
            try {
                if (consumer.wants(newState)) {
                    consumer.found(packed, newState);
                } else {
                    consumer.removed(packed);
                }
            } catch (RuntimeException exception) {
                CONSUMERS.remove(consumer);
                System.err.println(
                        "[MyClient] Chunk scanner consumer removed: "
                                + exception.getMessage()
                );
            }
        }
    }

    private static void clearQueue() {
        QUEUE.clear();
        QUEUED.clear();
    }

    private static void safeReset(Consumer consumer) {
        try {
            consumer.reset();
        } catch (RuntimeException exception) {
            CONSUMERS.remove(consumer);
            System.err.println(
                    "[MyClient] Chunk scanner reset failed: "
                            + exception.getMessage()
            );
        }
    }

    private static final class ScanCursor {

        private final ClientWorld world;
        private final WorldChunk chunk;
        private final List<Consumer> listeners;
        private final ChunkSection[] sections;

        private final int chunkX;
        private final int chunkZ;
        private final int bottomSection;

        private int sectionIndex;
        private int blockIndex;

        private ScanCursor(
                ClientWorld world,
                WorldChunk chunk,
                List<Consumer> listeners
        ) {
            this.world = world;
            this.chunk = chunk;
            this.listeners = listeners;
            this.sections = chunk.getSectionArray();

            this.chunkX = chunk.getPos().x;
            this.chunkZ = chunk.getPos().z;
            this.bottomSection = chunk.getBottomSectionCoord();
        }

        private boolean step(int maximumBlocks, long deadline) {
            int processed = 0;

            while (sectionIndex < sections.length
                    && processed < maximumBlocks
                    && System.nanoTime() < deadline) {

                ChunkSection section = sections[sectionIndex];

                if (section == null || section.isEmpty()) {
                    sectionIndex++;
                    blockIndex = 0;
                    continue;
                }

                int index = blockIndex++;

                int localX = index & 15;
                int localZ = (index >> 4) & 15;
                int localY = (index >> 8) & 15;

                BlockState state = section.getBlockState(
                        localX,
                        localY,
                        localZ
                );

                processed++;

                if (!state.isAir()) {
                    int worldX = (chunkX << 4) + localX;
                    int worldY = ((bottomSection + sectionIndex) << 4)
                            + localY;
                    int worldZ = (chunkZ << 4) + localZ;

                    long packed = BlockPos.asLong(
                            worldX,
                            worldY,
                            worldZ
                    );

                    notifyFound(packed, state);
                }

                if (blockIndex >= 4096) {
                    sectionIndex++;
                    blockIndex = 0;
                }
            }

            return sectionIndex >= sections.length;
        }

        private void notifyFound(long packed, BlockState state) {
            for (Consumer consumer : listeners) {
                try {
                    if (consumer.wants(state)) {
                        consumer.found(packed, state);
                    }
                } catch (RuntimeException exception) {
                    CONSUMERS.remove(consumer);
                    System.err.println(
                            "[MyClient] Chunk scanner consumer failed: "
                                    + exception.getMessage()
                    );
                }
            }
        }

        private void finish() {
            if (world != MC.world) {
                return;
            }

            int chunkX = this.chunkX;
            int chunkZ = this.chunkZ;

            for (Consumer consumer : listeners) {
                if (!CONSUMERS.contains(consumer)) {
                    continue;
                }

                try {
                    consumer.chunkScanned(
                            ChunkPos.toLong(chunkX, chunkZ),
                            packed -> {
                                BlockPos pos = BlockPos.fromLong(packed);

                                if ((pos.getX() >> 4) != chunkX
                                        || (pos.getZ() >> 4) != chunkZ) {
                                    return false;
                                }

                                return consumer.wants(
                                        world.getBlockState(pos)
                                );
                            }
                    );
                } catch (RuntimeException exception) {
                    CONSUMERS.remove(consumer);
                    System.err.println(
                            "[MyClient] Chunk reconciliation failed: "
                                    + exception.getMessage()
                    );
                }
            }
        }
    }
}
