package me.aleksilassila.litematica.printer;

import fi.dy.masa.malilib.event.InitializationHandler;
import me.aleksilassila.litematica.printer.network.RemotePickupPacket;
import me.aleksilassila.litematica.printer.network.RemoteWorkPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;

public class LitematicaPrinterMod implements ModInitializer, ClientModInitializer {
    @Override
    public void onInitialize() {
    }

    @Override
    public void onInitializeClient() {
        RemotePickupPacket.registerClient();
        RemoteWorkPacket.registerClient();
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }
}
