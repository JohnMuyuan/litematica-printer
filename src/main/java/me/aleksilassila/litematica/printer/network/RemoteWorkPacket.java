package me.aleksilassila.litematica.printer.network;

import fi.dy.masa.malilib.config.IConfigBase;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record RemoteWorkPacket(List<WorkAction> actions) implements CustomPacketPayload {
    public static final int MAX_ACTIONS = 64;
    public static final String MISSING_SERVER_WARNING = "\u672a\u68c0\u6d4b\u5230\u670d\u52a1\u7aef\uff0c\u53ef\u80fd\u4e0d\u53ef\u7528";
    public static final Type<RemoteWorkPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("litematica-printer", "work_actions")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteWorkPacket> CODEC = StreamCodec.ofMember(
            RemoteWorkPacket::encode,
            RemoteWorkPacket::decode
    );
    private static final List<WorkAction> PENDING = new ArrayList<>(MAX_ACTIONS);
    private static final ThreadLocal<Integer> DIRECT_SEND_DEPTH = ThreadLocal.withInitial(() -> 0);

    public RemoteWorkPacket {
        actions = List.copyOf(actions);
        if (actions.size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("Too many printer work actions");
        }
    }

    public static void registerClient() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
    }

    public static boolean isAvailable() {
        try {
            return ClientPlayNetworking.canSend(TYPE);
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    public static boolean isMissingOnRemoteServer() {
        Minecraft client = Minecraft.getInstance();
        return client.getConnection() != null
                && client.getSingleplayerServer() == null
                && !isAvailable();
    }

    public static boolean requiresServer(IConfigBase config) {
        return config == Configs.Core.WORK_MODE_TYPE
                || config == Configs.Core.PRINT
                || config == Configs.Core.MINE
                || config == Configs.Core.FILL
                || config == Configs.Core.FLUID
                || config == Configs.Hotkeys.ACTIVATE_CLEAR_MODE
                || config == Configs.Hotkeys.BEDROCK
                || config == Configs.Magnet.ENABLED
                || config == Configs.Mine.AUTO_STORE_MINING_DROPS;
    }

    public static boolean queue(Packet<?> packet) {
        if (DIRECT_SEND_DEPTH.get() > 0
                || !isAvailable()
                || !(packet instanceof ServerboundPlayerActionPacket)
                && !(packet instanceof ServerboundUseItemOnPacket)) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        PENDING.add(new WorkAction(
                InventoryUtils.getSelectedSlot(player.getInventory()),
                player.isShiftKeyDown(),
                player.getYRot(),
                player.getXRot(),
                packet
        ));
        if (PENDING.size() >= MAX_ACTIONS) {
            flush();
        }
        return true;
    }

    public static <T> T runWithoutBatching(Supplier<T> action) {
        DIRECT_SEND_DEPTH.set(DIRECT_SEND_DEPTH.get() + 1);
        try {
            return action.get();
        } finally {
            int depth = DIRECT_SEND_DEPTH.get() - 1;
            if (depth == 0) {
                DIRECT_SEND_DEPTH.remove();
            } else {
                DIRECT_SEND_DEPTH.set(depth);
            }
        }
    }

    public static void flush() {
        if (PENDING.isEmpty()) {
            return;
        }
        if (!isAvailable()) {
            PENDING.clear();
            return;
        }
        List<WorkAction> actions = List.copyOf(PENDING);
        PENDING.clear();
        ClientPlayNetworking.send(new RemoteWorkPacket(actions));
    }

    public static void reset() {
        PENDING.clear();
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(this.actions.size());
        for (WorkAction action : this.actions) {
            action.encode(buffer);
        }
    }

    private static RemoteWorkPacket decode(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ACTIONS) {
            throw new IllegalArgumentException("Invalid printer work action count: " + size);
        }
        List<WorkAction> actions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            actions.add(WorkAction.decode(buffer));
        }
        return new RemoteWorkPacket(actions);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record WorkAction(
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
}
