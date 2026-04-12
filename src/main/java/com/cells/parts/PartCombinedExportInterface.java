package com.cells.parts;

import net.minecraft.item.ItemStack;

import appeng.items.parts.PartModels;
import appeng.parts.PartModel;

import com.cells.gui.CellsGuiHandler;


/**
 * Part version of the Combined Export Interface.
 * Combines item, fluid, gas, and essentia export capabilities in a single cable part.
 */
public class PartCombinedExportInterface extends AbstractCombinedInterfacePart {

    private static final String prefix = "part/export_interface/combined/";
    private static final Object[] MODELS = PartModelsHelper.createInterfaceModels(prefix);

    @PartModels
    public static final PartModel MODELS_OFF = (PartModel) MODELS[1];

    @PartModels
    public static final PartModel MODELS_ON = (PartModel) MODELS[2];

    @PartModels
    public static final PartModel MODELS_HAS_CHANNEL = (PartModel) MODELS[3];

    public PartCombinedExportInterface(final ItemStack is) {
        super(is);
    }

    @Override
    protected PartModel getModelOff() { return MODELS_OFF; }

    @Override
    protected PartModel getModelOn() { return MODELS_ON; }

    @Override
    protected PartModel getModelHasChannel() { return MODELS_HAS_CHANNEL; }

    @Override
    public boolean isExport() { return true; }

    @Override
    public int getMainGuiId() {
        return CellsGuiHandler.GUI_PART_COMBINED_EXPORT_INTERFACE;
    }
}
