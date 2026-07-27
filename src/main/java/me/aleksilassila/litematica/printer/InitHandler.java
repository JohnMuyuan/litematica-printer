package me.aleksilassila.litematica.printer;

import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.gui.ConfigUi;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.printer.zxy.utils.HighlightBlockRenderer;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;

import static me.aleksilassila.litematica.printer.config.Configs.*;

public class InitHandler implements IInitializationHandler {
    private static void initModConfig() {
    }

    @Override
    public void registerModHandlers() {
        Configs.init();
        initModConfig();
        initConfigCallback();
        HighlightBlockRenderer.init();
    }

    private void initConfigCallback() {
        Hotkeys.CLOSE_ALL_MODE.getKeybind().setCallback((action, keybind) -> {
            if (keybind.isKeybindHeld()) {
                Core.PRINT.setBooleanValue(false);
                Core.MINE.setBooleanValue(false);
                Core.FILL.setBooleanValue(false);
                Core.FLUID.setBooleanValue(false);
                Hotkeys.BEDROCK.setBooleanValue(false);
                Core.WORK_SWITCH.setBooleanValue(false);
                Core.WORK_MODE_TYPE.setOptionListValue(PrintModeType.PRINTER);
                MessageUtils.setOverlayMessage(I18n.CLOSE_ALL_MODE_NOTICE.getName());
            }
            return true;
        });

        Core.WORK_SWITCH.setValueChangeCallback(config -> {
            if (!config.getBooleanValue()) {
                ClientPlayerTickManager.resetRuntime("work_switch_off");
            }
        });

        // A mode change must cancel every pending Bedrock target immediately.
        Core.WORK_MODE_TYPE.setValueChangeCallback(config -> {
            BedrockController.reset();
            ClientPlayerTickManager.BEDROCK.resetRuntimeState();
            ConfigUi.refresh();
        });
        Core.WORK_MODE.setValueChangeCallback(config -> {
            BedrockController.reset();
            ClientPlayerTickManager.BEDROCK.resetRuntimeState();
            ConfigUi.refresh();
        });

        // Multi-mode Bedrock can be stopped independently from the global work switch.
        Hotkeys.BEDROCK.setValueChangeCallback(config -> {
            if (!config.getBooleanValue()) {
                BedrockController.reset();
                ClientPlayerTickManager.BEDROCK.resetRuntimeState();
            }
            ConfigUi.refresh();
        });

        // Clear mode owns a separate Bedrock stage toggle.
        Clear.CLEAR_BEDROCK_ENABLED.setValueChangeCallback(config -> {
            if (!config.getBooleanValue()) {
                BedrockController.reset();
                ClientPlayerTickManager.BEDROCK.resetRuntimeState();
            }
            ConfigUi.refresh();
        });

        Core.WORK_AREA_SOURCE.setValueChangeCallback(config -> ConfigUi.refresh());
        Print.FILL_COMPOSTER.setValueChangeCallback(config -> ConfigUi.refresh());
        Break.BREAK_LIMITER.setValueChangeCallback(config -> ConfigUi.refresh());
        Break.BREAK_LIMIT.setValueChangeCallback(config -> ConfigUi.refresh());
        Mine.EXCAVATE_LIMITER.setValueChangeCallback(config -> ConfigUi.refresh());
        Mine.EXCAVATE_LIMIT.setValueChangeCallback(config -> ConfigUi.refresh());
        Magnet.ENABLED.setValueChangeCallback(config -> ConfigUi.refresh());
        Magnet.FILTER_ENABLED.setValueChangeCallback(config -> ConfigUi.refresh());
        Magnet.FILTER_MODE.setValueChangeCallback(config -> ConfigUi.refresh());
        Magnet.CUSTOM_LIST_TYPE.setValueChangeCallback(config -> ConfigUi.refresh());
        Fill.FILL_BLOCK_MODE.setValueChangeCallback(config -> ConfigUi.refresh());
        Core.LAG_CHECK.setValueChangeCallback(config -> ConfigUi.refresh());
        Core.RENDER_HUD.setValueChangeCallback(config -> ConfigUi.refresh());
        Placement.RTT_ADAPTIVE_INTERVAL.setValueChangeCallback(config -> ConfigUi.refresh());
    }
}
