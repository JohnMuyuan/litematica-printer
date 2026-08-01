package me.aleksilassila.litematica.printer.handler.pathing;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.List;

public final class WorkSectionCoordinatorTest {
    public static void main(String[] args) {
        sourceBoxOrderDoesNotRestartCompletedProgress();
        selectionContextDoesNotRestartCompletedProgress();
        sparseSectionsArePostponedThenRecovered();
    }

    private static void sourceBoxOrderDoesNotRestartCompletedProgress() {
        WorkSectionCoordinator coordinator = new WorkSectionCoordinator();
        PrinterBox near = new PrinterBox(0, 0, 0, 3, 3, 3);
        PrinterBox far = new PrinterBox(64, 0, 0, 67, 3, 3);
        BlockPos player = new BlockPos(0, 0, 0);

        coordinator.update(List.of(near, far), player, 6, 16, 100);
        check(coordinator.getActiveBoxes().equals(List.of(near)), "nearest section must be selected first");
        check(coordinator.completeAndAdvance(player), "the second section must be available");
        check(coordinator.getActiveBoxes().equals(List.of(far)), "completion must advance to the far section");

        WorkSectionCoordinator.UpdateResult reordered = coordinator.update(
                List.of(far, near), player, 6, 16, 100);
        check(!reordered.rebuilt() && !reordered.requiresLocalRestart(),
                "reordering identical source boxes must not request a local clear restart");
        check(coordinator.getActiveBoxes().equals(List.of(far)),
                "reordering identical source boxes must not rebuild sections or revive completed work");
    }

    private static void selectionContextDoesNotRestartCompletedProgress() {
        WorkSectionCoordinator coordinator = new WorkSectionCoordinator();
        PrinterBox near = new PrinterBox(0, 0, 0, 3, 3, 3);
        PrinterBox far = new PrinterBox(64, 0, 0, 67, 3, 3);
        BlockPos player = new BlockPos(0, 0, 0);

        coordinator.update(List.of(near, far), player, 6, 16, 100);
        check(coordinator.completeAndAdvance(player), "the second section must be available");
        WorkSectionCoordinator.UpdateResult contextChanged = coordinator.update(
                List.of(near, far), player, 6, 16, 101);
        check(contextChanged.selectionContextChanged() && !contextChanged.requiresLocalRestart(),
                "selection predicate changes may restart scanning but not the local clear pipeline");
        check(coordinator.getActiveBoxes().equals(List.of(far)),
                "a dynamic selection predicate change must not erase section completion progress");
    }

    private static void sparseSectionsArePostponedThenRecovered() {
        WorkSectionCoordinator coordinator = new WorkSectionCoordinator();
        PrinterBox near = new PrinterBox(0, 0, 0, 3, 3, 3);
        PrinterBox far = new PrinterBox(64, 0, 0, 67, 3, 3);
        BlockPos player = new BlockPos(0, 0, 0);
        coordinator.update(List.of(near, far), player, 6, 16, 100);
        check(coordinator.postponeSparseAndAdvance(player), "dense pass must skip the first sparse section");
        check(coordinator.getActiveBoxes().equals(List.of(far)), "another section must be searched first");
        check(coordinator.postponeSparseAndAdvance(player), "after all sections are sparse, fallback must remain runnable");
        check(!coordinator.isDenseBedrockPass(), "coordinator must enter sparse cleanup mode");
        check(coordinator.getActiveBoxes().equals(List.of(near)), "sparse fallback must revisit postponed sections");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}