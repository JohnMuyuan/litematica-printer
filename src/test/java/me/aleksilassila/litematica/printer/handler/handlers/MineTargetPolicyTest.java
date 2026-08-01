package me.aleksilassila.litematica.printer.handler.handlers;

public final class MineTargetPolicyTest {
    public static void main(String[] args) {
        automaticClearIgnoresOrdinaryMineWhitelist();
        automaticClearRespectsItsOwnBlacklist();
        ordinaryMiningKeepsConfiguredRestriction();
    }

    private static void automaticClearIgnoresOrdinaryMineWhitelist() {
        check(MineTargetPolicy.restrictionAllows(true, false, false),
                "automatic Clear must not skip stone just because ordinary mining uses a torch-only whitelist");
    }

    private static void automaticClearRespectsItsOwnBlacklist() {
        check(!MineTargetPolicy.restrictionAllows(true, true, true),
                "automatic Clear must preserve a dedicated safety blacklist");
    }

    private static void ordinaryMiningKeepsConfiguredRestriction() {
        check(!MineTargetPolicy.restrictionAllows(false, false, false),
                "ordinary mining must continue to honor its configured whitelist/blacklist");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
