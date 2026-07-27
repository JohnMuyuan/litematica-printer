package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.drop.MiningDropCollector;
import me.aleksilassila.litematica.printer.handler.scan.DirtyRegionTracker;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    /*** 鐜╁姝讳骸鍚庤嚜鍔ㄥ叧闂墦鍗版満(閬垮厤鎸佺画鎵ц鎵撳嵃鍙戦€佹暟鎹寘) ***/
    @Inject(method = "handleSetHealth", at = @At("RETURN"))
    private void injectHealthUpdate(ClientboundSetHealthPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (packet.getHealth() == 0 && Configs.Core.AUTO_DISABLE_PRINTER.getBooleanValue() && Configs.Core.WORK_SWITCH.getBooleanValue()) {
            MessageUtils.setOverlayMessage(I18n.AUTO_DISABLE_NOTICE.getName());
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
    private void invalidateScanCacheBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        ScanCache.INSTANCE.invalidate(packet.getPos());
        DirtyRegionTracker.INSTANCE.markDirty(packet.getPos());
        InteractionUtils.INSTANCE.confirmServerBlockUpdate(packet.getPos());
        HudStatsManager.INSTANCE.confirmBlockUpdate(packet.getPos());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
    private void invalidateScanCacheSection(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates((pos, state) -> {
            ScanCache.INSTANCE.invalidate(pos);
            DirtyRegionTracker.INSTANCE.markDirty(pos);
            InteractionUtils.INSTANCE.confirmServerBlockUpdate(pos);
            HudStatsManager.INSTANCE.confirmBlockUpdate(pos);
        });
    }

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void collectPrinterMinedDrop(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && packet.getPlayerId() == player.getId()) {
            MiningDropCollector.INSTANCE.recordPickup(packet.getItemId(), packet.getAmount());
        }
    }
}
