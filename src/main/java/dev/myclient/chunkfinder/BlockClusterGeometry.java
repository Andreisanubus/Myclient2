
package dev.myclient.chunkfinder;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public final class BlockClusterGeometry {

    private static final Direction[] DIRECTIONS = Direction.values();

    private BlockClusterGeometry() {
    }

    @FunctionalInterface
    public interface Membership {
        boolean contains(BlockPos pos);
    }

    public record Face(BlockPos pos, Direction side) {
    }

    public record Segment(Vec3d start, Vec3d end) {
    }

    public record Mesh(List<Face> faces, List<Segment> edges) {
        public Mesh {
            faces = List.copyOf(faces);
            edges = List.copyOf(edges);
        }
    }

    private record EdgeKey(
            int axis,
            int fixedA,
            int fixedB,
            int start,
            int end
    ) {
    }

    private record EdgeLine(
            int axis,
            int fixedA,
            int fixedB
    ) {
    }

    public static Mesh build(
            Collection<BlockPos> positions,
            Membership membership
    ) {
        if (positions == null || positions.isEmpty() || membership == null) {
            return new Mesh(List.of(), List.of());
        }

        List<Face> faces = new ArrayList<>();
        Map<EdgeLine, List<Integer>> edgeLines = new HashMap<>();

        for (BlockPos original : positions) {
            BlockPos pos = original.toImmutable();

            for (Direction direction : DIRECTIONS) {
                BlockPos neighbor = pos.offset(direction);

                if (membership.contains(neighbor)) {
                    continue;
                }

                faces.add(new Face(pos, direction));
                addFaceEdges(pos, direction, edgeLines);
            }
        }

        return new Mesh(faces, mergeCollinear(edgeLines));
    }

    private static void addFaceEdges(
            BlockPos pos,
            Direction side,
            Map<EdgeLine, List<Integer>> lines
    ) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        switch (side) {
            case DOWN -> {
                addEdge(lines, 0, y, z, x, x + 1);
                addEdge(lines, 0, y, z + 1, x, x + 1);
                addEdge(lines, 2, y, x, z, z + 1);
                addEdge(lines, 2, y, x + 1, z, z + 1);
            }
            case UP -> {
                addEdge(lines, 0, y + 1, z, x, x + 1);
                addEdge(lines, 0, y + 1, z + 1, x, x + 1);
                addEdge(lines, 2, y + 1, x, z, z + 1);
                addEdge(lines, 2, y + 1, x + 1, z, z + 1);
            }
            case NORTH -> {
                addEdge(lines, 0, z, y, x, x + 1);
                addEdge(lines, 0, z, y + 1, x, x + 1);
                addEdge(lines, 1, z, x, y, y + 1);
                addEdge(lines, 1, z, x + 1, y, y + 1);
            }
            case SOUTH -> {
                addEdge(lines, 0, z + 1, y, x, x + 1);
                addEdge(lines, 0, z + 1, y + 1, x, x + 1);
                addEdge(lines, 1, z + 1, x, y, y + 1);
                addEdge(lines, 1, z + 1, x + 1, y, y + 1);
            }
            case WEST -> {
                addEdge(lines, 2, x, y, z, z + 1);
                addEdge(lines, 2, x, y + 1, z, z + 1);
                addEdge(lines, 1, x, z, y, y + 1);
                addEdge(lines, 1, x, z + 1, y, y + 1);
            }
            case EAST -> {
                addEdge(lines, 2, x + 1, y, z, z + 1);
                addEdge(lines, 2, x + 1, y + 1, z, z + 1);
                addEdge(lines, 1, x + 1, z, y, y + 1);
                addEdge(lines, 1, x + 1, z + 1, y, y + 1);
            }
        }
    }

    private static void addEdge(
            Map<EdgeLine, List<Integer>> lines,
            int axis,
            int fixedA,
            int fixedB,
            int start,
            int end
    ) {
        EdgeLine key = new EdgeLine(axis, fixedA, fixedB);
        List<Integer> intervals = lines.computeIfAbsent(
                key,
                ignored -> new ArrayList<>()
        );

        intervals.add(start);
        intervals.add(end);
    }

    private static List<Segment> mergeCollinear(
            Map<EdgeLine, List<Integer>> lines
    ) {
        List<Segment> result = new ArrayList<>();

        for (Map.Entry<EdgeLine, List<Integer>> entry : lines.entrySet()) {
            EdgeLine line = entry.getKey();
            List<Integer> values = entry.getValue();

            values.sort(Integer::compareTo);

            if (values.size() < 2) {
                continue;
            }

            int start = values.get(0);
            int end = values.get(1);

            for (int i = 2; i + 1 < values.size(); i += 2) {
                int nextStart = values.get(i);
                int nextEnd = values.get(i + 1);

                if (nextStart <= end) {
                    end = Math.max(end, nextEnd);
                } else {
                    result.add(createSegment(line, start, end));
                    start = nextStart;
                    end = nextEnd;
                }
            }

            result.add(createSegment(line, start, end));
        }

        return result;
    }

    private static Segment createSegment(
            EdgeLine line,
            int start,
            int end
    ) {
        return switch (line.axis()) {
            case 0 -> new Segment(
                    new Vec3d(start, line.fixedA(), line.fixedB()),
                    new Vec3d(end, line.fixedA(), line.fixedB())
            );
            case 1 -> new Segment(
                    new Vec3d(line.fixedA(), start, line.fixedB()),
                    new Vec3d(line.fixedA(), end, line.fixedB())
            );
            case 2 -> new Segment(
                    new Vec3d(line.fixedB(), line.fixedA(), start),
                    new Vec3d(line.fixedB(), line.fixedA(), end)
            );
            default -> throw new IllegalArgumentException(
                    "Unknown edge axis: " + line.axis()
            );
        };
    }

    public static List<List<BlockPos>> connectedComponents(
            Collection<BlockPos> positions
    ) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }

        Set<BlockPos> remaining = new HashSet<>();

        for (BlockPos pos : positions) {
            remaining.add(pos.toImmutable());
        }

        List<List<BlockPos>> components = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            remaining.remove(start);
            queue.addLast(start);

            List<BlockPos> component = new ArrayList<>();

            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                component.add(current);

                for (Direction direction : DIRECTIONS) {
                    BlockPos neighbor = current.offset(direction);

                    if (remaining.remove(neighbor)) {
                        queue.addLast(neighbor);
                    }
                }
            }

            components.add(List.copyOf(component));
        }

        return List.copyOf(components);
    }
}
