
package dev.myclient.chunkfinder;

import dev.myclient.render.Proj;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

public final class BlockClusterRenderer {

    private static final double NEAR_PLANE = 0.1;
    private static final int MAX_OUTLINE_PIXELS_PER_FRAME = 40000;
    private static final int MAX_STEPS_PER_EDGE = 4096;

    private static int remainingOutlinePixels =
            MAX_OUTLINE_PIXELS_PER_FRAME;

    private BlockClusterRenderer() {
    }

    public static void beginFrame() {
        remainingOutlinePixels = MAX_OUTLINE_PIXELS_PER_FRAME;
        BlockClusterGlow.beginFrame();
        BlockClusterTracer.beginFrame();
    }

    public static void render(
            DrawContext context,
            BlockLayer.Geometry geometry,
            int fillColor,
            int outlineColor,
            boolean showFill,
            boolean showOutline
    ) {
        render(context, geometry, fillColor, outlineColor,
                showFill, showOutline, false, 0.0, false,
                1.0, 1, 1, 256);
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
        render(context, geometry, fillColor, outlineColor,
                showFill, showOutline, showGlow, glowIntensity,
                false, 1.0, 1, 1, 256);
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
        render(context, geometry, fillColor, outlineColor,
                showFill, showOutline, showGlow, glowIntensity,
                showTracers, 1.0, 1, 1, 256);
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
            boolean showTracers,
            double boxSize,
            int lineWidth,
            int tracerWidth,
            int maxTracers
    ) {
        if (context == null || geometry == null
                || geometry.mesh() == null) {
            return;
        }

        BlockClusterGeometry.Mesh mesh = geometry.mesh();

        double safeBoxSize = Math.max(0.1, Math.min(2.0, boxSize));
        int safeLineWidth = Math.max(1, Math.min(5, lineWidth));
        int safeTracerWidth = Math.max(1, Math.min(5, tracerWidth));
        int safeMaxTracers = Math.max(1, Math.min(256, maxTracers));

        if (showGlow) {
            BlockClusterGlow.render(
                    context,
                    mesh,
                    outlineColor & 0xFFFFFF,
                    glowIntensity
            );
        }

        Set<LineKey> drawnEdges = showOutline ? new HashSet<>() : Set.of();

        for (BlockClusterGeometry.Face face : mesh.faces()) {
            Vec3d[] vertices = faceVertices(face, safeBoxSize);

            if (showFill) {
                Proj.fillPoly(context, vertices, fillColor);
            }

            if (showOutline && remainingOutlinePixels > 0) {
                for (int i = 0; i < vertices.length; i++) {
                    Vec3d start = vertices[i];
                    Vec3d end = vertices[(i + 1) % vertices.length];

                    if (drawnEdges.add(LineKey.of(start, end))) {
                        renderThickLine(
                                context,
                                start,
                                end,
                                outlineColor,
                                safeLineWidth
                        );
                    }
                }
            }
        }

        if (showTracers) {
            BlockClusterTracer.render(
                    context,
                    geometry,
                    outlineColor,
                    safeTracerWidth,
                    safeMaxTracers
            );
        }
    }

    private record LineKey(long ax, long ay, long az, long bx, long by, long bz) {
        static LineKey of(Vec3d first, Vec3d second) {
            long ax = Math.round(first.x * 10000.0);
            long ay = Math.round(first.y * 10000.0);
            long az = Math.round(first.z * 10000.0);
            long bx = Math.round(second.x * 10000.0);
            long by = Math.round(second.y * 10000.0);
            long bz = Math.round(second.z * 10000.0);

            boolean swap = ax > bx || (ax == bx && ay > by)
                    || (ax == bx && ay == by && az > bz);
            return swap
                    ? new LineKey(bx, by, bz, ax, ay, az)
                    : new LineKey(ax, ay, az, bx, by, bz);
        }
    }

    private static Vec3d[] faceVertices(
            BlockClusterGeometry.Face face,
            double size
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

        if (Math.abs(size - 1.0) < 0.0001) {
            return vertices;
        }

        double centerX = x + 0.5;
        double centerY = y + 0.5;
        double centerZ = z + 0.5;

        for (int i = 0; i < vertices.length; i++) {
            Vec3d vertex = vertices[i];

            vertices[i] = new Vec3d(
                    centerX + (vertex.x - centerX) * size,
                    centerY + (vertex.y - centerY) * size,
                    centerZ + (vertex.z - centerZ) * size
            );
        }

        return vertices;
    }

    private static void renderThickLine(
            DrawContext context,
            Vec3d start,
            Vec3d end,
            int color,
            int width
    ) {
        if (remainingOutlinePixels <= 0) {
            return;
        }

        if (width <= 1) {
            Proj.line3(context, start, end, color);
            return;
        }

        if (Proj.depth(start) < NEAR_PLANE
                || Proj.depth(end) < NEAR_PLANE) {
            Proj.line3(context, start, end, color);
            return;
        }

        double[] a = Proj.to(start.x, start.y, start.z);
        double[] b = Proj.to(end.x, end.y, end.z);

        if (!validScreenPoint(a) || !validScreenPoint(b)) {
            Proj.line3(context, start, end, color);
            return;
        }

        drawScreenLine(
                context,
                (int) Math.round(a[0]),
                (int) Math.round(a[1]),
                (int) Math.round(b[0]),
                (int) Math.round(b[1]),
                color,
                width
        );
    }

    private static boolean validScreenPoint(double[] point) {
        return point != null
                && point.length >= 2
                && Double.isFinite(point[0])
                && Double.isFinite(point[1]);
    }

    private static void drawScreenLine(
            DrawContext context,
            int x0,
            int y0,
            int x1,
            int y1,
            int color,
            int width
    ) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);

        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;

        int error = dx - dy;
        int steps = 0;
        int offset = width / 2;

        while (steps < MAX_STEPS_PER_EDGE
                && remainingOutlinePixels > 0) {

            context.fill(
                    x0 - offset,
                    y0 - offset,
                    x0 - offset + width,
                    y0 - offset + width,
                    color
            );

            remainingOutlinePixels--;
            steps++;

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
    }
}
