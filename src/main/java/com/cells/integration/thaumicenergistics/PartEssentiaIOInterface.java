package com.cells.integration.thaumicenergistics;

import net.minecraft.item.ItemStack;

import appeng.items.parts.PartModels;
import appeng.parts.PartModel;

import com.cells.network.sync.ResourceType;
import com.cells.parts.AbstractIOInterfacePart;
import com.cells.parts.PartModelsHelper;


/**
 * Part version of the Essentia I/O Interface.
 * Combines essentia import and export capabilities in a single cable part
 * with direction-switching tabs.
 * <p>
 * <b>Important:</b> Unlike the block version, parts do NOT participate in the
 * Thaumcraft tube network. This is because {@code IEssentiaTransport} requires
 * a tile entity, and parts are attached to cables instead. Use the block version
 * if tube connectivity is required.
 */
public class PartEssentiaIOInterface extends AbstractIOInterfacePart<EssentiaInterfaceLogic> {

    private static final String prefix = "part/io_interface/essentia/";
    private static final Object[] MODELS = PartModelsHelper.createInterfaceModels(prefix);

    @PartModels
    public static final PartModel MODELS_OFF = (PartModel) MODELS[1];

    @PartModels
    public static final PartModel MODELS_ON = (PartModel) MODELS[2];

    @PartModels
    public static final PartModel MODELS_HAS_CHANNEL = (PartModel) MODELS[3];

    /**
     * Typed host wrapper for EssentiaInterfaceLogic.
     */
    private class EssentiaDirectionHost extends DirectionHost implements EssentiaInterfaceLogic.Host {
        EssentiaDirectionHost(boolean export) { super(export); }
    }

    public PartEssentiaIOInterface(final ItemStack is) {
        super(is);

        EssentiaDirectionHost importHost = new EssentiaDirectionHost(false);
        EssentiaDirectionHost exportHost = new EssentiaDirectionHost(true);

        this.initLogics(
            new EssentiaInterfaceLogic(importHost),
            new EssentiaInterfaceLogic(exportHost)
        );
    }

    @Override
    protected PartModel getModelOff() { return MODELS_OFF; }

    @Override
    protected PartModel getModelOn() { return MODELS_ON; }

    @Override
    protected PartModel getModelHasChannel() { return MODELS_HAS_CHANNEL; }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.ESSENTIA;
    }

    @Override
    public int getMainGuiId() {
        return EssentiaInterfaceGuiHandler.GUI_PART_ESSENTIA_IO_INTERFACE;
    }
}
