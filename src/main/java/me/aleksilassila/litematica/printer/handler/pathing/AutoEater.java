package me.aleksilassila.litematica.printer.handler.pathing;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.Modules;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import org.jetbrains.annotations.Nullable;

/** Eats available food while automatic bedrock work is otherwise idle. */
public final class AutoEater {
    public static final AutoEater INSTANCE = new AutoEater();

    private static final Minecraft MC = Minecraft.getInstance();
    private static final int FULL_FOOD_LEVEL = 20;
    private static final int MAX_ACTIVE_TICKS = 200;

    private State state = State.IDLE;
    private int originalHotbarSlot = -1;
    private int sourceInventorySlot = -1;
    private boolean swappedFromInventory;
    private long readyTick;
    private long stateStartedTick;

    private AutoEater() {
    }

    public void tick() {
        LocalPlayer player = MC.player;
        if (player == null || MC.level == null || MC.gameMode == null
                || !Configs.Core.WORK_SWITCH.getBooleanValue()
                || !ConfigUtils.isAutoBedrockMode()
                || !Configs.AutoClear.AUTO_EAT.getBooleanValue()) {
            this.reset();
            return;
        }

        if (this.state != State.IDLE && MC.level.getGameTime() - this.stateStartedTick > MAX_ACTIVE_TICKS) {
            Reference.LOGGER.warn("[AutoClear] automatic eating timed out: state={} age={} food={}",
                    this.state, MC.level.getGameTime() - this.stateStartedTick,
                    player.getFoodData().getFoodLevel());
            this.forceAbort(player);
            return;
        }

        if (this.state == State.PREPARING) {
            if (MC.level.getGameTime() >= this.readyTick) {
                this.startUsingFood(player);
            }
            return;
        }
        if (this.state == State.USING) {
            this.tickUsingFood(player);
            return;
        }
        if (player.getFoodData().getFoodLevel() >= FULL_FOOD_LEVEL
                || player.isUsingItem()
                || BedrockController.hasActiveWork()
                || SwitchItem.isWaitingForRestoreContainer()
                || me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.hasPendingSwitchRequest()
                || me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler
                || TakeItOutUtils.isAwaitingStack()
                || MC.screen != null
                || player.containerMenu != player.inventoryMenu) {
            return;
        }

        FoodChoice choice = findBestFood(player);
        if (choice != null) {
            // Stop Baritone before using the item so movement cannot interrupt eating or alter the work position.
            Modules.BEDROCK.pauseAutoBedrockForEating();
            this.prepareFood(player, choice);
        }
    }

    public boolean blocksAutomation() {
        return this.state != State.IDLE;
    }

    public void reset() {
        LocalPlayer player = MC.player;
        if (this.state != State.IDLE) {
            MC.options.keyUse.setDown(false);
            if (player != null) {
                if (player.isUsingItem() && MC.gameMode != null) {
                    MC.gameMode.releaseUsingItem(player);
                }
                if (!this.restoreInventory(player)) {
                    return;
                }
            }
        }
        this.clearState();
    }

    private void prepareFood(LocalPlayer player, FoodChoice choice) {
        this.originalHotbarSlot = player.getInventory().getSelectedSlot();
        this.sourceInventorySlot = choice.inventorySlot();
        this.swappedFromInventory = this.sourceInventorySlot >= 9;
        MC.gameMode.stopDestroyBlock();
        if (this.swappedFromInventory) {
            MC.gameMode.handleContainerInput(
                    player.inventoryMenu.containerId,
                    this.sourceInventorySlot,
                    this.originalHotbarSlot,
                    ContainerInput.SWAP,
                    player);
        } else {
            InventoryUtils.setHotbarSlot(this.sourceInventorySlot, player.getInventory());
        }
        this.state = State.PREPARING;
        this.stateStartedTick = MC.level.getGameTime();
        this.readyTick = MC.level.getGameTime() + 1L;
        Reference.LOGGER.info("[AutoClear] automatic eating started: item={} nutrition={} food={}",
                choice.stack().getItem(), choice.nutrition(), player.getFoodData().getFoodLevel());
    }

    private void startUsingFood(LocalPlayer player) {
        ItemStack stack = player.getMainHandItem();
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null || consumable == null || !consumable.canConsume(player, stack)) {
            this.finish(player);
            return;
        }
        MC.options.keyUse.setDown(true);
        InteractionResult result = MC.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (!result.consumesAction()) {
            this.finish(player);
            return;
        }
        this.state = State.USING;
    }

    private void tickUsingFood(LocalPlayer player) {
        MC.options.keyUse.setDown(true);
        if (player.isUsingItem()) {
            return;
        }
        if (player.getFoodData().getFoodLevel() >= FULL_FOOD_LEVEL) {
            this.finish(player);
            return;
        }
        ItemStack stack = player.getMainHandItem();
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null && consumable != null && consumable.canConsume(player, stack)) {
            this.startUsingFood(player);
            return;
        }
        this.finish(player);
    }

    private void finish(LocalPlayer player) {
        MC.options.keyUse.setDown(false);
        if (this.restoreInventory(player)) {
            this.clearState();
        }
    }

    private boolean restoreInventory(LocalPlayer player) {
        if (this.originalHotbarSlot < 0) {
            return true;
        }
        if (MC.gameMode == null) {
            return false;
        }
        if (this.swappedFromInventory && this.sourceInventorySlot >= 9) {
            if (player.containerMenu != player.inventoryMenu) {
                return false;
            }
            MC.gameMode.handleContainerInput(
                    player.inventoryMenu.containerId,
                    this.sourceInventorySlot,
                    this.originalHotbarSlot,
                    ContainerInput.SWAP,
                    player);
        }
        InventoryUtils.setHotbarSlot(this.originalHotbarSlot, player.getInventory());
        return true;
    }

    @Nullable
    private static FoodChoice findBestFood(LocalPlayer player) {
        FoodChoice best = null;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            FoodProperties food = stack.get(DataComponents.FOOD);
            Consumable consumable = stack.get(DataComponents.CONSUMABLE);
            if (food == null || consumable == null || !consumable.canConsume(player, stack)) {
                continue;
            }
            FoodChoice candidate = new FoodChoice(slot, stack, food.nutrition(), food.saturation());
            if (best == null || candidate.nutrition() > best.nutrition()
                    || candidate.nutrition() == best.nutrition()
                    && candidate.saturation() > best.saturation()) {
                best = candidate;
            }
        }
        return best;
    }

    private void forceAbort(LocalPlayer player) {
        MC.options.keyUse.setDown(false);
        if (player.isUsingItem() && MC.gameMode != null) {
            MC.gameMode.releaseUsingItem(player);
        }
        if (this.originalHotbarSlot >= 0) {
            InventoryUtils.setHotbarSlot(this.originalHotbarSlot, player.getInventory());
        }
        this.clearState();
    }

    private void clearState() {
        this.state = State.IDLE;
        this.originalHotbarSlot = -1;
        this.sourceInventorySlot = -1;
        this.swappedFromInventory = false;
        this.readyTick = 0L;
        this.stateStartedTick = 0L;
    }

    private enum State { IDLE, PREPARING, USING }

    private record FoodChoice(int inventorySlot, ItemStack stack, int nutrition, float saturation) { }
}

