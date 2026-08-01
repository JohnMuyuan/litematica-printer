package me.aleksilassila.litematica.printer.handler.pathing;

import net.minecraft.core.BlockPos;

public final class BedrockLocalProximityPolicyTest {
    public static void main(String[] args) {
        BlockPos bedrock = new BlockPos(0, 0, 0);
        check(BedrockLocalProximityPolicy.isWithin(new BlockPos(1, 0, 0), bedrock, 1),
                "a player one block beside bedrock may work locally");
        check(BedrockLocalProximityPolicy.isWithin(new BlockPos(0, 1, 0), bedrock, 1),
                "a player standing one block above bedrock may work locally");
        check(!BedrockLocalProximityPolicy.isWithin(new BlockPos(2, 0, 0), bedrock, 1),
                "bedrock two blocks away must trigger pathing");
        check(!BedrockLocalProximityPolicy.isWithin(new BlockPos(1, 2, 0), bedrock, 1),
                "the one-block limit applies vertically as well");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
