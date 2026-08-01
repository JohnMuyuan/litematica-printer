package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.clear.ClearController;
import me.aleksilassila.litematica.printer.handler.handlers.GuiHandler;
import me.aleksilassila.litematica.printer.handler.handlers.MineDebugLog;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.client.Minecraft;

import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.hasPendingSwitchRequest;
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.switchItem;

final class TickScheduler {
    private static final Minecraft MC = Minecraft.getInstance();

    private final ImmutableList<Module> modules;
    private final ClearController clearController;
    private int packetTick;
    private int packetEpoch;
    private int inventoryPacketEpoch;
    private String lastPauseReason;
    private boolean runtimeActive;
    private int executionScopeHash = Integer.MIN_VALUE;
    private int pendingScopeHash = Integer.MIN_VALUE;
    private int scopeDrainTicks;
    private int roundRobinOffset;
    private final AutomationPauseWatchdog pauseWatchdog = new AutomationPauseWatchdog();

    private static final int MAX_INVENTORY_PAUSE_TICKS = 100;
    private static final int MAX_LOOK_QUEUE_PAUSE_TICKS = 20;

    TickScheduler(ImmutableList<Module> modules) {
        this.modules = modules;
        this.clearController = new ClearController(Modules.FLUID, Modules.MINE, Modules.BEDROCK);
    }

    void tick() {
        HudStatsManager.INSTANCE.tick();
        if (!Configs.Core.WORK_SWITCH.getBooleanValue()) {
            if (this.runtimeActive) {
                ClientPlayerTickManager.resetRuntime("work_switch_disabled");
            }
            this.runtimeActive = false;
            HudStatsManager.INSTANCE.resetAll();
            this.lastPauseReason = null;
            return;
        }
        int currentScopeHash = this.currentExecutionScopeHash();
        if (!this.runtimeActive) {
            this.runtimeActive = true;
            this.executionScopeHash = currentScopeHash;
        } else if (this.executionScopeHash != currentScopeHash) {
            ActionManager.INSTANCE.clearQueue();
            ScanCache.INSTANCE.clear();
            HudStatsManager.INSTANCE.resetAll();
            for (Module module : this.modules) {
                module.resetRuntimeState();
            }
            this.clearController.reset();
            this.executionScopeHash = currentScopeHash;
            this.pendingScopeHash = Integer.MIN_VALUE;
            this.scopeDrainTicks = 0;
            this.resume();
            return;
        }
        if (this.pauseForInventoryState("shared_precheck")) {
            return;
        }
        if (this.pauseForPendingLookQueue()) {
            ActionManager.INSTANCE.sendQueue(MC.player);
            return;
        }
        if (this.pauseForLagCheck()) {
            return;
        }
        TickContext context = TickContext.capture();
        this.resume();
        for (Module handler : this.modules) {
            if (handler instanceof GuiHandler) {
                handler.tick(context);
            }
        }
        if (ConfigUtils.usesClearPipeline()) {
            this.clearController.tick(context);
            return;
        }
        int actionableCount = Math.max(0, this.modules.size() - 1);
        if (actionableCount == 0) {
            return;
        }
        int startIndex = this.roundRobinOffset % actionableCount;
        this.roundRobinOffset = (this.roundRobinOffset + 1) % actionableCount;
        for (int offset = 0; offset < actionableCount; offset++) {
            Module handler = this.modules.get(1 + (startIndex + offset) % actionableCount);
            if (this.pauseForHandlerPrecheck(handler)) {
                return;
            }
            handler.tick(context);
        }
    }

    int getPacketTick() {
        return this.packetTick;
    }

    void setPacketTick(int packetTick) {
        this.packetTick = packetTick;
    }

    int getPacketEpoch() {
        return this.packetEpoch;
    }

    int getInventoryPacketEpoch() {
        return this.inventoryPacketEpoch;
    }

    void recordInboundPacket() {
        this.packetTick = 0;
        this.packetEpoch++;
    }

    void recordInventoryPacket() {
        this.inventoryPacketEpoch++;
    }

    void resetRuntime() {
        this.packetTick = 0;
        this.packetEpoch++;
        this.lastPauseReason = null;
        this.runtimeActive = false;
        this.executionScopeHash = Integer.MIN_VALUE;
        this.pendingScopeHash = Integer.MIN_VALUE;
        this.scopeDrainTicks = 0;
        this.roundRobinOffset = 0;
        this.pauseWatchdog.reset();
        this.clearController.reset();
    }

    ClearController.Snapshot getClearSnapshot() {
        return this.clearController.snapshot();
    }

    String getLastPauseReason() {
        return this.lastPauseReason;
    }

    private void pause(String reason) {
        if (!reason.equals(this.lastPauseReason)) {
            Reference.LOGGER.info("[AutoClear] scheduler paused: reason={} packetTick={}", reason, this.packetTick);
            this.lastPauseReason = reason;
        }
    }

    private void resume() {
        if (this.lastPauseReason != null) {
            Reference.LOGGER.info("[AutoClear] scheduler resumed: previousReason={} packetTick={}", this.lastPauseReason, this.packetTick);
            this.lastPauseReason = null;
        }
        this.pauseWatchdog.reset();
    }

    private boolean pauseForInventoryState(String reasonPrefix) {
        boolean switchingItem = switchItem();
        boolean pendingSwitch = hasPendingSwitchRequest();
        boolean takeItOutPending = TakeItOutUtils.isAwaitingStack();
        boolean inventorySwitchPending = InventorySwitchGuard.isWaiting();
        boolean openHandler = isOpenHandler;
        if (pendingSwitch || switchingItem || takeItOutPending || inventorySwitchPending) {
            ActionManager.INSTANCE.clearQueue();
            String detail = reasonPrefix + " openHandler=" + openHandler + " pendingSwitch=" + pendingSwitch
                    + " switchingItem=" + switchingItem + " takeItOutPending=" + takeItOutPending
                    + " inventorySwitchPending=" + inventorySwitchPending;
            this.pause(detail);
            if (this.pauseWatchdog.shouldRecover("inventory", MAX_INVENTORY_PAUSE_TICKS)) {
                Reference.LOGGER.warn("[AutoClear] scheduler recovered stale inventory pause: ticks={} detail={}",
                        this.pauseWatchdog.ticks(), detail);
                InventorySwitchGuard.reset();
                TakeItOutUtils.resetPending();
                me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.resetRuntime();
                this.resume();
                return false;
            }
            return true;
        }
        return false;
    }

    private boolean pauseForPendingLookQueue() {
        if (!ActionManager.INSTANCE.needWaitModifyLook) {
            return false;
        }
        this.pause("send_queue_wait_modify_look");
        if (this.pauseWatchdog.shouldRecover("look_queue", MAX_LOOK_QUEUE_PAUSE_TICKS)) {
            Reference.LOGGER.warn("[AutoClear] scheduler discarded stale look queue: ticks={}",
                    this.pauseWatchdog.ticks());
            ActionManager.INSTANCE.clearQueue();
            this.resume();
            return false;
        }
        return true;
    }

    private boolean pauseForLagCheck() {
        if (!Configs.Core.LAG_CHECK.getBooleanValue()) {
            return false;
        }
        if (this.packetTick > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
            this.pause("lag_check packetTick=" + this.packetTick + " max=" + Configs.Core.LAG_CHECK_MAX.getIntegerValue());
            return true;
        }
        this.packetTick++;
        return false;
    }

    private boolean pauseForHandlerPrecheck(Module handler) {
        if (this.pauseForInventoryState("handler_precheck handler=" + handler.getId())) {
            return true;
        }
        if (ActionManager.INSTANCE.needWaitModifyLook) {
            this.pause("action_wait_modify_look handler=" + handler.getId());
            return true;
        }
        return false;
    }

    private int currentExecutionScopeHash() {
        int result = Configs.Core.WORK_MODE.getOptionListValue().hashCode();
        result = 31 * result + Configs.Core.WORK_MODE_TYPE.getOptionListValue().hashCode();
        result = 31 * result + Configs.Core.WORK_AREA_SOURCE.getOptionListValue().hashCode();
        result = 31 * result + Configs.Core.WORK_RANGE.getIntegerValue();
        result = 31 * result + Configs.Core.ITERATOR_SHAPE.getOptionListValue().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Core.PRINT.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Core.MINE.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Core.FILL.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Core.FLUID.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Hotkeys.BEDROCK.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Clear.CLEAR_FLUID_ENABLED.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Clear.CLEAR_MINE_ENABLED.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Clear.CLEAR_BEDROCK_ENABLED.getBooleanValue());
        if (ConfigUtils.isAutoBedrockMode()) {
            result = 31 * result + Configs.Clear.CLEAR_MAX_RETRIES.getIntegerValue();
        }
        result = 31 * result + Configs.Clear.CLEAR_SELECTION_TYPE.getOptionListValue().hashCode();
        return result;
    }
}
