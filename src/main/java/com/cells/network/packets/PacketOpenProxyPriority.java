package com.cells.network.packets;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import appeng.api.util.AEPartLocation;
import appeng.core.sync.GuiBridge;
import appeng.util.Platform;

import com.cells.gui.subnetproxy.ContainerSubnetProxy;
import com.cells.parts.subnetproxy.PartSubnetProxyFront;


/**
 * Packet to switch the Subnet Proxy's GUI from the filter configuration GUI
 * to AE2's built-in priority GUI.
 * <p>
 * Sent client→server when the player clicks the priority tab button. The
 * handler resolves the currently-open proxy part (via the player's container)
 * and opens {@link GuiBridge#GUI_PRIORITY} for it. Because
 * {@link PartSubnetProxyFront#getGuiBridge()} returns {@code null}, the
 * priority GUI will not render a "back" button; the player reopens the filter
 * GUI by right-clicking the part again.
 * <p>
 * Carries no payload: the container reference on the server is sufficient to
 * resolve the target.
 */
public class PacketOpenProxyPriority implements IMessage {

    public PacketOpenProxyPriority() {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // No payload
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // No payload
    }

    public static class Handler implements IMessageHandler<PacketOpenProxyPriority, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenProxyPriority message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                Container container = player.openContainer;
                if (!(container instanceof ContainerSubnetProxy)) return;

                PartSubnetProxyFront part = ((ContainerSubnetProxy) container).getPart();
                if (part == null) return;

                TileEntity tile = part.getHost() != null ? part.getHost().getTile() : null;
                if (tile == null) return;

                // TODO: Add our own priority GUI sometime in the future, so we can display the "back" button
                AEPartLocation side = part.getSide();
                Platform.openGUI(player, tile, side, GuiBridge.GUI_PRIORITY);
            });

            return null;
        }
    }
}
