package com.cells.blocks.combinedinterface;

import javax.annotation.Nonnull;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import com.cells.blocks.interfacebase.AbstractResourceInterfaceBlock;
import com.cells.blocks.interfacebase.AbstractResourceInterfaceLogic;
import com.cells.blocks.interfacebase.IInterfaceLogic;
import com.cells.gui.CellsGuiHandler;


/**
 * Block for the Combined Export Interface.
 * Wraps all resource types (item, fluid, gas, essentia) in a single block with tab-based GUI.
 */
public class BlockCombinedExportInterface extends AbstractResourceInterfaceBlock<TileCombinedExportInterface> {

    public BlockCombinedExportInterface() {
        super(
            "export_combined_interface",
            "export_interface.combined",
            TileCombinedExportInterface.class,
            CellsGuiHandler.GUI_COMBINED_EXPORT_INTERFACE
        );
    }

    @Override
    public void neighborChanged(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos,
                                @Nonnull Block blockIn, @Nonnull BlockPos fromPos) {
        if (world.isRemote) return;

        final TileCombinedExportInterface tile = this.getTileEntity(world, pos);
        if (tile == null) return;

        for (IInterfaceLogic logic : tile.getAllLogics()) {
            if (logic instanceof AbstractResourceInterfaceLogic) {
                ((AbstractResourceInterfaceLogic<?, ?, ?>) logic)
                    .onNeighborChanged(fromPos);
            }
        }
    }
}
