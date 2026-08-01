package me.aleksilassila.litematica.printer.handler.pathing;

public final class VerificationTimeoutPolicyTest {
    public static void main(String[] args) {
        check(VerificationTimeoutPolicy.decide(false, true, true) == VerificationTimeoutPolicy.Decision.WAIT, "unsettled work waits before deadline");
        check(VerificationTimeoutPolicy.decide(false, true, false) == VerificationTimeoutPolicy.Decision.RETRY_SEARCH, "unsettled work must not wait forever after deadline");
        check(VerificationTimeoutPolicy.decide(true, false, true) == VerificationTimeoutPolicy.Decision.RETRY_SEARCH, "removed target resumes search immediately");
        check(VerificationTimeoutPolicy.decide(true, true, true) == VerificationTimeoutPolicy.Decision.WAIT, "present target gets its verification window");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
