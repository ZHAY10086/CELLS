package com.cells.integration.mekanismenergistics;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;

import appeng.items.parts.PartModels;
import appeng.parts.PartModel;

import mekanism.api.gas.GasStack;
import mekanism.common.capabilities.Capabilities;

import com.cells.parts.AbstractInterfacePart;
import com.cells.parts.PartModelsHelper;


/**
 * Part version of the Gas Import Interface.
 * Can be placed on cables and behaves identically to the block version.
 * <p>
 * Business logic is delegated to {@link GasInterfaceLogic} to avoid code
 * duplication with tile and export variants.
 */
public class PartGasImportInterface extends AbstractInterfacePart<GasInterfaceLogic>
        implements IGasInterfaceHost, GasInterfaceLogic.Host {

    private static final String prefix = "part/import_interface/gas/";
    private static final Object[] MODELS = PartModelsHelper.createInterfaceModels(prefix);

    @PartModels
    public static final PartModel MODELS_OFF = (PartModel) MODELS[1];

    @PartModels
    public static final PartModel MODELS_ON = (PartModel) MODELS[2];

    @PartModels
    public static final PartModel MODELS_HAS_CHANNEL = (PartModel) MODELS[3];

    public PartGasImportInterface(final ItemStack is) {
        super(is);
        setLogic(new GasInterfaceLogic(this));
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
        return GasInterfaceGuiHandler.GUI_PART_GAS_IMPORT_INTERFACE;
    }

    // ============================== IGasInterfaceHost Implementation ==============================

    @Nullable
    @Override
    public GasStack getGasInTank(int slot) {
        return this.logic.getGasInTank(slot);
    }

    @Override
    public void setGasInTank(int slot, @Nullable GasStack gas) {
        this.logic.setGasInTank(slot, gas);
    }

    @Override
    public boolean hasCapability(Capability<?> capability) {
        if (capability == Capabilities.GAS_HANDLER_CAPABILITY) return true;
        return super.hasCapability(capability);
    }

    @Override
    public <T> T getCapability(Capability<T> capability) {
        if (capability == Capabilities.GAS_HANDLER_CAPABILITY) {
            return Capabilities.GAS_HANDLER_CAPABILITY.cast(this.logic.getExternalHandler());
        }
        return super.getCapability(capability);
    }
}
