
// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz.
// See docs/spectre-base-finder-port.md.

package dev.myclient.chunkfinder;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
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

        Map<SuspectObjects.Category, Integer> categoryCounts =
                new EnumMap<>(SuspectObjects.Category.class);

        Map<BlockPos, SuspectObjects.Found> geodeCells =
                new HashMap<>();

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

                        categoryCounts.merge(
                                category,
                                1,
                                Integer::sum
                        );

                        if (category == SuspectObjects.Category.AMETHYST) {
                            int worldX = chunkPos.getStartX() + localX;
                            int worldY = (sectionY << 4) + localY;
                            int worldZ = chunkPos.getStartZ() + localZ;

                            BlockPos pos = new BlockPos(
                                    worldX,
                                    worldY,
                                    worldZ
                            );

                            geodeCells.put(
                                    pos,
                                    new SuspectObjects.Found(
                                            pos,
                                            state,
                                            category
                                    )
                            );
                        }
                    }
                }
            }
        }

        List<SuspectObjects.Marker> markers = new ArrayList<>();

        // Amethyst must have a sufficiently mature connected formation.
        if (!geodeCells.isEmpty()) {
            List<SuspectObjects.Marker> geodes =
                    SuspectObjects.geodes(
                            geodeCells,
                            4,
                            60,
                            false
                    );

            if (!geodes.isEmpty()) {
                markers.add(createChunkMarker(
                        chunkPos,
                        SuspectObjects.Category.AMETHYST,
                        geodeCells.size(),
                        "Mature amethyst formation"
                ));
            }
        }

        // Other categories require multiple blocks or growth clues.
        for (SuspectObjects.Category category
                : SuspectObjects.Category.values()) {

            if (category == SuspectObjects.Category.GEODES
                    || category == SuspectObjects.Category.GROWTH
                    || category == SuspectObjects.Category.AMETHYST) {
                continue;
            }

            int count = categoryCounts.getOrDefault(category, 0);
            int required = minimumCount(category);

            if (count < required) {
                continue;
            }

            markers.add(createChunkMarker(
                    chunkPos,
                    category,
                    count,
                    count + " matching blocks"
            ));
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

    private static int minimumCount(SuspectObjects.Category category) {
        return switch (category) {
            case CONTAINERS -> 3;
            case ENDER_STORAGE -> 2;
            case HOPPERS -> 4;
            case MACHINES -> 3;
            case REDSTONE -> 8;
            case SPAWNERS -> 2;
            case BEE_NESTS -> 2;

            case KELP -> 8;
            case CAVE_VINES -> 6;
            case VINES -> 12;
            case BAMBOO -> 8;
            case COCOA -> 6;

            // These categories do not currently have block-based detection.
            case DISPLAYS, ANOMALIES -> Integer.MAX_VALUE;

            case AMETHYST, GEODES, GROWTH -> Integer.MAX_VALUE;
        };
    }

    private static SuspectObjects.Marker createChunkMarker(
            ChunkPos chunk,
            SuspectObjects.Category category,
            int count,
            String detail
    ) {
        double startX = chunk.getStartX();
        double startZ = chunk.getStartZ();

        // The module replaces the Y coordinate with the global Y setting.
        // This placeholder represents the complete chunk-width marker.
        var bounds = new net.minecraft.util.math.Box(
                startX,
                0,
                startZ,
                startX + 16,
                1,
                startZ + 1
        );

        return new SuspectObjects.Marker(
                bounds,
                List.of(bounds),
                category,
                Map.of(),
                category.label + " (" + count + ")",
                detail
        );
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

    public void forgetOutside(int centerX, int centerZ, int radius) {
        Iterator<Map.Entry<Long, Report>> iterator =
                reports.entrySet().iterator();

        while (iterator.hasNext()) {
            Report report = iterator.next().getValue();

            if (Math.abs(report.chunkX - centerX) > radius
                    || Math.abs(report.chunkZ - centerZ) > radius) {
                iterator.remove();
            }
        }
    }

    public void clear() {
        reports.clear();
        geodeScan.clear();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
