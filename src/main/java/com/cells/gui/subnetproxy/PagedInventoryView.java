package com.cells.gui.subnetproxy;

import java.util.function.IntSupplier;

import javax.annotation.Nonnull;

import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;


/**
 * A read-through view over a backing {@link IItemHandler} that maps
 * slot indices through a page offset.
 * <p>
 * Given a backing inventory of size {@code slotsPerPage * maxPages},
 * this view exposes exactly {@code slotsPerPage} slots. The mapping is:
 * <pre>
 *   view slot i  →  backing slot (currentPage * slotsPerPage + i)
 * </pre>
 * When the page changes (via the supplier), all slot accesses automatically
 * redirect to the new page without needing to recreate container slots.
 * <p>
 * Implements {@link IItemHandlerModifiable} so AE2's slot synchronization
 * (which calls {@code setStackInSlot}) works correctly for fake slots.
 */
public class PagedInventoryView implements IItemHandlerModifiable {

    private final IItemHandler backing;
    private final int slotsPerPage;
    private final IntSupplier pageSupplier;

    public PagedInventoryView(IItemHandler backing, int slotsPerPage, IntSupplier pageSupplier) {
        this.backing = backing;
        this.slotsPerPage = slotsPerPage;
        this.pageSupplier = pageSupplier;
    }

    private int mapSlot(int slot) {
        return this.pageSupplier.getAsInt() * this.slotsPerPage + slot;
    }

    @Override
    public int getSlots() {
        return this.slotsPerPage;
    }

    @Nonnull
    @Override
    public ItemStack getStackInSlot(int slot) {
        int mapped = mapSlot(slot);
        if (mapped >= this.backing.getSlots()) return ItemStack.EMPTY;
        return this.backing.getStackInSlot(mapped);
    }

    @Nonnull
    @Override
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        int mapped = mapSlot(slot);
        if (mapped >= this.backing.getSlots()) return stack;
        return this.backing.insertItem(mapped, stack, simulate);
    }

    @Nonnull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        int mapped = mapSlot(slot);
        if (mapped >= this.backing.getSlots()) return ItemStack.EMPTY;
        return this.backing.extractItem(mapped, amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        int mapped = mapSlot(slot);
        if (mapped >= this.backing.getSlots()) return 0;
        return this.backing.getSlotLimit(mapped);
    }

    @Override
    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        int mapped = mapSlot(slot);
        if (mapped >= this.backing.getSlots()) return;

        if (this.backing instanceof IItemHandlerModifiable) {
            ((IItemHandlerModifiable) this.backing).setStackInSlot(mapped, stack);
        }
    }
}
