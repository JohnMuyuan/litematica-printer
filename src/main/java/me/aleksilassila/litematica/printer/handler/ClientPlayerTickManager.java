package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.handler.clear.ClearController;
import me.aleksilassila.litematica.printer.handler.drop.MiningDropCollector;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.handlers.*;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.handler.pathing.AutoEater;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.network.RemoteWorkPacket;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.RttReplayController;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.client.Minecraft;

@SuppressWarnings("SpellCheckingInspection")
public class ClientPlayerTickManager {
    public static final Minecraft mc = Minecraft.getInstance();

    public static final GuiHandler GUI = Modules.GUI;
    public static final PrintHandler PRINT = Modules.PRINT;
    public static final FillHandler FILL = Modules.FILL;
    public static final MineHandler MINE = Modules.MINE;
    public static final FluidHandler FLUID = Modules.FLUID;
    public static final BedrockHandler BEDROCK = Modules.BEDROCK;

    public static final ImmutableList<Module> VALUES = Modules.VALUES;
    private static final TickScheduler SCHEDULER = new TickScheduler(VALUES);

    public static void tick() {
        try {
            AutoEater.INSTANCE.tick();
            if (!AutoEater.INSTANCE.blocksAutomation()) {
                SCHEDULER.tick();
            }
            MiningDropCollector.INSTANCE.tick();
        } finally {
            RemoteWorkPacket.flush();
        }
    }

    public static long getCurrentHandlerTime() {
        return TickContext.currentGameTime();
    }

    public static int getPacketTick() {
        return SCHEDULER.getPacketTick();
    }

    public static void setPacketTick(int packetTick) {
        SCHEDULER.setPacketTick(packetTick);
    }

    public static int getPacketEpoch() {
        return SCHEDULER.getPacketEpoch();
    }

    public static int getInventoryPacketEpoch() {
        return SCHEDULER.getInventoryPacketEpoch();
    }

    public static void recordInboundPacket() {
        SCHEDULER.recordInboundPacket();
    }

    public static void recordInventoryPacket() {
        SCHEDULER.recordInventoryPacket();
    }

    public static void resetRuntime(String reason) {
        AutoEater.INSTANCE.reset();
        BedrockController.reset();
        ActionManager.INSTANCE.clearQueue();
        if (mc.gameMode instanceof MultiPlayerGameModeExtension extension) {
            extension.litematica_printer$resetRuntime();
        }
        NetworkUtils.clearScopedLookOverride();
        RttReplayController.INSTANCE.reset();
        RemoteWorkPacket.reset();
        ScanCache.INSTANCE.clear();
        CooldownUtils.INSTANCE.clearAllCooldowns();
        InteractionUtils.INSTANCE.resetRuntime();
        InventorySwitchGuard.reset();
        TakeItOutUtils.resetPending();
        SwitchItem.reSet();
        me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.resetRuntime();
        HudStatsManager.INSTANCE.resetAll();
        SCHEDULER.resetRuntime();
        for (Module module : VALUES) {
            module.resetRuntimeState();
        }
    }

    public static ClearController.Snapshot getClearSnapshot() {
        return SCHEDULER.getClearSnapshot();
    }

    public static String getLastPauseReason() {
        return SCHEDULER.getLastPauseReason();
    }
}
