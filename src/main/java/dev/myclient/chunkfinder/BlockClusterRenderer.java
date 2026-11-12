
package dev.myclient.chunkfinder;

import dev.myclient.render.Proj;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public final class BlockClusterRenderer {

    private BlockClusterRenderer() {
    }

    public static void render(
            DrawContext context,
            Collection<BlockPos> positions,
            int fillColor,
            int outlineColor,
            boolean showFill,
            boolean showOutline
    ) {
        if (context == null || positions == null || positions.isEmpty()) {
            return;
        }

        Set<BlockPos> membership = new HashSet<>();

        for (BlockPos pos : positions) {
            if (pos != null) {
                membership.add(pos.toImmutable());
            }
        }

        if (membership.isEmpty()) {
            return;
        }

        BlockClusterGeometry.Mesh mesh =
                BlockClusterGeometry.build(
                        membership,
                        membership::contains
                );

        if (showFill) {
            for (BlockClusterGeometry.Face face : mesh.faces()) {
                Proj.fillPoly(
                        context,
                        faceVertices(face),
                        fillColor
                );
            }
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

    private static Vec3d[] faceVertices(
            BlockClusterGeometry.Face face
    ) {
        BlockPos pos = face.pos();

        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        return switch (face.side()) {
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
    }
}
