
 // SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz.
// See docs/spectre-base-finder-port.md.

package dev.myclient.chunkfinder;

import java.util.Arrays;
import java.util.function.LongConsumer;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.ChunkLightingView;
import net.minecraft.world.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

@Environment(EnvType.CLIENT)
public final class GeodeGlowScan {

    public static final int MIN_Y = -58;
    public static final int MAX_Y = 30;

    private static final int EDGE_LIGHT = 4;

    private static final int LOW_SECTION = -4;
    private static final int HIGH_SECTION = 1;

    private static final int FIRST_CACHED = -5;
    private static final int CACHED = 8;

    private static final Direction[] SIDES = Direction.values();

    private final ChunkNibbleArray[] layers = new ChunkNibbleArray[72];

    private final BlockPos.Mutable cursor = new BlockPos.Mutable();

    private int chunkX;
    private int chunkZ;

    public void clear() {
        Arrays.fill(layers, null);
    }

    public int scan(ClientWorld level, WorldChunk chunk, LongConsumer hit) {
        if (level == null || chunk == null) {
            return 0;
        }

        chunkX = chunk.getPos().x;
        chunkZ = chunk.getPos().z;

        if (!cacheLight(level)) {
            return 0;
        }

        ChunkSection[] sections = chunk.getSectionArray();

        int minX = chunkX << 4;
        int minZ = chunkZ << 4;

        int count = 0;

        for (int sy = LOW_SECTION; sy <= HIGH_SECTION; sy++) {
            int index = sy - chunk.getBottomSectionCoord();

            if (index < 0 || index >= sections.length) {
                continue;
            }

            ChunkSection section = sections[index];

            if (section == null || section.isEmpty() || !nearLight(sy)) {
                continue;
            }

            int from = Math.max(MIN_Y, sy << 4);
            int to = Math.min(MAX_Y, (sy << 4) + 15);

            for (int y = from; y <= to; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {

                        if (!open(section.getBlockState(x, y & 15, z))) {
                            continue;
                        }

                        int wx = minX + x;
                        int wz = minZ + z;

                        if (lightAt(wx, y, wz) == 0
                                && edge(level, wx, y, wz)) {

                            count++;

                            if (hit != null) {
                                hit.accept(BlockPos.asLong(wx, y, wz));
                            }
                        }
                    }
                }
            }
        }

        return count;
    }

    private boolean cacheLight(ClientWorld level) {
        ChunkLightingView light = level.getChunkManager()
                .getLightingProvider()
                .get(LightType.BLOCK);

        boolean any = false;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int s = 0; s < CACHED; s++) {

                    int sectionY = FIRST_CACHED + s;

                    ChunkSectionPos sectionPos = ChunkSectionPos.from(
                            chunkX + dx,
                            sectionY,
                            chunkZ + dz
                    );

                    ChunkNibbleArray layer = light.getLightSection(sectionPos);

                    layers[slot(dx, dz, s)] = layer;

                    any |= layer != null;
                }
            }
        }

        return any;
    }

    private boolean nearLight(int sy) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int s = sy - FIRST_CACHED - 1;
                     s <= sy - FIRST_CACHED + 1;
                     s++) {

                    if (s < 0 || s >= CACHED) {
                        continue;
                    }

                    if (layers[slot(dx, dz, s)] != null) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean edge(ClientWorld level, int x, int y, int z) {
        boolean litOpening = false;
        int brightest = 0;

        for (Direction side : SIDES) {
            int nx = x + side.getOffsetX();
            int ny = y + side.getOffsetY();
            int nz = z + side.getOffsetZ();

            int value = lightAt(nx, ny, nz);

            if (value > EDGE_LIGHT) {
                return false;
            }

            brightest = Math.max(brightest, value);

            if (value == EDGE_LIGHT && !litOpening) {
                BlockState neighbor = level.getBlockState(
                        cursor.set(nx, ny, nz)
                );

                if (open(neighbor)) {
                    litOpening = true;
                }
            }
        }

        return brightest == EDGE_LIGHT && litOpening;
    }

    private int lightAt(int x, int y, int z) {
        int s = (y >> 4) - FIRST_CACHED;

        if (s < 0 || s >= CACHED) {
            return 0;
        }

        int dx = (x >> 4) - chunkX;
        int dz = (z >> 4) - chunkZ;

        if (dx < -1 || dx > 1 || dz < -1 || dz > 1) {
            return 0;
        }

        ChunkNibbleArray layer = layers[slot(dx, dz, s)];

        return layer == null
                ? 0
                : layer.get(x & 15, y & 15, z & 15);
    }

    private static int slot(int dx, int dz, int section) {
        return ((dx + 1) * 3 + dz + 1) * CACHED + section;
    }

    private static boolean open(BlockState state) {
        return state.isAir() || state.isOf(Blocks.AMETHYST_CLUSTER);
    }
}
