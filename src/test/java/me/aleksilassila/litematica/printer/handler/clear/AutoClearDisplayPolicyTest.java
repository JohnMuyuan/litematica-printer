package me.aleksilassila.litematica.printer.handler.clear;

public final class AutoClearDisplayPolicyTest {
    public static void main(String[] args) {
        check(AutoClearDisplayPolicy.displayStage(ClearController.Stage.VERIFY, false) == ClearController.Stage.AUTO_SEARCH,
                "automatic clear must not leave a stale final-verification HUD while global work continues");
        check(AutoClearDisplayPolicy.displayStage(ClearController.Stage.COMPLETE, false) == ClearController.Stage.AUTO_SEARCH,
                "local completion must display global automatic search until all sections finish");
        check(AutoClearDisplayPolicy.displayStage(ClearController.Stage.COMPLETE, true) == ClearController.Stage.COMPLETE,
                "true global completion may display complete");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
