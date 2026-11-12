
package dev.myclient.chunkfinder;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class BlockClusterRenderer {

    private BlockClusterRenderer() {
    }

    public static void render(
            DrawContext context,
            BlockLayer.Geometry geometry,
            int fillColor,
            int outlineColor,
            boolean showFill,
            boolean showOutline
    ) {
        if (context == null || geometry == null) {
            return;
        }

        BlockClusterGeometry.Mesh mesh = geometry.mesh();

        if (mesh == null) {
            return;
        }

        if (showFill) {
            for (BlockClusterGeometry.Face face : mesh.faces()) {
                ProjFill(context, face, fillColor);
            }
        }

        if (showOutline) {
            for (BlockClusterGeometry.Segment edge : mesh.edges()) {
                dev.myclient.render.Proj.line3(
                        context,
                        edge.start(),
                        edge.end(),
                        outlineColor
                );
            }
        }
    }

    private static void ProjFill(
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

        dev.myclient.render.Proj.fillPoly(
                context,
                vertices,
                color
        );
    }
}
