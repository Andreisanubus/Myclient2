
package dev.myclient.chunkfinder;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public final class BlockBoxHelper {

    private static final float MIN_SIZE = 0.1F;
    private static final float MAX_SIZE = 2.0F;

    private BlockBoxHelper() {
    }

    public static Box scaledBlockBox(BlockPos pos, float size) {
        float clamped = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        double half = clamped * 0.5;

        double centerX = pos.getX() + 0.5;
        double centerY = pos.getY() + 0.5;
        double centerZ = pos.getZ() + 0.5;

        return new Box(
                centerX - half,
                centerY - half,
                centerZ - half,
                centerX + half,
                centerY + half,
                centerZ + half
        );
    }
}
