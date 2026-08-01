package me.aleksilassila.litematica.printer.handler.handlers;

/** Selects the restriction source for ordinary mining versus the dedicated Clear pipeline. */
final class MineTargetPolicy {
    private MineTargetPolicy() {
    }

    static boolean restrictionAllows(boolean clearPipeline,
                                     boolean ordinaryRestrictionAllows,
                                     boolean clearBlacklistMatches) {
        return clearPipeline ? !clearBlacklistMatches : ordinaryRestrictionAllows;
    }
}
