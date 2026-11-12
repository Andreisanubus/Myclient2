
package dev.myclient.chunkfinder;

import dev.myclient.render.Proj;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;

public final class BlockClusterGlow {

    private static final double NEAR_PLANE = 0.1;

    private static final int MAX_OPERATIONS_PER_RENDER = 12_000;
    private static final int MAX_STEPS_PER_EDGE = 512;

    private static final double[] RADII = {
            6.0,
            3.0,
            1.0
    };

    private static final int[] ALPHAS = {
            18,
            35,
            60
    };

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

        int operationBudget = MAX_OPERATIONS_PER_RENDER;

        for (BlockClusterGeometry.Segment edge : mesh.edges()) {
            if (operationBudget <= 0) {
                break;
            }

            operationBudget -= renderEdge(
                    context,
                    edge.start(),
                    edge.end(),
                    color,
                    strength,
                    operationBudget
            );
        }
    }

    private static int renderEdge(
            DrawContext context,
            Vec3d start,
            Vec3d end,
            int color,
            double intensity,
            int operationBudget
    ) {
        double depthStart = Proj.depth(start);
        double depthEnd = Proj.depth(end);

        if (depthStart < NEAR_PLANE && depthEnd < NEAR_PLANE) {
            return 0;
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
            return 0;
        }

        double dx = screenB[0] - screenA[0];
        double dy = screenB[1] - screenA[1];

        double length = Math.sqrt(dx * dx + dy * dy);

        if (length < 0.5 || length > 10000.0) {
            return 0;
        }

        int steps = Math.max(
                1,
                Math.min(MAX_STEPS_PER_EDGE, (int) Math.ceil(length))
        );

        double nx = -dy / length;
        double ny = dx / length;

        int used = 0;

        for (int layer = 0; layer < RADII.length; layer++) {
            if (used + 2 > operationBudget) {
                break;
            }

            int alpha = (int) (ALPHAS[layer] * intensity);

            if (alpha <= 0) {
                continue;
            }

            int remaining = operationBudget - used;
            int availableSteps = remaining / 2;

            if (availableSteps <= 0) {
                break;
            }

            int layerSteps = Math.min(steps, availableSteps);

            used += drawLayer(
                    context,
                    screenA,
                    screenB,
                    nx,
                    ny,
                    color,
                    RADII[layer],
                    alpha,
                    layerSteps
            );
        }

        return used;
    }

    private static int drawLayer(
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
        if (alpha <= 0 || steps <= 0) {
            return 0;
        }

        int argb = Proj.alpha(color, alpha);

        double dx = end[0] - start[0];
        double dy = end[1] - start[1];

        int offset = (int) Math.ceil(radius);
        int operations = 0;

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;

            int x = (int) Math.round(start[0] + dx * t);
            int y = (int) Math.round(start[1] + dy * t);

            int positiveX = (int) Math.round(x + nx * radius);
            int positiveY = (int) Math.round(y + ny * radius);

            context.fill(
                    positiveX - offset,
                    positiveY - offset,
                    positiveX + offset + 1,
                    positiveY + offset + 1,
                    argb
            );

            operations++;

            int negativeX = (int) Math.round(x - nx * radius);
            int negativeY = (int) Math.round(y - ny * radius);

            context.fill(
                    negativeX - offset,
                    negativeY - offset,
                    negativeX + offset + 1,
                    negativeY + offset + 1,
                    argb
            );

            operations++;
        }

        return operations;
    }
}
