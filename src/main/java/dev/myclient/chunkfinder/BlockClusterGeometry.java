
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

    private record EdgeLine(int axis, int a, int b) {
    }

    private record UnitEdge(EdgeLine line, int start) {
    }

    public static Mesh build(
            Collection<BlockPos> positions,
            Membership membership
    ) {
        if (positions == null || positions.isEmpty() || membership == null) {
            return new Mesh(List.of(), List.of());
        }

        List<Face> faces = new ArrayList<>();
        Set<UnitEdge> edges = new HashSet<>();

        for (BlockPos original : positions) {
            BlockPos pos = original.toImmutable();

            for (Direction side : DIRECTIONS) {
                if (membership.contains(pos.offset(side))) {
                    continue;
                }

                faces.add(new Face(pos, side));
                addFaceEdges(pos, side, edges);
            }
        }

        return new Mesh(faces, mergeEdges(edges));
    }

    private static void addFaceEdges(
            BlockPos pos,
            Direction side,
            Set<UnitEdge> edges
    ) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        switch (side) {
            case DOWN -> {
                addEdge(edges, 0, y, z, x);
                addEdge(edges, 0, y, z + 1, x);
                addEdge(edges, 2, x, y, z);
                addEdge(edges, 2, x + 1, y, z);
            }
            case UP -> {
                addEdge(edges, 0, y + 1, z, x);
                addEdge(edges, 0, y + 1, z + 1, x);
                addEdge(edges, 2, x, y + 1, z);
                addEdge(edges, 2, x + 1, y + 1, z);
            }
            case NORTH -> {
                addEdge(edges, 0, y, z, x);
                addEdge(edges, 0, y + 1, z, x);
                addEdge(edges, 1, x, z, y);
                addEdge(edges, 1, x + 1, z, y);
            }
            case SOUTH -> {
                addEdge(edges, 0, y, z + 1, x);
                addEdge(edges, 0, y + 1, z + 1, x);
                addEdge(edges, 1, x, z + 1, y);
                addEdge(edges, 1, x + 1, z + 1, y);
            }
            case WEST -> {
                addEdge(edges, 1, x, z, y);
                addEdge(edges, 1, x, z + 1, y);
                addEdge(edges, 2, x, y, z);
                addEdge(edges, 2, x, y + 1, z);
            }
            case EAST -> {
                addEdge(edges, 1, x + 1, z, y);
                addEdge(edges, 1, x + 1, z + 1, y);
                addEdge(edges, 2, x + 1, y, z);
                addEdge(edges, 2, x + 1, y + 1, z);
            }
        }
    }

    private static void addEdge(
            Set<UnitEdge> edges,
            int axis,
            int a,
            int b,
            int start
    ) {
        edges.add(new UnitEdge(new EdgeLine(axis, a, b), start));
    }

    private static List<Segment> mergeEdges(Set<UnitEdge> edges) {
        Map<EdgeLine, TreeSet<Integer>> lines = new HashMap<>();

        for (UnitEdge edge : edges) {
            lines.computeIfAbsent(
                    edge.line(),
                    ignored -> new TreeSet<>()
            ).add(edge.start());
        }

        List<Segment> result = new ArrayList<>();

        for (Map.Entry<EdgeLine, TreeSet<Integer>> entry : lines.entrySet()) {
            EdgeLine line = entry.getKey();
            Iterator<Integer> iterator = entry.getValue().iterator();

            if (!iterator.hasNext()) {
                continue;
            }

            int start = iterator.next();
            int previous = start;

            while (iterator.hasNext()) {
                int current = iterator.next();

                if (current == previous + 1) {
                    previous = current;
                    continue;
                }

                result.add(createSegment(line, start, previous + 1));
                start = current;
                previous = current;
            }

            result.add(createSegment(line, start, previous + 1));
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
                    new Vec3d(start, line.a(), line.b()),
                    new Vec3d(end, line.a(), line.b())
            );
            case 1 -> new Segment(
                    new Vec3d(line.a(), start, line.b()),
                    new Vec3d(line.a(), end, line.b())
            );
            case 2 -> new Segment(
                    new Vec3d(line.a(), line.b(), start),
                    new Vec3d(line.a(), line.b(), end)
            );
            default -> throw new IllegalArgumentException("Invalid axis");
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
