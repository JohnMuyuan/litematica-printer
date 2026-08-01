package me.aleksilassila.litematica.printer.handler.handlers;

/** Selects the single server destroy channel used for non-instant Mine targets. */
public final class MineDestroyChannelPolicy {
    private MineDestroyChannelPolicy() {
    }

    public static Decision decide(boolean delayedDestroyActive, boolean sameDelayedTarget) {
        if (delayedDestroyActive) {
            return sameDelayedTarget ? Decision.CONTINUE_ACTIVE : Decision.REJECT_OTHER_TARGET;
        }
        return Decision.START_SERIAL_DELAYED_DESTROY;
    }

    public enum Decision {
        CONTINUE_ACTIVE(false, false),
        REJECT_OTHER_TARGET(false, false),
        START_SERIAL_DELAYED_DESTROY(true, true);

        private final boolean submit;
        private final boolean forceDelayedDestroy;

        Decision(boolean submit, boolean forceDelayedDestroy) {
            this.submit = submit;
            this.forceDelayedDestroy = forceDelayedDestroy;
        }

        public boolean submit() {
            return this.submit;
        }

        public boolean forceDelayedDestroy() {
            return this.forceDelayedDestroy;
        }
    }
}
