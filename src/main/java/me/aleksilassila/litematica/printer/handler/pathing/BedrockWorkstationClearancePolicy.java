package me.aleksilassila.litematica.printer.handler.pathing;

import net.minecraft.core.BlockPos;

final class BedrockWorkstationClearancePolicy {
    private BedrockWorkstationClearancePolicy() {
    }

    static boolean blocksMachine(BlockPos playerFeet, BlockPos pistonPos, BlockPos headPos) {
        if (playerFeet == null) {
            return false;
        }
        BlockPos playerHead = playerFeet.above();
        return overlaps(playerFeet, pistonPos, headPos) || overlaps(playerHead, pistonPos, headPos);
    }

    static boolean blocksWorkstation(BlockPos playerFeet, BlockPos target,
                                     BlockPos pistonPos, BlockPos headPos) {
        return standsOnTarget(playerFeet, target) || blocksMachine(playerFeet, pistonPos, headPos);
    }

    private static boolean standsOnTarget(BlockPos playerFeet, BlockPos target) {
        return playerFeet != null && target != null && playerFeet.below().equals(target);
    }

    private static boolean overlaps(BlockPos playerPart, BlockPos pistonPos, BlockPos headPos) {
        return playerPart.equals(pistonPos) || playerPart.equals(headPos);
    }
}
