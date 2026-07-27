package me.aleksilassila.litematica.printer.utils.mods;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.QuickShulkerModeType;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

@SuppressWarnings({"DataFlowIssue", "SpellCheckingInspection"})
public class ShulkerUtils {
    private static final String QUICK_SHULKER_CLIENT_UTIL = "net.kyrptonaught.quickshulker.client.ClientUtil";
    private static final String QUICK_SHULKER_METHOD = "CheckAndSend";
    static final Minecraft client = Minecraft.getInstance();

    public static boolean openShulker(ItemStack stack, int shulkerBoxSlot) {
        if (client.player == null || client.gameMode == null) {
            return false;
        }
        IConfigOptionListEntry openMode = Configs.Placement.QUICK_SHULKER_MODE.getOptionListValue();
        if (openMode == QuickShulkerModeType.INVOKE && tryInvokeQuickShulker(stack, shulkerBoxSlot)) {
            return true;
        }
        if (openMode == QuickShulkerModeType.CLICK_SLOT || openMode == QuickShulkerModeType.INVOKE) {
            return clickSlot(shulkerBoxSlot);
        }
        return false;
    }

    private static boolean clickSlot(int shulkerBoxSlot) {
        client.gameMode.handleContainerInput(client.player.containerMenu.containerId, shulkerBoxSlot, 1, ContainerInput.PICKUP, client.player);
        return true;
    }

    private static boolean tryInvokeQuickShulker(ItemStack stack, int shulkerBoxSlot) {
        if (!ModLoadUtils.isQuickShulkerLoaded()) {
            MessageUtils.addMessage(I18n.SHULKER_MOD_NOT_LOADED.getName());
            return false;
        }
        try {
            Class<?> clientUtil = Class.forName(QUICK_SHULKER_CLIENT_UTIL);
            Method checkAndSend = clientUtil.getMethod(QUICK_SHULKER_METHOD, ItemStack.class, int.class);
            return (boolean) checkAndSend.invoke(null, stack, shulkerBoxSlot);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException ignored) {
            return false;
        }
    }
}
