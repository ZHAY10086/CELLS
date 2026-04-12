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
 * Block for the Combined Import Interface.
 * Wraps all resource types (item, fluid, gas, essentia) in a single block with tab-based GUI.
 */
public class BlockCombinedImportInterface extends AbstractResourceInterfaceBlock<TileCombinedImportInterface> {

    public BlockCombinedImportInterface() {
        super(
            "import_combined_interface",
            "import_interface.combined",
            TileCombinedImportInterface.class,
            CellsGuiHandler.GUI_COMBINED_IMPORT_INTERFACE
        );
    }

    @Override
    public void neighborChanged(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos,
                                @Nonnull Block blockIn, @Nonnull BlockPos fromPos) {
        if (world.isRemote) return;

        final TileCombinedImportInterface tile = this.getTileEntity(world, pos);
        if (tile == null) return;

        // Forward neighbor change to all logics for auto-pull/push card cache invalidation
        for (IInterfaceLogic logic : tile.getAllLogics()) {
            if (logic instanceof AbstractResourceInterfaceLogic) {
                ((AbstractResourceInterfaceLogic<?, ?, ?>) logic)
                    .onNeighborChanged(fromPos);
            }
        }
    }
}
