
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

    private static final long REVEAL_DURATION_NANOS = 150_000_000L;

    private final BlockLayer layer = new BlockLayer();

    private final BlockLayerConsumer consumer =
            new BlockLayerConsumer(layer);

    private long revealStartNanos;

    private final NumberSetting renderDistance =
            num("Render Distance", 2048, 16, 2048, 16);

    private final Setting<Boolean> showFill =
            bool("Show Fill", true);

    private final Setting<Boolean> showOutline =
            bool("Show Outline", true);

    private final NumberSetting fillAlpha =
            num("Fill Alpha", 20, 0, 100, 5);

    private final NumberSetting boxSize =
            num("Box Size", 1.0, 0.1, 2.0, 0.1);

    private final NumberSetting lineWidth =
            num("Line Width", 1.5, 0.5, 5, 0.5);

    private final Setting<Boolean> distanceFade =
            bool("Distance Fade", true);

    private final Setting<Boolean> revealAnimation =
            bool("Reveal Animation", true);

    private final Setting<Boolean> showGlow =
            bool("Glow", true);

    private final NumberSetting glowIntensity =
            num("Glow Intensity", 0.65, 0.1, 1.0, 0.05);

    private final Setting<Boolean> showTracers =
            bool("Tracers", false);

    private final NumberSetting tracerWidth =
            num("Tracer Width", 1.5, 0.5, 5, 0.5);

    private final NumberSetting maxTracers =
            num("Max Tracers", 256, 16, 256, 16);

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
        super("Block ESP", Category.RENDER);

        header("General");
        add(renderDistance);
        add(showFill);
        add(showOutline);
        add(fillAlpha);
        add(boxSize);
        add(lineWidth);
        add(distanceFade);
        add(revealAnimation);

        header("Glow");
        add(showGlow);
        add(glowIntensity);

        header("Tracers");
        add(showTracers);
        add(tracerWidth);
        add(maxTracers);

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
        revealStartNanos = System.nanoTime();
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

        double reveal = getRevealProgress();

        BlockClusterRenderer.beginFrame();

        for (SuspectObjects.Category category
                : SuspectObjects.Category.values()) {

            DetectionSettings settings =
                    detectionSettings.get(category);

            if (settings == null || !settings.enabled.value) {
                continue;
            }

            BlockLayer.Geometry geometry = layer.geometry(
                    dimension,
                    category
            );

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

            double fade = distanceFade.value
                    ? calculateDistanceFade(distance, maxDistance)
                    : 1.0;

            double visibility = fade * reveal;

            if (visibility <= 0.0) {
                continue;
            }

            int color = Colors.of(settings.color.value);

            double categoryOpacity =
                    settings.opacity.get() / 100.0;

            double globalFillOpacity =
                    fillAlpha.get() / 100.0;

            int fillOpacity = clampAlpha(
                    255.0
                            * categoryOpacity
                            * globalFillOpacity
                            * visibility
            );

            int outlineOpacity = clampAlpha(
                    235.0 * visibility
            );

            int fillColor = Proj.alpha(color, fillOpacity);
            int outlineColor = Proj.alpha(color, outlineOpacity);

            BlockClusterRenderer.render(
                    context,
                    geometry,
                    fillColor,
                    outlineColor,
                    showFill.value && fillOpacity > 0,
                    showOutline.value && settings.outline.value
                            && outlineOpacity > 0,
                    showGlow.value,
                    glowIntensity.get() * visibility,
                    showTracers.value,
                    boxSize.get(),
                    (int) Math.round(lineWidth.get()),
                    (int) Math.round(tracerWidth.get()),
                    (int) Math.round(maxTracers.get())
            );
        }
    }

    private double getRevealProgress() {
        if (!revealAnimation.value) {
            return 1.0;
        }

        long elapsed = Math.max(
                0L,
                System.nanoTime() - revealStartNanos
        );

        double progress = elapsed
                / (double) REVEAL_DURATION_NANOS;

        return Math.max(0.0, Math.min(1.0, progress));
    }

    private static double calculateDistanceFade(
            double distance,
            double maxDistance
    ) {
        double fadeStart = maxDistance * 0.70;

        if (distance <= fadeStart) {
            return 1.0;
        }

        double range = Math.max(1.0, maxDistance - fadeStart);

        double progress = (distance - fadeStart) / range;
        progress = Math.max(0.0, Math.min(1.0, progress));

        double smooth = progress * progress
                * (3.0 - 2.0 * progress);

        return 1.0 - smooth;
    }

    private static int clampAlpha(double value) {
        return (int) Math.max(
                0,
                Math.min(255, Math.round(value))
        );
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
