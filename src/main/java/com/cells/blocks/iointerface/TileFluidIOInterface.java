package com.cells.blocks.iointerface;

import javax.annotation.Nullable;

import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import com.cells.blocks.interfacebase.fluid.FluidInterfaceLogic;
import com.cells.gui.CellsGuiHandler;
import com.cells.network.sync.ResourceType;


/**
 * Tile entity for the Fluid I/O Interface block.
 * Combines a Fluid Import Interface and a Fluid Export Interface in a single block
 * with direction-switching tabs.
 */
public class TileFluidIOInterface extends AbstractIOInterfaceTile<FluidInterfaceLogic> {

    /**
     * Typed host wrapper for FluidInterfaceLogic (marker interface, no extra methods).
     */
    private class FluidDirectionHost extends DirectionHost implements FluidInterfaceLogic.Host {
        FluidDirectionHost(boolean export) { super(export); }
    }

    public TileFluidIOInterface() {
        FluidDirectionHost importHost = new FluidDirectionHost(false);
        FluidDirectionHost exportHost = new FluidDirectionHost(true);

        FluidInterfaceLogic importLogic = new FluidInterfaceLogic(importHost);
        FluidInterfaceLogic exportLogic = new FluidInterfaceLogic(exportHost);

        this.initLogics(importLogic, exportLogic);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.FLUID;
    }

    @Override
    public int getMainGuiId() {
        return CellsGuiHandler.GUI_FLUID_IO_INTERFACE;
    }

    // ============================== Capabilities ==============================

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) return true;
        return super.hasCapability(capability, facing);
    }

    @Override
    @Nullable
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(
                new CompositeFluidHandler(
                    this.importLogic.getExternalHandler(),
                    this.exportLogic.getExternalHandler()
                )
            );
        }
        return super.getCapability(capability, facing);
    }

    // ============================== Composite Fluid Handler ==============================

    /**
     * Composite handler that routes fill to the import handler
     * and drain to the export handler.
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
            // Combine properties from both handlers
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
