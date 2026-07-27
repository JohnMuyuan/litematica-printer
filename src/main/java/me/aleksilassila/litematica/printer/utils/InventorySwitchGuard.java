package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;

public final class InventorySwitchGuard {
    private static final Minecraft client = Minecraft.getInstance();
    private static final int MAX_WAIT_TICKS = 10;

    private static Item pendingItem;
    private static long pendingStartedTick;
    private static int pendingStartedInventoryPacketEpoch;

    private InventorySwitchGuard() {
    }

    public static void reset() {
        clear();
    }

    public static boolean markSwitchIfNeeded(Item item) {
        if (item == null) {
            return false;
        }
        pendingItem = item;
        pendingStartedTick = ClientPlayerTickManager.getCurrentHandlerTime();
        pendingStartedInventoryPacketEpoch = ClientPlayerTickManager.getInventoryPacketEpoch();
        ActionManager.INSTANCE.clearQueue();
        return true;
    }

    public static boolean isWaiting() {
        if (pendingItem == null) {
            return false;
        }
        ActionManager.INSTANCE.clearQueue();
        long age = ClientPlayerTickManager.getCurrentHandlerTime() - pendingStartedTick;
        if (age > MAX_WAIT_TICKS) {
            clear();
            return false;
        }
        if (age <= 0) {
            return true;
        }
        // The selected slot can be acknowledged by a normal single-slot
        // update, not only by a full container-content packet. Once the
        // expected item is in the hand after a tick boundary, continuing is
        // safe and avoids the old full-content-only stall.
        if (isMainHandReady(pendingItem)
                && (ClientPlayerTickManager.getInventoryPacketEpoch() > pendingStartedInventoryPacketEpoch
                || age >= 1)) {
            clear();
            return false;
        }
        return true;
    }

    private static void clear() {
        pendingItem = null;
        pendingStartedTick = 0L;
        pendingStartedInventoryPacketEpoch = 0;
    }

    private static boolean isMainHandReady(Item item) {
        return client.player != null && client.player.getMainHandItem().is(item);
    }
}
