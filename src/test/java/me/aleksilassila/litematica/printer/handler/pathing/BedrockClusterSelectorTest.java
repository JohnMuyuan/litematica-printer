package me.aleksilassila.litematica.printer.handler.pathing;

import net.minecraft.core.BlockPos;
import java.util.List;

public final class BedrockClusterSelectorTest {
    public static void main(String[] args) {
        BlockPos lone = new BlockPos(0, 0, 0);
        BlockPos denseA = new BlockPos(30, 0, 0);
        BlockPos denseB = new BlockPos(31, 0, 0);
        BlockPos denseC = new BlockPos(32, 1, 1);
        BedrockClusterSelector.Selection result = BedrockClusterSelector.select(List.of(lone, denseA, denseB, denseC), lone);
        check(result.count() == 3, "densest cluster must win over nearest isolated bedrock");
        check(result.target().equals(denseA), "nearest stable member of densest cluster must be selected");
        check(BedrockClusterSelector.select(List.of(), lone).target() == null, "empty input must have no target");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
