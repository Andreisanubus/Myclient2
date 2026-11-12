
package dev.myclient.chunkfinder;

import dev.myclient.render.Proj;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public final class BlockClusterTracer {

    private static final int MAX_TRACERS_PER_FRAME = 256;
    private static final int MAX_PIXELS_PER_FRAME = 24000;
    private static final int MAX_STEPS_PER_TRACER = 4096;

    private static int remainingTracers;
    private static int remainingPixels;

    private BlockClusterTracer() {
    }

    public static void beginFrame() {
        remainingTracers = MAX_TRACERS_PER_FRAME;
        remainingPixels = MAX_PIXELS_PER_FRAME;
    }

    public static void render(
            DrawContext context,
            BlockLayer.Geometry geometry,
            int color,
            int lineWidth,
            int maxTracers
    ) {
        if (context == null || geometry == null) {
            return;
        }

        if (remainingTracers <= 0 || remainingPixels <= 0) {
            return;
        }

        List<List<BlockPos>> components = geometry.components();

        if (components == null || components.isEmpty()) {
            return;
        }

        Vec3d camera = Proj.camPos();

        if (camera == null) {
            return;
        }

        int width = Math.max(1, Math.min(5, lineWidth));
        int limit = Math.max(1, Math.min(MAX_TRACERS_PER_FRAME, maxTracers));

        int startX = Proj.sw / 2;
        int startY = Proj.sh / 2;

        int rendered = 0;

        for (List<BlockPos> component : components) {
            if (rendered >= limit
                    || remainingTracers <= 0
                    || remainingPixels <= 0) {
                break;
            }

            Vec3d target = findNearestBlock(component, camera);

            if (target == null) {
                continue;
            }

            if (Proj.depth(target) < 0.1) {
                continue;
            }

            double[] screen = Proj.to(
                    target.x,
                    target.y,
                    target.z
            );

            if (screen == null || screen.length < 2) {
                continue;
            }

            if (!Double.isFinite(screen[0])
                    || !Double.isFinite(screen[1])) {
                continue;
            }

            int endX = (int) Math.round(screen[0]);
            int endY = (int) Math.round(screen[1]);

            if (!clipToScreen(
                    startX,
                    startY,
                    endX,
                    endY
            )) {
                continue;
            }

            int[] clipped = clipLine(
                    startX,
                    startY,
                    endX,
                    endY,
                    Proj.sw,
                    Proj.sh
            );

            if (clipped == null) {
                continue;
            }

            int used = drawLine(
                    context,
                    clipped[0],
                    clipped[1],
                    clipped[2],
                    clipped[3],
                    color,
                    width
            );

            if (used > 0) {
                remainingTracers--;
                rendered++;
            }
        }
    }

    private static Vec3d findNearestBlock(
            List<BlockPos> positions,
            Vec3d camera
    ) {
        if (positions == null || positions.isEmpty()) {
            return null;
        }

        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (BlockPos pos : positions) {
            if (pos == null) {
                continue;
            }

            double x = pos.getX() + 0.5;
            double y = pos.getY() + 0.5;
            double z = pos.getZ() + 0.5;

            double dx = x - camera.x;
            double dy = y - camera.y;
            double dz = z - camera.z;

            double distance = dx * dx + dy * dy + dz * dz;

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = pos;
            }
        }

        if (nearest == null) {
            return null;
        }

        return new Vec3d(
                nearest.getX() + 0.5,
                nearest.getY() + 0.5,
                nearest.getZ() + 0.5
        );
    }

    private static boolean clipToScreen(
            int x1,
            int y1,
            int x2,
            int y2
    ) {
        return Proj.sw > 0
                && Proj.sh > 0
                && !(x1 < 0 && x2 < 0)
                && !(y1 < 0 && y2 < 0)
                && !(x1 >= Proj.sw && x2 >= Proj.sw)
                && !(y1 >= Proj.sh && y2 >= Proj.sh);
    }

    private static int[] clipLine(
            int x1,
            int y1,
            int x2,
            int y2,
            int screenWidth,
            int screenHeight
    ) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return null;
        }

        double dx = x2 - x1;
        double dy = y2 - y1;

        double t0 = 0.0;
        double t1 = 1.0;

        double[] p = {
                -dx,
                dx,
                -dy,
                dy
        };

        double[] q = {
                x1,
                screenWidth - 1.0 - x1,
                y1,
                screenHeight - 1.0 - y1
        };

        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 0.000001) {
                if (q[i] < 0) {
                    return null;
                }
            } else {
                double t = q[i] / p[i];

                if (p[i] < 0) {
                    t0 = Math.max(t0, t);
                } else {
                    t1 = Math.min(t1, t);
                }

                if (t0 > t1) {
                    return null;
                }
            }
        }

        int clippedX1 = (int) Math.round(x1 + t0 * dx);
        int clippedY1 = (int) Math.round(y1 + t0 * dy);
        int clippedX2 = (int) Math.round(x1 + t1 * dx);
        int clippedY2 = (int) Math.round(y1 + t1 * dy);

        return new int[]{
                clippedX1,
                clippedY1,
                clippedX2,
                clippedY2
        };
    }

    private static int drawLine(
            DrawContext context,
            int x1,
            int y1,
            int x2,
            int y2,
            int color,
            int width
    ) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);

        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;

        int error = dx - dy;
        int steps = 0;
        int used = 0;

        int offset = width / 2;

        while (steps < MAX_STEPS_PER_TRACER
                && remainingPixels > 0) {

            int left = x1 - offset;
            int top = y1 - offset;
            int right = left + width;
            int bottom = top + width;

            context.fill(left, top, right, bottom, color);

            remainingPixels--;
            used++;
            steps++;

            if (x1 == x2 && y1 == y2) {
                break;
            }

            int doubleError = error * 2;

            if (doubleError > -dy) {
                error -= dy;
                x1 += sx;
            }

            if (doubleError < dx) {
                error += dx;
                y1 += sy;
            }
        }

        return used;
    }
}
