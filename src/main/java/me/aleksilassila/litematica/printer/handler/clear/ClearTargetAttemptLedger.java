package me.aleksilassila.litematica.printer.handler.clear;

import java.util.HashMap;
import java.util.Map;

public final class ClearTargetAttemptLedger<K> {
    private final Map<K, Integer> attempts = new HashMap<>();

    public Decision decision(K target, boolean pendingConfirmation, int maxRetries) {
        if (pendingConfirmation) {
            return Decision.WAITING_CONFIRMATION;
        }
        int maxAttempts = Math.max(0, maxRetries) + 1;
        return this.attempts.getOrDefault(target, 0) < maxAttempts
                ? Decision.ATTEMPT
                : Decision.EXHAUSTED;
    }

    public void recordAttempt(K target) {
        this.attempts.merge(target, 1, Integer::sum);
    }

    public void confirmed(K target) {
        this.attempts.remove(target);
    }

    public void reset() {
        this.attempts.clear();
    }

    public enum Decision {
        ATTEMPT,
        WAITING_CONFIRMATION,
        EXHAUSTED
    }
}
