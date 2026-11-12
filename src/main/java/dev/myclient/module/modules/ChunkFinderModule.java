
package dev.myclient.module.modules;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.myclient.chunkfinder.BlockBoxHelper;
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
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

public final class ChunkFinderModule extends Module {

    private final BlockLayer layer = new BlockLayer();
    private final BlockLayerConsumer consumer =
            new BlockLayerConsumer(layer);

    private final NumberSetting renderDistance =
            num("Render Distance", 64, 16, 256, 16);

    private final NumberSetting boxSize =
            num("Box Size", 1.0, 0.1, 2.0, 0.1);

    private final Setting<Boolean> showFill =
            bool("Show Fill", true);

    private final Setting<Boolean> showOutline =
            bool("Show Outline", true);

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
        add(boxSize);
        add(showFill);
        add(showOutline);

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
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            return;
        }

        // The scanner processes loaded chunks incrementally.
        // No synchronous chunk scanning is performed here.
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

        RegistryKey<World> dimension = mc.world.getRegistryKey();

        double maxDistance = renderDistance.get();
        double maxDistanceSquared = maxDistance * maxDistance;

        double playerX = mc.player.getX();
        double playerY = mc.player.getY();
        double playerZ = mc.player.getZ();

        int currentChunkX = mc.player.getChunkPos().x;
        int currentChunkZ = mc.player.getChunkPos().z;

        for (SuspectObjects.Category category
                : SuspectObjects.Category.values()) {

            DetectionSettings settings =
                    detectionSettings.get(category);

            if (settings == null || !settings.enabled.value) {
                continue;
            }

            List<BlockPos> positions =
                    layer.positions(dimension, category);

            if (positions.isEmpty()) {
                continue;
            }

            int color = Colors.of(settings.color.value);

            int opacity = (int) Math.round(
                    settings.opacity.get() * 255.0 / 100.0
            );

            int fillColor = Proj.alpha(color, opacity);
            int outlineColor = Proj.alpha(color, 235);

            for (BlockPos pos : positions) {

                // Never display blocks from the player's current chunk.
                if ((pos.getX() >> 4) == currentChunkX
                        && (pos.getZ() >> 4) == currentChunkZ) {
                    continue;
                }

                double dx = pos.getX() + 0.5 - playerX;
                double dy = pos.getY() + 0.5 - playerY;
                double dz = pos.getZ() + 0.5 - playerZ;

                if (dx * dx + dy * dy + dz * dz
                        > maxDistanceSquared) {
                    continue;
                }

                Box box = BlockBoxHelper.scaledBlockBox(
                        pos,
                        (float) boxSize.get()
                );

                if (showFill.value && opacity > 0) {
                    Proj.fillBox3D(
                            context,
                            box,
                            fillColor,
                            opacity
                    );
                }

                if (showOutline.value && settings.outline.value) {
                    Proj.boxEdges(
                            context,
                            box,
                            outlineColor
                    );
                }
            }
        }
    }

    public BlockLayer getLayer() {
        return layer;
    }
}
