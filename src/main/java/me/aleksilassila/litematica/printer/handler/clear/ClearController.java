package me.aleksilassila.litematica.printer.handler.clear;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ModuleStageStatus;
import me.aleksilassila.litematica.printer.handler.TickContext;
import me.aleksilassila.litematica.printer.handler.WorkAreaPolicy;
import me.aleksilassila.litematica.printer.handler.handlers.BedrockHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FluidHandler;
import me.aleksilassila.litematica.printer.handler.handlers.MineHandler;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;

public final class ClearController {
    private final FluidHandler fluid;
    private final MineHandler mine;
    private final BedrockHandler bedrock;
    private Stage stage = Stage.FLUID;
    private FluidPass fluidPass = FluidPass.INITIAL;
    private boolean stageDidWork;
    private Snapshot snapshot = new Snapshot(Stage.FLUID, ModuleStageStatus.INACTIVE);
    private int observedWorkAreaGeneration = Integer.MIN_VALUE;

    public ClearController(FluidHandler fluid, MineHandler mine, BedrockHandler bedrock) {
        this.fluid = fluid;
        this.mine = mine;
        this.bedrock = bedrock;
    }

    public void tick(TickContext context) {
        int generation = WorkAreaPolicy.generation();
        if (this.observedWorkAreaGeneration != generation) {
            this.observedWorkAreaGeneration = generation;
            BedrockController.reset();
            this.bedrock.resetRuntimeState();
            this.enterFluid(FluidPass.INITIAL, true);
        }

        boolean fluidEnabled = Configs.Clear.CLEAR_FLUID_ENABLED.getBooleanValue();
        boolean mineEnabled = Configs.Clear.CLEAR_MINE_ENABLED.getBooleanValue();
        boolean bedrockEnabled = Configs.Clear.CLEAR_BEDROCK_ENABLED.getBooleanValue();

        // COMPLETE remains visible for one tick. Afterwards the normal lazy/dirty
        // scan starts again from fluids, preserving the same strict priority.
        if (this.stage == Stage.COMPLETE) {
            if (fluidEnabled) {
                this.enterFluid(FluidPass.INITIAL, false);
            } else if (mineEnabled) {
                this.enterMine(false);
            } else if (bedrockEnabled) {
                this.enterBedrock(false);
            } else {
                return;
            }
        }

        // Clear has strict phase ordering:
        // 1. remove fluids; 2. mine normal blocks; 3. break bedrock.
        // If mining changed the world, fluids are verified again before bedrock.
        // If that verification removes fluid, normal blocks are verified again.
        for (int transitions = 0; transitions < Stage.values().length + 2; transitions++) {
            switch (this.stage) {
                case FLUID -> {
                    if (!fluidEnabled) {
                        this.enterMine(true);
                        continue;
                    }
                    this.fluid.tick(context);
                    ModuleStageStatus status = this.fluid.getStageStatus();
                    this.observeStageWork(status);
                    this.snapshot = new Snapshot(Stage.FLUID, status);
                    if (!status.isSettled()) {
                        return;
                    }

                    if (this.fluidPass == FluidPass.POST_MINE_VERIFY && !this.stageDidWork) {
                        this.enterBedrock(true);
                    } else {
                        this.enterMine(true);
                    }
                }
                case MINE -> {
                    if (!mineEnabled) {
                        this.enterBedrock(true);
                        continue;
                    }
                    this.mine.tick(context);
                    ModuleStageStatus status = this.mine.getStageStatus();
                    this.observeStageWork(status);
                    this.snapshot = new Snapshot(Stage.MINE, status);
                    if (!status.isSettled()) {
                        return;
                    }

                    if (this.stageDidWork && fluidEnabled) {
                        // Mining can expose water or lava. Do not start bedrock until
                        // a fresh fluid pass confirms that the area is dry again.
                        this.enterFluid(FluidPass.POST_MINE_VERIFY, true);
                    } else {
                        this.enterBedrock(true);
                    }
                }
                case BEDROCK -> {
                    if (!bedrockEnabled) {
                        // Do not leave controller-owned targets running after the
                        // Clear Bedrock option has been disabled.
                        BedrockController.reset();
                        this.bedrock.resetRuntimeState();
                        this.stage = Stage.VERIFY;
                        continue;
                    }

                    if (!BedrockController.hasActiveWork()) {
                        if (fluidEnabled) {
                            this.fluid.tick(context);
                            ModuleStageStatus fluidStatus = this.fluid.getStageStatus();
                            if (fluidStatus.blocksLowerPriority()) {
                                this.stage = Stage.FLUID;
                                this.fluidPass = FluidPass.POST_MINE_VERIFY;
                                this.stageDidWork = false;
                                this.observeStageWork(fluidStatus);
                                this.snapshot = new Snapshot(Stage.FLUID, fluidStatus);
                                return;
                            }
                        }
                        if (mineEnabled) {
                            this.mine.tick(context);
                            ModuleStageStatus mineStatus = this.mine.getStageStatus();
                            if (mineStatus.blocksLowerPriority()) {
                                this.stage = Stage.MINE;
                                this.stageDidWork = false;
                                this.observeStageWork(mineStatus);
                                this.snapshot = new Snapshot(Stage.MINE, mineStatus);
                                return;
                            }
                        }
                    }
                    this.bedrock.tick(context);
                    ModuleStageStatus status = this.bedrock.getStageStatus();
                    this.observeStageWork(status);
                    this.snapshot = new Snapshot(Stage.BEDROCK, status);
                    if (!status.isSettled()) {
                        return;
                    }
                    this.stage = Stage.VERIFY;
                }
                case VERIFY -> {
                    this.stage = Stage.COMPLETE;
                    this.stageDidWork = false;
                    this.snapshot = new Snapshot(Stage.COMPLETE,
                            new ModuleStageStatus(ModuleStageStatus.State.SETTLED, "complete"));
                    return;
                }
                case COMPLETE -> {
                    return;
                }
            }
        }
    }

    private void enterFluid(FluidPass pass, boolean requestVerificationScan) {
        this.stage = Stage.FLUID;
        this.fluidPass = pass;
        this.stageDidWork = false;
        if (requestVerificationScan) {
            this.fluid.requestClearIncrementalVerificationScan();
        }
    }

    private void enterMine(boolean requestVerificationScan) {
        this.stage = Stage.MINE;
        this.stageDidWork = false;
        if (requestVerificationScan) {
            this.mine.requestClearIncrementalVerificationScan();
        }
    }

    private void enterBedrock(boolean requestVerificationScan) {
        this.stage = Stage.BEDROCK;
        this.stageDidWork = false;
        if (requestVerificationScan) {
            this.bedrock.requestClearVerificationScan();
        }
    }

    private void observeStageWork(ModuleStageStatus status) {
        if (status == null) {
            return;
        }
        if (status.state() == ModuleStageStatus.State.WORKING
                || status.state() == ModuleStageStatus.State.WAITING_CONFIRMATION) {
            this.stageDidWork = true;
        }
    }

    public void reset() {
        BedrockController.reset();
        this.bedrock.resetRuntimeState();
        this.observedWorkAreaGeneration = Integer.MIN_VALUE;
        this.stage = Stage.FLUID;
        this.fluidPass = FluidPass.INITIAL;
        this.stageDidWork = false;
        this.snapshot = new Snapshot(Stage.FLUID, ModuleStageStatus.INACTIVE);
    }

    public Snapshot snapshot() {
        return this.snapshot;
    }

    private enum FluidPass {
        INITIAL,
        POST_MINE_VERIFY
    }

    public enum Stage {
        FLUID,
        MINE,
        BEDROCK,
        VERIFY,
        COMPLETE
    }

    public record Snapshot(Stage stage, ModuleStageStatus status) {
    }
}
