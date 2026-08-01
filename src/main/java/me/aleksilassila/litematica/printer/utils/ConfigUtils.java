package me.aleksilassila.litematica.printer.utils;

import fi.dy.masa.malilib.config.options.ConfigOptionList;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.handler.WorkAreaPolicy;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

public class ConfigUtils {
    @NotNull
    public static final Minecraft client = Minecraft.getInstance();

    public static boolean isEnable() {
        return Configs.Core.WORK_SWITCH.getBooleanValue();
    }

    public static boolean isMultiMode() {
        return Configs.Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.MULTI);
    }

    public static boolean isSingleMode() {
        return Configs.Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.SINGLE);
    }

    public static boolean isPrintMode() {
        if (isMultiMode()) {
            return Configs.Core.PRINT.getBooleanValue();
        }
        return Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.PRINTER;
    }

    public static boolean isClearMode() {
        return isSingleMode()
                && Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.CLEAR;
    }

    /** Uses the strict FLUID -> MINE -> BEDROCK clear pipeline. */
    public static boolean usesClearPipeline() {
        return isClearMode() || isAutoBedrockMode();
    }

    public static boolean isMineMode() {
        if (isMultiMode()) {
            return Configs.Core.MINE.getBooleanValue();
        }
        return Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.MINE
                || usesClearPipeline();
    }

    public static boolean isFillMode() {
        if (isMultiMode()) {
            return Configs.Core.FILL.getBooleanValue();
        }
        return Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.FILL;
    }

    public static boolean isFluidMode() {
        if (isMultiMode()) {
            return Configs.Core.FLUID.getBooleanValue();
        }
        return Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.FLUID
                || usesClearPipeline();
    }

    public static boolean isBedrockMode() {
        if (isMultiMode()) {
            return Configs.Hotkeys.BEDROCK.getBooleanValue();
        }
        PrintModeType mode = (PrintModeType) Configs.Core.WORK_MODE_TYPE.getOptionListValue();
        return mode == PrintModeType.BEDROCK || mode == PrintModeType.AUTO_BEDROCK;
    }

    public static boolean isAutoBedrockMode() {
        return isSingleMode()
                && Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.AUTO_BEDROCK;
    }

    public static PrintModeType getPrintModeType() {
        return (PrintModeType) Configs.Core.WORK_MODE_TYPE.getOptionListValue();
    }

    public static int getPlaceCooldown() {
        return Configs.Placement.PLACE_COOLDOWN.getIntegerValue();
    }

    public static int getBreakCooldown() {
        return Configs.Break.BREAK_COOLDOWN.getIntegerValue();
    }

    public static int getWorkRange() {
        return Configs.Core.WORK_RANGE.getIntegerValue();
    }

    public static boolean canInteracted(BlockPos blockPos) {
        return WorkAreaPolicy.canInteract(blockPos);
    }

    public static boolean isPositionInClearSelection(Player player, @NotNull BlockPos pos) {
        if (WorkAreaPolicy.followsPlayer()) {
            return WorkAreaPolicy.canInteract(pos);
        }
        return LitematicaUtils.isWithinSelection1ModeRange(pos)
                && isPositionInSelectionRange(player, pos, Configs.Clear.CLEAR_SELECTION_TYPE);
    }

    public static boolean isPositionInSelectionRange(Player player, @NotNull BlockPos pos, ConfigOptionList selectionTypeConfig) {
        if (player == null || selectionTypeConfig == null) {
            return false;
        }
        if (!(selectionTypeConfig.getOptionListValue() instanceof SelectionType selectionType)) {
            return false;
        }
        return switch (selectionType) {
            case LITEMATICA_RENDER_LAYER -> LitematicaUtils.isPositionWithinRange(pos);
            case LITEMATICA_SELECTION_BELOW_PLAYER -> pos.getY() <= Math.floor(player.getY());
            case LITEMATICA_SELECTION_ABOVE_PLAYER -> pos.getY() >= Math.ceil(player.getY());
            default -> true;
        };
    }

    public static Direction getFillModeFacing() {
        if (Configs.Fill.FILL_BLOCK_FACING.getOptionListValue() instanceof FillModeFacingType fillModeFacingType) {
            return switch (fillModeFacingType) {
                case DOWN -> Direction.DOWN;
                case UP -> Direction.UP;
                case WEST -> Direction.WEST;
                case EAST -> Direction.EAST;
                case NORTH -> Direction.NORTH;
                case SOUTH -> Direction.SOUTH;
                default -> null;
            };
        }
        return null;
    }

    public static float getBreakProgressThreshold() {
        int value = Configs.Break.BREAK_PROGRESS_THRESHOLD.getIntegerValue();
        if (value < 70) {
            value = 70;
        } else if (value > 100) {
            value = 100;
        }
        return (float) value / 100;
    }

}
