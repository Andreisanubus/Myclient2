
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

    private final Map<RegistryKey<World>,
            EnumMap<SuspectObjects.Category, Geometry>> geometryCache =
            new HashMap<>();

    public record Geometry(
            List<BlockPos> positions,
            List<List<BlockPos>> components,
            BlockClusterGeometry.Mesh mesh
    ) {
        public Geometry {
            positions = List.copyOf(positions);

            List<List<BlockPos>> copiedComponents = new ArrayList<>();

            for (List<BlockPos> component : components) {
                copiedComponents.add(List.copyOf(component));
            }

            components = List.copyOf(copiedComponents);
        }
    }

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

        EnumSet<SuspectObjects.Category> changed =
                removeFromLayers(layers, packedPos);

        Map<Long, BlockPos> blocks =
                layers.computeIfAbsent(
                        category,
                        ignored -> new LinkedHashMap<>()
                );

        BlockPos pos = BlockPos.fromLong(packedPos);
        BlockPos previous = blocks.put(packedPos, pos);

        if (previous == null || !previous.equals(pos)
                || !changed.isEmpty()) {
            changed.add(category);
            invalidate(dimension, changed);
        }
    }

    public void remove(
            RegistryKey<World> dimension,
            long packedPos
    ) {
        EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers =
                dimensions.get(dimension);

        if (layers == null) {
            return;
        }

        EnumSet<SuspectObjects.Category> changed =
                removeFromLayers(layers, packedPos);

        if (changed.isEmpty()) {
            return;
        }

        removeEmptyLayers(layers);
        invalidate(dimension, changed);

        if (layers.isEmpty()) {
            dimensions.remove(dimension);
            geometryCache.remove(dimension);
        }
    }

    public void clear(RegistryKey<World> dimension) {
        dimensions.remove(dimension);
        geometryCache.remove(dimension);
    }

    public void clear() {
        dimensions.clear();
        geometryCache.clear();
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

        EnumSet<SuspectObjects.Category> changed =
                EnumSet.noneOf(SuspectObjects.Category.class);

        for (Map.Entry<SuspectObjects.Category, Map<Long, BlockPos>> entry
                : layers.entrySet()) {

            boolean removed = entry.getValue().entrySet().removeIf(block -> {
                BlockPos pos = block.getValue();

                if ((pos.getX() >> 4) != chunkX
                        || (pos.getZ() >> 4) != chunkZ) {
                    return false;
                }

                return !stillThere.test(block.getKey());
            });

            if (removed) {
                changed.add(entry.getKey());
            }
        }

        if (!changed.isEmpty()) {
            removeEmptyLayers(layers);
            invalidate(dimension, changed);
        }

        if (layers.isEmpty()) {
            dimensions.remove(dimension);
            geometryCache.remove(dimension);
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

    public Geometry geometry(
            RegistryKey<World> dimension,
            SuspectObjects.Category category
    ) {
        if (dimension == null || category == null) {
            return emptyGeometry();
        }

        EnumMap<SuspectObjects.Category, Geometry> cache =
                geometryCache.computeIfAbsent(
                        dimension,
                        ignored -> new EnumMap<>(SuspectObjects.Category.class)
                );

        Geometry cached = cache.get(category);

        if (cached != null) {
            return cached;
        }

        List<BlockPos> positions = positions(dimension, category);

        if (positions.isEmpty()) {
            Geometry empty = emptyGeometry();
            cache.put(category, empty);
            return empty;
        }

        Set<BlockPos> membership = new HashSet<>(positions);

        List<List<BlockPos>> components =
                BlockClusterGeometry.connectedComponents(positions);

        BlockClusterGeometry.Mesh mesh =
                BlockClusterGeometry.build(
                        positions,
                        membership::contains
                );

        Geometry result = new Geometry(
                positions,
                components,
                mesh
        );

        cache.put(category, result);
        return result;
    }

    public Set<SuspectObjects.Category> categories(
            RegistryKey<World> dimension
    ) {
        EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers =
                dimensions.get(dimension);

        if (layers == null || layers.isEmpty()) {
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
        return geometry(dimension, category).components();
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

    private static Geometry emptyGeometry() {
        return new Geometry(
                List.of(),
                List.of(),
                new BlockClusterGeometry.Mesh(
                        List.of(),
                        List.of()
                )
        );
    }

    private void invalidate(
            RegistryKey<World> dimension,
            Set<SuspectObjects.Category> categories
    ) {
        EnumMap<SuspectObjects.Category, Geometry> cache =
                geometryCache.get(dimension);

        if (cache == null) {
            return;
        }

        for (SuspectObjects.Category category : categories) {
            cache.remove(category);
        }

        if (cache.isEmpty()) {
            geometryCache.remove(dimension);
        }
    }

    private static EnumSet<SuspectObjects.Category> removeFromLayers(
            EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers,
            long packedPos
    ) {
        EnumSet<SuspectObjects.Category> changed =
                EnumSet.noneOf(SuspectObjects.Category.class);

        for (Map.Entry<SuspectObjects.Category, Map<Long, BlockPos>> entry
                : layers.entrySet()) {

            if (entry.getValue().remove(packedPos) != null) {
                changed.add(entry.getKey());
            }
        }

        return changed;
    }

    private static void removeEmptyLayers(
            EnumMap<SuspectObjects.Category, Map<Long, BlockPos>> layers
    ) {
        layers.values().removeIf(Map::isEmpty);
    }
}
