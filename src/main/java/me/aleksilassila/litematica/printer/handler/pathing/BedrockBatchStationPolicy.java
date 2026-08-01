package me.aleksilassila.litematica.printer.handler.pathing;

final class BedrockBatchStationPolicy {
    private BedrockBatchStationPolicy() {
    }

    static boolean hasEnoughCoverage(int bestCoverage, int minimumBatch) {
        return bestCoverage >= Math.max(1, minimumBatch);
    }
}
