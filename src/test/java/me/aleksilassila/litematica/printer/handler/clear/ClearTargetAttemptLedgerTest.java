package me.aleksilassila.litematica.printer.handler.clear;

public final class ClearTargetAttemptLedgerTest {
    public static void main(String[] args) {
        sameFluidTargetCannotRefreshConfirmationForever();
        zeroRetriesStopsAfterInitialAttempt();
        confirmedTargetGetsFreshBudget();
    }

    private static void sameFluidTargetCannotRefreshConfirmationForever() {
        ClearTargetAttemptLedger<String> ledger = new ClearTargetAttemptLedger<>();
        String fluid = "0,64,0:fluid";

        for (int attempt = 0; attempt < 4; attempt++) {
            check(ledger.decision(fluid, false, 3) == ClearTargetAttemptLedger.Decision.ATTEMPT,
                    "initial attempt plus three retries must be allowed");
            ledger.recordAttempt(fluid);
            check(ledger.decision(fluid, true, 3) == ClearTargetAttemptLedger.Decision.WAITING_CONFIRMATION,
                    "a sent action must wait for its fixed confirmation window");
        }
        for (int tick = 32; tick < 1000; tick += 8) {
            check(ledger.decision(fluid, false, 3) == ClearTargetAttemptLedger.Decision.EXHAUSTED,
                    "exhausted fluid target must not revive when confirmations repeat");
        }
        ledger.reset();
        check(ledger.decision(fluid, false, 3) == ClearTargetAttemptLedger.Decision.ATTEMPT,
                "a new clear pass must get a fresh retry budget");
    }

    private static void zeroRetriesStopsAfterInitialAttempt() {
        ClearTargetAttemptLedger<String> ledger = new ClearTargetAttemptLedger<>();
        check(ledger.decision("fluid", false, 0) == ClearTargetAttemptLedger.Decision.ATTEMPT,
                "zero retries still allows the initial attempt");
        ledger.recordAttempt("fluid");
        check(ledger.decision("fluid", false, 0) == ClearTargetAttemptLedger.Decision.EXHAUSTED,
                "zero retries must reject the first retry");
    }

    private static void confirmedTargetGetsFreshBudget() {
        ClearTargetAttemptLedger<String> ledger = new ClearTargetAttemptLedger<>();
        ledger.recordAttempt("fluid");
        ledger.confirmed("fluid");
        check(ledger.decision("fluid", false, 0) == ClearTargetAttemptLedger.Decision.ATTEMPT,
                "confirmed progress must give the target a fresh retry budget");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
