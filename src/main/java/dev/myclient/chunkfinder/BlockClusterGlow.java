
package dev.myclient.chunkfinder;

import dev.myclient.render.Proj;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;

public final class BlockClusterGlow {

    private static final double NEAR_PLANE = 0.1;

    private static final int MAX_OPERATIONS_PER_FRAME = 12000;
    private static final int MAX_STEPS_PER_EDGE = 512;

    private static final int[] RADII = {6, 3, 1};
    private static final int[] ALPHAS = {18, 35, 60};

    private static int remainingOperations =
            MAX_OPERATIONS_PER_FRAME;

    private BlockClusterGlow() {
    }

    public static void beginFrame() {
        remainingOperations = MAX_OPERATIONS_PER_FRAME;
    }

    public static void render(
            DrawContext context,
            BlockClusterGeometry.Mesh mesh,
            int color,
            double intensity
    ) {
        if (context == null || mesh == null || intensity <= 0.0
                || remainingOperations <= 0) {
            return;
        }

        double strength = Math.max(0.0, Math.min(1.0, intensity));

        for (BlockClusterGeometry.Segment edge : mesh.edges()) {
            if (remainingOperations <= 0) {
                return;
            }

            renderEdge(
                    context,
                    edge.start(),
                    edge.end(),
                    color,
                    strength
            );
        }
    }

    private static void renderEdge(
            DrawContext context,
            Vec3d start,
            Vec3d end,
            int color,
            double intensity
    ) {
        double depthStart = Proj.depth(start);
        double depthEnd = Proj.depth(end);

        if (depthStart < NEAR_PLANE && depthEnd < NEAR_PLANE) {
            return;
        }

        if (depthStart < NEAR_PLANE) {
            double t = (NEAR_PLANE - depthStart)
                    / (depthEnd - depthStart);

            start = interpolate(start, end, t);
        } else if (depthEnd < NEAR_PLANE) {
            double t = (NEAR_PLANE - depthEnd)
                    / (depthStart - depthEnd);

            end = interpolate(end, start, t);
        }

        double[] screenA = Proj.to(start.x, start.y, start.z);
        double[] screenB = Proj.to(end.x, end.y, end.z);

        if (screenA == null || screenB == null
                || screenA.length < 2 || screenB.length < 2) {
            return;
        }

        double dx = screenB[0] - screenA[0];
        double dy = screenB[1] - screenA[1];

        double length = Math.sqrt(dx * dx + dy * dy);

        if (length < 1.0 || length > 10000.0) {
            return;
        }

        int steps = Math.max(
                1,
                Math.min(
                        MAX_STEPS_PER_EDGE,
                        (int) Math.ceil(length)
                )
        );

        double normalX = -dy / length;
        double normalY = dx / length;

        for (int layer = 0; layer < RADII.length; layer++) {
            if (remainingOperations < 2) {
                return;
            }

            int availableSamples = remainingOperations / 2;
            int samples = Math.min(steps + 1, availableSamples);

            if (samples <= 0) {
                return;
            }

            int radius = RADII[layer];
            int alpha = (int) Math.round(
                    ALPHAS[layer] * intensity
            );

            if (alpha <= 0) {
                continue;
            }

            int argb = Proj.alpha(color, alpha);

            int used = drawLayer(
                    context,
                    screenA,
                    screenB,
                    normalX,
                    normalY,
                    radius,
                    argb,
                    samples
            );

            remainingOperations -= used;
        }
    }

    private static int drawLayer(
            DrawContext context,
            double[] screenA,
            double[] screenB,
            double normalX,
            double normalY,
            int radius,
            int color,
            int samples
    ) {
        int used = 0;

        for (int i = 0; i < samples; i++) {
            double t = samples == 1
                    ? 0.0
                    : (double) i / (samples - 1);

            double x = screenA[0]
                    + (screenB[0] - screenA[0]) * t;

            double y = screenA[1]
                    + (screenB[1] - screenA[1]) * t;

            int offsetX = (int) Math.round(normalX * radius);
            int offsetY = (int) Math.round(normalY * radius);

            int px = (int) Math.round(x);
            int py = (int) Math.round(y);

            context.fill(
                    px + offsetX - 1,
                    py + offsetY - 1,
                    px + offsetX + 1,
                    py + offsetY + 1,
                    color
            );

            used++;

            context.fill(
                    px - offsetX - 1,
                    py - offsetY - 1,
                    px - offsetX + 1,
                    py - offsetY + 1,
                    color
            );

            used++;
        }

        return used;
    }

    private static Vec3d interpolate(
            Vec3d a,
            Vec3d b,
            double t
    ) {
        return new Vec3d(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t
        );
    }
}
