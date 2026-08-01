package me.aleksilassila.litematica.printer.handler.pathing;

import net.minecraft.core.BlockPos;

final class BedrockLocalProximityPolicy {
    private BedrockLocalProximityPolicy() {
    }

    static boolean isWithin(BlockPos playerFeet, BlockPos bedrock, int maximumDistance) {
        if (playerFeet == null || bedrock == null) {
            return false;
        }
        int limit = Math.max(1, maximumDistance);
        return Math.abs(playerFeet.getX() - bedrock.getX()) <= limit
                && Math.abs(playerFeet.getY() - bedrock.getY()) <= limit
                && Math.abs(playerFeet.getZ() - bedrock.getZ()) <= limit;
    }
}
