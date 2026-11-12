
package dev.myclient.module.modules;

import dev.myclient.chunkfinder.BlockClusterRenderer;
import dev.myclient.chunkfinder.BlockLayer;
import dev.myclient.chunkfinder.BlockLayerConsumer;
import dev.myclient.chunkfinder.ChunkScanner;
import dev.myclient.chunkfinder.SuspectObjects;
import dev.myclient.module.Category;
import dev.myclient.module.ModeSetting;
import dev.myclient.module.Module;
import dev.myclient.module.NumberSetting;
import dev.myclient.module.Setting;
import dev.myclient.render.Proj;
import dev.myclient.util.Colors;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ChunkFinderModule extends Module {

    private final BlockLayer layer = new BlockLayer();

    private final BlockLayerConsumer consumer =
            new BlockLayerConsumer(layer);

    private final NumberSetting renderDistance =
            num("Render Distance", 128, 16, 256, 16);

    private final Setting<Boolean> showFill =
            bool("Show Fill", true);

    private final Setting<Boolean> showOutline =
            bool("Show Outline", true);

    private final Setting<Boolean> distanceFade =
            bool("Distance Fade", true);

    private final Map<SuspectObjects.Category, DetectionSettings>
            detectionSettings =
            new EnumMap<>(SuspectObjects.Category.class);

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
        add(renderDistance);
        add(showFill);
        add(showOutline);
        add(distanceFade);

        for (SuspectObjects.Category category
                : SuspectObjects.Category.values()) {

            if (category == SuspectObjects.Category.GEODES
                    || category == SuspectObjects.Category.GROWTH) {
                continue;
            }

            addDetectionSettings(category);
        }
    }

    private void addDetectionSettings(
            SuspectObjects.Category category
    ) {
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

        detectionSettings.put(
                category,
                new DetectionSettings(
                        enabled,
                        color,
                        opacity,
                        outline
                )
        );

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
        layer.clear();
        ChunkScanner.register(consumer);
    }

    @Override
    protected void onDisable() {
        ChunkScanner.unregister(consumer);
        layer.clear();
    }

    @Override
    public void onRender(
            DrawContext context,
            int width,
            int height
    ) {
        if (mc.player == null || mc.world == null) {
            return;
        }

        RegistryKey<World> dimension =
                mc.world.getRegistryKey();

        double maxDistance = renderDistance.get();
        double maxDistanceSquared = maxDistance * maxDistance;

        double playerX = mc.player.getX();
        double playerY = mc.player.getY();
        double playerZ = mc.player.getZ();

        for (SuspectObjects.Category category
                : SuspectObjects.Category.values()) {

            DetectionSettings settings =
                    detectionSettings.get(category);

            if (settings == null || !settings.enabled.value) {
                continue;
            }

            BlockLayer.Geometry geometry =
                    layer.geometry(dimension, category);

            List<BlockPos> positions = geometry.positions();

            if (positions.isEmpty()) {
                continue;
            }

            double nearestDistanceSquared =
                    nearestDistanceSquared(
                            positions,
                            playerX,
                            playerY,
                            playerZ
                    );

            if (nearestDistanceSquared > maxDistanceSquared) {
                continue;
            }

            double distance = Math.sqrt(nearestDistanceSquared);

            double fade = 1.0;

            if (distanceFade.value) {
                double fadeStart = maxDistance * 0.70;

                if (distance > fadeStart) {
                    fade = 1.0 - (
                            (distance - fadeStart)
                                    / Math.max(1.0, maxDistance - fadeStart)
                    );

                    fade = Math.max(0.0, Math.min(1.0, fade));
                }
            }

            int color = Colors.of(settings.color.value);

            int opacity = (int) Math.round(
                    settings.opacity.get() * 255.0 / 100.0 * fade
            );

            int fillColor = Proj.alpha(color, opacity);
            int outlineColor = Proj.alpha(
                    color,
                    (int) Math.round(235.0 * fade)
            );

            BlockClusterRenderer.render(
                    context,
                    geometry,
                    fillColor,
                    outlineColor,
                    showFill.value && opacity > 0,
                    showOutline.value && settings.outline.value
            );
        }
    }

    private static double nearestDistanceSquared(
            List<BlockPos> positions,
            double playerX,
            double playerY,
            double playerZ
    ) {
        double nearest = Double.MAX_VALUE;

        for (BlockPos pos : positions) {
            double dx = pos.getX() + 0.5 - playerX;
            double dy = pos.getY() + 0.5 - playerY;
            double dz = pos.getZ() + 0.5 - playerZ;

            double distanceSquared =
                    dx * dx + dy * dy + dz * dz;

            if (distanceSquared < nearest) {
                nearest = distanceSquared;
            }
        }

        return nearest;
    }

    public BlockLayer getLayer() {
        return layer;
    }
}
