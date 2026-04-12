package com.cells.integration.thaumicenergistics;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import appeng.items.parts.PartModels;
import appeng.parts.PartModel;

import thaumicenergistics.api.EssentiaStack;

import com.cells.parts.AbstractInterfacePart;
import com.cells.parts.PartModelsHelper;


/**
 * Part version of the Essentia Import Interface.
 * Can be placed on cables and behaves identically to the block version.
 * <p>
 * <b>Important:</b> Unlike the block version, parts do NOT participate in the
 * Thaumcraft tube network. This is because {@code IEssentiaTransport} requires
 * a tile entity, and parts are attached to cables instead. Use the block version
 * if tube connectivity is required.
 * <p>
 * This part still works with:
 * <ul>
 *   <li>Storage buses pointing at the interface</li>
 *   <li>Direct adjacent machine interactions (if they check part capabilities)</li>
 *   <li>Manual essentia insertion via phials</li>
 * </ul>
 * <p>
 * Business logic is delegated to {@link EssentiaInterfaceLogic} to avoid code
 * duplication with tile and export variants.
 */
public class PartEssentiaImportInterface extends AbstractInterfacePart<EssentiaInterfaceLogic>
        implements IEssentiaInterfaceHost, EssentiaInterfaceLogic.Host {

    private static final String prefix = "part/import_interface/essentia/";
    private static final Object[] MODELS = PartModelsHelper.createInterfaceModels(prefix);

    @PartModels
    public static final PartModel MODELS_OFF = (PartModel) MODELS[1];

    @PartModels
    public static final PartModel MODELS_ON = (PartModel) MODELS[2];

    @PartModels
    public static final PartModel MODELS_HAS_CHANNEL = (PartModel) MODELS[3];

    public PartEssentiaImportInterface(final ItemStack is) {
        super(is);
        setLogic(new EssentiaInterfaceLogic(this));
    }

    @Override
    protected PartModel getModelOff() {
        return MODELS_OFF;
    }

    @Override
    protected PartModel getModelOn() {
        return MODELS_ON;
    }

    @Override
    protected PartModel getModelHasChannel() {
        return MODELS_HAS_CHANNEL;
    }

    @Override
    public boolean isExport() {
        return false;
    }

    @Override
    public int getMainGuiId() {
        return EssentiaInterfaceGuiHandler.GUI_PART_ESSENTIA_IMPORT_INTERFACE;
    }

    // ============================== IEssentiaInterfaceHost Implementation ==============================

    @Nullable
    @Override
    public EssentiaStack getEssentiaInSlot(int slot) {
        return this.logic.getEssentiaInSlot(slot);
    }

    @Override
    public void setEssentiaInSlot(int slot, @Nullable EssentiaStack essentia) {
        this.logic.setEssentiaInSlot(slot, essentia);
    }

}
