package com.cells.network.packets;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Container;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.cells.blocks.interfacebase.ISizeOverrideContainer;


/**
 * Server → Client packet to sync a per-slot size override to the client container.
 * Sent in detectAndSendChanges when an override changes.
 * <p>
 * A size of -1 indicates the override was cleared (slot reverts to global maxSlotSize).
 */
public class PacketSyncSlotSizeOverride implements IMessage {

    private int slot;
    private long size;

    public PacketSyncSlotSizeOverride() {
    }

    /**
     * @param slot The slot index
     * @param size The override size, or -1 to clear
     */
    public PacketSyncSlotSizeOverride(int slot, long size) {
        this.slot = slot;
        this.size = size;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.slot = buf.readInt();
        this.size = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.slot);
        buf.writeLong(this.size);
    }

    public static class ClientHandler implements IMessageHandler<PacketSyncSlotSizeOverride, IMessage> {

        @Override
        @SideOnly(Side.CLIENT)
        public IMessage onMessage(PacketSyncSlotSizeOverride message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                Container container = Minecraft.getMinecraft().player.openContainer;

                if (container instanceof ISizeOverrideContainer) {
                    ((ISizeOverrideContainer) container).receiveMaxSlotSizeOverridesync(message.slot, message.size);
                }
            });

            return null;
        }
    }
}
