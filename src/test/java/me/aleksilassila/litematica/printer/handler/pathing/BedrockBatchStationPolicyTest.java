package me.aleksilassila.litematica.printer.handler.pathing;

public final class BedrockBatchStationPolicyTest {
    public static void main(String[] args) {
        check(!BedrockBatchStationPolicy.hasEnoughCoverage(1, 3),
                "a station that reaches one bedrock must not bypass a minimum batch of three");
        check(!BedrockBatchStationPolicy.hasEnoughCoverage(2, 3),
                "nearby leftovers below the configured batch must be postponed");
        check(BedrockBatchStationPolicy.hasEnoughCoverage(3, 3),
                "a station meeting the configured batch may start local bedrock work");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
