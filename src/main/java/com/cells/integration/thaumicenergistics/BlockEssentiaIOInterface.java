package com.cells.integration.thaumicenergistics;

import com.cells.blocks.interfacebase.AbstractResourceInterfaceBlock;


/**
 * Block for the Essentia I/O Interface.
 * Combines essentia import and export in a single block with tab-based direction switching.
 */
public class BlockEssentiaIOInterface extends AbstractResourceInterfaceBlock<TileEssentiaIOInterface> {

    public BlockEssentiaIOInterface() {
        super(
            "io_essentia_interface",
            "io_interface.essentia",
            TileEssentiaIOInterface.class,
            EssentiaInterfaceGuiHandler.GUI_ESSENTIA_IO_INTERFACE
        );
    }
}
