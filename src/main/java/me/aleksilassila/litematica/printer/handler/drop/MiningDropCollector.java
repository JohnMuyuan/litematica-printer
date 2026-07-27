package me.aleksilassila.litematica.printer.handler.drop;

import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.PickupFilterMode;
import me.aleksilassila.litematica.printer.network.RemotePickupPacket;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST;
import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST;
import static fi.dy.masa.tweakeroo.tweaks.PlacementTweaks.BLOCK_TYPE_BREAK_RESTRICTION;

/**
 * Tracks item entities produced by printer mining, picks them up at range in
 * local worlds, and uses QuickShulker to store them in carried shulker boxes.
 */
public final class MiningDropCollector {
    public static final MiningDropCollector INSTANCE = new MiningDropCollector();

    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final int PLAYER_STORAGE_SIZE = 36;
    private static final int SHULKER_STORAGE_SIZE = 27;
    private static final int BREAK_TRACK_TICKS = 100;
    private static final int ENTITY_TRACK_TICKS = 600;
    private static final int MAGNET_RETRY_TICKS = 2;
    private static final int PICKUP_WAIT_TICKS = 2;
    private static final int PICKUP_EXPIRE_TICKS = 80;
    private static final int ACTION_COOLDOWN_TICKS = 2;
    private static final double DROP_MATCH_DISTANCE_SQUARED = 9.0D;

    private final Deque<MinedBlock> recentMinedBlocks = new ArrayDeque<>();
    private final Map<Integer, TrackedDrop> trackedDrops = new HashMap<>();
    private final Deque<PendingPickup> pendingPickups = new ArrayDeque<>();
    private final Set<Integer> directlyStoredDrops = ConcurrentHashMap.newKeySet();

    private static Method quickShulkerInsertMethod;

    private ClientLevel observedLevel;
    private long nextActionTick;

    private MiningDropCollector() {
    }

    public void recordMinedBlock(BlockPos pos) {
        if (pos == null || !isWorkActive() || !isCollectorConfigured()) {
            return;
        }
        ClientLevel level = CLIENT.level;
        if (level == null) {
            return;
        }
        ensureLevel(level);
        long now = level.getGameTime();
        prune(now);
        this.recentMinedBlocks.addLast(new MinedBlock(pos.immutable(), now));
    }

    public void onEntityAdded(Entity entity) {
        if (!(entity instanceof ItemEntity itemEntity) || !isAutoStoreActive()) {
            return;
        }
        ClientLevel level = CLIENT.level;
        if (level == null || itemEntity.getItem().isEmpty()) {
            return;
        }
        ensureLevel(level);
        long now = level.getGameTime();
        prune(now);
        if (isNearRecentMinedBlock(itemEntity)) {
            this.trackedDrops.put(itemEntity.getId(), new TrackedDrop(itemEntity.getItem().copy(), now));
        }
    }

    public void recordPickup(int itemEntityId, int amount) {
        if (!CLIENT.isSameThread()) {
            CLIENT.execute(() -> recordPickup(itemEntityId, amount));
            return;
        }
        if (amount <= 0 || !isWorkActive() || !isCollectorConfigured()) {
            return;
        }
        ClientLevel level = CLIENT.level;
        if (level == null) {
            return;
        }
        ensureLevel(level);
        long now = level.getGameTime();
        prune(now);
        if (this.directlyStoredDrops.remove(itemEntityId)) {
            this.trackedDrops.remove(itemEntityId);
            return;
        }
        TrackedDrop tracked = this.trackedDrops.remove(itemEntityId);
        if (tracked == null || tracked.stack.isEmpty() || isShulkerBox(tracked.stack)) {
            return;
        }
        if (!isAutoStoreActive()) {
            return;
        }
        ItemStack expected = tracked.stack.copy();
        expected.setCount(1);
        this.pendingPickups.addLast(new PendingPickup(expected, amount, now));
    }

    public void tick() {
        ClientLevel level = CLIENT.level;
        LocalPlayer player = CLIENT.player;
        if (level == null || player == null) {
            reset();
            return;
        }
        ensureLevel(level);
        long now = level.getGameTime();
        prune(now);

        if (!isWorkActive() || !isCollectorConfigured()) {
            clearTracking();
            return;
        }
        if (isMagnetActive()) {
            requestRangePickups(level, player, now);
        }
        if (!isAutoStoreActive()) {
            this.pendingPickups.clear();
            return;
        }
        if (this.pendingPickups.isEmpty() || now < this.nextActionTick) {
            return;
        }
        if (!ModLoadUtils.isQuickShulkerLoaded()) {
            this.pendingPickups.clear();
            return;
        }
        if (CLIENT.gameMode == null
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || SwitchItem.isWaitingForRestoreContainer()
                || TakeItOutUtils.isAwaitingStack()
                || InventorySwitchGuard.isWaiting()) {
            return;
        }

        PendingPickup pickup = this.pendingPickups.peekFirst();
        if (pickup == null) {
            return;
        }
        if (now - pickup.createdTick < PICKUP_WAIT_TICKS) {
            return;
        }

        AbstractContainerMenu menu = player.inventoryMenu;
        int sourceMenuSlot = findSmallestMatchingSource(menu, player.getInventory(), pickup.expectedStack);
        if (sourceMenuSlot < 0) {
            return;
        }
        ItemStack sourceStack = menu.slots.get(sourceMenuSlot).getItem();
        if (sourceStack.isEmpty() || isShulkerBox(sourceStack)) {
            this.pendingPickups.removeFirst();
            return;
        }

        int shulkerMenuSlot = findNextShulker(
                menu,
                player.getInventory(),
                sourceMenuSlot,
                sourceStack,
                pickup.attemptedShulkerSlots
        );
        if (shulkerMenuSlot < 0) {
            // No suitable shulker: vanilla pickup remains in the normal inventory.
            this.pendingPickups.removeFirst();
            return;
        }
        pickup.attemptedShulkerSlots.add(shulkerMenuSlot);

        ItemStack movedStack = sourceStack.copy();
        int beforeCount = movedStack.getCount();
        CLIENT.gameMode.handleContainerInput(menu.containerId, sourceMenuSlot, 0, ContainerInput.PICKUP, player);
        if (!sameItem(movedStack, menu.getCarried())) {
            restoreCarried(menu, sourceMenuSlot, player);
            this.pendingPickups.removeFirst();
            this.nextActionTick = now + ACTION_COOLDOWN_TICKS;
            return;
        }

        CLIENT.gameMode.handleContainerInput(menu.containerId, shulkerMenuSlot, 1, ContainerInput.PICKUP, player);
        if (!menu.getCarried().isEmpty() && !sameItem(movedStack, menu.getCarried())) {
            // QuickShulker did not intercept the right click. Undo vanilla's
            // slot swap before it can leave a shulker or item stack on cursor.
            CLIENT.gameMode.handleContainerInput(menu.containerId, shulkerMenuSlot, 0, ContainerInput.PICKUP, player);
            restoreCarried(menu, sourceMenuSlot, player);
            this.pendingPickups.removeFirst();
            this.nextActionTick = now + ACTION_COOLDOWN_TICKS;
            return;
        }
        restoreCarried(menu, sourceMenuSlot, player);

        ItemStack afterStack = menu.slots.get(sourceMenuSlot).getItem();
        int afterCount = sameItem(movedStack, afterStack) ? afterStack.getCount() : 0;
        int moved = Math.max(0, beforeCount - afterCount);
        this.nextActionTick = now + ACTION_COOLDOWN_TICKS;
        if (moved <= 0) {
            // This shulker may be full. Try the next one on a later tick.
            return;
        }
        pickup.remaining -= moved;
        if (pickup.remaining <= 0) {
            this.pendingPickups.removeFirst();
        } else if (moved == beforeCount) {
            pickup.attemptedShulkerSlots.clear();
        }
    }

    public void reset() {
        this.observedLevel = null;
        this.nextActionTick = 0L;
        clearTracking();
    }

    private void ensureLevel(ClientLevel level) {
        if (this.observedLevel != level) {
            reset();
            this.observedLevel = level;
        }
    }

    private void clearTracking() {
        this.recentMinedBlocks.clear();
        this.trackedDrops.clear();
        this.pendingPickups.clear();
        this.directlyStoredDrops.clear();
    }

    private void prune(long now) {
        while (!this.recentMinedBlocks.isEmpty()
                && now - this.recentMinedBlocks.peekFirst().createdTick() > BREAK_TRACK_TICKS) {
            this.recentMinedBlocks.removeFirst();
        }
        Iterator<Map.Entry<Integer, TrackedDrop>> dropIterator = this.trackedDrops.entrySet().iterator();
        while (dropIterator.hasNext()) {
            Map.Entry<Integer, TrackedDrop> entry = dropIterator.next();
            if (now - entry.getValue().createdTick > ENTITY_TRACK_TICKS) {
                this.directlyStoredDrops.remove(entry.getKey());
                dropIterator.remove();
            }
        }
        while (!this.pendingPickups.isEmpty()
                && now - this.pendingPickups.peekFirst().createdTick > PICKUP_EXPIRE_TICKS) {
            this.pendingPickups.removeFirst();
        }
    }

    private boolean isNearRecentMinedBlock(ItemEntity itemEntity) {
        for (MinedBlock minedBlock : this.recentMinedBlocks) {
            BlockPos pos = minedBlock.pos();
            double dx = itemEntity.getX() - (pos.getX() + 0.5D);
            double dy = itemEntity.getY() - (pos.getY() + 0.5D);
            double dz = itemEntity.getZ() - (pos.getZ() + 0.5D);
            if (dx * dx + dy * dy + dz * dz <= DROP_MATCH_DISTANCE_SQUARED) {
                return true;
            }
        }
        return false;
    }

    private void requestRangePickups(ClientLevel clientLevel, LocalPlayer clientPlayer, long now) {
        IntegratedServer server = CLIENT.getSingleplayerServer();
        double range = Configs.Magnet.RANGE.getIntegerValue();
        double rangeSquared = range * range;
        UUID playerId = clientPlayer.getUUID();
        var dimension = clientLevel.dimension();
        boolean storeInShulkers = isAutoStoreActive();
        List<Integer> remoteEntityIds = new ArrayList<>();

        AABB pickupArea = clientPlayer.getBoundingBox().inflate(range);
        for (ItemEntity itemEntity : clientLevel.getEntitiesOfClass(ItemEntity.class, pickupArea, Entity::isAlive)) {
            if (!itemEntity.getItem().isEmpty() && allowsMagnetPickup(itemEntity)) {
                this.trackedDrops.putIfAbsent(
                        itemEntity.getId(),
                        new TrackedDrop(itemEntity.getItem().copy(), now)
                );
            }
        }

        for (Map.Entry<Integer, TrackedDrop> entry : this.trackedDrops.entrySet()) {
            TrackedDrop tracked = entry.getValue();
            if (now < tracked.nextMagnetTick) {
                continue;
            }
            Entity entity = clientLevel.getEntity(entry.getKey());
            if (!(entity instanceof ItemEntity itemEntity)
                    || !itemEntity.isAlive()
                    || clientPlayer.distanceToSqr(itemEntity) > rangeSquared
                    || !allowsMagnetPickup(itemEntity)) {
                continue;
            }

            tracked.nextMagnetTick = now + MAGNET_RETRY_TICKS;
            int entityId = entry.getKey();
            ItemStack expected = tracked.stack.copy();
            if (server != null && CLIENT.isLocalServer()) {
                server.execute(() -> collectOnLocalServer(
                        server,
                        dimension,
                        playerId,
                        entityId,
                        expected,
                        rangeSquared,
                        storeInShulkers
                ));
            } else if (remoteEntityIds.size() < RemotePickupPacket.MAX_ENTITY_IDS) {
                remoteEntityIds.add(entityId);
            }
        }
        if (server == null) {
            if (RemotePickupPacket.send(remoteEntityIds, storeInShulkers) && storeInShulkers) {
                this.directlyStoredDrops.addAll(remoteEntityIds);
            }
        }
    }

    private void collectOnLocalServer(
            IntegratedServer server,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            UUID playerId,
            int entityId,
            ItemStack expected,
            double rangeSquared,
            boolean storeInShulkers
    ) {
        if (!isWorkActive() || !Configs.Magnet.ENABLED.getBooleanValue()) {
            return;
        }
        ServerLevel level = server.getLevel(dimension);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (level == null || player == null) {
            return;
        }
        Entity entity = level.getEntity(entityId);
        if (!(entity instanceof ItemEntity itemEntity)
                || !itemEntity.isAlive()
                || itemEntity.hasPickUpDelay()
                || player.distanceToSqr(itemEntity) > rangeSquared
                || !sameItem(expected, itemEntity.getItem())) {
            return;
        }

        ItemStack dropStack = itemEntity.getItem();
        int stored = storeInShulkers ? tryStoreInQuickShulker(player, dropStack) : 0;
        if (stored > 0) {
            player.awardStat(Stats.ITEM_PICKED_UP.get(expected.getItem()), stored);
            player.onItemPickup(itemEntity);
            if (dropStack.isEmpty()) {
                this.directlyStoredDrops.add(entityId);
                player.take(itemEntity, stored);
                itemEntity.discard();
                return;
            }
        }
        itemEntity.playerTouch(player);
    }

    private static int tryStoreInQuickShulker(ServerPlayer player, ItemStack dropStack) {
        if (!ModLoadUtils.isQuickShulkerLoaded() || dropStack.isEmpty() || isShulkerBox(dropStack)) {
            return 0;
        }
        int beforeCount = dropStack.getCount();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < PLAYER_STORAGE_SIZE && !dropStack.isEmpty(); slot++) {
            ItemStack shulkerStack = inventory.getItem(slot);
            if (!isShulkerBox(shulkerStack)) {
                continue;
            }
            if (!invokeQuickShulkerInsert(player, shulkerStack, dropStack)) {
                break;
            }
        }
        return Math.max(0, beforeCount - dropStack.getCount());
    }

    private static boolean invokeQuickShulkerInsert(ServerPlayer player, ItemStack shulkerStack, ItemStack dropStack) {
        try {
            Method method = quickShulkerInsertMethod;
            if (method == null) {
                Class<?> helper = Class.forName("net.kyrptonaught.quickshulker.util.BundleHelper");
                for (Method candidate : helper.getMethods()) {
                    if (candidate.getName().equals("bundleItemIntoStack")
                            && candidate.getParameterCount() == 4
                            && candidate.getParameterTypes()[1] == ItemStack.class
                            && candidate.getParameterTypes()[2] == ItemStack.class) {
                        method = candidate;
                        quickShulkerInsertMethod = candidate;
                        break;
                    }
                }
            }
            if (method == null) {
                return false;
            }
            method.invoke(null, player, shulkerStack, dropStack, null);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return false;
        }
    }

    private static int findSmallestMatchingSource(AbstractContainerMenu menu, Inventory inventory, ItemStack expected) {
        int bestSlot = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            int inventorySlot = slot.getContainerSlot();
            ItemStack stack = slot.getItem();
            if (slot.container == inventory
                    && inventorySlot >= 0
                    && inventorySlot < PLAYER_STORAGE_SIZE
                    && sameItem(expected, stack)
                    && stack.getCount() < bestCount) {
                bestSlot = menuSlot;
                bestCount = stack.getCount();
            }
        }
        return bestSlot;
    }

    private static int findNextShulker(
            AbstractContainerMenu menu,
            Inventory inventory,
            int sourceMenuSlot,
            ItemStack sourceStack,
            Set<Integer> attemptedSlots
    ) {
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            if (menuSlot == sourceMenuSlot || attemptedSlots.contains(menuSlot)) {
                continue;
            }
            Slot slot = menu.slots.get(menuSlot);
            int inventorySlot = slot.getContainerSlot();
            ItemStack shulkerStack = slot.getItem();
            if (slot.container != inventory
                    || inventorySlot < 0
                    || inventorySlot >= PLAYER_STORAGE_SIZE
                    || !isShulkerBox(shulkerStack)
                    || !canStoreInShulker(shulkerStack, sourceStack)) {
                continue;
            }
            return menuSlot;
        }
        return -1;
    }

    private static boolean canStoreInShulker(ItemStack shulkerStack, ItemStack sourceStack) {
        ItemContainerContents contents = shulkerStack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return false;
        }
        NonNullList<ItemStack> storedItems = NonNullList.withSize(SHULKER_STORAGE_SIZE, ItemStack.EMPTY);
        contents.copyInto(storedItems);
        for (ItemStack stored : storedItems) {
            if (stored.isEmpty()
                    || sameItem(stored, sourceStack) && stored.getCount() < stored.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private static void restoreCarried(AbstractContainerMenu menu, int sourceMenuSlot, LocalPlayer player) {
        if (!menu.getCarried().isEmpty()) {
            CLIENT.gameMode.handleContainerInput(menu.containerId, sourceMenuSlot, 0, ContainerInput.PICKUP, player);
        }
    }

    private boolean allowsMagnetPickup(ItemEntity itemEntity) {
        if (usesMiningFilter() && isNearRecentMinedBlock(itemEntity)) {
            return true;
        }
        return allowsMagnetPickup(itemEntity.getItem());
    }

    private static boolean allowsMagnetPickup(ItemStack stack) {
        if (!Configs.Magnet.FILTER_ENABLED.getBooleanValue()) {
            return true;
        }
        Object configuredMode = Configs.Magnet.FILTER_MODE.getOptionListValue();
        PickupFilterMode mode = configuredMode instanceof PickupFilterMode pickupMode
                ? pickupMode
                : PickupFilterMode.ALL;
        return switch (mode) {
            case ALL -> true;
            case MINE -> allowsMiningList(stack);
            case BEDROCK -> stack.is(Items.PISTON)
                    || stack.is(Items.STICKY_PISTON)
                    || stack.is(Items.REDSTONE_TORCH)
                    || stack.is(Items.SLIME_BLOCK);
            case CUSTOM -> allowsItemList(
                    getListType(Configs.Magnet.CUSTOM_LIST_TYPE.getOptionListValue()),
                    Configs.Magnet.CUSTOM_BLACKLIST.getStrings(),
                    Configs.Magnet.CUSTOM_WHITELIST.getStrings(),
                    stack
            );
        };
    }

    private static boolean usesMiningFilter() {
        return Configs.Magnet.FILTER_ENABLED.getBooleanValue()
                && Configs.Magnet.FILTER_MODE.getOptionListValue().equals(PickupFilterMode.MINE);
    }

    private static boolean allowsMiningList(ItemStack stack) {
        if (Configs.Mine.EXCAVATE_LIMITER.getOptionListValue().equals(ExcavateListMode.TWEAKEROO)
                && ModLoadUtils.isTweakerooLoaded()) {
            return allowsItemList(
                    BLOCK_TYPE_BREAK_RESTRICTION.getListType(),
                    BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST.getStrings(),
                    BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST.getStrings(),
                    stack
            );
        }
        return allowsItemList(
                getListType(Configs.Mine.EXCAVATE_LIMIT.getOptionListValue()),
                Configs.Mine.EXCAVATE_BLACKLIST.getStrings(),
                Configs.Mine.EXCAVATE_WHITELIST.getStrings(),
                stack
        );
    }

    private static boolean allowsItemList(
            UsageRestriction.ListType listType,
            List<String> blacklist,
            List<String> whitelist,
            ItemStack stack
    ) {
        List<String> filters = listType == UsageRestriction.ListType.BLACKLIST ? blacklist : whitelist;
        boolean matches = filters.stream().anyMatch(filter -> FilterUtils.matchItemName(filter, stack));
        return listType != UsageRestriction.ListType.BLACKLIST
                ? listType != UsageRestriction.ListType.WHITELIST || matches
                : !matches;
    }

    private static UsageRestriction.ListType getListType(Object value) {
        return value instanceof UsageRestriction.ListType type ? type : UsageRestriction.ListType.NONE;
    }

    private static boolean isWorkActive() {
        return ConfigUtils.isEnable();
    }

    private static boolean isMagnetActive() {
        return isWorkActive() && Configs.Magnet.ENABLED.getBooleanValue();
    }

    private static boolean isAutoStoreActive() {
        return isWorkActive() && Configs.Mine.AUTO_STORE_MINING_DROPS.getBooleanValue();
    }

    private static boolean isCollectorConfigured() {
        return Configs.Magnet.ENABLED.getBooleanValue()
                || Configs.Mine.AUTO_STORE_MINING_DROPS.getBooleanValue();
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && stack.getCount() == 1
                && Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock;
    }

    private static boolean sameItem(ItemStack first, ItemStack second) {
        return first != null
                && second != null
                && !first.isEmpty()
                && !second.isEmpty()
                && ItemStack.isSameItemSameComponents(first, second);
    }

    private record MinedBlock(BlockPos pos, long createdTick) {
    }

    private static final class TrackedDrop {
        private final ItemStack stack;
        private final long createdTick;
        private long nextMagnetTick;

        private TrackedDrop(ItemStack stack, long createdTick) {
            this.stack = stack;
            this.createdTick = createdTick;
        }
    }

    private static final class PendingPickup {
        private final ItemStack expectedStack;
        private final Set<Integer> attemptedShulkerSlots = new HashSet<>();
        private final long createdTick;
        private int remaining;

        private PendingPickup(ItemStack expectedStack, int remaining, long createdTick) {
            this.expectedStack = expectedStack;
            this.remaining = remaining;
            this.createdTick = createdTick;
        }
    }
}
