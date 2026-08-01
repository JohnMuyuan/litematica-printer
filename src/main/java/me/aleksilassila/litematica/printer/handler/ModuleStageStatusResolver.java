package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.enums.ScanState;
import org.jetbrains.annotations.Nullable;

/** Pure status policy kept separate so finite clear-pass completion can be regression tested. */
final class ModuleStageStatusResolver {
    private ModuleStageStatusResolver() {
    }

    static ModuleStageStatus resolve(@Nullable String blockingReason,
                                     boolean pendingConfirmation,
                                     boolean activeOrFoundWork,
                                     boolean interrupted,
                                     ScanState scanState,
                                     boolean pendingPartialScan,
                                     boolean finitePassSettlement) {
        if (blockingReason != null) {
            return new ModuleStageStatus(ModuleStageStatus.State.BLOCKED, blockingReason);
        }
        if (pendingConfirmation) {
            return new ModuleStageStatus(ModuleStageStatus.State.WAITING_CONFIRMATION, "confirmation");
        }
        if (activeOrFoundWork) {
            return new ModuleStageStatus(ModuleStageStatus.State.WORKING, "working");
        }
        if (interrupted || pendingPartialScan) {
            return new ModuleStageStatus(ModuleStageStatus.State.SCANNING, "scanning");
        }
        if (scanState == ScanState.LAZY || finitePassSettlement) {
            return new ModuleStageStatus(ModuleStageStatus.State.SETTLED, "settled");
        }
        return new ModuleStageStatus(ModuleStageStatus.State.SCANNING, "scanning");
    }
}