package me.aleksilassila.litematica.printer.handler;

/** Tracks a single scheduler pause category and requests recovery after a bounded wait. */
final class AutomationPauseWatchdog {
    private String reason;
    private int ticks;

    boolean shouldRecover(String reason, int maximumTicks) {
        if (!reason.equals(this.reason)) {
            this.reason = reason;
            this.ticks = 1;
        } else {
            this.ticks++;
        }
        return this.ticks > Math.max(0, maximumTicks);
    }

    int ticks() {
        return this.ticks;
    }

    void reset() {
        this.reason = null;
        this.ticks = 0;
    }
}
