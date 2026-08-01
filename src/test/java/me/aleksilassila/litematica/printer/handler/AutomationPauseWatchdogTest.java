package me.aleksilassila.litematica.printer.handler;

public final class AutomationPauseWatchdogTest {
    public static void main(String[] args) {
        AutomationPauseWatchdog watchdog = new AutomationPauseWatchdog();
        check(!watchdog.shouldRecover("inventory", 3), "first paused tick waits");
        check(!watchdog.shouldRecover("inventory", 3), "second paused tick waits");
        check(!watchdog.shouldRecover("inventory", 3), "maximum paused tick still waits");
        check(watchdog.shouldRecover("inventory", 3), "pause exceeding maximum recovers");
        check(!watchdog.shouldRecover("look", 3), "changing pause category resets age");
        watchdog.reset();
        check(watchdog.ticks() == 0, "resume clears pause age");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
