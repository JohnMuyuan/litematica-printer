package me.aleksilassila.litematica.printer.handler.pathing;

public final class BedrockBatchGatePolicyTest {
    public static void main(String[] args) {
        check(BedrockBatchGatePolicy.decide(true, true, 1, 3, false) == BedrockBatchGatePolicy.Decision.POSTPONE_SECTION,
                "a sparse bedrock section must be postponed before local breaking starts");
        check(BedrockBatchGatePolicy.decide(true, true, 5, 3, false) == BedrockBatchGatePolicy.Decision.MOVE_TO_CLUSTER,
                "a dense but distant cluster must trigger pathing instead of breaking a nearby singleton");
        check(BedrockBatchGatePolicy.decide(true, true, 5, 3, true) == BedrockBatchGatePolicy.Decision.ALLOW_LOCAL,
                "a locally reachable dense cluster may be processed");
        check(BedrockBatchGatePolicy.decide(true, false, 1, 3, true) == BedrockBatchGatePolicy.Decision.ALLOW_LOCAL,
                "the sparse cleanup pass must eventually process leftovers");
        check(BedrockBatchGatePolicy.decide(false, true, 1, 3, false) == BedrockBatchGatePolicy.Decision.NOT_APPLICABLE,
                "the bedrock gate must not interrupt fluid or mining phases");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
