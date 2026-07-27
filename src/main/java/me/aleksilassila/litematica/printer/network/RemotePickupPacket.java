package me.aleksilassila.litematica.printer.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record RemotePickupPacket(List<Integer> entityIds, boolean storeInShulkers) implements CustomPacketPayload {
    public static final int MAX_ENTITY_IDS = 64;
    public static final Type<RemotePickupPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("litematica-printer", "pickup_items")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, RemotePickupPacket> CODEC = StreamCodec.ofMember(
            RemotePickupPacket::encode,
            RemotePickupPacket::decode
    );

    public RemotePickupPacket {
        entityIds = List.copyOf(entityIds);
        if (entityIds.size() > MAX_ENTITY_IDS) {
            throw new IllegalArgumentException("Too many item entities in pickup packet");
        }
    }

    public static void registerClient() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
    }

    public static boolean send(List<Integer> entityIds, boolean storeInShulkers) {
        if (!entityIds.isEmpty() && ClientPlayNetworking.canSend(TYPE)) {
            ClientPlayNetworking.send(new RemotePickupPacket(entityIds, storeInShulkers));
            return true;
        }
        return false;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(this.entityIds.size());
        for (int entityId : this.entityIds) {
            buffer.writeVarInt(entityId);
        }
        buffer.writeBoolean(this.storeInShulkers);
    }

    private static RemotePickupPacket decode(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTITY_IDS) {
            throw new IllegalArgumentException("Invalid item entity count: " + size);
        }
        List<Integer> entityIds = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entityIds.add(buffer.readVarInt());
        }
        return new RemotePickupPacket(entityIds, buffer.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
