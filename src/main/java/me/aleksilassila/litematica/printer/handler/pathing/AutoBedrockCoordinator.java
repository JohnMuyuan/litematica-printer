package me.aleksilassila.litematica.printer.handler.pathing;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.FluidHandler;
import me.aleksilassila.litematica.printer.handler.handlers.MineHandler;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockMachineLayout;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTargetBlocks;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Friend-mod style automatic clearing coordinator. Local work is still performed by the
 * printer's strict FLUID -> MINE -> BEDROCK pipeline; this class only chooses sections and
 * asks Baritone to enter the selection, approach work, and survey unloaded parts.
 */
public final class AutoBedrockCoordinator {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final int SCAN_BUDGET = 8192;
    private static final int SETTLE_TICKS = 4;
    private static final int VERIFY_TICKS = 40;
    private static final int PATH_START_GRACE = 40;
    private static final int PATH_STALL_TICKS = 100;
    private static final int TARGET_DEFER_TICKS = 100;
    private static final int MANUAL_RESUME_TICKS = 10;
    private static final int MOVEMENT_MARGIN_HORIZONTAL = 3;
    private static final int MOVEMENT_MARGIN_VERTICAL = 4;
    private static final int ENTRY_SEARCH_HORIZONTAL = 32;
    private static final int ENTRY_SEARCH_VERTICAL = 8;
    private static final int SURVEY_SPACING = 12;
    private static final int MAX_SURVEY_POINTS = 20000;
    private static final int DIAGNOSTIC_REPEAT_TICKS = 100;
    private static final int REPOSITION_RETRY_TICKS = 10;

    private final BaritoneBridge baritone = new BaritoneBridge();
    private final WorkSectionCoordinator sections = new WorkSectionCoordinator();
    private final LoadedScanner scanner = new LoadedScanner();
    private final BedrockGateScanner bedrockGateScanner = new BedrockGateScanner();
    private final Map<BlockPos, Long> deferredTargets = new HashMap<>();
    private final Set<Long> visitedSurveyPoints = new HashSet<>();
    private List<BlockPos> surveyPoints = List.of();
    private List<PrinterBox> surveySourceBoxes = List.of();
    private List<PrinterBox> activeBoxes = List.of();
    private State state = State.IDLE;
    @Nullable private GoalSpec currentGoal;
    @Nullable private BlockPos currentTarget;
    @Nullable private CandidateType currentTargetType;
    @Nullable private BlockPos lastPlayerPos;
    private long pathStartedTick;
    private long lastProgressTick;
    private long settleUntilTick;
    private long verifyUntilTick;
    private long manualResumeTick;
    private long lastDiagnosticTick = Long.MIN_VALUE;
    private String lastDiagnosticSignature = "";
    private String statusKey = "autoBedrock.status.idle";
    private boolean repositioningActiveTarget;
    private int repositionAttempt;
    private long repositionRetryUntilTick;

    public TickResult tick(ClientLevel level, LocalPlayer player, List<PrinterBox> globalBoxes,
                           Predicate<BlockPos> selectionPredicate, FluidHandler fluid,
                           MineHandler mine, boolean localSettled, boolean localBedrockPhase,
                           boolean bedrockReady, int selectionContextHash) {
        long now = level.getGameTime();
        WorkSectionCoordinator.UpdateResult sectionUpdate = this.sections.update(
                globalBoxes, player.blockPosition(),
                Configs.AutoClear.AUTO_SECTION_SHORT_SIZE.getIntegerValue(),
                Configs.AutoClear.AUTO_SECTION_LONG_SIZE.getIntegerValue(),
                selectionContextHash);
        this.activeBoxes = sectionUpdate.activeBoxes();
        AutoWorkAreaScope.setActiveBoxes(this.activeBoxes);
        if (globalBoxes.isEmpty()) {
            if (sectionUpdate.requiresLocalRestart() || this.state != State.IDLE) {
                this.onSectionChanged();
            }
            this.statusKey = "autoBedrock.status.missingWorkArea";
            this.logDiagnostic(now, "missing-work-area",
                    "[AutoBedrock] no Litematica work area is available: player={}",
                    player.blockPosition().toShortString());
            return new TickResult(true, this.statusKey, false, false);
        }
        if (sectionUpdate.requiresLocalRestart()) {
            this.onSectionChanged();
            this.logDiagnostic(now, "section-" + this.sections.activeNumber()
                            + "-" + sectionUpdate.rebuildReason(),
                    "[AutoBedrock] active section changed: reason={} rebuilt={} section={}/{} boxes={} volume={} player={}",
                    sectionUpdate.rebuildReason(), sectionUpdate.rebuilt(),
                    this.sections.activeNumber(), this.sections.sectionCount(),
                    describeBoxes(this.activeBoxes), volumeOf(this.activeBoxes),
                    player.blockPosition().toShortString());
            return new TickResult(true, "autoBedrock.status.nextSection", false, true);
        }
        if (sectionUpdate.selectionContextChanged()) {
            // The predicate may change while the player moves (above/below-player and render-layer modes).
            // Restart only the incremental search, never the completed-section ledger or the local clear stage.
            this.scanner.reset();
            this.bedrockGateScanner.reset();
        }

        this.deferredTargets.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (!this.baritone.isAvailable()) {
            this.statusKey = "autoBedrock.status.missingBaritone";
            return new TickResult(true, this.statusKey, false, false);
        }

        if (hasManualMovementInput()) {
            if (this.state != State.MANUAL_PAUSED) {
                this.finishRepositionAttempt(this.currentTarget);
                this.cancelPath();
                this.state = State.MANUAL_PAUSED;
                this.manualResumeTick = 0L;
            }
            this.statusKey = "autoBedrock.status.manualPaused";
            return new TickResult(true, this.statusKey, false, false);
        }
        if (this.state == State.MANUAL_PAUSED) {
            if (this.manualResumeTick == 0L) {
                this.manualResumeTick = now + MANUAL_RESUME_TICKS;
            }
            if (now < this.manualResumeTick) {
                return new TickResult(true, "autoBedrock.status.manualPaused", false, false);
            }
            this.state = State.IDLE;
            this.manualResumeTick = 0L;
        }

        // The reference mod does not let an UNINITIALIZED machine own the local
        // pipeline forever. After 20 failed initialization ticks it paths to a
        // different construction station and retries the same target.
        if (this.repositioningActiveTarget) {
            if (this.state == State.PATHING) {
                TickResult pathResult = this.tickActivePath(level, player, selectionPredicate, now);
                if (pathResult != null) {
                    return pathResult;
                }
            }
            if (this.state == State.SETTLING) {
                if (now < this.settleUntilTick) {
                    return new TickResult(true, "autoBedrock.status.settling", false, false);
                }
                BlockPos recoveredTarget = this.currentTarget;
                int completedAttempt = this.repositionAttempt;
                this.finishRepositionAttempt(recoveredTarget);
                this.state = State.VERIFYING;
                this.verifyUntilTick = now + VERIFY_TICKS;
                this.statusKey = "autoBedrock.status.verifying";
                Reference.LOGGER.info("[AutoBedrock] initialization reposition completed: target={} attempt={} player={}",
                        recoveredTarget, completedAttempt, player.blockPosition().toShortString());
                return new TickResult(false, this.statusKey, false, true);
            }
        }

        // Never move or start ordinary clearing while a bedrock machine owns temporary blocks.
        if (BedrockController.hasActiveWork()) {
            if (BedrockController.hasPendingPathingRecovery() && now >= this.repositionRetryUntilTick) {
                BedrockController.PathingRecovery recovery = BedrockController.consumePathingRecovery();
                if (recovery != null && this.startInitializationRecovery(level, player, recovery, now)) {
                    this.repositionRetryUntilTick = 0L;
                    return new TickResult(true, this.statusKey, false, false);
                }
                if (recovery != null) {
                    BedrockController.finishPathingRecovery(recovery.target());
                    this.repositionRetryUntilTick = now + REPOSITION_RETRY_TICKS;
                    Reference.LOGGER.warn("[AutoBedrock] no alternate station for stalled initialization: target={} attempt={} origin={}",
                            recovery.target(), recovery.attempt(), recovery.origin());
                }
            }
            this.bedrockGateScanner.reset();
            this.cancelPath();
            this.state = State.IDLE;
            this.currentGoal = null;
            this.currentTarget = null;
            this.currentTargetType = null;
            this.statusKey = "autoBedrock.status.working";
            return new TickResult(false, this.statusKey, false, false);
        }

        if (this.state == State.PATHING) {
            TickResult pathResult = this.tickActivePath(level, player, selectionPredicate, now);
            if (pathResult != null) {
                return pathResult;
            }
        }
        if (this.state == State.SETTLING) {
            if (now < this.settleUntilTick) {
                return new TickResult(true, "autoBedrock.status.settling", false, false);
            }
            this.state = State.VERIFYING;
            this.verifyUntilTick = now + VERIFY_TICKS;
            this.statusKey = "autoBedrock.status.verifying";
            return new TickResult(false, this.statusKey, false, true);
        }
        if (this.state == State.VERIFYING) {
            boolean candidatePresent = this.currentTarget != null && isCandidate(level, this.currentTarget,
                    this.currentTargetType, fluid, mine, bedrockReady);
            boolean beforeDeadline = now < this.verifyUntilTick;
            if (VerificationTimeoutPolicy.decide(localSettled, candidatePresent, beforeDeadline)
                    == VerificationTimeoutPolicy.Decision.WAIT) {
                return new TickResult(false, "autoBedrock.status.verifying", false, false);
            }
            if (candidatePresent) {
                this.deferTarget(this.currentTarget, now);
            }
            this.currentTarget = null;
            this.currentTargetType = null;
            this.currentGoal = null;
            this.state = State.SEARCHING;
            this.scanner.reset();
        }

        if (localBedrockPhase && bedrockReady
                && Configs.Clear.CLEAR_BEDROCK_ENABLED.getBooleanValue() && !this.activeBoxes.isEmpty()) {
            BedrockGateResult gate = this.bedrockGateScanner.scan(
                    level, player, this.activeBoxes, selectionPredicate);
            if (!gate.complete()) {
                this.statusKey = "autoBedrock.status.searchingDenseBedrock";
                this.logDiagnostic(now, "bedrock-gate-scan-" + this.sections.activeNumber(),
                        "[AutoBedrock] bedrock batch gate scanning: section={}/{} inspected={}/{} pass={}",
                        this.sections.activeNumber(), this.sections.sectionCount(), gate.inspected(),
                        volumeOf(this.activeBoxes), this.sections.isDenseBedrockPass() ? "dense" : "sparse");
                return new TickResult(true, this.statusKey, false, false);
            }
            if (gate.target() != null) {
                int minimumBatch = Configs.AutoClear.MIN_BEDROCK_BATCH_SIZE.getIntegerValue();
                boolean locallyReachable = gate.locallyReachableCount() >= Math.max(1, minimumBatch);
                boolean selectedTargetLocallyWorkable = canWorkLocally(
                        level, player, gate.target(), CandidateType.BEDROCK);

                // Sparse cleanup may ignore the minimum batch size, but it must not
                // ignore player/machine overlap. Move aside immediately instead of
                // creating a target and waiting for initialization recovery.
                if (!this.sections.isDenseBedrockPass() && !selectedTargetLocallyWorkable) {
                    List<BlockPos> stations = findWorkStations(level, player, gate.target(),
                            CandidateType.BEDROCK, this::isInsideMovementArea, false);
                    if (!stations.isEmpty()
                            && this.startCompositePath(stations, gate.target(), CandidateType.BEDROCK, now)) {
                        return new TickResult(true, this.statusKey, false, false);
                    }
                    if (Configs.AutoClear.PATHING_ALLOW_BREAK.getBooleanValue()) {
                        List<BlockPos> carvedStations = findWorkStations(level, player, gate.target(),
                                CandidateType.BEDROCK, this::isInsideMovementArea, true);
                        if (!carvedStations.isEmpty()
                                && this.startCompositePath(carvedStations, gate.target(), CandidateType.BEDROCK, now)) {
                            return new TickResult(true, this.statusKey, false, false);
                        }
                    }
                    this.statusKey = "autoBedrock.status.waitingDeferred";
                    return new TickResult(true, this.statusKey, false, false);
                }
                BedrockBatchGatePolicy.Decision decision = BedrockBatchGatePolicy.decide(
                        true, this.sections.isDenseBedrockPass(), gate.clusterSize(),
                        minimumBatch, locallyReachable);
                this.logDiagnostic(now, "bedrock-gate-" + this.sections.activeNumber() + "-" + decision
                                + "-" + gate.target().asLong(),
                        "[AutoBedrock] bedrock batch gate: section={}/{} pass={} cluster={} localCluster={} minimum={} target={} local={} allLoaded={} decision={}",
                        this.sections.activeNumber(), this.sections.sectionCount(),
                        this.sections.isDenseBedrockPass() ? "dense" : "sparse", gate.clusterSize(),
                        gate.locallyReachableCount(), minimumBatch, gate.target().toShortString(), locallyReachable,
                        gate.allLoaded(), decision);
                if (decision == BedrockBatchGatePolicy.Decision.POSTPONE_SECTION) {
                    boolean advanced = this.sections.postponeSparseAndAdvance(player.blockPosition());
                    this.activeBoxes = this.sections.getActiveBoxes();
                    AutoWorkAreaScope.setActiveBoxes(this.activeBoxes);
                    this.onSectionChanged();
                    this.statusKey = this.sections.isDenseBedrockPass()
                            ? "autoBedrock.status.searchingDenseBedrock"
                            : "autoBedrock.status.sparseCleanup";
                    return new TickResult(true, this.statusKey, !advanced && this.sections.isComplete(), true);
                }
                if (decision == BedrockBatchGatePolicy.Decision.MOVE_TO_CLUSTER) {
                    List<BlockPos> stations = findBatchWorkStations(level, player,
                            gate.clusterCandidates(), this::isInsideMovementArea, false, minimumBatch);
                    if (!stations.isEmpty()
                            && this.startCompositePath(stations, gate.target(), CandidateType.BEDROCK, now)) {
                        return new TickResult(true, this.statusKey, false, false);
                    }
                    if (Configs.AutoClear.PATHING_ALLOW_BREAK.getBooleanValue()) {
                        List<BlockPos> carvedStations = findBatchWorkStations(level, player,
                                gate.clusterCandidates(), this::isInsideMovementArea, true, minimumBatch);
                        if (!carvedStations.isEmpty()
                                && this.startCompositePath(carvedStations, gate.target(), CandidateType.BEDROCK, now)) {
                            return new TickResult(true, this.statusKey, false, false);
                        }
                    }
                    if (!this.isInsideMovementArea(player.blockPosition())) {
                        BlockPos entry = findNearestSelectionEntry(level, player, globalBoxes);
                        if (entry != null
                                && this.startBlockPath(entry, gate.target(), CandidateType.BEDROCK, now)) {
                            return new TickResult(true, this.statusKey, false, false);
                        }
                    }
                    // A geometrically dense cluster may still have no station that can
                    // actually reach the configured number of targets. The old fusion
                    // returned the best one-target station here, effectively ignoring
                    // minBedrockBatchSize and walking through leftovers one by one.
                    boolean advanced = this.sections.postponeSparseAndAdvance(player.blockPosition());
                    this.activeBoxes = this.sections.getActiveBoxes();
                    AutoWorkAreaScope.setActiveBoxes(this.activeBoxes);
                    this.onSectionChanged();
                    this.statusKey = this.sections.isDenseBedrockPass()
                            ? "autoBedrock.status.searchingDenseBedrock"
                            : "autoBedrock.status.sparseCleanup";
                    this.logDiagnostic(now, "bedrock-gate-insufficient-coverage-" + gate.target().asLong(),
                            "[AutoBedrock] postponing cluster without a true batch station: target={} cluster={} minimum={} player={}",
                            gate.target().toShortString(), gate.clusterSize(), minimumBatch,
                            player.blockPosition().toShortString());
                    return new TickResult(true, this.statusKey,
                            !advanced && this.sections.isComplete(), true);
                }
            }
        }

        if (!localSettled) {
            this.statusKey = "autoBedrock.status.working";
            return new TickResult(false, this.statusKey, false, false);
        }
        if (this.activeBoxes.isEmpty()) {
            this.statusKey = this.sections.isComplete()
                    ? "autoBedrock.status.complete" : "autoBedrock.status.searching";
            return new TickResult(true, this.statusKey, this.sections.isComplete(), false);
        }


        this.state = State.SEARCHING;
        SearchResult search = this.scanner.scan(level, player, this.activeBoxes, selectionPredicate,
                fluid, mine, this.deferredTargets, now, bedrockReady);
        if (!search.complete()) {
            this.statusKey = "autoBedrock.status.searching";
            this.logDiagnostic(now, "scan-" + this.sections.activeNumber(),
                    "[AutoBedrock] scanning section: section={}/{} inspected={}/{} boxes={} player={}",
                    this.sections.activeNumber(), this.sections.sectionCount(), search.inspected(),
                    volumeOf(this.activeBoxes), describeBoxes(this.activeBoxes),
                    player.blockPosition().toShortString());
            return new TickResult(true, this.statusKey, false, false);
        }
        if (search.target() != null) {
            BlockPos target = search.target();
            CandidateType type = search.type();
            int minimumBatch = Configs.AutoClear.MIN_BEDROCK_BATCH_SIZE.getIntegerValue();
            if (type == CandidateType.BEDROCK && minimumBatch > 1
                    && this.sections.isDenseBedrockPass() && search.bedrockClusterSize() < minimumBatch) {
                boolean advanced = this.sections.postponeSparseAndAdvance(player.blockPosition());
                this.activeBoxes = this.sections.getActiveBoxes();
                AutoWorkAreaScope.setActiveBoxes(this.activeBoxes);
                this.onSectionChanged();
                this.statusKey = this.sections.isDenseBedrockPass()
                        ? "autoBedrock.status.searchingDenseBedrock"
                        : "autoBedrock.status.sparseCleanup";
                return new TickResult(true, this.statusKey, !advanced && this.sections.isComplete(), true);
            }
            if (canWorkLocally(level, player, target, type)) {
                this.currentTarget = target;
                this.currentTargetType = type;
                this.state = State.VERIFYING;
                this.verifyUntilTick = now + VERIFY_TICKS;
                this.statusKey = "autoBedrock.status.working";
                return new TickResult(false, this.statusKey, false, true);
            }
            List<BlockPos> stations = findWorkStations(level, player, target, type,
                    this::isInsideMovementArea, false);
            if (!stations.isEmpty() && this.startCompositePath(stations, target, type, now)) {
                return new TickResult(true, this.statusKey, false, false);
            }

            // In underground work areas there is often no pre-existing two-block-high air pocket.
            // When Baritone breaking is enabled, give it stations that become safe after it clears
            // the feet/head blocks. Requiring an already-open station here caused the coordinator
            // to defer every target forever and the HUD to appear stuck on scanning.
            if (Configs.AutoClear.PATHING_ALLOW_BREAK.getBooleanValue()) {
                List<BlockPos> carvedStations = findWorkStations(level, player, target, type,
                        this::isInsideMovementArea, true);
                if (!carvedStations.isEmpty()) {
                    this.logDiagnostic(now, "carve-" + target.asLong(),
                            "[AutoBedrock] no open work station; starting a carving approach: target={} type={} stations={}",
                            target.toShortString(), type, carvedStations.size());
                    if (this.startCompositePath(carvedStations, target, type, now)) {
                        return new TickResult(true, this.statusKey, false, false);
                    }
                }
            }

            // A solid uncleared section may not have a safe station next to the selected target yet.
            // Do not deadlock before the FLUID -> MINE -> BEDROCK pipeline gets a chance to carve an
            // entrance: approach any safe position at the section edge, then restart local clearing.
            if (!this.isInsideMovementArea(player.blockPosition())) {
                BlockPos entry = findNearestSelectionEntry(level, player, globalBoxes);
                if (entry != null && this.startBlockPath(entry, target, type, now)) {
                    return new TickResult(true, this.statusKey, false, false);
                }
            }

            this.logDiagnostic(now, "unreachable-" + target.asLong(),
                    "[AutoBedrock] target temporarily unreachable: target={} type={} player={} insideSection={} openStations=0 allowBreak={}",
                    target.toShortString(), type, player.blockPosition().toShortString(),
                    this.isInsideMovementArea(player.blockPosition()),
                    Configs.AutoClear.PATHING_ALLOW_BREAK.getBooleanValue());
            this.deferTarget(target, now);
            this.scanner.reset();
            this.statusKey = "autoBedrock.status.waitingDeferred";
            return new TickResult(true, this.statusKey, false, false);
        }

        this.logDiagnostic(now, "scan-empty-" + this.sections.activeNumber(),
                "[AutoBedrock] section scan completed without an available target: section={}/{} allLoaded={} deferred={} inspected={} boxes={}",
                this.sections.activeNumber(), this.sections.sectionCount(), search.allLoaded(),
                search.hasDeferredTarget(), search.inspected(), describeBoxes(this.activeBoxes));
        if (search.allLoaded()) {
            boolean advanced = search.hasDeferredTarget()
                    ? this.sections.deferAndAdvance(player.blockPosition())
                    : this.sections.completeAndAdvance(player.blockPosition());
            this.activeBoxes = this.sections.getActiveBoxes();
            AutoWorkAreaScope.setActiveBoxes(this.activeBoxes);
            this.onSectionChanged();
            if (!advanced) {
                this.statusKey = this.sections.isComplete()
                        ? "autoBedrock.status.complete" : "autoBedrock.status.waitingDeferred";
                return new TickResult(true, this.statusKey, this.sections.isComplete(), false);
            }
            this.statusKey = "autoBedrock.status.nextSection";
            return new TickResult(true, this.statusKey, false, true);
        }

        BlockPos survey = this.nextSurveyPoint(player, this.activeBoxes);
        if (survey != null && this.startSurveyPath(survey, now)) {
            return new TickResult(true, this.statusKey, false, false);
        }
        this.sections.deferAndAdvance(player.blockPosition());
        this.activeBoxes = this.sections.getActiveBoxes();
        AutoWorkAreaScope.setActiveBoxes(this.activeBoxes);
        this.onSectionChanged();
        this.statusKey = "autoBedrock.status.nextSection";
        return new TickResult(true, this.statusKey, false, true);
    }

    @Nullable
    private TickResult tickActivePath(ClientLevel level, LocalPlayer player,
                                      Predicate<BlockPos> selectionPredicate, long now) {
        if (this.currentTarget != null && level.hasChunkAt(this.currentTarget)
                && (!selectionPredicate.test(this.currentTarget)
                || !candidateStillPresent(level, this.currentTarget, this.currentTargetType))) {
            this.finishRepositionAttempt(this.currentTarget);
            this.cancelPath();
            this.state = State.SEARCHING;
            this.currentGoal = null;
            this.currentTarget = null;
            this.currentTargetType = null;
            this.scanner.reset();
            return null;
        }

        BlockPos playerPos = player.blockPosition();
        if (this.currentGoal != null && this.currentGoal.isReached(playerPos)) {
            if (this.currentGoal.kind() == GoalKind.XZ) {
                this.visitedSurveyPoints.add(xzKey(this.currentGoal.primary().getX(),
                        this.currentGoal.primary().getZ()));
            }
            Reference.LOGGER.info("[AutoBedrock] Baritone path reached: kind={} target={} player={}",
                    this.currentGoal.kind(), this.currentTarget, playerPos.toShortString());
            this.cancelPath();
            this.state = State.SETTLING;
            this.settleUntilTick = now + SETTLE_TICKS;
            return new TickResult(true, "autoBedrock.status.settling", false, false);
        }

        if (this.lastPlayerPos == null || !this.lastPlayerPos.equals(playerPos)) {
            this.lastPlayerPos = playerPos.immutable();
            this.lastProgressTick = now;
        } else if (now - this.lastProgressTick >= PATH_STALL_TICKS) {
            Reference.LOGGER.warn("[AutoBedrock] Baritone path stalled; restarting search: kind={} type={} target={} player={} idleTicks={} active={}",
                    this.currentGoal == null ? null : this.currentGoal.kind(), this.currentTargetType,
                    this.currentTarget, playerPos.toShortString(), now - this.lastProgressTick,
                    this.baritone.isActive());
            if (this.currentTarget != null) {
                this.deferTarget(this.currentTarget, now);
            }
            this.finishRepositionAttempt(this.currentTarget);
            this.cancelPath();
            this.state = State.SEARCHING;
            this.currentGoal = null;
            this.currentTarget = null;
            this.currentTargetType = null;
            this.scanner.reset();
            return new TickResult(true, "autoBedrock.status.searching", false, false);
        }

        if (this.baritone.isActive() || now - this.pathStartedTick < PATH_START_GRACE) {
            this.logDiagnostic(now, "path-active-" + (this.currentTarget == null ? "survey" : this.currentTarget.asLong()),
                    "[AutoBedrock] Baritone path heartbeat: kind={} type={} target={} player={} active={} pathAge={} idleTicks={}",
                    this.currentGoal == null ? null : this.currentGoal.kind(), this.currentTargetType,
                    this.currentTarget, playerPos.toShortString(), this.baritone.isActive(),
                    now - this.pathStartedTick, now - this.lastProgressTick);
            this.statusKey = this.currentGoal != null && this.currentGoal.kind() == GoalKind.XZ
                    ? "autoBedrock.status.surveying" : "autoBedrock.status.moving";
            return new TickResult(true, this.statusKey, false, false);
        }

        Reference.LOGGER.warn("[AutoBedrock] Baritone path became inactive before reaching goal: kind={} type={} target={} player={} pathAge={}",
                this.currentGoal == null ? null : this.currentGoal.kind(), this.currentTargetType,
                this.currentTarget, playerPos.toShortString(), now - this.pathStartedTick);
        if (this.currentTarget != null) {
            this.deferTarget(this.currentTarget, now);
        }
        this.finishRepositionAttempt(this.currentTarget);
        this.cancelPath();
        this.state = State.SEARCHING;
        this.currentGoal = null;
        this.currentTarget = null;
        this.currentTargetType = null;
        this.scanner.reset();
        return new TickResult(true, "autoBedrock.status.searching", false, false);
    }

    private boolean startBlockPath(BlockPos station, @Nullable BlockPos target,
                                   @Nullable CandidateType type, long now) {
        if (!this.baritone.startBlock(station)) {
            return false;
        }
        return this.pathStarted(GoalSpec.block(station), target, type, now);
    }

    private boolean startCompositePath(List<BlockPos> stations, BlockPos target,
                                       CandidateType type, long now) {
        List<BlockPos> limited = stations.size() > 24 ? stations.subList(0, 24) : stations;
        if (!this.baritone.startComposite(limited)) {
            return false;
        }
        return this.pathStarted(GoalSpec.composite(limited), target, type, now);
    }

    private boolean startInitializationRecovery(ClientLevel level, LocalPlayer player,
                                                BedrockController.PathingRecovery recovery, long now) {
        List<BlockPos> stations = findWorkStations(level, player, recovery.target(), CandidateType.BEDROCK,
                this::isInsideMovementArea, false).stream()
                .filter(pos -> !pos.equals(recovery.origin()))
                .toList();
        if (stations.isEmpty() && Configs.AutoClear.PATHING_ALLOW_BREAK.getBooleanValue()) {
            stations = findWorkStations(level, player, recovery.target(), CandidateType.BEDROCK,
                    this::isInsideMovementArea, true).stream()
                    .filter(pos -> !pos.equals(recovery.origin()))
                    .toList();
        }
        if (stations.isEmpty() || !this.startCompositePath(
                stations, recovery.target(), CandidateType.BEDROCK, now)) {
            return false;
        }
        this.repositioningActiveTarget = true;
        this.repositionAttempt = recovery.attempt();
        Reference.LOGGER.info("[AutoBedrock] initialization reposition path started: target={} attempt={} origin={} goals={}",
                recovery.target(), recovery.attempt(), recovery.origin(), stations.size());
        return true;
    }

    private void finishRepositionAttempt(@Nullable BlockPos target) {
        if (!this.repositioningActiveTarget) {
            return;
        }
        BedrockController.finishPathingRecovery(target);
        this.repositioningActiveTarget = false;
        this.repositionAttempt = 0;
        this.repositionRetryUntilTick = 0L;
    }

    private boolean startSurveyPath(BlockPos point, long now) {
        if (!this.baritone.startXZ(point.getX(), point.getZ())) {
            return false;
        }
        return this.pathStarted(GoalSpec.xz(point), null, null, now);
    }

    private boolean pathStarted(GoalSpec goal, @Nullable BlockPos target,
                                @Nullable CandidateType type, long now) {
        this.currentGoal = goal;
        this.currentTarget = target == null ? null : target.immutable();
        this.currentTargetType = type;
        this.pathStartedTick = now;
        this.lastProgressTick = now;
        this.lastPlayerPos = null;
        this.state = State.PATHING;
        this.statusKey = goal.kind() == GoalKind.XZ
                ? "autoBedrock.status.surveying" : "autoBedrock.status.moving";
        Reference.LOGGER.info("[AutoBedrock] Baritone path started: kind={} type={} target={} goals={}",
                goal.kind(), type, target, goal.positions().size());
        return true;
    }

    public void reset() {
        this.finishRepositionAttempt(this.currentTarget);
        this.baritone.shutdown();
        this.sections.reset();
        this.scanner.reset();
        this.bedrockGateScanner.reset();
        this.deferredTargets.clear();
        this.visitedSurveyPoints.clear();
        this.surveyPoints = List.of();
        this.surveySourceBoxes = List.of();
        this.activeBoxes = List.of();
        AutoWorkAreaScope.clear();
        this.state = State.IDLE;
        this.currentGoal = null;
        this.currentTarget = null;
        this.currentTargetType = null;
        this.manualResumeTick = 0L;
        this.lastDiagnosticTick = Long.MIN_VALUE;
        this.lastDiagnosticSignature = "";
        this.statusKey = "autoBedrock.status.idle";
        this.repositioningActiveTarget = false;
        this.repositionAttempt = 0;
        this.repositionRetryUntilTick = 0L;
    }

    public void pauseForEating() {
        if (this.state == State.PATHING) {
            this.cancelPath();
            this.state = State.IDLE;
        }
    }

    public void pauseForWarning() {
        this.cancelPath();
        this.state = State.IDLE;
        this.statusKey = "autoBedrock.status.working";
    }

    public List<PrinterBox> getActiveBoxes() {
        return this.activeBoxes;
    }

    public boolean containsActiveSection(BlockPos pos) {
        return this.activeBoxes.isEmpty() || AutoWorkAreaScope.contains(pos);
    }

    public String getStatusKey() {
        return this.statusKey;
    }

    public int getActiveSectionNumber() {
        return this.sections.activeNumber();
    }

    public int getSectionCount() {
        return this.sections.sectionCount();
    }

    public boolean isPathing() {
        return this.state == State.PATHING || this.state == State.SETTLING;
    }

    private void onSectionChanged() {
        this.finishRepositionAttempt(this.currentTarget);
        this.cancelPath();
        this.scanner.reset();
        this.bedrockGateScanner.reset();
        this.visitedSurveyPoints.clear();
        this.surveyPoints = List.of();
        this.surveySourceBoxes = List.of();
        this.currentGoal = null;
        this.currentTarget = null;
        this.currentTargetType = null;
        this.repositionRetryUntilTick = 0L;
        this.state = State.IDLE;
    }

    private void cancelPath() {
        this.baritone.shutdown();
    }

    private void deferTarget(BlockPos target, long now) {
        this.deferredTargets.put(target.immutable(), now + TARGET_DEFER_TICKS);
    }

    private boolean isInsideMovementArea(BlockPos pos) {
        for (PrinterBox box : this.activeBoxes) {
            if (pos.getX() >= box.minX - MOVEMENT_MARGIN_HORIZONTAL
                    && pos.getX() <= box.maxX + MOVEMENT_MARGIN_HORIZONTAL
                    && pos.getY() >= box.minY - MOVEMENT_MARGIN_VERTICAL
                    && pos.getY() <= box.maxY + MOVEMENT_MARGIN_VERTICAL
                    && pos.getZ() >= box.minZ - MOVEMENT_MARGIN_HORIZONTAL
                    && pos.getZ() <= box.maxZ + MOVEMENT_MARGIN_HORIZONTAL) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos findNearestSelectionEntry(ClientLevel level, LocalPlayer player,
                                                       List<PrinterBox> boxes) {
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (PrinterBox box : boxes) {
            int centerX = clamp(origin.getX(), box.minX, box.maxX);
            int centerY = clamp(origin.getY(), box.minY, box.maxY);
            int centerZ = clamp(origin.getZ(), box.minZ, box.maxZ);
            int minX = Math.max(box.minX - MOVEMENT_MARGIN_HORIZONTAL,
                    centerX - ENTRY_SEARCH_HORIZONTAL);
            int maxX = Math.min(box.maxX + MOVEMENT_MARGIN_HORIZONTAL,
                    centerX + ENTRY_SEARCH_HORIZONTAL);
            int minY = Math.max(box.minY - MOVEMENT_MARGIN_VERTICAL,
                    centerY - ENTRY_SEARCH_VERTICAL);
            int maxY = Math.min(box.maxY + MOVEMENT_MARGIN_VERTICAL,
                    centerY + ENTRY_SEARCH_VERTICAL);
            int minZ = Math.max(box.minZ - MOVEMENT_MARGIN_HORIZONTAL,
                    centerZ - ENTRY_SEARCH_HORIZONTAL);
            int maxZ = Math.min(box.maxZ + MOVEMENT_MARGIN_HORIZONTAL,
                    centerZ + ENTRY_SEARCH_HORIZONTAL);
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos feet = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(feet) || !isSafeStandingPosition(level, feet)) {
                            continue;
                        }
                        double distance = feet.distSqr(origin);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = feet;
                        }
                    }
                }
            }
        }
        return best == null ? null : best.immutable();
    }

    private static List<BlockPos> findWorkStations(ClientLevel level, LocalPlayer player,
                                                    BlockPos target, CandidateType type,
                                                    Predicate<BlockPos> movementPredicate,
                                                    boolean allowCarving) {
        List<BlockPos> stations = new ArrayList<>();
        double reach = Math.max(3.0D, PlayerUtils.getPlayerBlockInteractionRange() - 0.35D);
        double reachSq = reach * reach;
        int minHorizontal = type == CandidateType.BEDROCK ? 0 : 1;
        int maxHorizontal = type == CandidateType.BEDROCK
                ? Configs.AutoClear.MAX_LOCAL_BEDROCK_DISTANCE.getIntegerValue() : 3;
        BedrockMachineLayout machineLayout = type == CandidateType.BEDROCK
                ? BedrockMachineLayout.find(level, target) : null;
        int bestHorizontal = Integer.MAX_VALUE;
        for (int dy = -2; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    int horizontal = Math.max(Math.abs(dx), Math.abs(dz));
                    if (horizontal < minHorizontal || horizontal > maxHorizontal || horizontal > bestHorizontal) {
                        continue;
                    }
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (type == CandidateType.BEDROCK
                            && !BedrockLocalProximityPolicy.isWithin(feet, target,
                            Configs.AutoClear.MAX_LOCAL_BEDROCK_DISTANCE.getIntegerValue())) {
                        continue;
                    }
                    BlockPos pistonPos = machineLayout == null ? null : machineLayout.getPistonPos();
                    BlockPos headPos = machineLayout == null ? null : machineLayout.getHeadPos();
                    if (type == CandidateType.BEDROCK
                            && BedrockWorkstationClearancePolicy.blocksWorkstation(
                            feet, target, pistonPos, headPos)) {
                        continue;
                    }
                    boolean usable = allowCarving
                            ? isStandingPositionAfterCarving(level, feet)
                            : isSafeStandingPosition(level, feet);
                    if (!movementPredicate.test(feet) || !usable) {
                        continue;
                    }
                    double eyeX = feet.getX() + 0.5D;
                    double eyeY = feet.getY() + player.getEyeHeight();
                    double eyeZ = feet.getZ() + 0.5D;
                    double tx = target.getX() + 0.5D;
                    double ty = target.getY() + 0.5D;
                    double tz = target.getZ() + 0.5D;
                    double distanceSq = square(eyeX - tx) + square(eyeY - ty) + square(eyeZ - tz);
                    if (distanceSq <= reachSq) {
                        if (horizontal < bestHorizontal) {
                            stations.clear();
                            bestHorizontal = horizontal;
                        }
                        stations.add(feet.immutable());
                    }
                }
            }
        }
        stations.sort(Comparator.comparingDouble(pos -> pos.distSqr(player.blockPosition())));
        return stations;
    }

    private static List<BlockPos> findBatchWorkStations(ClientLevel level, LocalPlayer player,
                                                         List<BlockPos> candidates,
                                                         Predicate<BlockPos> movementPredicate,
                                                         boolean allowCarving, int minimumBatch) {
        Set<BlockPos> allStations = new LinkedHashSet<>();
        for (BlockPos candidate : candidates) {
            allStations.addAll(findWorkStations(level, player, candidate, CandidateType.BEDROCK,
                    movementPredicate, allowCarving));
        }
        return preferBatchStations(player, new ArrayList<>(allStations), candidates, minimumBatch);
    }

    private static List<BlockPos> preferBatchStations(LocalPlayer player, List<BlockPos> stations,
                                                          List<BlockPos> candidates, int minimumBatch) {
        if (stations.size() < 2 || candidates.isEmpty()) {
            return stations;
        }
        int bestCoverage = 0;
        List<BlockPos> best = new ArrayList<>();
        for (BlockPos station : stations) {
            int coverage = 0;
            for (BlockPos candidate : candidates) {
                if (canReachFromStation(player, station, candidate)) {
                    coverage++;
                }
            }
            if (coverage > bestCoverage) {
                bestCoverage = coverage;
                best.clear();
            }
            if (coverage == bestCoverage) {
                best.add(station);
            }
        }
        if (!BedrockBatchStationPolicy.hasEnoughCoverage(bestCoverage, minimumBatch)) {
            return List.of();
        }
        return best;
    }

    private static boolean canReachFromStation(LocalPlayer player, BlockPos station, BlockPos target) {
        if (!BedrockLocalProximityPolicy.isWithin(station, target,
                Configs.AutoClear.MAX_LOCAL_BEDROCK_DISTANCE.getIntegerValue())) {
            return false;
        }
        double reach = Math.max(3.0D, PlayerUtils.getPlayerBlockInteractionRange() - 0.35D);
        double eyeX = station.getX() + 0.5D;
        double eyeY = station.getY() + player.getEyeHeight();
        double eyeZ = station.getZ() + 0.5D;
        double targetX = target.getX() + 0.5D;
        double targetY = target.getY() + 0.5D;
        double targetZ = target.getZ() + 0.5D;
        return square(eyeX - targetX) + square(eyeY - targetY) + square(eyeZ - targetZ)
                <= reach * reach;
    }

    private BlockPos nextSurveyPoint(LocalPlayer player, List<PrinterBox> boxes) {
        this.updateSurveyPoints(boxes);
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos point : this.surveyPoints) {
            if (this.visitedSurveyPoints.contains(xzKey(point.getX(), point.getZ()))) {
                continue;
            }
            double distance = square(point.getX() - origin.getX()) + square(point.getZ() - origin.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = point;
            }
        }
        return best;
    }

    private void updateSurveyPoints(List<PrinterBox> boxes) {
        if (boxes.equals(this.surveySourceBoxes)) {
            return;
        }
        this.surveySourceBoxes = List.copyOf(boxes);
        Set<Long> seen = new LinkedHashSet<>();
        List<BlockPos> result = new ArrayList<>();
        for (PrinterBox box : boxes) {
            int startX = box.minX + Math.min(SURVEY_SPACING / 2,
                    Math.max(0, (box.maxX - box.minX) / 2));
            int startZ = box.minZ + Math.min(SURVEY_SPACING / 2,
                    Math.max(0, (box.maxZ - box.minZ) / 2));
            for (int x = startX; x <= box.maxX && result.size() < MAX_SURVEY_POINTS; x += SURVEY_SPACING) {
                for (int z = startZ; z <= box.maxZ && result.size() < MAX_SURVEY_POINTS; z += SURVEY_SPACING) {
                    if (seen.add(xzKey(x, z))) {
                        result.add(new BlockPos(x, box.minY, z));
                    }
                }
            }
        }
        this.surveyPoints = List.copyOf(result);
        this.visitedSurveyPoints.clear();
    }

    private static boolean isStandingPositionAfterCarving(ClientLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above())
                || !level.hasChunkAt(feet.below())) {
            return false;
        }
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        BlockState floorState = level.getBlockState(feet.below());
        return canOccupyAfterPathing(feetState)
                && canOccupyAfterPathing(headState)
                && isSafeFloor(level, feet.below(), floorState);
    }

    private static boolean canOccupyAfterPathing(BlockState state) {
        if (!state.getFluidState().isEmpty() || isHazard(state)) {
            return false;
        }
        if (state.isAir()) {
            return true;
        }
        return !state.is(Blocks.BEDROCK)
                && !state.is(Blocks.PISTON)
                && !state.is(Blocks.PISTON_HEAD)
                && !state.is(Blocks.MOVING_PISTON)
                && !state.is(Blocks.REDSTONE_TORCH)
                && !state.is(Blocks.REDSTONE_WALL_TORCH)
                && !state.is(Blocks.SLIME_BLOCK);
    }

    private static boolean isSafeStandingPosition(ClientLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet)) {
            return false;
        }
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        BlockState floorState = level.getBlockState(feet.below());
        if (!feetState.getCollisionShape(level, feet).isEmpty()
                || !headState.getCollisionShape(level, feet.above()).isEmpty()
                || !feetState.getFluidState().isEmpty()
                || !headState.getFluidState().isEmpty()) {
            return false;
        }
        return isSafeFloor(level, feet.below(), floorState);
    }

    private static boolean isSafeFloor(ClientLevel level, BlockPos floorPos, BlockState floorState) {
        return !floorState.getCollisionShape(level, floorPos).isEmpty() && !isHazard(floorState);
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW);
    }

    private static boolean canWorkLocally(ClientLevel level, LocalPlayer player, BlockPos target,
                                          @Nullable CandidateType type) {
        if (type == CandidateType.BEDROCK) {
            if (!BedrockLocalProximityPolicy.isWithin(player.blockPosition(), target,
                    Configs.AutoClear.MAX_LOCAL_BEDROCK_DISTANCE.getIntegerValue())) {
                return false;
            }
            BedrockMachineLayout layout = BedrockMachineLayout.find(level, target);
            return layout != null && !BedrockWorkstationClearancePolicy.blocksWorkstation(
                    player.blockPosition(), target, layout.getPistonPos(), layout.getHeadPos());
        }
        return PlayerUtils.isWithinBlockInteractionRange(player, target, 0.0D);
    }

    private static boolean candidateStillPresent(ClientLevel level, BlockPos pos,
                                                  @Nullable CandidateType type) {
        if (type == null || !level.hasChunkAt(pos)) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        return switch (type) {
            case FLUID -> !state.getFluidState().isEmpty();
            case MINE -> !state.isAir() && !state.is(Blocks.BEDROCK);
            case BEDROCK -> BedrockTargetBlocks.isTargetBlock(state);
        };
    }

    private static boolean isCandidate(ClientLevel level, BlockPos pos,
                                       @Nullable CandidateType type, FluidHandler fluid,
                                       MineHandler mine, boolean bedrockReady) {
        if (type == null || !level.hasChunkAt(pos)) {
            return false;
        }
        return switch (type) {
            case FLUID -> fluid.isPotentialAutoTarget(pos);
            case MINE -> mine.isPotentialAutoTarget(pos);
            case BEDROCK -> bedrockReady
                    && Configs.Clear.CLEAR_BEDROCK_ENABLED.getBooleanValue()
                    && BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos));
        };
    }

    private static boolean hasManualMovementInput() {
        return CLIENT.options.keyUp.isDown()
                || CLIENT.options.keyDown.isDown()
                || CLIENT.options.keyLeft.isDown()
                || CLIENT.options.keyRight.isDown()
                || CLIENT.options.keyJump.isDown()
                || CLIENT.options.keyShift.isDown()
                || CLIENT.options.keySprint.isDown();
    }

    private void logDiagnostic(long now, String signature, String message, Object... arguments) {
        if (!signature.equals(this.lastDiagnosticSignature)
                || now - this.lastDiagnosticTick >= DIAGNOSTIC_REPEAT_TICKS) {
            this.lastDiagnosticSignature = signature;
            this.lastDiagnosticTick = now;
            Reference.LOGGER.info(message, arguments);
        }
    }

    private static long volumeOf(List<PrinterBox> boxes) {
        long volume = 0L;
        for (PrinterBox box : boxes) {
            volume += (long) (box.maxX - box.minX + 1)
                    * (box.maxY - box.minY + 1)
                    * (box.maxZ - box.minZ + 1);
        }
        return volume;
    }

    private static String describeBoxes(List<PrinterBox> boxes) {
        if (boxes.isEmpty()) {
            return "[]";
        }
        List<String> descriptions = new ArrayList<>(boxes.size());
        for (PrinterBox box : boxes) {
            descriptions.add("[" + box.minX + "," + box.minY + "," + box.minZ
                    + " -> " + box.maxX + "," + box.maxY + "," + box.maxZ + "]");
        }
        return descriptions.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double square(double value) {
        return value * value;
    }

    private static long xzKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public record TickResult(boolean blocksLocalWork, String statusKey,
                             boolean complete, boolean restartLocalClear) {
    }

    private enum State { IDLE, SEARCHING, PATHING, SETTLING, VERIFYING, MANUAL_PAUSED }
    private enum CandidateType { FLUID, MINE, BEDROCK }
    private enum GoalKind { BLOCK, COMPOSITE, XZ }

    private record GoalSpec(GoalKind kind, List<BlockPos> positions) {
        static GoalSpec block(BlockPos pos) {
            return new GoalSpec(GoalKind.BLOCK, List.of(pos.immutable()));
        }

        static GoalSpec composite(List<BlockPos> positions) {
            return new GoalSpec(GoalKind.COMPOSITE, List.copyOf(positions));
        }

        static GoalSpec xz(BlockPos pos) {
            return new GoalSpec(GoalKind.XZ, List.of(pos.immutable()));
        }

        BlockPos primary() {
            return this.positions.getFirst();
        }

        boolean isReached(BlockPos playerPos) {
            if (this.kind == GoalKind.XZ) {
                BlockPos goal = this.primary();
                return playerPos.getX() == goal.getX() && playerPos.getZ() == goal.getZ();
            }
            for (BlockPos pos : this.positions) {
                if (pos.equals(playerPos)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class BedrockGateScanner {
        private List<PrinterBox> boxes = List.of();
        private int boxIndex;
        @Nullable private Iterator<BlockPos> iterator;
        private boolean complete;
        private boolean allLoaded = true;
        private long inspectedTotal;
        private final List<BlockPos> candidates = new ArrayList<>();
        @Nullable private BedrockGateResult result;

        void reset() {
            this.boxes = List.of();
            this.boxIndex = 0;
            this.iterator = null;
            this.complete = false;
            this.allLoaded = true;
            this.inspectedTotal = 0L;
            this.candidates.clear();
            this.result = null;
        }

        BedrockGateResult scan(ClientLevel level, LocalPlayer player, List<PrinterBox> nextBoxes,
                                Predicate<BlockPos> selectionPredicate) {
            if (!nextBoxes.equals(this.boxes)) {
                this.reset();
                this.boxes = List.copyOf(nextBoxes);
            }
            if (this.result != null) {
                return this.result;
            }
            int inspected = 0;
            while (inspected < SCAN_BUDGET) {
                if (this.iterator == null || !this.iterator.hasNext()) {
                    if (!this.advance()) {
                        this.complete = true;
                        break;
                    }
                }
                BlockPos pos = this.iterator.next().immutable();
                inspected++;
                this.inspectedTotal++;
                if (!selectionPredicate.test(pos)) {
                    continue;
                }
                if (!level.hasChunkAt(pos)) {
                    this.allLoaded = false;
                    continue;
                }
                if (BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos))) {
                    this.candidates.add(pos);
                }
            }
            if (!this.complete) {
                return new BedrockGateResult(null, 0, 0, List.of(), false,
                        this.allLoaded, this.inspectedTotal);
            }
            BedrockClusterSelector.Selection selected = BedrockClusterSelector.select(
                    this.candidates, player.blockPosition());
            List<BlockPos> clusterCandidates = selected.target() == null ? List.of()
                    : this.candidates.stream()
                    .filter(candidate -> BedrockClusterSelector.belongsToCluster(selected.target(), candidate))
                    .toList();
            int locallyReachableCount = 0;
            for (BlockPos candidate : clusterCandidates) {
                if (canWorkLocally(level, player, candidate, CandidateType.BEDROCK)) {
                    locallyReachableCount++;
                }
            }
            this.result = new BedrockGateResult(selected.target(), selected.count(),
                    locallyReachableCount, List.copyOf(clusterCandidates), true,
                    this.allLoaded, this.inspectedTotal);
            return this.result;
        }

        private boolean advance() {
            while (this.boxIndex < this.boxes.size()) {
                this.iterator = this.boxes.get(this.boxIndex++).iterator();
                if (this.iterator.hasNext()) {
                    return true;
                }
            }
            this.iterator = null;
            return false;
        }
    }

    private static final class LoadedScanner {
        private List<PrinterBox> boxes = List.of();
        private int boxIndex;
        @Nullable private Iterator<BlockPos> iterator;
        private boolean complete;
        private boolean allLoaded = true;
        private boolean hasDeferredTarget;
        private long inspectedTotal;
        @Nullable private BlockPos bestFluid;
        @Nullable private BlockPos bestMine;
        private final List<BlockPos> bedrockCandidates = new ArrayList<>();
        private double bestFluidDistance = Double.POSITIVE_INFINITY;
        private double bestMineDistance = Double.POSITIVE_INFINITY;

        void reset() {
            this.boxes = List.of();
            this.boxIndex = 0;
            this.iterator = null;
            this.complete = false;
            this.allLoaded = true;
            this.hasDeferredTarget = false;
            this.inspectedTotal = 0L;
            this.bestFluid = null;
            this.bestMine = null;
            this.bedrockCandidates.clear();
            this.bestFluidDistance = Double.POSITIVE_INFINITY;
            this.bestMineDistance = Double.POSITIVE_INFINITY;
        }

        SearchResult scan(ClientLevel level, LocalPlayer player, List<PrinterBox> nextBoxes,
                          Predicate<BlockPos> selectionPredicate, FluidHandler fluid,
                          MineHandler mine, Map<BlockPos, Long> deferred, long now,
                          boolean bedrockReady) {
            if (!nextBoxes.equals(this.boxes) || this.complete) {
                this.reset();
                this.boxes = List.copyOf(nextBoxes);
            }
            int inspected = 0;
            while (inspected < SCAN_BUDGET) {
                if (this.iterator == null || !this.iterator.hasNext()) {
                    if (!this.advance()) {
                        this.complete = true;
                        break;
                    }
                }
                BlockPos pos = this.iterator.next().immutable();
                inspected++;
                this.inspectedTotal++;
                if (!selectionPredicate.test(pos)) {
                    continue;
                }
                if (!level.hasChunkAt(pos)) {
                    this.allLoaded = false;
                    continue;
                }
                Long retry = deferred.get(pos);
                CandidateType type = classify(level, pos, fluid, mine, bedrockReady);
                if (type == null) {
                    continue;
                }
                if (retry != null && retry > now) {
                    this.hasDeferredTarget = true;
                    continue;
                }
                double distance = pos.distSqr(player.blockPosition());
                switch (type) {
                    case FLUID -> {
                        if (distance < this.bestFluidDistance) {
                            this.bestFluidDistance = distance;
                            this.bestFluid = pos;
                        }
                    }
                    case MINE -> {
                        if (distance < this.bestMineDistance) {
                            this.bestMineDistance = distance;
                            this.bestMine = pos;
                        }
                    }
                    case BEDROCK -> this.bedrockCandidates.add(pos);
                }
            }
            if (!this.complete) {
                return new SearchResult(null, null, false, this.allLoaded, this.hasDeferredTarget,
                        this.inspectedTotal, 0);
            }
            if (this.bestFluid != null) {
                return new SearchResult(this.bestFluid, CandidateType.FLUID, true,
                        this.allLoaded, this.hasDeferredTarget, this.inspectedTotal, 0);
            }
            if (this.bestMine != null) {
                return new SearchResult(this.bestMine, CandidateType.MINE, true,
                        this.allLoaded, this.hasDeferredTarget, this.inspectedTotal, 0);
            }
            BedrockClusterSelector.Selection bedrock = BedrockClusterSelector.select(
                    this.bedrockCandidates, player.blockPosition());
            return new SearchResult(bedrock.target(),
                    bedrock.target() == null ? null : CandidateType.BEDROCK,
                    true, this.allLoaded, this.hasDeferredTarget, this.inspectedTotal, bedrock.count());
        }

        @Nullable
        private static CandidateType classify(ClientLevel level, BlockPos pos,
                                              FluidHandler fluid, MineHandler mine,
                                              boolean bedrockReady) {
            if (Configs.Clear.CLEAR_FLUID_ENABLED.getBooleanValue()
                    && fluid.isPotentialAutoTarget(pos)) {
                return CandidateType.FLUID;
            }
            if (Configs.Clear.CLEAR_MINE_ENABLED.getBooleanValue()
                    && mine.isPotentialAutoTarget(pos)) {
                return CandidateType.MINE;
            }
            if (bedrockReady
                    && Configs.Clear.CLEAR_BEDROCK_ENABLED.getBooleanValue()
                    && BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos))) {
                return CandidateType.BEDROCK;
            }
            return null;
        }

        private boolean advance() {
            while (this.boxIndex < this.boxes.size()) {
                this.iterator = this.boxes.get(this.boxIndex++).iterator();
                if (this.iterator.hasNext()) {
                    return true;
                }
            }
            this.iterator = null;
            return false;
        }
    }

    private record BedrockGateResult(@Nullable BlockPos target, int clusterSize,
                                      int locallyReachableCount, List<BlockPos> clusterCandidates,
                                      boolean complete, boolean allLoaded, long inspected) {
    }

    private record SearchResult(@Nullable BlockPos target, @Nullable CandidateType type,
                                boolean complete, boolean allLoaded,
                                boolean hasDeferredTarget, long inspected, int bedrockClusterSize) {
    }
}
