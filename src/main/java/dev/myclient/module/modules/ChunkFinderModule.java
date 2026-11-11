
// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz.
// See docs/spectre-base-finder-port.md.

package dev.myclient.module.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.myclient.chunkfinder.ChunkFinderScanner;
import dev.myclient.chunkfinder.ChunkFinderScanner.Report;
import dev.myclient.chunkfinder.SuspectObjects;
import dev.myclient.module.Category;
import dev.myclient.module.ModeSetting;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
import dev.myclient.render.Proj;
import dev.myclient.util.Colors;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

public final class ChunkFinderModule extends Module {

    private final ChunkFinderScanner scanner = new ChunkFinderScanner();

    private final NumberSetting rescanDelay =
            num("Rescan Delay", 100, 20, 400, 20);

    private final NumberSetting renderDistance =
            num("Render Distance", 64, 16, 256, 16);

    private final NumberSetting globalYLevel =
            num("Global Y Level", 64, -64, 320, 1);

    private final Setting<Boolean> showFill =
            bool("Show Fill", true);

    private final Setting<Boolean> showOutline =
            bool("Show Outline", true);

    private final Map<SuspectObjects.Category, DetectionSettings> detectionSettings =
            new EnumMap<>(SuspectObjects.Category.class);

    private int ticks;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;

    private static final double BAND_HEIGHT = 0.12;

    private static final class DetectionSettings {
        final Setting<Boolean> enabled;
        final ModeSetting color;
        final NumberSetting opacity;
        final Setting<Boolean> outline;

        DetectionSettings(
                Setting<Boolean> enabled,
                ModeSetting color,
                NumberSetting opacity,
                Setting<Boolean> outline
        ) {
            this.enabled = enabled;
            this.color = color;
            this.opacity = opacity;
            this.outline = outline;
        }
    }

    public ChunkFinderModule() {
        super("Chunk Finder", Category.BASE_RAID);

        header("General");
        add(rescanDelay);
        add(renderDistance);
        add(globalYLevel);
        add(showFill);
        add(showOutline);

        for (SuspectObjects.Category category : SuspectObjects.Category.values()) {
            if (category == SuspectObjects.Category.GEODES
                    || category == SuspectObjects.Category.GROWTH) {
                continue;
            }

            addDetectionSettings(category);
        }
    }

    private void addDetectionSettings(SuspectObjects.Category category) {
        header(category.label);

        String prefix = category.label + " ";

        Setting<Boolean> enabled =
                bool(prefix + "ESP", true);

        ModeSetting color =
                color(prefix + "Color", closestColor(category.color));

        NumberSetting opacity =
                num(prefix + "Opacity", 65, 0, 100, 5);

        Setting<Boolean> outline =
                bool(prefix + "Outline", true);

        DetectionSettings settings = new DetectionSettings(
                enabled,
                color,
                opacity,
                outline
        );

        detectionSettings.put(category, settings);

        add(enabled);
        add(color);
        add(opacity);
        add(outline);
    }

    private static String closestColor(int color) {
        String best = Colors.NAMES[0];
        long bestDistance = Long.MAX_VALUE;

        for (String name : Colors.NAMES) {
            int candidate = Colors.of(name);

            int red = ((color >> 16) & 255)
                    - ((candidate >> 16) & 255);

            int green = ((color >> 8) & 255)
                    - ((candidate >> 8) & 255);

            int blue = (color & 255)
                    - (candidate & 255);

            long distance = (long) red * red
                    + (long) green * green
                    + (long) blue * blue;

            if (distance < bestDistance) {
                bestDistance = distance;
                best = name;
            }
        }

        return best;
    }

    @Override
    protected void onEnable() {
        ticks = 0;
        lastChunkX = Integer.MIN_VALUE;
        lastChunkZ = Integer.MIN_VALUE;
        scanner.clear();
    }

    @Override
    protected void onDisable() {
        scanner.clear();
        lastChunkX = Integer.MIN_VALUE;
        lastChunkZ = Integer.MIN_VALUE;
        ticks = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            return;
        }

        ticks++;

        ChunkPos playerChunk = mc.player.getChunkPos();

        int centerX = playerChunk.x;
        int centerZ = playerChunk.z;

        boolean changed = centerX != lastChunkX || centerZ != lastChunkZ;

        if (!changed && ticks < rescanDelay.asInt()) {
            return;
        }

        double distance = renderDistance.get();
        double distanceSquared = distance * distance;

        int radiusChunks = (int) Math.ceil(distance / 16.0);

        // Scan nearby loaded chunks, but never scan the player's current chunk.
        for (int offsetX = -radiusChunks;
             offsetX <= radiusChunks;
             offsetX++) {

            for (int offsetZ = -radiusChunks;
                 offsetZ <= radiusChunks;
                 offsetZ++) {

                int chunkX = centerX + offsetX;
                int chunkZ = centerZ + offsetZ;

                if (chunkX == centerX && chunkZ == centerZ) {
                    continue;
                }

                double chunkCenterX = chunkX * 16.0 + 8.0;
                double chunkCenterZ = chunkZ * 16.0 + 8.0;

                double dx = chunkCenterX - mc.player.getX();
                double dz = chunkCenterZ - mc.player.getZ();

                if (dx * dx + dz * dz > distanceSquared) {
                    continue;
                }

                WorldChunk chunk = mc.world.getChunkManager()
                        .getWorldChunk(chunkX, chunkZ);

                // Do not request or force-load chunks.
                if (chunk == null) {
                    continue;
                }

                scanner.scan(mc.world, chunk);
            }
        }

        scanner.forgetOutside(
                centerX,
                centerZ,
                radiusChunks + 1
        );

        // Make sure the current player chunk is never displayed.
        scanner.forget(centerX, centerZ);

        lastChunkX = centerX;
        lastChunkZ = centerZ;
        ticks = 0;
    }

    @Override
    public void onRender(DrawContext context, int width, int height) {
        if (mc.player == null || mc.world == null) {
            return;
        }

        Vec3d camera = Proj.camPos();

        double maxDistance = renderDistance.get();
        double maxDistanceSquared = maxDistance * maxDistance;

        double y = globalYLevel.get();

        for (Report report : scanner.getReports().values()) {
            // Never draw the chunk the player currently occupies.
            if (report.chunkX == mc.player.getChunkPos().x
                    && report.chunkZ == mc.player.getChunkPos().z) {
                continue;
            }

            double chunkStartX = report.chunkX * 16.0;
            double chunkStartZ = report.chunkZ * 16.0;

            double centerX = chunkStartX + 8.0;
            double centerZ = chunkStartZ + 8.0;

            double dx = centerX - camera.x;
            double dz = centerZ - camera.z;

            if (dx * dx + dz * dz > maxDistanceSquared) {
                continue;
            }

            List<SuspectObjects.Marker> activeMarkers = new ArrayList<>();

            for (SuspectObjects.Marker marker : report.markers) {
                DetectionSettings settings =
                        detectionSettings.get(marker.category());

                if (settings != null && settings.enabled.value) {
                    activeMarkers.add(marker);
                }
            }

            if (activeMarkers.isEmpty()) {
                continue;
            }

            activeMarkers.sort(
                    Comparator.comparingInt(
                            marker -> marker.category().ordinal()
                    )
            );

            drawChunkBand(
                    context,
                    activeMarkers,
                    chunkStartX,
                    chunkStartZ,
                    y
            );
        }
    }

    private void drawChunkBand(
            DrawContext context,
            List<SuspectObjects.Marker> markers,
            double chunkStartX,
            double chunkStartZ,
            double y
    ) {
        /*
         * All category segments together form one clean band:
         * 16 blocks wide, 1 block long and 0.12 blocks high.
         *
         * Each detected category receives its own color.
         */
        double segmentWidth = 16.0 / markers.size();

        for (int i = 0; i < markers.size(); i++) {
            SuspectObjects.Marker marker = markers.get(i);

            DetectionSettings settings =
                    detectionSettings.get(marker.category());

            if (settings == null) {
                continue;
            }

            double minX = chunkStartX + segmentWidth * i;
            double maxX = chunkStartX + segmentWidth * (i + 1);

            Box band = new Box(
                    minX,
                    y,
                    chunkStartZ,
                    maxX,
                    y + BAND_HEIGHT,
                    chunkStartZ + 1.0
            );

            int color = Colors.of(settings.color.value);

            int opacity = (int) Math.round(
                    settings.opacity.get() * 255.0 / 100.0
            );

            if (showFill.value && opacity > 0) {
                Proj.fillBox3D(
                        context,
                        band,
                        color,
                        opacity
                );
            }

            if (showOutline.value && settings.outline.value) {
                Proj.boxEdges(
                        context,
                        band,
                        Proj.alpha(color, 235)
                );
            }

            // A fine highlight line gives the segment a cleaner appearance.
            if (segmentWidth > 0.5) {
                Proj.line3(
                        context,
                        new Vec3d(
                                minX + 0.2,
                                y + BAND_HEIGHT,
                                chunkStartZ + 0.5
                        ),
                        new Vec3d(
                                maxX - 0.2,
                                y + BAND_HEIGHT,
                                chunkStartZ + 0.5
                        ),
                        Proj.alpha(color, 210)
                );
            }
        }
    }

    public ChunkFinderScanner getScanner() {
        return scanner;
    }
}
