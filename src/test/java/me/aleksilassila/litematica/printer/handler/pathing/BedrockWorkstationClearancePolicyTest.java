package me.aleksilassila.litematica.printer.handler.pathing;

import net.minecraft.core.BlockPos;

public final class BedrockWorkstationClearancePolicyTest {
    public static void main(String[] args) {
        BlockPos bedrock = new BlockPos(0, 0, 0);
        BlockPos piston = bedrock.above();
        BlockPos head = piston.above();
        check(BedrockWorkstationClearancePolicy.blocksMachine(bedrock.above(), piston, head),
                "standing on the target bedrock occupies the piston position");
        check(!BedrockWorkstationClearancePolicy.blocksMachine(new BlockPos(1, 0, 0), piston, head),
                "an adjacent one-block-away station leaves the machine footprint clear");
        check(BedrockWorkstationClearancePolicy.blocksWorkstation(
                        bedrock.above(), bedrock, piston, head),
                "standing on the current target bedrock must not be a workstation");
        check(!BedrockWorkstationClearancePolicy.blocksWorkstation(
                        new BlockPos(1, 1, 0), bedrock, piston, head),
                "standing on a different bedrock may remain a valid workstation");
        check(!BedrockWorkstationClearancePolicy.blocksWorkstation(
                        new BlockPos(1, 0, 0), bedrock, piston, head),
                "standing beside the current target on ordinary floor remains valid");
        check(BedrockLocalProximityPolicy.isWithin(new BlockPos(1, 0, 0), bedrock, 1),
                "the clear adjacent station still obeys the one-block proximity limit");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
