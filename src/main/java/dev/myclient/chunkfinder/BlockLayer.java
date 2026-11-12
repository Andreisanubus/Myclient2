
package dev.myclient.chunkfinder;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.LongPredicate;

public final class BlockLayer {

    private final Map<RegistryKey<World>,
            EnumMap<SuspectObjects.Category, Map<Long, BlockPos>>> dimensions =
            new HashMap<>();

    public void put(
            RegistryKey<World> dimension,
            long packedPos,
            SuspectObjects.Category category
    ) {
        if (dimension == null || category == null) {
            return;
        }

        EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers =
                dimensions.computeIfAbsent(
                        dimension,
                        ignored -> new EnumMap<>(SuspectObjects.Category.class)
                );

        removeFromLayers(layers, packedPos);

        layers.computeIfAbsent(category, ignored -> new LinkedHashMap<>())
                .put(packedPos, BlockPos.fromLong(packedPos));
    }

    public void remove(
            RegistryKey<World> dimension,
            long packedPos
    ) {
        EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers =
                dimensions.get(dimension);

        if (layers != null) {
            removeFromLayers(layers, packedPos);
            removeEmptyLayers(layers);

            if (layers.isEmpty()) {
                dimensions.remove(dimension);
            }
        }
    }

    public void clear(RegistryKey<World> dimension) {
        dimensions.remove(dimension);
    }

    public void clear() {
        dimensions.clear();
    }

    public void retainChunk(
            RegistryKey<World> dimension,
            int chunkX,
            int chunkZ,
            LongPredicate stillThere
    ) {
        EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers =
                dimensions.get(dimension);

        if (layers == null || stillThere == null) {
            return;
        }

        for (Map<Long, BlockPos> blocks : layers.values()) {
            blocks.entrySet().removeIf(entry -> {
                BlockPos pos = entry.getValue();

                if ((pos.getX() >> 4) != chunkX
                        || (pos.getZ() >> 4) != chunkZ) {
                    return false;
                }

                return !stillThere.test(entry.getKey());
            });
        }

        removeEmptyLayers(layers);

        if (layers.isEmpty()) {
            dimensions.remove(dimension);
        }
    }

    public List<BlockPos> positions(
            RegistryKey<World> dimension,
            SuspectObjects.Category category
    ) {
        EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers =
                dimensions.get(dimension);

        if (layers == null) {
            return List.of();
        }

        Map<Long, BlockPos> blocks = layers.get(category);

        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }

        return List.copyOf(blocks.values());
    }

    public Set<SuspectObjects.Category> categories(
            RegistryKey<World> dimension
    ) {
        EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers =
                dimensions.get(dimension);

        if (layers == null) {
            return Set.of();
        }

        return Collections.unmodifiableSet(
                EnumSet.copyOf(layers.keySet())
        );
    }

    public List<List<BlockPos>> veins(
            RegistryKey<World> dimension,
            SuspectObjects.Category category
    ) {
        List<BlockPos> blocks = positions(dimension, category);

        if (blocks.isEmpty()) {
            return List.of();
        }

        return BlockClusterGeometry.connectedComponents(blocks);
    }

    public int count(RegistryKey<World> dimension) {
        EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers =
                dimensions.get(dimension);

        if (layers == null) {
            return 0;
        }

        int count = 0;

        for (Map<Long, BlockPos> blocks : layers.values()) {
            count += blocks.size();
        }

        return count;
    }

    private static void removeFromLayers(
            EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers,
            long packedPos
    ) {
        for (Map<Long, BlockPos> blocks : layers.values()) {
            blocks.remove(packedPos);
        }
    }

    private static void removeEmptyLayers(
            EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers
    ) {
        layers.values().removeIf(Map::isEmpty);
    }
}
