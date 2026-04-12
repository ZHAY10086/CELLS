package com.cells.network.packets;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.cells.blocks.interfacebase.ContainerMaxSlotSize;


/**
 * Client → Server packet to set a per-slot size override for a specific filter slot.
 * Sent from the Max Slot Size GUI when operating in per-slot override mode.
 * <p>
 * A size of -1 indicates the override should be cleared (revert to global maxSlotSize).
 */
public class PacketsetMaxSlotSizeOverride implements IMessage {

    private int slot;
    private long size;

    public PacketsetMaxSlotSizeOverride() {
    }

    public PacketsetMaxSlotSizeOverride(int slot, long size) {
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

    public static class Handler implements IMessageHandler<PacketsetMaxSlotSizeOverride, IMessage> {

        @Override
        public IMessage onMessage(PacketsetMaxSlotSizeOverride message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                Container container = player.openContainer;

                if (container instanceof ContainerMaxSlotSize && ((ContainerMaxSlotSize) container).isOverrideMode()) {
                    ((ContainerMaxSlotSize) container).setMaxSlotSize(message.size);
                }
            });

            return null;
        }
    }
}
