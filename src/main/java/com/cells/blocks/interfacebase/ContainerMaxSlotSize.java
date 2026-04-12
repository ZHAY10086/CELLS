package com.cells.blocks.interfacebase;

import net.minecraft.client.gui.GuiTextField;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.tileentity.TileEntity;

import appeng.api.parts.IPart;
import appeng.container.AEBaseContainer;
import appeng.container.guisync.GuiSync;
import appeng.util.Platform;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.cells.gui.CellsGuiHandler;


/**
 * Container for the Max Slot Size configuration GUI.
 * Similar to AE2's ContainerPriority but for slot size configuration.
 * Works with any host implementing {@link IInterfaceHost} (both TileEntity and IPart).
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Global mode</b> ({@code overrideSlot == -1}): Edits the global maxSlotSize for all slots.</li>
 *   <li><b>Per-slot mode</b> ({@code overrideSlot >= 0}): Edits the size override for a specific slot.</li>
 * </ul>
 * Per-slot mode is activated when a pending override slot is set in {@link CellsGuiHandler}
 * before the GUI is opened (via right-clicking a filter slot).
 */
public class ContainerMaxSlotSize extends AEBaseContainer {

    private final IInterfaceHost host;

    /**
     * The slot index being edited, or -1 for global mode.
     * Set during construction by consuming from {@link CellsGuiHandler#consumePendingOverrideSlot}.
     */
    private final int overrideSlot;

    @SideOnly(Side.CLIENT)
    private GuiTextField textField;

    @GuiSync(0)
    public long maxSlotSize = -1;

    /**
     * Synced override slot index. -1 = global mode. >= 0 = per-slot mode.
     * The client needs this to know which mode to display.
     */
    @GuiSync(1)
    public int syncedOverrideSlot = -1;

    public ContainerMaxSlotSize(final InventoryPlayer ip, final IInterfaceHost host) {
        super(ip, host instanceof TileEntity ? (TileEntity) host : null, host instanceof IPart ? (IPart) host : null);
        this.host = host;

        // Consume the pending override slot (if any) to determine mode.
        // This is set by PacketOpenSlotOverrideGui right before the GUI opens.
        if (Platform.isServer()) {
            this.overrideSlot = CellsGuiHandler.consumePendingOverrideSlot(ip.player);
            this.syncedOverrideSlot = this.overrideSlot;
        } else {
            this.overrideSlot = -1;
        }
    }

    /**
     * @return The slot index being overridden, or -1 for global mode.
     */
    public int getOverrideSlot() {
        return this.syncedOverrideSlot;
    }

    /**
     * @return true if this container is in per-slot override mode.
     */
    public boolean isOverrideMode() {
        return this.syncedOverrideSlot >= 0;
    }

    @SideOnly(Side.CLIENT)
    public void setTextField(final GuiTextField field) {
        this.textField = field;

        // Values < 0 mean "not set" (override not configured, or initial
        // state before @GuiSync delivers the real value). Show empty so
        // the user doesn't see a confusing "-1".
        if (this.maxSlotSize < 0) {
            this.textField.setText("");
        } else {
            this.textField.setText(String.format("%,d", this.maxSlotSize));
        }
    }

    /**
     * Set the slot size value. In global mode, sets global maxSlotSize.
     * In per-slot mode, sets the per-slot override.
     *
     * @param newValue The new value. In per-slot mode, -1 clears the override.
     */
    public void setMaxSlotSize(final long newValue) {
        if (isOverrideMode()) {
            if (newValue < 0) {
                this.host.clearMaxSlotSizeOverride(this.overrideSlot);
                this.maxSlotSize = -1;
            } else {
                this.maxSlotSize = this.host.setMaxSlotSizeOverride(this.overrideSlot, newValue);
            }
        } else {
            this.maxSlotSize = this.host.setMaxSlotSize(newValue);
        }
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();

        if (Platform.isServer()) {
            if (isOverrideMode()) {
                long current = this.host.getMaxSlotSizeOverride(this.overrideSlot);
                if (this.maxSlotSize != current) this.maxSlotSize = current;
            } else {
                long current = this.host.getMaxSlotSize();
                if (this.maxSlotSize != current) this.maxSlotSize = current;
            }
        }
    }

    @Override
    public void onUpdate(final String field, final Object oldValue, final Object newValue) {
        if (field.equals("maxSlotSize") && this.textField != null) {
            if (this.maxSlotSize < 0) {
                // Not set (override cleared, or initial state before sync)
                this.textField.setText("");
            } else {
                // Format with commas for readability, preserve cursor position
                String formatted = String.format("%,d", this.maxSlotSize);
                String currentText = this.textField.getText();

                if (!currentText.equals(formatted)) {
                    int cursorFromEnd = currentText.length() - this.textField.getCursorPosition();
                    this.textField.setText(formatted);
                    int newCursor = Math.max(0, formatted.length() - cursorFromEnd);
                    this.textField.setCursorPosition(newCursor);
                }
            }
        }

        super.onUpdate(field, oldValue, newValue);
    }
}
