
// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre 26.2 by 4ldenz.
// See docs/spectre-base-finder-port.md.

package dev.myclient.module.modules;

import java.util.EnumMap;
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
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

public final class ChunkFinderModule extends Module {

    private final ChunkFinderScanner scanner = new ChunkFinderScanner();

    private final NumberSetting rescanDelay =
            num("Rescan Delay", 40, 10, 200, 10);

    private final NumberSetting renderDistance =
            num("Render Distance", 64, 8, 256, 8);

    private final Setting<Boolean> showFill =
            bool("Show Fill", true);

    private final Setting<Boolean> showOutline =
            bool("Show Outline", true);

    private final Setting<Boolean> showCornerPillars =
            bool("Show Corner Pillars", true);

    private final NumberSetting pillarHeight =
            num("Pillar Height", 4, 1, 32, 1);

    private final Map<SuspectObjects.Category, DetectionSettings> detectionSettings =
            new EnumMap<>(SuspectObjects.Category.class);

    private int ticks;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;

    private static final class DetectionSettings {
        final Setting<Boolean> enabled;
        final ModeSetting color;
        final NumberSetting opacity;
        final Setting<Boolean> outline;
        final Setting<Boolean> customY;
        final NumberSetting yLevel;

        DetectionSettings(
                Setting<Boolean> enabled,
                ModeSetting color,
                NumberSetting opacity,
                Setting<Boolean> outline,
                Setting<Boolean> customY,
                NumberSetting yLevel
        ) {
            this.enabled = enabled;
            this.color = color;
            this.opacity = opacity;
            this.outline = outline;
            this.customY = customY;
            this.yLevel = yLevel;
        }
    }

    public ChunkFinderModule() {
        super("Chunk Finder", Category.BASE_RAID);

        header("General");
        add(rescanDelay);
        add(renderDistance);
        add(showFill);
        add(showOutline);
        add(showCornerPillars);
        add(pillarHeight);

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

        Setting<Boolean> customY =
                bool(prefix + "Custom Y", false);

        NumberSetting yLevel =
                num(prefix + "Y Level", 64, -64, 320, 1);

        DetectionSettings settings = new DetectionSettings(
                enabled,
                color,
                opacity,
                outline,
                customY,
                yLevel
        );

        detectionSettings.put(category, settings);

        add(enabled);
        add(color);
        add(opacity);
        add(outline);
        add(customY);
        add(yLevel);
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

        int chunkX = playerChunk.x;
        int chunkZ = playerChunk.z;

        boolean changed = chunkX != lastChunkX || chunkZ != lastChunkZ;

        if (!changed && ticks < rescanDelay.asInt()) {
            return;
        }

        WorldChunk chunk = mc.world.getChunkManager()
                .getWorldChunk(chunkX, chunkZ);

        if (chunk == null) {
            return;
        }

        Report report = scanner.scan(mc.world, chunk);

        if (report == null) {
            return;
        }

        lastChunkX = chunkX;
        lastChunkZ = chunkZ;
        ticks = 0;

        if (changed) {
            mc.player.sendMessage(
                    Text.literal(
                            "[Chunk Finder] Chunk "
                                    + chunkX + ", " + chunkZ
                                    + " | Activity clues: "
                                    + report.activityScore()
                                    + " | Geode glow: "
                                    + report.geodeGlowCount
                                    + " | Markers: "
                                    + report.markers.size()
                    ),
                    false
            );
        }
    }

    @Override
    public void onRender(DrawContext context, int width, int height) {
        if (mc.player == null || mc.world == null) {
            return;
        }

        ChunkPos playerChunk = mc.player.getChunkPos();

        Report report = scanner.get(playerChunk.x, playerChunk.z);

        if (report == null || report.markers.isEmpty()) {
            return;
        }

        Vec3d camera = Proj.camPos();
        double maxDistance = renderDistance.get();
        double maxDistanceSquared = maxDistance * maxDistance;

        for (SuspectObjects.Marker marker : report.markers) {
            DetectionSettings settings =
                    detectionSettings.get(marker.category());

            if (settings == null || !settings.enabled.value) {
                continue;
            }

            Box original = marker.bounds();

            double centerX = (original.minX + original.maxX) * 0.5;
            double centerY = (original.minY + original.maxY) * 0.5;
            double centerZ = (original.minZ + original.maxZ) * 0.5;

            double dx = centerX - camera.x;
            double dy = centerY - camera.y;
            double dz = centerZ - camera.z;

            if (dx * dx + dy * dy + dz * dz > maxDistanceSquared) {
                continue;
            }

            Box bounds = original;

            if (settings.customY.value) {
                double newY = settings.yLevel.get();
                double originalHeight = Math.max(
                        1.0,
                        original.maxY - original.minY
                );

                bounds = new Box(
                        original.minX,
                        newY,
                        original.minZ,
                        original.maxX,
                        newY + originalHeight,
                        original.maxZ
                );
            }

            int color = Colors.of(settings.color.value);
            int opacity = (int) Math.round(
                    settings.opacity.get() * 255.0 / 100.0
            );

            if (showFill.value && opacity > 0) {
                Proj.fillBox3D(
                        context,
                        bounds,
                        color,
                        opacity
                );
            }

            if (showOutline.value && settings.outline.value) {
                Proj.boxEdges(
                        context,
                        bounds,
                        Proj.alpha(color, 255)
                );
            }

            if (showCornerPillars.value) {
                drawCornerPillars(
                        context,
                        bounds,
                        color,
                        pillarHeight.get()
                );
            }
        }
    }

    private void drawCornerPillars(
            DrawContext context,
            Box bounds,
            int color,
            double height
    ) {
        double pillarTop = bounds.maxY + height;

        int pillarColor = Proj.alpha(color, 220);

        double[][] corners = {
                {bounds.minX, bounds.minZ},
                {bounds.maxX, bounds.minZ},
                {bounds.minX, bounds.maxZ},
                {bounds.maxX, bounds.maxZ}
        };

        for (double[] corner : corners) {
            Proj.line3(
                    context,
                    new Vec3d(corner[0], bounds.minY, corner[1]),
                    new Vec3d(corner[0], pillarTop, corner[1]),
                    pillarColor
            );
        }
    }

    public ChunkFinderScanner getScanner() {
        return scanner;
    }
}
