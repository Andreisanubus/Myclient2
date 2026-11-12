
package dev.myclient.chunkfinder;

import dev.myclient.render.Proj;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;

public final class BlockClusterGlow {

    private static final double NEAR_PLANE = 0.1;

    private BlockClusterGlow() {
    }

    public static void render(
            DrawContext context,
            BlockClusterGeometry.Mesh mesh,
            int color,
            double intensity
    ) {
        if (context == null || mesh == null || intensity <= 0.0) {
            return;
        }

        double strength = Math.max(0.0, Math.min(1.0, intensity));

        for (BlockClusterGeometry.Segment edge : mesh.edges()) {
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

        Vec3d a = start;
        Vec3d b = end;

        if (depthStart < NEAR_PLANE) {
            double t = (NEAR_PLANE - depthStart)
                    / (depthEnd - depthStart);

            a = start.add(end.subtract(start).multiply(t));
        } else if (depthEnd < NEAR_PLANE) {
            double t = (NEAR_PLANE - depthStart)
                    / (depthEnd - depthStart);

            b = start.add(end.subtract(start).multiply(t));
        }

        double[] screenA = Proj.to(a.x, a.y, a.z);
        double[] screenB = Proj.to(b.x, b.y, b.z);

        if (screenA == null || screenB == null) {
            return;
        }

        double dx = screenB[0] - screenA[0];
        double dy = screenB[1] - screenA[1];

        double length = Math.sqrt(dx * dx + dy * dy);

        if (length < 0.5 || length > 10000.0) {
            return;
        }

        int steps = Math.max(1, Math.min(4096, (int) Math.ceil(length)));

        double nx = -dy / length;
        double ny = dx / length;

        drawLayer(
                context, screenA, screenB,
                nx, ny, color,
                7.0, (int) (13 * intensity), steps
        );

        drawLayer(
                context, screenA, screenB,
                nx, ny, color,
                5.0, (int) (22 * intensity), steps
        );

        drawLayer(
                context, screenA, screenB,
                nx, ny, color,
                3.0, (int) (38 * intensity), steps
        );

        drawLayer(
                context, screenA, screenB,
                nx, ny, color,
                1.0, (int) (65 * intensity), steps
        );
    }

    private static void drawLayer(
            DrawContext context,
            double[] start,
            double[] end,
            double nx,
            double ny,
            int color,
            double radius,
            int alpha,
            int steps
    ) {
        if (alpha <= 0) {
            return;
        }

        int argb = Proj.alpha(color, alpha);

        double dx = end[0] - start[0];
        double dy = end[1] - start[1];

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;

            int x = (int) Math.round(start[0] + dx * t);
            int y = (int) Math.round(start[1] + dy * t);

            int offset = (int) Math.ceil(radius);

            context.fill(
                    (int) Math.round(x + nx * radius) - offset,
                    (int) Math.round(y + ny * radius) - offset,
                    (int) Math.round(x + nx * radius) + offset + 1,
                    (int) Math.round(y + ny * radius) + offset + 1,
                    argb
            );

            context.fill(
                    (int) Math.round(x - nx * radius) - offset,
                    (int) Math.round(y - ny * radius) - offset,
                    (int) Math.round(x - nx * radius) + offset + 1,
                    (int) Math.round(y - ny * radius) + offset + 1,
                    argb
            );
        }
    }
}
