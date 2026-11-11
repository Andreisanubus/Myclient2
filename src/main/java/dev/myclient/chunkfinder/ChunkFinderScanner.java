
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
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
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
        public final List<SuspectObjects.Marker> markers;

        private Report(
                int chunkX,
                int chunkZ,
                ActivityClues.Summary clues,
                int geodeGlowCount,
                List<Long> geodeGlowPositions,
                List<SuspectObjects.Marker> markers
        ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.clues = clues;
            this.geodeGlowCount = geodeGlowCount;
            this.geodeGlowPositions = List.copyOf(geodeGlowPositions);
            this.markers = List.copyOf(markers);
        }

        public int activityScore() {
            return clues.score();
        }

        public List<SuspectObjects.Marker> getMarkers() {
            return markers;
        }
    }

    public Report scan(ClientWorld world, WorldChunk chunk) {
        if (world == null || chunk == null) {
            return null;
        }

        ChunkPos chunkPos = chunk.getPos();

        ActivityClues.Summary clues = new ActivityClues.Summary();

        Map<BlockPos, SuspectObjects.Found> allCells = new HashMap<>();
        Map<BlockPos, SuspectObjects.Found> geodeCells = new HashMap<>();
        Map<BlockPos, SuspectObjects.Found> plantCells = new HashMap<>();

        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSection = world.getBottomSectionCoord();

        for (int sectionIndex = 0;
             sectionIndex < sections.length;
             sectionIndex++) {

            ChunkSection section = sections[sectionIndex];

            if (section == null || section.isEmpty()) {
                continue;
            }

            int sectionY = bottomSection + sectionIndex;

            for (int localX = 0; localX < 16; localX++) {
                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {

                        BlockState state = section.getBlockState(
                                localX,
                                localY,
                                localZ
                        );

                        if (state.isAir()) {
                            continue;
                        }

                        ActivityClues.Kind clueKind =
                                ActivityClues.kind(state);

                        if (clueKind != null) {
                            clues.add(state);
                        }

                        SuspectObjects.Category category =
                                SuspectObjects.category(state);

                        if (category == null) {
                            continue;
                        }

                        int worldX = chunkPos.getStartX() + localX;
                        int worldY = (sectionY << 4) + localY;
                        int worldZ = chunkPos.getStartZ() + localZ;

                        BlockPos pos = new BlockPos(
                                worldX,
                                worldY,
                                worldZ
                        );

                        SuspectObjects.Found found =
                                new SuspectObjects.Found(
                                        pos,
                                        state,
                                        category
                                );

                        allCells.put(pos, found);

                        if (category == SuspectObjects.Category.AMETHYST) {
                            geodeCells.put(pos, found);
                        }

                        if (clueKind != null
                                && clueKind != ActivityClues.Kind.AMETHYST
                                && clueKind != ActivityClues.Kind.BEES) {
                            plantCells.put(pos, found);
                        }
                    }
                }
            }
        }

        List<SuspectObjects.Marker> markers = new ArrayList<>();

        // Amethyst formations are grouped into separate markers.
        if (!geodeCells.isEmpty()) {
            markers.addAll(
                    SuspectObjects.geodes(
                            geodeCells,
                            1,
                            0,
                            true
                    )
            );
        }

        // Vertical plant growth is grouped by detection type.
        if (!plantCells.isEmpty()) {
            markers.addAll(
                    SuspectObjects.plants(
                            plantCells,
                            kind -> 1,
                            true
                    )
            );
        }

        // Remaining detections receive individual markers.
        for (SuspectObjects.Found found : allCells.values()) {
            SuspectObjects.Category category = found.category();

            if (category == SuspectObjects.Category.AMETHYST
                    || category == SuspectObjects.Category.KELP
                    || category == SuspectObjects.Category.CAVE_VINES
                    || category == SuspectObjects.Category.VINES
                    || category == SuspectObjects.Category.BAMBOO
                    || category == SuspectObjects.Category.COCOA) {
                continue;
            }

            markers.add(SuspectObjects.block(found, world));
        }

        List<Long> glowPositions = new ArrayList<>();

        int glowCount = geodeScan.scan(
                world,
                chunk,
                glowPositions::add
        );

        Report report = new Report(
                chunkPos.x,
                chunkPos.z,
                clues,
                glowCount,
                glowPositions,
                markers
        );

        reports.put(key(chunkPos.x, chunkPos.z), report);

        return report;
    }

    public Report get(int x, int z) {
        return reports.get(key(x, z));
    }

    public Map<Long, Report> getReports() {
        return Map.copyOf(reports);
    }

    public void forget(int x, int z) {
        reports.remove(key(x, z));
    }

    public void clear() {
        reports.clear();
        geodeScan.clear();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
