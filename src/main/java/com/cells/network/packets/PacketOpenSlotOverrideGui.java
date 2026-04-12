package com.cells.network.packets;

import javax.annotation.Nullable;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import appeng.api.util.AEPartLocation;

import com.cells.Cells;
import com.cells.gui.CellsGuiHandler;
import com.cells.gui.GuiIdUtils;


/**
 * Client → Server packet to open the per-slot size override GUI.
 * <p>
 * Instead of encoding the slot index in the GUI ID (which doesn't scale to 180 slots),
 * this packet stores the slot index in a per-player pending map on {@link CellsGuiHandler},
 * then opens the regular Max Slot Size GUI. The {@link com.cells.blocks.interfacebase.ContainerMaxSlotSize}
 * reads and consumes the pending slot to enter per-slot mode.
 */
public class PacketOpenSlotOverrideGui implements IMessage {

    private int x, y, z;
    private int slotIndex;
    private boolean isPart;
    private int side; // AEPartLocation ordinal, only used if isPart

    public PacketOpenSlotOverrideGui() {
    }

    /**
     * @param pos       Block position of the host
     * @param slotIndex Absolute slot index (0-179)
     * @param partSide  Part side (null for block-based hosts)
     */
    public PacketOpenSlotOverrideGui(BlockPos pos, int slotIndex, @Nullable AEPartLocation partSide) {
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
        this.slotIndex = slotIndex;
        this.isPart = partSide != null;
        this.side = partSide != null ? partSide.ordinal() : 0;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.x = buf.readInt();
        this.y = buf.readInt();
        this.z = buf.readInt();
        this.slotIndex = buf.readInt();
        this.isPart = buf.readBoolean();
        this.side = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.x);
        buf.writeInt(this.y);
        buf.writeInt(this.z);
        buf.writeInt(this.slotIndex);
        buf.writeBoolean(this.isPart);
        buf.writeByte(this.side);
    }

    public static class Handler implements IMessageHandler<PacketOpenSlotOverrideGui, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenSlotOverrideGui message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                // Store the slot index in the per-player pending map
                CellsGuiHandler.setPendingOverrideSlot(player, message.slotIndex);

                // Open the regular Max Slot Size GUI, the container will read the pending slot
                BlockPos pos = new BlockPos(message.x, message.y, message.z);

                if (message.isPart) {
                    AEPartLocation side = AEPartLocation.fromOrdinal(message.side);
                    GuiIdUtils.openPartGui(player, player.world.getTileEntity(pos), side,
                        CellsGuiHandler.GUI_PART_MAX_SLOT_SIZE);
                } else {
                    player.openGui(Cells.instance, CellsGuiHandler.GUI_MAX_SLOT_SIZE,
                        player.world, pos.getX(), pos.getY(), pos.getZ());
                }
            });

            return null;
        }
    }
}
