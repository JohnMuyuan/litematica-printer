package me.aleksilassila.litematica.printer.server;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PrinterServerMod implements ModInitializer {
    private static final int MAX_ENTITY_IDS = 64;
    private static final int PLAYER_STORAGE_SIZE = 36;
    private static final double MAX_PICKUP_DISTANCE_SQUARED = 32.0D * 32.0D;
    private static final CustomPacketPayload.Type<PickupRequest> TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath("litematica-printer", "pickup_items")
    );
    private static final StreamCodec<RegistryFriendlyByteBuf, PickupRequest> CODEC = StreamCodec.ofMember(
            PickupRequest::encode,
            PickupRequest::decode
    );
    private static final int MAX_WORK_ACTIONS = 64;
    private static final int MAX_WORK_ACTIONS_PER_TICK = 1024;
    private static final CustomPacketPayload.Type<WorkRequest> WORK_TYPE = new CustomPacketPayload.Type<>(
            Identifier.fromNamespaceAndPath("litematica-printer", "work_actions")
    );
    private static final StreamCodec<RegistryFriendlyByteBuf, WorkRequest> WORK_CODEC = StreamCodec.ofMember(
            WorkRequest::encode,
            WorkRequest::decode
    );
    private static final Map<UUID, WorkBudget> WORK_BUDGETS = new HashMap<>();

    private static Method quickShulkerInsertMethod;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WORK_TYPE, WORK_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
                context.server().execute(() -> handlePickupRequest(context.player(), payload))
        );
        ServerPlayNetworking.registerGlobalReceiver(WORK_TYPE, (payload, context) ->
                context.server().execute(() -> handleWorkRequest(context.player(), payload))
        );
    }

    private static void handleWorkRequest(ServerPlayer player, WorkRequest request) {
        int allowed = reserveWorkBudget(player, request.actions().size());
        if (allowed <= 0) {
            return;
        }
        int previousSlot = player.getInventory().getSelectedSlot();
        boolean previousShift = player.isShiftKeyDown();
        float previousYaw = player.getYRot();
        float previousPitch = player.getXRot();
        float previousHeadYaw = player.getYHeadRot();
        try {
            for (int index = 0; index < allowed; index++) {
                WorkAction action = request.actions().get(index);
                if (action.selectedSlot() < 0 || action.selectedSlot() >= 9
                        || !Float.isFinite(action.yaw())
                        || !Float.isFinite(action.pitch())) {
                    continue;
                }
                player.getInventory().setSelectedSlot(action.selectedSlot());
                player.setShiftKeyDown(action.shiftKeyDown());
                float actionYaw = Mth.wrapDegrees(action.yaw());
                player.setYRot(actionYaw);
                player.setXRot(Mth.clamp(action.pitch(), -90.0F, 90.0F));
                player.setYHeadRot(actionYaw);
                if (action.packet() instanceof ServerboundPlayerActionPacket actionPacket) {
                    ServerboundPlayerActionPacket.Action packetAction = actionPacket.getAction();
                    if (packetAction == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                            || packetAction == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                            || packetAction == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {
                        player.connection.handlePlayerAction(actionPacket);
                    }
                } else if (action.packet() instanceof ServerboundUseItemOnPacket usePacket) {
                    player.connection.handleUseItemOn(usePacket);
                }
            }
        } finally {
            player.getInventory().setSelectedSlot(previousSlot);
            player.setShiftKeyDown(previousShift);
            player.setYRot(previousYaw);
            player.setXRot(previousPitch);
            player.setYHeadRot(previousHeadYaw);
        }
    }

    private static int reserveWorkBudget(ServerPlayer player, int requested) {
        long tick = player.level().getGameTime();
        WorkBudget budget = WORK_BUDGETS.computeIfAbsent(player.getUUID(), ignored -> new WorkBudget());
        if (budget.tick != tick) {
            budget.tick = tick;
            budget.used = 0;
        }
        int allowed = Math.min(Math.max(requested, 0), MAX_WORK_ACTIONS_PER_TICK - budget.used);
        budget.used += allowed;
        return allowed;
    }

    private static void handlePickupRequest(ServerPlayer player, PickupRequest request) {
        if (request.entityIds().isEmpty() || request.entityIds().size() > MAX_ENTITY_IDS) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        Set<Integer> handledIds = new HashSet<>();
        for (int entityId : request.entityIds()) {
            if (!handledIds.add(entityId)) {
                continue;
            }
            Entity entity = level.getEntity(entityId);
            if (!(entity instanceof ItemEntity itemEntity)
                    || !itemEntity.isAlive()
                    || itemEntity.hasPickUpDelay()
                    || player.distanceToSqr(itemEntity) > MAX_PICKUP_DISTANCE_SQUARED) {
                continue;
            }
            Entity owner = itemEntity.getOwner();
            if (owner != null && !owner.getUUID().equals(player.getUUID())) {
                continue;
            }
            collectItem(player, itemEntity, request.storeInShulkers());
        }
    }

    private static void collectItem(ServerPlayer player, ItemEntity itemEntity, boolean storeInShulkers) {
        ItemStack dropStack = itemEntity.getItem();
        ItemStack expected = dropStack.copy();
        int stored = storeInShulkers ? tryStoreInQuickShulker(player, dropStack) : 0;
        if (stored > 0) {
            player.awardStat(Stats.ITEM_PICKED_UP.get(expected.getItem()), stored);
            player.onItemPickup(itemEntity);
            if (dropStack.isEmpty()) {
                player.take(itemEntity, stored);
                itemEntity.discard();
                return;
            }
        }
        itemEntity.playerTouch(player);
    }

    private static int tryStoreInQuickShulker(ServerPlayer player, ItemStack dropStack) {
        if (!FabricLoader.getInstance().isModLoaded("quickshulker")
                || dropStack.isEmpty()
                || isShulkerBox(dropStack)) {
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

    private static boolean invokeQuickShulkerInsert(
            ServerPlayer player,
            ItemStack shulkerStack,
            ItemStack dropStack
    ) {
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

    private static boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getCount() == 1
                && Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock;
    }

    private record PickupRequest(List<Integer> entityIds, boolean storeInShulkers)
            implements CustomPacketPayload {
        private PickupRequest {
            entityIds = List.copyOf(entityIds);
            if (entityIds.size() > MAX_ENTITY_IDS) {
                throw new IllegalArgumentException("Too many item entities in pickup packet");
            }
        }

        private void encode(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(this.entityIds.size());
            for (int entityId : this.entityIds) {
                buffer.writeVarInt(entityId);
            }
            buffer.writeBoolean(this.storeInShulkers);
        }

        private static PickupRequest decode(RegistryFriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_ENTITY_IDS) {
                throw new IllegalArgumentException("Invalid item entity count: " + size);
            }
            List<Integer> entityIds = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                entityIds.add(buffer.readVarInt());
            }
            return new PickupRequest(entityIds, buffer.readBoolean());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private record WorkRequest(List<WorkAction> actions) implements CustomPacketPayload {
        private WorkRequest {
            actions = List.copyOf(actions);
            if (actions.size() > MAX_WORK_ACTIONS) {
                throw new IllegalArgumentException("Too many printer work actions");
            }
        }

        private void encode(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(this.actions.size());
            for (WorkAction action : this.actions) {
                action.encode(buffer);
            }
        }

        private static WorkRequest decode(RegistryFriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_WORK_ACTIONS) {
                throw new IllegalArgumentException("Invalid printer work action count: " + size);
            }
            List<WorkAction> actions = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                actions.add(WorkAction.decode(buffer));
            }
            return new WorkRequest(actions);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return WORK_TYPE;
        }
    }

    private record WorkAction(
            int selectedSlot,
            boolean shiftKeyDown,
            float yaw,
            float pitch,
            Packet<?> packet
    ) {
        private static final int PLAYER_ACTION = 0;
        private static final int USE_ITEM_ON = 1;

        private void encode(RegistryFriendlyByteBuf buffer) {
            buffer.writeByte(this.packet instanceof ServerboundPlayerActionPacket ? PLAYER_ACTION : USE_ITEM_ON);
            buffer.writeByte(this.selectedSlot);
            buffer.writeBoolean(this.shiftKeyDown);
            buffer.writeFloat(this.yaw);
            buffer.writeFloat(this.pitch);
            if (this.packet instanceof ServerboundPlayerActionPacket actionPacket) {
                ServerboundPlayerActionPacket.STREAM_CODEC.encode(buffer, actionPacket);
            } else if (this.packet instanceof ServerboundUseItemOnPacket usePacket) {
                ServerboundUseItemOnPacket.STREAM_CODEC.encode(buffer, usePacket);
            } else {
                throw new IllegalArgumentException("Unsupported printer work packet");
            }
        }

        private static WorkAction decode(RegistryFriendlyByteBuf buffer) {
            int type = buffer.readUnsignedByte();
            int selectedSlot = buffer.readUnsignedByte();
            boolean shiftKeyDown = buffer.readBoolean();
            float yaw = buffer.readFloat();
            float pitch = buffer.readFloat();
            Packet<?> packet = switch (type) {
                case PLAYER_ACTION -> ServerboundPlayerActionPacket.STREAM_CODEC.decode(buffer);
                case USE_ITEM_ON -> ServerboundUseItemOnPacket.STREAM_CODEC.decode(buffer);
                default -> throw new IllegalArgumentException("Unknown printer work action type: " + type);
            };
            return new WorkAction(selectedSlot, shiftKeyDown, yaw, pitch, packet);
        }
    }

    private static final class WorkBudget {
        private long tick = Long.MIN_VALUE;
        private int used;
    }
}
