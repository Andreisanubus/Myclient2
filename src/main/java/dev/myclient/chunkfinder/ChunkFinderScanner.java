
// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz.
// See docs/spectre-base-finder-port.md.

package dev.myclient.chunkfinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

@Environment(EnvType.CLIENT)
public final class ChunkFinderScanner {

    private final GeodeGlowScan geodeScan = new GeodeGlowScan();

    private final Map<Long, Report> reports = new HashMap<>();

    public static final class Report {
        public final int chunkX;
        public final int chunkZ;
        public final ActivityClues.Summary clues;
        public final int geodeGlowCount;
        public final List<Long> geodeGlowPositions;

        private Report(
                int chunkX,
                int chunkZ,
                ActivityClues.Summary clues,
                int geodeGlowCount,
                List<Long> geodeGlowPositions
        ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.clues = clues;
            this.geodeGlowCount = geodeGlowCount;
            this.geodeGlowPositions = List.copyOf(geodeGlowPositions);
        }

        public int activityScore() {
            return clues.score();
        }
    }

    public Report scan(ClientWorld world, WorldChunk chunk) {
        if (world == null || chunk == null) {
            return null;
        }

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        ActivityClues.Summary clues = new ActivityClues.Summary();

        ChunkSection[] sections = chunk.getSectionArray();

        int bottomSection = chunk.getBottomSectionCoord();

        for (int sectionY = -4; sectionY <= 1; sectionY++) {
            int index = sectionY - bottomSection;

            if (index < 0 || index >= sections.length) {
                continue;
            }

            ChunkSection section = sections[index];

            if (section == null || section.isEmpty()) {
                continue;
            }

            int fromY = Math.max(-58, sectionY << 4);
            int toY = Math.min(30, (sectionY << 4) + 15);

            for (int y = fromY; y <= toY; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        clues.add(section.getBlockState(x, y & 15, z));
                    }
                }
            }
        }

        List<Long> glowPositions = new ArrayList<>();

        int glowCount = geodeScan.scan(
                world,
                chunk,
                glowPositions::add
        );

        Report report = new Report(
                chunkX,
                chunkZ,
                clues,
                glowCount,
                glowPositions
        );

        reports.put(key(chunkX, chunkZ), report);

        return report;
    }

    public Report get(int chunkX, int chunkZ) {
        return reports.get(key(chunkX, chunkZ));
    }

    public Map<Long, Report> getReports() {
        return Map.copyOf(reports);
    }

    public void forget(int chunkX, int chunkZ) {
        reports.remove(key(chunkX, chunkZ));
    }

    public void clear() {
        reports.clear();
        geodeScan.clear();
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
