package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

public final class InitializationRecoveryPolicyTest {
    public static void main(String[] args) {
        check(InitializationRecoveryPolicy.nextAttempt(19, 0) == -1,
                "initialization may settle before the reference timeout");
        check(InitializationRecoveryPolicy.nextAttempt(20, 0) == 1,
                "a machine stalled during initialization must request another station");
        check(InitializationRecoveryPolicy.nextAttempt(20, 2) == 3,
                "the third reposition attempt remains available");
        check(InitializationRecoveryPolicy.nextAttempt(20, 3) == 0,
                "an endlessly replaced torch must eventually retire the target");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
