package com.cells.integration.mekanismenergistics;

import com.cells.blocks.interfacebase.AbstractResourceInterfaceBlock;


/**
 * Block for the Gas I/O Interface.
 * Combines gas import and export in a single block with tab-based direction switching.
 */
public class BlockGasIOInterface extends AbstractResourceInterfaceBlock<TileGasIOInterface> {

    public BlockGasIOInterface() {
        super(
            "io_gas_interface",
            "io_interface.gas",
            TileGasIOInterface.class,
            GasInterfaceGuiHandler.GUI_GAS_IO_INTERFACE
        );
    }
}
