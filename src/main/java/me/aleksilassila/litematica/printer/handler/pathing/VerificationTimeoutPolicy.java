package me.aleksilassila.litematica.printer.handler.pathing;

final class VerificationTimeoutPolicy {
    private VerificationTimeoutPolicy() {}

    static Decision decide(boolean localSettled, boolean candidatePresent, boolean beforeDeadline) {
        if (!localSettled && beforeDeadline) return Decision.WAIT;
        if (localSettled && candidatePresent && beforeDeadline) return Decision.WAIT;
        return Decision.RETRY_SEARCH;
    }

    enum Decision { WAIT, RETRY_SEARCH }
}
