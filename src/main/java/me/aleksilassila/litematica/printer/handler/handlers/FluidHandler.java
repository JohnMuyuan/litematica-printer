package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.clear.ClearTargetAttemptLedger;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.RegistryFilterResolver;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class FluidHandler extends Module {
    public final static String NAME = "fluid";
    private static final Direction[] PLACEMENT_SIDE_ORDER = {
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.UP
    };

    private List<String> fillBlocks = new ArrayList<>();
    private List<Item> fillItems = new ArrayList<>();
    private Item[] fillItemArray = new Item[0];

    private List<String> fluidBlocks = new ArrayList<>();
    private Set<Fluid> fluids = Set.of();
    private final ClearTargetAttemptLedger<BlockPos> clearAttempts = new ClearTargetAttemptLedger<>();
    private int observedScanConfigHash = Integer.MIN_VALUE;
    private boolean retryLimitFailureReported;

    public FluidHandler() {
        super(NAME, PrintModeType.FLUID, Configs.Core.FLUID, Configs.Fluid.FLUID_SELECTION_TYPE, true);
    }

    @Override
    protected int getTickInterval() {
        return Configs.Placement.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected void preprocess() {
        // 填充方块
        List<String> fileBlocks = Configs.Fluid.FLUID_REPLACE_BLOCK_LIST.getStrings();
        if (!fileBlocks.equals(fillBlocks)) {
            fillBlocks = new ArrayList<>(fileBlocks);
            fillItems = new ArrayList<>();
            if (!fileBlocks.isEmpty()) {
                fillItems.addAll(RegistryFilterResolver.resolveItems(fillBlocks));
            }
            fillItemArray = fillItems.toArray(new Item[0]);
        }
        // 流体方块
        List<String> fluidBlocks = Configs.Fluid.FLUID_LIST.getStrings();
        if (!fluidBlocks.equals(this.fluidBlocks)) {
            this.fluidBlocks = new ArrayList<>(fluidBlocks);
            fluids = fluidBlocks.isEmpty() ? Set.of() : RegistryFilterResolver.resolveFluids(this.fluidBlocks);
        }
        if (fillItems.isEmpty()) {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "无流体填充方块");
        } else if (this.fluidBlocks.isEmpty()) {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "无目标流体配置");
        } else {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "运行中");
        }
        int scanConfigHash = this.getScanConfigHash();
        if (this.observedScanConfigHash != Integer.MIN_VALUE
                && this.observedScanConfigHash != scanConfigHash) {
            ScanCache.INSTANCE.resetOwner(NAME);
            this.requestFullScan();
        }
        this.observedScanConfigHash = scanConfigHash;
    }

    @Override
    protected void onRuntimeReset() {
        this.observedScanConfigHash = Integer.MIN_VALUE;
        this.clearAttempts.reset();
        this.retryLimitFailureReported = false;
    }

    public void updateAutoClearContext(me.aleksilassila.litematica.printer.handler.TickContext context) {
        this.updateVariables(context);
    }

    @Override
    protected boolean hasPendingConfirmation() {
        return HudStatsManager.INSTANCE.hasPendingConfirmations(HudStatsManager.Mode.FLUID);
    }

    @Override
    protected String getBlockingReason() {
        if (this.fillItems.isEmpty()) {
            return "missing_fluid_fill_block";
        }
        if (this.fluids.isEmpty()) {
            return "empty_fluid_list";
        }
        return null;
    }

    @Override
    protected boolean canIterate() {
        return !fillItems.isEmpty() && !fluids.isEmpty();
    }

    @Override
    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return true;
    }

    @Override
    protected boolean iterationPositionsAreExactCandidates() {
        return true;
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        if (scanSourceBoxes.isEmpty()) {
            return List.of();
        }
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        
        return ScanCache.INSTANCE.iterable(
                NAME,
                scanSourceBoxes,
                this.level,
                null,
                this.player,
                this.getScanGuardLimit(),
                ScanIntent.FLUID,
                this::isTargetFluid,
                pos -> this.canReachIterationPosition(pos)
                        && selectionPredicate.test(pos)
                        && (!ConfigUtils.isAutoBedrockMode() || this.isPotentialAutoTarget(pos))
        );
    }

    @Override
    public boolean canIterationBlockPos(BlockPos blockPos) {
        return ConfigUtils.isAutoBedrockMode()
                ? this.isPotentialAutoTarget(blockPos)
                : this.isTargetFluid(blockPos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        FluidState fluidState = level.getBlockState(blockPos).getFluidState();
        if (!this.isTargetFluid(fluidState)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        if (ConfigUtils.isAutoBedrockMode()) {
            HudStatsManager stats = HudStatsManager.INSTANCE;
            HudStatsManager.BlockChangeResult blockChange = stats.consumeBlockChangeResult(
                    HudStatsManager.Mode.FLUID, blockPos);
            if (blockChange == HudStatsManager.BlockChangeResult.CONFIRMED) {
                this.clearAttempts.confirmed(blockPos);
            }
            ClearTargetAttemptLedger.Decision decision = this.clearAttempts.decision(
                    blockPos,
                    stats.isBlockChangePending(HudStatsManager.Mode.FLUID, blockPos),
                    Configs.Clear.CLEAR_MAX_RETRIES.getIntegerValue()
            );
            if (decision != ClearTargetAttemptLedger.Decision.ATTEMPT) {
                setIterationConsumedEffectiveExecution(false);
                if (decision == ClearTargetAttemptLedger.Decision.EXHAUSTED
                        && !this.retryLimitFailureReported) {
                    this.retryLimitFailureReported = true;
                    HudStatsManager.INSTANCE.recordFailure(HudStatsManager.Mode.FLUID, "超过清除重试上限");
                }
                return;
            }
        }
        if (!InventoryUtils.switchToItems(player, fillItemArray)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "缺少流体填充方块");
            setIterationConsumedEffectiveExecution(false);
            if (me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.shouldPauseForSwitchRequest()
                    || me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils.isAwaitingStack()) {
                skipIteration.set(true);
            }
            return;
        }
        BlockPos clickTarget = blockPos;
        Direction clickSide = Direction.DOWN;
        if (!Configs.Print.PLACE_IN_AIR.getBooleanValue()) {
            Direction placementSide = this.findPlacementSide(blockPos);
            if (placementSide == null) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "无有效放置面");
                setIterationConsumedEffectiveExecution(false);
                return;
            }
            clickTarget = blockPos.relative(placementSide);
            clickSide = placementSide.getOpposite();
        }
        if (!ActionManager.INSTANCE.queueClick(
                clickTarget,
                blockPos,
                clickSide,
                Vec3.ZERO,
                false,
                1,
                fillItemArray,
                ActionManager.ActionSource.FLUID
        )) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "动作队列占用");
            setIterationConsumedEffectiveExecution(false);
            skipIteration.set(true);
            return;
        }
        BlockPos stablePos = blockPos.immutable();
        BlockState previousState = level.getBlockState(stablePos);
        AtomicBoolean deferred = new AtomicBoolean(false);
        ActionManager.INSTANCE.setQueueCompletionListener(result -> {
            if (!deferred.get()) {
                return;
            }
            if (result.isSent()) {
                this.recordFluidActionSent(stablePos, previousState);
            } else {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, describeSendFailure(result));
            }
        });
        ActionManager.SendResult sendResult = ActionManager.INSTANCE.sendQueue(player);
        if (sendResult.isWaiting()) {
            deferred.set(true);
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "等待转头");
            skipIteration.set(true);
            return;
        }
        if (!sendResult.isSent()) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, describeSendFailure(sendResult));
            setIterationConsumedEffectiveExecution(false);
            skipIteration.set(true);
            return;
        }
        this.recordFluidActionSent(stablePos, previousState);
    }

    private void recordFluidActionSent(BlockPos blockPos, BlockState previousState) {
        HudStatsManager.INSTANCE.trackExpectedBlockChange(
                HudStatsManager.Mode.FLUID,
                blockPos,
                previousState,
                ConfigUtils.isAutoBedrockMode() ? () -> this.clearAttempts.confirmed(blockPos) : null
        );
        if (ConfigUtils.isAutoBedrockMode()) {
            this.clearAttempts.recordAttempt(blockPos);
        }
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.FLUID, 1);
        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "运行中");
        this.setBlockPosCooldown(blockPos, ConfigUtils.getPlaceCooldown());
    }

    private static String describeSendFailure(ActionManager.SendResult result) {
        return result == ActionManager.SendResult.OUTSIDE_WORK_AREA
                ? I18n.HUD_ACTION_OUTSIDE_WORK_AREA.getName().getString()
                : result == ActionManager.SendResult.OUT_OF_REACH
                ? "超出玩家实际交互距离"
                : "放置动作未发送";
    }

    private Direction findPlacementSide(BlockPos blockPos) {
        for (Direction side : PLACEMENT_SIDE_ORDER) {
            BlockPos neighborPos = blockPos.relative(side);
            if (PrinterUtils.canBeClicked(this.level, neighborPos)
                    && !BlockUtils.isReplaceable(this.level.getBlockState(neighborPos))) {
                return side;
            }
        }
        return null;
    }

    /** Candidate check used by automatic clearing; unlike execution it does not require local reach. */
    public boolean isPotentialAutoTarget(BlockPos blockPos) {
        if (!this.isTargetFluid(blockPos)) {
            return false;
        }
        HudStatsManager stats = HudStatsManager.INSTANCE;
        HudStatsManager.BlockChangeResult blockChange = stats.consumeBlockChangeResult(
                HudStatsManager.Mode.FLUID, blockPos);
        if (blockChange == HudStatsManager.BlockChangeResult.CONFIRMED) {
            this.clearAttempts.confirmed(blockPos);
        }
        ClearTargetAttemptLedger.Decision decision = this.clearAttempts.decision(
                blockPos,
                blockChange == HudStatsManager.BlockChangeResult.PENDING,
                Configs.Clear.CLEAR_MAX_RETRIES.getIntegerValue()
        );
        if (decision == ClearTargetAttemptLedger.Decision.EXHAUSTED
                && !this.retryLimitFailureReported) {
            this.retryLimitFailureReported = true;
            HudStatsManager.INSTANCE.recordFailure(HudStatsManager.Mode.FLUID, "超过清除重试上限");
        }
        return decision != ClearTargetAttemptLedger.Decision.EXHAUSTED;
    }

    private boolean isTargetFluid(BlockPos blockPos) {
        return this.level != null && this.isTargetFluid(this.level.getBlockState(blockPos).getFluidState());
    }

    private boolean isTargetFluid(FluidState fluidState) {
        return fluids.contains(fluidState.getType())
                && (Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue() || fluidState.isSource());
    }

    private int getScanConfigHash() {
        int result = this.fillBlocks.hashCode();
        result = 31 * result + this.fluidBlocks.hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue());
        return result;
    }
}
