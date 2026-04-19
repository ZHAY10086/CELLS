package com.cells.parts;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import appeng.items.parts.PartModels;
import appeng.parts.PartModel;

import com.cells.blocks.interfacebase.fluid.FluidInterfaceLogic;
import com.cells.gui.CellsGuiHandler;
import com.cells.network.sync.ResourceType;


/**
 * Part version of the Fluid I/O Interface.
 * Combines fluid import and export capabilities in a single cable part
 * with direction-switching tabs.
 */
public class PartFluidIOInterface extends AbstractIOInterfacePart<FluidInterfaceLogic> {

    private static final String prefix = "part/io_interface/fluid/";
    private static final Object[] MODELS = PartModelsHelper.createInterfaceModels(prefix);

    @PartModels
    public static final PartModel MODELS_OFF = (PartModel) MODELS[1];

    @PartModels
    public static final PartModel MODELS_ON = (PartModel) MODELS[2];

    @PartModels
    public static final PartModel MODELS_HAS_CHANNEL = (PartModel) MODELS[3];

    /**
     * Typed host wrapper for FluidInterfaceLogic (marker interface, no extra methods).
     */
    private class FluidDirectionHost extends DirectionHost implements FluidInterfaceLogic.Host {
        FluidDirectionHost(boolean export) { super(export); }
    }

    public PartFluidIOInterface(final ItemStack is) {
        super(is);

        FluidDirectionHost importHost = new FluidDirectionHost(false);
        FluidDirectionHost exportHost = new FluidDirectionHost(true);

        this.initLogics(
            new FluidInterfaceLogic(importHost),
            new FluidInterfaceLogic(exportHost)
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
        return ResourceType.FLUID;
    }

    @Override
    public int getMainGuiId() {
        return CellsGuiHandler.GUI_PART_FLUID_IO_INTERFACE;
    }

    // ============================== Capabilities ==============================

    @Override
    public boolean hasCapability(Capability<?> capability) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) return true;
        return super.hasCapability(capability);
    }

    @Override
    public <T> T getCapability(Capability<T> capability) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(
                new CompositeFluidHandler(
                    this.importLogic.getExternalHandler(),
                    this.exportLogic.getExternalHandler()
                )
            );
        }
        return super.getCapability(capability);
    }

    // ============================== Composite Fluid Handler ==============================

    /**
     * Routes fill to the import handler, drain to the export handler.
     */
    private static class CompositeFluidHandler implements IFluidHandler {

        private final IFluidHandler importHandler;
        private final IFluidHandler exportHandler;

        CompositeFluidHandler(IFluidHandler importHandler, IFluidHandler exportHandler) {
            this.importHandler = importHandler;
            this.exportHandler = exportHandler;
        }

        @Override
        public IFluidTankProperties[] getTankProperties() {
            IFluidTankProperties[] importProps = this.importHandler.getTankProperties();
            IFluidTankProperties[] exportProps = this.exportHandler.getTankProperties();
            IFluidTankProperties[] combined = new IFluidTankProperties[importProps.length + exportProps.length];
            System.arraycopy(importProps, 0, combined, 0, importProps.length);
            System.arraycopy(exportProps, 0, combined, importProps.length, exportProps.length);
            return combined;
        }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            return this.importHandler.fill(resource, doFill);
        }

        @Nullable
        @Override
        public FluidStack drain(FluidStack resource, boolean doDrain) {
            return this.exportHandler.drain(resource, doDrain);
        }

        @Nullable
        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            return this.exportHandler.drain(maxDrain, doDrain);
        }
    }
}
