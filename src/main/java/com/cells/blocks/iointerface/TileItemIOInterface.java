package com.cells.blocks.iointerface;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import appeng.capabilities.Capabilities;

import com.cells.blocks.interfacebase.AbstractResourceInterfaceLogic;
import com.cells.blocks.interfacebase.item.ItemInterfaceLogic;
import com.cells.gui.CellsGuiHandler;
import com.cells.network.sync.ResourceType;


/**
 * Tile entity for the Item I/O Interface block.
 * Combines an Item Import Interface and an Item Export Interface in a single block
 * with direction-switching tabs.
 */
public class TileItemIOInterface extends AbstractIOInterfaceTile<ItemInterfaceLogic> {

    /**
     * Typed host wrapper for ItemInterfaceLogic (marker interface, no extra methods).
     */
    private class ItemDirectionHost extends DirectionHost implements ItemInterfaceLogic.Host {
        ItemDirectionHost(boolean export) { super(export); }
    }

    public TileItemIOInterface() {
        ItemDirectionHost importHost = new ItemDirectionHost(false);
        ItemDirectionHost exportHost = new ItemDirectionHost(true);

        ItemInterfaceLogic importLogic = new ItemInterfaceLogic(importHost);
        ItemInterfaceLogic exportLogic = new ItemInterfaceLogic(exportHost);

        this.initLogics(importLogic, exportLogic);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.ITEM;
    }

    @Override
    public int getMainGuiId() {
        return CellsGuiHandler.GUI_ITEM_IO_INTERFACE;
    }

    // ============================== Capabilities ==============================

    // The composite capability routes insertItem to the import logic
    // and extractItem to the export logic, so that adjacent machines
    // can both push items in (import) and pull items out (export).

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) return true;
        if (capability == Capabilities.ITEM_REPOSITORY_CAPABILITY) return true;
        return super.hasCapability(capability, facing);
    }

    @Override
    @Nullable
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(
                new CompositeItemHandler(
                    this.importLogic.getExternalHandler(),
                    this.exportLogic.getExternalHandler()
                )
            );
        }
        if (capability == Capabilities.ITEM_REPOSITORY_CAPABILITY) {
            // Use the import logic's repository for item lookup (both are equivalent for reading)
            return Capabilities.ITEM_REPOSITORY_CAPABILITY.cast(this.importLogic.getItemRepository());
        }
        return super.getCapability(capability, facing);
    }

    // ============================== Composite Item Handler ==============================

    /**
     * Composite handler that routes insertion to the import handler
     * and extraction to the export handler.
     */
    private static class CompositeItemHandler implements IItemHandler {

        private final IItemHandler importHandler;
        private final IItemHandler exportHandler;

        CompositeItemHandler(IItemHandler importHandler, IItemHandler exportHandler) {
            this.importHandler = importHandler;
            this.exportHandler = exportHandler;
        }

        @Override
        public int getSlots() {
            return Math.max(this.importHandler.getSlots(), this.exportHandler.getSlots());
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            // Show export items (these are the items available for extraction)
            if (slot < this.exportHandler.getSlots()) {
                return this.exportHandler.getStackInSlot(slot);
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < this.importHandler.getSlots()) {
                return this.importHandler.insertItem(slot, stack, simulate);
            }
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot < this.exportHandler.getSlots()) {
                return this.exportHandler.extractItem(slot, amount, simulate);
            }
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return AbstractResourceInterfaceLogic.SLOTS_PER_PAGE;
        }
    }
}
