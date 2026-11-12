
package dev.myclient.chunkfinder;

import dev.myclient.render.Proj;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public final class BlockClusterRenderer {

    private static final int MAX_TRACER_PIXELS_PER_FRAME = 12000;
    private static final int MAX_TRACERS_PER_FRAME = 256;

    private static int remainingTracerPixels =
            MAX_TRACER_PIXELS_PER_FRAME;

    private static int remainingTracers =
            MAX_TRACERS_PER_FRAME;

    private static final double NEAR_PLANE = 0.1;

    private BlockClusterRenderer() {
    }

    public static void beginFrame() {
        remainingTracerPixels = MAX_TRACER_PIXELS_PER_FRAME;
        remainingTracers = MAX_TRACERS_PER_FRAME;
    }

    public static void render(
            DrawContext context,
            BlockLayer.Geometry geometry,
            int fillColor,
            int outlineColor,
            boolean showFill,
            boolean showOutline
    ) {
        render(
                context,
                geometry,
                fillColor,
                outlineColor,
                showFill,
                showOutline,
                false,
                0.0,
                false
        );
    }

    public static void render(
            DrawContext context,
            BlockLayer.Geometry geometry,
            int fillColor,
            int outlineColor,
            boolean showFill,
            boolean showOutline,
            boolean showGlow,
            double glowIntensity
    ) {
        render(
                context,
                geometry,
                fillColor,
                outlineColor,
                showFill,
                showOutline,
                showGlow,
                glowIntensity,
                false
        );
    }

    public static void render(
            DrawContext context,
            BlockLayer.Geometry geometry,
            int fillColor,
            int outlineColor,
            boolean showFill,
            boolean showOutline,
            boolean showGlow,
            double glowIntensity,
            boolean showTracers
    ) {
        if (context == null || geometry == null
                || geometry.mesh() == null) {
            return;
        }

        BlockClusterGeometry.Mesh mesh = geometry.mesh();

        if (showGlow) {
            BlockClusterGlow.render(
                    context,
                    mesh,
                    outlineColor & 0xFFFFFF,
                    glowIntensity
            );
        }

        if (showFill) {
            for (BlockClusterGeometry.Face face : mesh.faces()) {
                renderFace(context, face, fillColor);
            }
        }

        if (showTracers) {
            renderTracers(
                    context,
                    geometry.components(),
                    outlineColor
            );
        }

        if (showOutline) {
            for (BlockClusterGeometry.Segment edge : mesh.edges()) {
                Proj.line3(
                        context,
                        edge.start(),
                        edge.end(),
                        outlineColor
                );
            }
        }
    }

    private static void renderTracers(
            DrawContext context,
            List<List<BlockPos>> components,
            int color
    ) {
        if (components == null || components.isEmpty()
                || remainingTracerPixels <= 0
                || remainingTracers <= 0) {
            return;
        }

        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();

        int startX = width / 2;
        int startY = height / 2;

        for (List<BlockPos> component : components) {
            if (remainingTracerPixels <= 0
                    || remainingTracers <= 0) {
                return;
            }

            if (component == null || component.isEmpty()) {
                continue;
            }

            Vec3d center = componentCenter(component);

            if (Proj.depth(center) < NEAR_PLANE) {
                continue;
            }

            double[] screen = Proj.to(
                    center.x,
                    center.y,
                    center.z
            );

            if (screen == null || screen.length < 2
                    || !Double.isFinite(screen[0])
                    || !Double.isFinite(screen[1])) {
                continue;
            }

            int endX = clamp(
                    (int) Math.round(screen[0]),
                    0,
                    Math.max(0, width - 1)
            );

            int endY = clamp(
                    (int) Math.round(screen[1]),
                    0,
                    Math.max(0, height - 1)
            );

            int used = drawLine(
                    context,
                    startX,
                    startY,
                    endX,
                    endY,
                    color
            );

            if (used > 0) {
                remainingTracerPixels -= used;
                remainingTracers--;
            }
        }
    }

    private static Vec3d componentCenter(
            List<BlockPos> component
    ) {
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;

        for (BlockPos pos : component) {
            x += pos.getX() + 0.5;
            y += pos.getY() + 0.5;
            z += pos.getZ() + 0.5;
        }

        double count = component.size();

        return new Vec3d(
                x / count,
                y / count,
                z / count
        );
    }

    private static int drawLine(
            DrawContext context,
            int x0,
            int y0,
            int x1,
            int y1,
            int color
    ) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);

        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;

        int error = dx - dy;
        int used = 0;

        while (true) {
            if (remainingTracerPixels - used <= 0) {
                break;
            }

            context.fill(
                    x0,
                    y0,
                    x0 + 1,
                    y0 + 1,
                    color
            );

            used++;

            if (x0 == x1 && y0 == y1) {
                break;
            }

            int doubledError = error * 2;

            if (doubledError > -dy) {
                error -= dy;
                x0 += sx;
            }

            if (doubledError < dx) {
                error += dx;
                y0 += sy;
            }
        }

        return used;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void renderFace(
            DrawContext context,
            BlockClusterGeometry.Face face,
            int color
    ) {
        BlockPos pos = face.pos();

        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        Vec3d[] vertices = switch (face.side()) {
            case DOWN -> new Vec3d[] {
                    new Vec3d(x, y, z),
                    new Vec3d(x, y, z + 1),
                    new Vec3d(x + 1, y, z + 1),
                    new Vec3d(x + 1, y, z)
            };

            case UP -> new Vec3d[] {
                    new Vec3d(x, y + 1, z),
                    new Vec3d(x + 1, y + 1, z),
                    new Vec3d(x + 1, y + 1, z + 1),
                    new Vec3d(x, y + 1, z + 1)
            };

            case NORTH -> new Vec3d[] {
                    new Vec3d(x, y, z),
                    new Vec3d(x + 1, y, z),
                    new Vec3d(x + 1, y + 1, z),
                    new Vec3d(x, y + 1, z)
            };

            case SOUTH -> new Vec3d[] {
                    new Vec3d(x, y, z + 1),
                    new Vec3d(x, y + 1, z + 1),
                    new Vec3d(x + 1, y + 1, z + 1),
                    new Vec3d(x + 1, y, z + 1)
            };

            case WEST -> new Vec3d[] {
                    new Vec3d(x, y, z),
                    new Vec3d(x, y + 1, z),
                    new Vec3d(x, y + 1, z + 1),
                    new Vec3d(x, y, z + 1)
            };

            case EAST -> new Vec3d[] {
                    new Vec3d(x + 1, y, z),
                    new Vec3d(x + 1, y, z + 1),
                    new Vec3d(x + 1, y + 1, z + 1),
                    new Vec3d(x + 1, y + 1, z)
            };
        };

        Proj.fillPoly(context, vertices, color);
    }
}
