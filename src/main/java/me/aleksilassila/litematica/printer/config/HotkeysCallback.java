package me.aleksilassila.litematica.printer.config;

import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.enums.WorkingModeType;
import me.aleksilassila.litematica.printer.gui.ConfigUi;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.client.Minecraft;


//监听按键
public class HotkeysCallback {
    private static final Minecraft client = Minecraft.getInstance();

    public static boolean onKeyAction(KeyAction action, IKeybind key) {
        if (client.player == null || client.level == null) {
            return false;
        }
        if (key == Configs.Hotkeys.OPEN_SCREEN.getKeybind()) {
            //#if MC > 260100
            //$$ client.gui.setScreen(new ConfigUi());
            //#else
            client.setScreen(new ConfigUi());
            //#endif
            return true;
        }
        if (key == Configs.Hotkeys.ACTIVATE_CLEAR_MODE.getKeybind()) {
            if (Configs.Core.WORK_MODE.getOptionListValue() != WorkingModeType.SINGLE) {
                return true;
            }

            Configs.Core.WORK_MODE_TYPE.setOptionListValue(PrintModeType.CLEAR);
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            MessageUtils.setOverlayMessage(PrintModeType.CLEAR.getDisplayName());
            return true;
        }

        return false;
    }
}
