package me.aleksilassila.litematica.printer.handler.pathing;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Client-thread scope shared by the automatic clear coordinator and clear handlers. */
public final class AutoWorkAreaScope {
    private static List<PrinterBox> activeBoxes = List.of();

    private AutoWorkAreaScope() {
    }

    public static void setActiveBoxes(List<PrinterBox> boxes) {
        activeBoxes = boxes == null || boxes.isEmpty() ? List.of() : List.copyOf(boxes);
    }

    public static List<PrinterBox> getActiveBoxes() {
        return activeBoxes;
    }

    public static boolean contains(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        for (PrinterBox box : activeBoxes) {
            if (box.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    public static List<PrinterBox> intersect(List<PrinterBox> localBoxes) {
        if (localBoxes == null || localBoxes.isEmpty() || activeBoxes.isEmpty()) {
            return List.of();
        }
        List<PrinterBox> result = new ArrayList<>();
        for (PrinterBox local : localBoxes) {
            for (PrinterBox active : activeBoxes) {
                int minX = Math.max(local.minX, active.minX);
                int minY = Math.max(local.minY, active.minY);
                int minZ = Math.max(local.minZ, active.minZ);
                int maxX = Math.min(local.maxX, active.maxX);
                int maxY = Math.min(local.maxY, active.maxY);
                int maxZ = Math.min(local.maxZ, active.maxZ);
                if (minX <= maxX && minY <= maxY && minZ <= maxZ) {
                    result.add(new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ));
                }
            }
        }
        return List.copyOf(result);
    }

    public static void clear() {
        activeBoxes = List.of();
    }
}
