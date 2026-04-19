package com.cells.blocks.iointerface;

import javax.annotation.Nonnull;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.cells.blocks.interfacebase.AbstractResourceInterfaceBlock;
import com.cells.gui.CellsGuiHandler;


/**
 * Block for the Fluid I/O Interface.
 * Combines fluid import and export in a single block with tab-based direction switching.
 */
public class BlockFluidIOInterface extends AbstractResourceInterfaceBlock<TileFluidIOInterface> {

    public BlockFluidIOInterface() {
        super(
            "io_fluid_interface",
            "io_interface.fluid",
            TileFluidIOInterface.class,
            CellsGuiHandler.GUI_FLUID_IO_INTERFACE
        );
    }

    @Override
    public void neighborChanged(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos,
                                @Nonnull Block blockIn, @Nonnull BlockPos fromPos) {
        if (world.isRemote) return;

        final TileFluidIOInterface tile = this.getTileEntity(world, pos);
        if (tile == null) return;

        tile.onNeighborChanged(fromPos);
    }
}
