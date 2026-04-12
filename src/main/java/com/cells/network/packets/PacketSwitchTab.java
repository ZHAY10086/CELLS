package com.cells.network.packets;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.cells.blocks.combinedinterface.ContainerCombinedInterface;
import com.cells.network.sync.ResourceType;


/**
 * Packet to switch the active tab in a Combined Interface GUI.
 * The tab ordinal corresponds to a {@link ResourceType} enum value.
 */
public class PacketSwitchTab implements IMessage {

    private int tabOrdinal;

    public PacketSwitchTab() {
    }

    public PacketSwitchTab(ResourceType tab) {
        this.tabOrdinal = tab.ordinal();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.tabOrdinal = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.tabOrdinal);
    }

    public static class Handler implements IMessageHandler<PacketSwitchTab, IMessage> {

        @Override
        public IMessage onMessage(PacketSwitchTab message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                Container container = player.openContainer;

                if (container instanceof ContainerCombinedInterface) {
                    ResourceType[] types = ResourceType.values();
                    if (message.tabOrdinal >= 0 && message.tabOrdinal < types.length) {
                        ((ContainerCombinedInterface) container).switchTab(types[message.tabOrdinal]);
                    }
                }
            });

            return null;
        }
    }
}
