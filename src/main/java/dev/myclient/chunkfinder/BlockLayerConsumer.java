
package dev.myclient.chunkfinder;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class BlockLayerConsumer implements ChunkScanner.Consumer {

    private final BlockLayer layer;

    private RegistryKey<World> activeDimension;

    public BlockLayerConsumer(BlockLayer layer) {
        this.layer = layer;
    }

    @Override
    public boolean wants(BlockState state) {
        return state != null && SuspectObjects.category(state) != null;
    }

    @Override
    public void found(long packedPos, BlockState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;

        if (world == null || state == null) {
            return;
        }

        RegistryKey<World> dimension = world.getRegistryKey();
        updateDimension(dimension);

        SuspectObjects.Category category = SuspectObjects.category(state);

        if (category != null) {
            layer.put(dimension, packedPos, category);
        } else {
            layer.remove(dimension, packedPos);
        }
    }

    @Override
    public void removed(long packedPos) {
        if (activeDimension != null) {
            layer.remove(activeDimension, packedPos);
        }
    }

    @Override
    public void reset() {
        if (activeDimension != null) {
            layer.clear(activeDimension);
        }

        ClientWorld world = MinecraftClient.getInstance().world;

        activeDimension = world == null
                ? null
                : world.getRegistryKey();

        if (activeDimension != null) {
            layer.clear(activeDimension);
        }
    }

    @Override
    public void chunkScanned(
            long chunkKey,
            java.util.function.LongPredicate stillThere
    ) {
        if (activeDimension == null) {
            return;
        }

        int chunkX = net.minecraft.util.math.ChunkPos.getPackedX(chunkKey);
        int chunkZ = net.minecraft.util.math.ChunkPos.getPackedZ(chunkKey);

        layer.retainChunk(
                activeDimension,
                chunkX,
                chunkZ,
                stillThere
        );
    }

    private void updateDimension(RegistryKey<World> dimension) {
        if (activeDimension != null && !activeDimension.equals(dimension)) {
            layer.clear(activeDimension);
        }

        activeDimension = dimension;
    }
}
