package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.enums.WorkAreaSourceType;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

public final class WorkAreaPolicy {
    private static final Minecraft CLIENT = Minecraft.getInstance();

    private WorkAreaPolicy() {
    }

    public static boolean followsPlayer() {
        return Configs.Core.WORK_AREA_SOURCE.getOptionListValue() == WorkAreaSourceType.FOLLOW_PLAYER;
    }

    public static boolean usesFixedLitematicaArea() {
        return !followsPlayer();
    }

    public static boolean canInteract(BlockPos pos) {
        if (!passesPlayerInteractionRangeCheck(pos)) {
            return false;
        }
        double range = Configs.Core.WORK_RANGE.getIntegerValue();
        if (Configs.Core.ITERATOR_SHAPE.getOptionListValue() instanceof RadiusShapeType shape) {
            return switch (shape) {
                case SPHERE -> PlayerUtils.isWithinWorkInteractedEuclideanRange(pos, range);
                case OCTAHEDRON -> PlayerUtils.isWithinWorkInteractedManhattanRange(pos, range);
                case CUBE -> PlayerUtils.isWithinWorkInteractedCubeRange(pos, range);
                case UPPER_SPHERE -> PlayerUtils.isWithinUpperWorkInteractedEuclideanRange(pos, range);
                case UPPER_OCTAHEDRON -> PlayerUtils.isWithinUpperWorkInteractedManhattanRange(pos, range);
                case UPPER_CUBE -> PlayerUtils.isWithinUpperWorkInteractedCubeRange(pos, range);
            };
        }
        return true;
    }

    public static boolean passesPlayerInteractionRangeCheck(BlockPos pos) {
        LocalPlayer player = CLIENT.player;
        if (player == null || pos == null) {
            return false;
        }
        return isWithinActualPlayerReach(pos);
    }

    /**
     * Checks the client's real block interaction reach, independent of the
     * optional work-area filter. Packet-producing actions must always use this
     * guard; disabling the work-area option must not enable out-of-reach actions.
     */
    public static boolean isWithinActualPlayerReach(BlockPos pos) {
        LocalPlayer player = CLIENT.player;
        return player != null
                && pos != null
                && PlayerUtils.isWithinBlockInteractionRange(player, pos, 0.0D);
    }

    public static boolean passesMandatoryPlayerReachCheck(BlockPos pos) {
        return isWithinActualPlayerReach(pos);
    }

    public static int generation() {
        int result = Configs.Core.WORK_AREA_SOURCE.getOptionListValue().hashCode();
        result = 31 * result + Configs.Core.WORK_RANGE.getIntegerValue();
        result = 31 * result + Boolean.hashCode(Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue());
        result = 31 * result + Configs.Core.ITERATOR_SHAPE.getOptionListValue().hashCode();
        return result;
    }

    public static boolean usesUpperHalfShape() {
        return Configs.Core.ITERATOR_SHAPE.getOptionListValue() instanceof RadiusShapeType shape
                && switch (shape) {
                    case UPPER_SPHERE, UPPER_OCTAHEDRON, UPPER_CUBE -> true;
                    default -> false;
                };
    }
}
