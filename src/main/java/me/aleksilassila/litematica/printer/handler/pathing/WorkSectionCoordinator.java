package me.aleksilassila.litematica.printer.handler.pathing;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Splits a fixed Litematica work area into balanced horizontal sections. */
final class WorkSectionCoordinator {
    private static final Comparator<PrinterBox> BOX_ORDER = Comparator
            .comparingInt((PrinterBox box) -> box.minX)
            .thenComparingInt(box -> box.minY)
            .thenComparingInt(box -> box.minZ)
            .thenComparingInt(box -> box.maxX)
            .thenComparingInt(box -> box.maxY)
            .thenComparingInt(box -> box.maxZ);

    private List<PrinterBox> sourceBoxes = List.of();
    private List<PrinterBox> sections = List.of();
    private final Set<Integer> completed = new HashSet<>();
    private final Set<Integer> deferred = new HashSet<>();
    private final Set<Integer> postponedSparse = new HashSet<>();
    private boolean denseBedrockPass = true;
    private int active = -1;
    private int configuredShort = -1;
    private int configuredLong = -1;
    private int selectionContextHash = Integer.MIN_VALUE;

    UpdateResult update(List<PrinterBox> boxes, BlockPos playerPos, int shortSize, int longSize,
                        int selectionContextHash) {
        List<PrinterBox> snapshot = normalize(boxes);
        int normalizedShort = Math.max(1, shortSize);
        int normalizedLong = Math.max(normalizedShort, longSize);
        int previousActive = this.active;
        boolean sourceChanged = !snapshot.equals(this.sourceBoxes);
        boolean sectionSizeChanged = normalizedShort != this.configuredShort
                || normalizedLong != this.configuredLong;
        boolean contextChanged = selectionContextHash != this.selectionContextHash;
        this.selectionContextHash = selectionContextHash;

        boolean rebuilt = sourceChanged || sectionSizeChanged;
        String rebuildReason = "none";
        if (rebuilt) {
            if (sourceChanged && sectionSizeChanged) {
                rebuildReason = "source-and-section-size";
            } else if (sourceChanged) {
                rebuildReason = "source-boxes";
            } else {
                rebuildReason = "section-size";
            }
            this.sourceBoxes = snapshot;
            this.configuredShort = normalizedShort;
            this.configuredLong = normalizedLong;
            this.sections = partition(snapshot, normalizedShort, normalizedLong);
            this.completed.clear();
            this.deferred.clear();
            this.postponedSparse.clear();
            this.denseBedrockPass = true;
            this.active = this.findNearest(playerPos);
        } else if (this.active < 0 && this.completed.size() < this.sections.size()) {
            this.active = this.findNearest(playerPos);
        }

        return new UpdateResult(this.getActiveBoxes(), rebuilt, previousActive != this.active,
                contextChanged, rebuildReason);
    }

    boolean completeAndAdvance(BlockPos playerPos) {
        if (this.active < 0) {
            return false;
        }
        this.completed.add(this.active);
        this.deferred.clear();
        this.active = this.findNearest(playerPos);
        if (this.active < 0 && this.denseBedrockPass && !this.postponedSparse.isEmpty()) {
            this.denseBedrockPass = false;
            this.postponedSparse.clear();
            this.active = this.findNearest(playerPos);
        }
        return this.active >= 0;
    }

    boolean postponeSparseAndAdvance(BlockPos playerPos) {
        if (this.active < 0 || !this.denseBedrockPass) return false;
        this.postponedSparse.add(this.active);
        this.active = this.findNearest(playerPos);
        if (this.active >= 0) return true;
        this.denseBedrockPass = false;
        this.postponedSparse.clear();
        this.active = this.findNearest(playerPos);
        return this.active >= 0;
    }

    boolean isDenseBedrockPass() {
        return this.denseBedrockPass;
    }

    boolean deferAndAdvance(BlockPos playerPos) {
        if (this.active < 0) {
            return false;
        }
        int previous = this.active;
        this.deferred.add(previous);
        this.active = this.findNearest(playerPos);
        if (this.active >= 0) {
            return true;
        }
        this.deferred.clear();
        this.deferred.add(previous);
        this.active = this.findNearest(playerPos);
        if (this.active >= 0) {
            return true;
        }
        this.deferred.remove(previous);
        this.active = previous;
        return false;
    }

    List<PrinterBox> getActiveBoxes() {
        return this.active < 0 ? List.of() : List.of(this.sections.get(this.active));
    }

    boolean contains(BlockPos pos) {
        return this.active >= 0 && this.sections.get(this.active).contains(pos);
    }

    boolean isComplete() {
        return !this.sections.isEmpty() && this.completed.size() >= this.sections.size();
    }

    int activeNumber() {
        return this.active < 0 ? Math.min(this.completed.size(), this.sections.size()) : this.active + 1;
    }

    int sectionCount() {
        return this.sections.size();
    }

    void reset() {
        this.sourceBoxes = List.of();
        this.sections = List.of();
        this.completed.clear();
        this.deferred.clear();
        this.postponedSparse.clear();
        this.denseBedrockPass = true;
        this.active = -1;
        this.configuredShort = -1;
        this.configuredLong = -1;
        this.selectionContextHash = Integer.MIN_VALUE;
    }

    private int findNearest(BlockPos origin) {
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < this.sections.size(); index++) {
            if (this.completed.contains(index) || this.deferred.contains(index)
                    || (this.denseBedrockPass && this.postponedSparse.contains(index))) {
                continue;
            }
            PrinterBox box = this.sections.get(index);
            BlockPos nearest = new BlockPos(
                    clamp(origin.getX(), box.minX, box.maxX),
                    clamp(origin.getY(), box.minY, box.maxY),
                    clamp(origin.getZ(), box.minZ, box.maxZ));
            double distance = origin.distSqr(nearest);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    private static List<PrinterBox> normalize(List<PrinterBox> boxes) {
        List<PrinterBox> normalized = new ArrayList<>(new HashSet<>(boxes));
        normalized.sort(BOX_ORDER);
        return List.copyOf(normalized);
    }

    private static List<PrinterBox> partition(List<PrinterBox> boxes, int configuredShort, int configuredLong) {
        int shortSize = Math.max(1, configuredShort);
        int longSize = Math.max(shortSize, configuredLong);
        List<PrinterBox> result = new ArrayList<>();
        Set<PrinterBox> unique = new HashSet<>();
        for (PrinterBox box : boxes) {
            int width = box.maxX - box.minX + 1;
            int depth = box.maxZ - box.minZ + 1;
            boolean xLong = width >= depth;
            int targetX = xLong ? longSize : shortSize;
            int targetZ = xLong ? shortSize : longSize;
            int partsX = Math.max(1, (width + targetX - 1) / targetX);
            int partsZ = Math.max(1, (depth + targetZ - 1) / targetZ);
            for (int zPart = 0; zPart < partsZ; zPart++) {
                int minZ = box.minZ + zPart * depth / partsZ;
                int maxZ = box.minZ + (zPart + 1) * depth / partsZ - 1;
                boolean reverse = (zPart & 1) != 0;
                for (int step = 0; step < partsX; step++) {
                    int xPart = reverse ? partsX - 1 - step : step;
                    int minX = box.minX + xPart * width / partsX;
                    int maxX = box.minX + (xPart + 1) * width / partsX - 1;
                    PrinterBox section = new PrinterBox(minX, box.minY, minZ, maxX, box.maxY, maxZ);
                    if (unique.add(section)) {
                        result.add(section);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    record UpdateResult(List<PrinterBox> activeBoxes, boolean rebuilt, boolean activeChanged,
                        boolean selectionContextChanged, String rebuildReason) {
        boolean requiresLocalRestart() {
            return this.rebuilt || this.activeChanged;
        }
    }
}