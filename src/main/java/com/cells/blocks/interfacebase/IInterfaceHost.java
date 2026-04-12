package com.cells.blocks.interfacebase;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import appeng.api.util.AEPartLocation;
import appeng.util.SettingsFrom;


/**
 * Base interface for all Interface host variants (item/fluid, import/export, block/part).
 * Provides the common contract needed by sub-GUIs (MaxSlotSize, PollingRate)
 * and network packets to operate without knowing the concrete host type.
 * <p>
 * Implementations can be either {@link net.minecraft.tileentity.TileEntity}
 * subclasses or {@link appeng.api.parts.IPart} implementations.
 */
public interface IInterfaceHost {

    long validateMaxSlotSize(long size);

    long getMaxSlotSize();

    long setMaxSlotSize(long size);

    // ================================= Per-Slot Size Overrides =================================

    /**
     * Get the effective size for a specific slot (override or global maxSlotSize).
     */
    long getEffectiveMaxSlotSize(int slot);

    /**
     * Set a per-slot size override.
     * @return The validated override size.
     */
    long setMaxSlotSizeOverride(int slot, long size);

    /**
     * Get the per-slot size override, or -1 if no override is set.
     */
    long getMaxSlotSizeOverride(int slot);

    /**
     * Clear the per-slot size override, reverting to global maxSlotSize.
     */
    void clearMaxSlotSizeOverride(int slot);

    int getPollingRate();

    int setPollingRate(int ticks);

    /**
     * @return true if this is an export interface, false if import.
     */
    boolean isExport();

    /**
     * @return direction string for GUI titles and lang keys ("import" or "export").
     */
    default String getDirectionString() {
        return isExport() ? "export" : "import";
    }

    /**
     * @return the title key for the main GUI.
     */
    default String getGuiTitleLangKey() {
        return String.format("cells.%s_interface.%s.title", this.getDirectionString(), this.getTypeName());
    }

    /**
     * @return the GUI ID of the main interface GUI, used by sub-GUIs to navigate back.
     */
    int getMainGuiId();

    /**
     * @return the type name for any type-specific display (e.g. "Item", "Fluid", "Gas"), used in sub-GUIs and tooltips.
     */
    String getTypeName();

    /**
     * @return the position of this host in the world.
     */
    BlockPos getHostPos();

    /**
     * @return the world this host is in, or null if not yet placed.
     */
    @Nullable
    World getHostWorld();

    /**
     * @return an ItemStack representing this host, for display in the back button.
     */
    ItemStack getBackButtonStack();

    /**
     * @return the part side if this host is a part, or null if this is a block.
     */
    @Nullable
    default AEPartLocation getPartSide() {
        return null;
    }

    /**
     * @return true if this host is a part (on a cable), false if it's a block.
     */
    default boolean isPart() {
        return getPartSide() != null;
    }

    /**
     * Download settings to NBT for memory cards and dismantling.
     */
    NBTTagCompound downloadSettings(SettingsFrom from);

    /**
     * Download settings with filters to NBT for memory card + keybind.
     * Does NOT include upgrades (those stay in the source interface).
     */
    NBTTagCompound downloadSettingsWithFilter();
}
