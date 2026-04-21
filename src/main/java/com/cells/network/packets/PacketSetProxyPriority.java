package com.cells.network.packets;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.cells.gui.subnetproxy.ContainerSubnetProxy;


/**
 * Packet to set the priority value for a Subnet Proxy from the GUI.
 * Sent client→server when the player adjusts the priority via +/- buttons.
 */
public class PacketSetProxyPriority implements IMessage {

    private int priority;

    public PacketSetProxyPriority() {
    }

    public PacketSetProxyPriority(int priority) {
        this.priority = priority;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.priority = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.priority);
    }

    public static class Handler implements IMessageHandler<PacketSetProxyPriority, IMessage> {

        @Override
        public IMessage onMessage(PacketSetProxyPriority message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                Container container = player.openContainer;

                if (container instanceof ContainerSubnetProxy) {
                    ((ContainerSubnetProxy) container).setPriority(message.priority);
                }
            });

            return null;
        }
    }
}
