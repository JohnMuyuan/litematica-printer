package me.aleksilassila.litematica.printer.handler;

public record ModuleStageStatus(State state, String reason) {
    public static final ModuleStageStatus INACTIVE = new ModuleStageStatus(State.INACTIVE, "inactive");

    public boolean isSettled() {
        return this.state == State.SETTLED;
    }

    public boolean blocksLowerPriority() {
        return this.state != State.SETTLED && this.state != State.INACTIVE;
    }

    public enum State {
        INACTIVE,
        SCANNING,
        WORKING,
        WAITING_INTERVAL,
        WAITING_CONFIRMATION,
        BLOCKED,
        SETTLED
    }
}
