package me.aleksilassila.litematica.printer.handler.handlers;

public final class MineDestroyChannelPolicyTest {
    public static void main(String[] args) {
        slowMineStartsOnSerialDelayedChannel();
        activeSlowMineRejectsAnotherTarget();
        activeSlowMineContinuesOnlyItsOwnTarget();
    }

    private static void slowMineStartsOnSerialDelayedChannel() {
        MineDestroyChannelPolicy.Decision decision = MineDestroyChannelPolicy.decide(false, false);
        check(decision.submit(), "a new slow Mine target must be submitted");
        check(decision.forceDelayedDestroy(),
                "a slow Mine target must use the delayed serial channel instead of vanilla destroyBlockPos");
    }

    private static void activeSlowMineRejectsAnotherTarget() {
        MineDestroyChannelPolicy.Decision decision = MineDestroyChannelPolicy.decide(true, false);
        check(!decision.submit(),
                "while one slow target is active, a different destroy position must not be submitted");
    }

    private static void activeSlowMineContinuesOnlyItsOwnTarget() {
        MineDestroyChannelPolicy.Decision decision = MineDestroyChannelPolicy.decide(true, true);
        check(decision == MineDestroyChannelPolicy.Decision.CONTINUE_ACTIVE,
                "the active delayed target must remain the only slow target in progress");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
