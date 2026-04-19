package com.cells.network.packets;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.cells.gui.subnetproxy.ContainerSubnetProxy;


/**
 * Packet sent by the JEI recipe transfer handler to add multiple
 * filter ItemStacks to the Subnet Proxy config inventory in one batch.
 * <p>
 * All error handling (duplicates, full inventory) is done silently
 * on the server side via {@link ContainerSubnetProxy#addFilterStacksSilently}.
 */
public class PacketJEISubnetProxyFilter implements IMessage {

    private List<ItemStack> filterStacks;

    public PacketJEISubnetProxyFilter() {
        this.filterStacks = new ArrayList<>();
    }

    public PacketJEISubnetProxyFilter(List<ItemStack> filterStacks) {
        this.filterStacks = filterStacks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = buf.readByte() & 0xFF;
        this.filterStacks = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            this.filterStacks.add(ByteBufUtils.readItemStack(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // Cap at 255 items (more than enough for any recipe)
        int count = Math.min(this.filterStacks.size(), 255);
        buf.writeByte(count);

        for (int i = 0; i < count; i++) {
            ByteBufUtils.writeItemStack(buf, this.filterStacks.get(i));
        }
    }

    public static class Handler implements IMessageHandler<PacketJEISubnetProxyFilter, IMessage> {

        @Override
        public IMessage onMessage(PacketJEISubnetProxyFilter message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                Container container = player.openContainer;

                if (container instanceof ContainerSubnetProxy) {
                    ((ContainerSubnetProxy) container).addFilterStacksSilently(message.filterStacks);
                }
            });

            return null;
        }
    }
}
