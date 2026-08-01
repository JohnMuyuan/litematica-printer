package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.TickContext;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockCandidatePlanner;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockEnvironment;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockInventory;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTargetBlocks;
import me.aleksilassila.litematica.printer.handler.pathing.AutoBedrockCoordinator;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class BedrockHandler extends Module {
    private final BedrockCandidatePlanner candidatePlanner = new BedrockCandidatePlanner();
    private final AutoBedrockCoordinator autoCoordinator = new AutoBedrockCoordinator();
    private boolean autoModeActive;

    public BedrockHandler() {
        super("bedrock", PrintModeType.BEDROCK, Configs.Hotkeys.BEDROCK, null, true);
    }

    @Override
    protected int getTickInterval() {
        return 0;
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Math.max(1, Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue());
    }

    @Override
    protected boolean canExecute() {
        if (player.isCreative()) {
            BedrockController.clearHorizontalLookState();
            MessageUtils.setOverlayMessage(I18n.BEDROCK_CREATIVE_MODE.getName());
            return false;
        }
        String warning = BedrockInventory.warningMessage();
        if (warning != null) {
            if (ConfigUtils.isAutoBedrockMode()) {
                // Existing machines and cleanup must keep advancing even while new work is blocked by missing materials.
                BedrockController.tick();
                this.autoCoordinator.pauseForWarning();
            }
            BedrockController.clearHorizontalLookState();
            MessageUtils.setOverlayMessage(me.aleksilassila.litematica.printer.utils.minecraft.StringUtils.translatable(warning));
            return false;
        }
        return true;
    }

    @Override
    protected boolean canIterate() {
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        BedrockController.tick();
        if (!ConfigUtils.isAutoBedrockMode()) {
            if (this.autoModeActive) {
                this.autoCoordinator.reset();
                this.autoModeActive = false;
            }
            BedrockController.setActiveTargetPredicate(selectionPredicate);
        } else {
            this.autoModeActive = true;
            BedrockController.setActiveTargetPredicate(
                    selectionPredicate.and(this.autoCoordinator::containsActiveSection));
        }
        return BedrockController.canScanForTargets();
    }

    /** Drives the full automatic clear pipeline; called by ClearController before local work. */
    public AutoBedrockCoordinator.TickResult tickAutoClear(TickContext context, FluidHandler fluid,
                                                            MineHandler mine, boolean localSettled,
                                                            boolean localBedrockPhase) {
        this.updateVariables(context);
        fluid.updateAutoClearContext(context);
        mine.updateAutoClearContext(context);
        this.autoModeActive = true;
        BedrockController.tick();
        boolean bedrockReady = !Configs.Clear.CLEAR_BEDROCK_ENABLED.getBooleanValue()
                || BedrockInventory.warningMessage() == null;
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        AutoBedrockCoordinator.TickResult result = this.autoCoordinator.tick(
                this.level,
                this.player,
                LitematicaUtils.createSelection1Boxes(),
                selectionPredicate,
                fluid,
                mine,
                localSettled,
                localBedrockPhase,
                bedrockReady,
                this.getSelectionContextHash());
        BedrockController.setActiveTargetPredicate(
                selectionPredicate.and(this.autoCoordinator::containsActiveSection));
        return result;
    }

    private int getSelectionContextHash() {
        Object selection = Configs.Clear.CLEAR_SELECTION_TYPE.getOptionListValue();
        int result = selection.hashCode();
        if (selection == SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER) {
            result = 31 * result + (int) Math.floor(this.player.getY());
        } else if (selection == SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER) {
            result = 31 * result + (int) Math.ceil(this.player.getY());
        } else if (selection == SelectionType.LITEMATICA_RENDER_LAYER) {
            for (PrinterBox box : LitematicaUtils.createSelection1Boxes()) {
                PrinterBox clamped = LitematicaUtils.clampToRenderLayer(box);
                result = 31 * result + (clamped == null ? 0 : clamped.hashCode());
            }
        }
        return result;
    }

    @Override
    protected void onRuntimeReset() {
        this.autoCoordinator.reset();
        this.autoModeActive = false;
        BedrockController.reset();
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        BedrockController.clearSubmissionPlans();
        if (playerInteractionBox == null || this.level == null || this.player == null) {
            return List.of();
        }

        List<PrinterBox> sourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        if (sourceBoxes.isEmpty()) {
            return List.of();
        }
        Predicate<BlockPos> activeTargetPredicate = this.createSelectionRangePredicate();
        if (ConfigUtils.isAutoBedrockMode()) {
            activeTargetPredicate = activeTargetPredicate.and(this.autoCoordinator::containsActiveSection);
        }
        BedrockController.setActiveTargetPredicate(activeTargetPredicate);

        return this.candidatePlanner.iterable(
                sourceBoxes,
                this.level,
                this.player,
                this.getMaxEffectiveExecutionsPerTick(),
                this.getScanGuardLimit(),
                activeTargetPredicate
        );
    }

    @Override
    protected List<PrinterBox> getScanSourceBoxes(PrinterBox playerInteractionBox) {
        List<PrinterBox> localBoxes = super.getScanSourceBoxes(playerInteractionBox);
        if (!ConfigUtils.isAutoBedrockMode()) {
            return localBoxes;
        }
        List<PrinterBox> activeBoxes = this.autoCoordinator.getActiveBoxes();
        if (localBoxes.isEmpty() || activeBoxes.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<PrinterBox> result = new java.util.ArrayList<>();
        for (PrinterBox local : localBoxes) {
            for (PrinterBox active : activeBoxes) {
                PrinterBox intersection = intersect(local, active);
                if (intersection != null) {
                    result.add(intersection);
                }
            }
        }
        return result;
    }

    public String getAutoBedrockStatusKey() {
        return this.autoCoordinator.getStatusKey();
    }

    public int getAutoBedrockSectionNumber() {
        return this.autoCoordinator.getActiveSectionNumber();
    }

    public int getAutoBedrockSectionCount() {
        return this.autoCoordinator.getSectionCount();
    }

    public boolean isAutoBedrockPathing() {
        return this.autoModeActive && this.autoCoordinator.isPathing();
    }

    public void pauseAutoBedrockForEating() {
        if (this.autoModeActive) {
            this.autoCoordinator.pauseForEating();
        }
    }

    private static PrinterBox intersect(PrinterBox first, PrinterBox second) {
        int minX = Math.max(first.minX, second.minX);
        int minY = Math.max(first.minY, second.minY);
        int minZ = Math.max(first.minZ, second.minZ);
        int maxX = Math.min(first.maxX, second.maxX);
        int maxY = Math.min(first.maxY, second.maxY);
        int maxZ = Math.min(first.maxZ, second.maxZ);
        return minX > maxX || minY > maxY || minZ > maxZ
                ? null
                : new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean canIterationBlockPos(BlockPos pos) {
        if (level == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos))) {
            return false;
        }
        return BedrockController.canAccept(pos);
    }

    @Override
    protected boolean canReachIterationPosition(BlockPos pos) {
        return BedrockEnvironment.canInteract(pos);
    }

    @Override
    protected boolean requiresSelection1ModeRangeCheck() {
        return true;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (level == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(blockPos))) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        boolean submitted = BedrockController.submit(blockPos);
        setIterationConsumedEffectiveExecution(submitted);
        if (submitted) {
            // Allow a second same-tick submit when the controller still has safe capacity.
            skipIteration.set(!BedrockController.canScanForTargets());
        }
    }

    @Override
    protected void stopIteration(boolean interrupt) {
        if (!interrupt) {
            BedrockController.tick();
        }
    }

}
