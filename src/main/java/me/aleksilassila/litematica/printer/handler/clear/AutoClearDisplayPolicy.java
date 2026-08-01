package me.aleksilassila.litematica.printer.handler.clear;

final class AutoClearDisplayPolicy {
    private AutoClearDisplayPolicy() {}

    static ClearController.Stage displayStage(ClearController.Stage localStage, boolean globallyComplete) {
        if (globallyComplete) return ClearController.Stage.COMPLETE;
        return localStage == ClearController.Stage.VERIFY || localStage == ClearController.Stage.COMPLETE
                ? ClearController.Stage.AUTO_SEARCH : localStage;
    }
}
