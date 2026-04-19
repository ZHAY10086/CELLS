package com.cells.network.packets;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.cells.gui.subnetproxy.ContainerSubnetProxy;


/**
 * Packet to cycle the filter mode in the Subnet Proxy GUI.
 * Sent from client when the type cycling button is clicked.
 * The server cycles to the next available ResourceType.
 */
public class PacketChangeFilterMode implements IMessage {

    public PacketChangeFilterMode() {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // No data needed, server just cycles to the next mode
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // No data needed
    }

    public static class Handler implements IMessageHandler<PacketChangeFilterMode, IMessage> {

        @Override
        public IMessage onMessage(PacketChangeFilterMode message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                Container container = player.openContainer;
                if (container instanceof ContainerSubnetProxy) {
                    ((ContainerSubnetProxy) container).getPart().cycleFilterMode();
                }
            });

            return null;
        }
    }
}
