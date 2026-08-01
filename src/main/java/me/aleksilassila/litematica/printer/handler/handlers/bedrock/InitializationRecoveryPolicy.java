package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

final class InitializationRecoveryPolicy {
    static final int REPOSITION_TICKS = 20;
    static final int MAX_REPOSITION_ATTEMPTS = 3;

    private InitializationRecoveryPolicy() {
    }

    static boolean needsReposition(int stallTicks) {
        return stallTicks >= REPOSITION_TICKS;
    }

    /** Returns -1 while waiting, 0 when exhausted, otherwise the next 1-based attempt. */
    static int nextAttempt(int stallTicks, int completedAttempts) {
        if (!needsReposition(stallTicks)) {
            return -1;
        }
        return completedAttempts >= MAX_REPOSITION_ATTEMPTS ? 0 : completedAttempts + 1;
    }
}
