package com.cells.blocks.iointerface;

import javax.annotation.Nonnull;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.cells.blocks.interfacebase.AbstractResourceInterfaceBlock;
import com.cells.gui.CellsGuiHandler;


/**
 * Block for the Item I/O Interface.
 * Combines item import and export in a single block with tab-based direction switching.
 */
public class BlockItemIOInterface extends AbstractResourceInterfaceBlock<TileItemIOInterface> {

    public BlockItemIOInterface() {
        super(
            "io_item_interface",
            "io_interface",
            TileItemIOInterface.class,
            CellsGuiHandler.GUI_ITEM_IO_INTERFACE
        );
    }

    @Override
    public void neighborChanged(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos,
                                @Nonnull Block blockIn, @Nonnull BlockPos fromPos) {
        if (world.isRemote) return;

        final TileItemIOInterface tile = this.getTileEntity(world, pos);
        if (tile == null) return;

        tile.onNeighborChanged(fromPos);
    }
}
