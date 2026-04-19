package com.cells.network.packets;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import com.cells.blocks.interfacebase.AbstractContainerInterface;
import com.cells.blocks.combinedinterface.ContainerCombinedInterface;
import com.cells.gui.subnetproxy.ContainerSubnetProxy;


/**
 * Packet to change the current page in a paginated Import/Export Interface GUI.
 * The page number is sent as an integer, and is clamped on the server side.
 */
public class PacketChangePage implements IMessage {

    private int page;

    public PacketChangePage() {
    }

    public PacketChangePage(int page) {
        this.page = page;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.page = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.page);
    }

    public static class Handler implements IMessageHandler<PacketChangePage, IMessage> {

        @Override
        public IMessage onMessage(PacketChangePage message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                Container container = player.openContainer;

                // AbstractContainerInterface covers all single-type interfaces
                // (item, fluid, gas, essentia). ContainerCombinedInterface is
                // separate because it extends AEBaseContainer directly.
                if (container instanceof AbstractContainerInterface) {
                    ((AbstractContainerInterface<?, ?, ?>) container).setCurrentPage(message.page);
                } else if (container instanceof ContainerCombinedInterface) {
                    ((ContainerCombinedInterface) container).setCurrentPage(message.page);
                } else if (container instanceof ContainerSubnetProxy) {
                    ((ContainerSubnetProxy) container).setCurrentPage(message.page);
                }
            });

            return null;
        }
    }
}
