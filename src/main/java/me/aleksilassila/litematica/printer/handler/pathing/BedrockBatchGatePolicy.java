package me.aleksilassila.litematica.printer.handler.pathing;

final class BedrockBatchGatePolicy {
    private BedrockBatchGatePolicy() {}

    static Decision decide(boolean localBedrockPhase, boolean densePass, int clusterSize,
                           int minimumBatch, boolean clusterLocallyReachable) {
        if (!localBedrockPhase || clusterSize <= 0) return Decision.NOT_APPLICABLE;
        if (!densePass) return Decision.ALLOW_LOCAL;
        if (clusterSize < Math.max(1, minimumBatch)) return Decision.POSTPONE_SECTION;
        return clusterLocallyReachable ? Decision.ALLOW_LOCAL : Decision.MOVE_TO_CLUSTER;
    }

    enum Decision { NOT_APPLICABLE, ALLOW_LOCAL, POSTPONE_SECTION, MOVE_TO_CLUSTER }
}
