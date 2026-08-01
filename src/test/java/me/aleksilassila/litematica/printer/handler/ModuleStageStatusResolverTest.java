package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.enums.ScanState;

public final class ModuleStageStatusResolverTest {
    public static void main(String[] args) {
        finiteClearPassSettlesWithLazyScanningDisabled();
        unfinishedFinitePassKeepsScanning();
        ordinaryFullScanKeepsLegacyBehavior();
        confirmationsKeepPriorityOverCompletion();
    }

    private static void finiteClearPassSettlesWithLazyScanningDisabled() {
        ModuleStageStatus status = ModuleStageStatusResolver.resolve(
                null, false, false, false, ScanState.FULL, false, true);
        check(status.isSettled(),
                "an exhausted finite clear pass must settle even when lazyEnterTicks is zero");
    }

    private static void unfinishedFinitePassKeepsScanning() {
        ModuleStageStatus status = ModuleStageStatusResolver.resolve(
                null, false, false, true, ScanState.FULL, false, true);
        check(status.state() == ModuleStageStatus.State.SCANNING,
                "a budget-paused clear pass must remain in scanning state");
    }

    private static void ordinaryFullScanKeepsLegacyBehavior() {
        ModuleStageStatus status = ModuleStageStatusResolver.resolve(
                null, false, false, false, ScanState.FULL, false, false);
        check(status.state() == ModuleStageStatus.State.SCANNING,
                "non-clear handlers must retain the normal full-scan status policy");
    }

    private static void confirmationsKeepPriorityOverCompletion() {
        ModuleStageStatus status = ModuleStageStatusResolver.resolve(
                null, true, false, false, ScanState.FULL, false, true);
        check(status.state() == ModuleStageStatus.State.WAITING_CONFIRMATION,
                "pending server confirmation must block finite-pass completion");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}