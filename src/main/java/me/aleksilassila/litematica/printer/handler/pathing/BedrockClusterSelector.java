package me.aleksilassila.litematica.printer.handler.pathing;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BedrockClusterSelector {
    private static final int HORIZONTAL_RADIUS = 8;
    private static final int VERTICAL_RADIUS = 4;
    private static final int HORIZONTAL_CELL = HORIZONTAL_RADIUS + 1;
    private static final int VERTICAL_CELL = VERTICAL_RADIUS + 1;

    private BedrockClusterSelector() {}

    static Selection select(List<BlockPos> candidates, BlockPos origin) {
        Map<Cell, List<BlockPos>> buckets = new HashMap<>();
        for (BlockPos candidate : candidates) {
            buckets.computeIfAbsent(cell(candidate), ignored -> new ArrayList<>()).add(candidate);
        }
        BlockPos best = null;
        int bestCount = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos candidate : candidates) {
            Cell center = cell(candidate);
            int count = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                for (BlockPos other : buckets.getOrDefault(center.offset(dx, dy, dz), List.of())) {
                    if (belongsToCluster(candidate, other)) count++;
                }
            }
            double distance = candidate.distSqr(origin);
            if (count > bestCount || (count == bestCount && (distance < bestDistance
                    || (distance == bestDistance && compare(candidate, best) < 0)))) {
                best = candidate; bestCount = count; bestDistance = distance;
            }
        }
        return new Selection(best, bestCount);
    }

    static boolean belongsToCluster(BlockPos center, BlockPos candidate) {
        return Math.abs(center.getX() - candidate.getX()) <= HORIZONTAL_RADIUS
                && Math.abs(center.getY() - candidate.getY()) <= VERTICAL_RADIUS
                && Math.abs(center.getZ() - candidate.getZ()) <= HORIZONTAL_RADIUS;
    }

    private static Cell cell(BlockPos pos) {
        return new Cell(Math.floorDiv(pos.getX(), HORIZONTAL_CELL), Math.floorDiv(pos.getY(), VERTICAL_CELL),
                Math.floorDiv(pos.getZ(), HORIZONTAL_CELL));
    }
    private static int compare(BlockPos left, BlockPos right) {
        if (right == null) return -1;
        int x = Integer.compare(left.getX(), right.getX());
        if (x != 0) return x;
        int y = Integer.compare(left.getY(), right.getY());
        return y != 0 ? y : Integer.compare(left.getZ(), right.getZ());
    }
    private record Cell(int x, int y, int z) { Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); } }
    record Selection(BlockPos target, int count) {}
}
