package com.cells.integration.mekanismenergistics;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;

import net.minecraft.inventory.Slot;

import appeng.client.gui.widgets.GuiCustomSlot;

import mekanism.api.gas.GasStack;

import com.cells.blocks.interfacebase.IInterfaceLogic;
import com.cells.blocks.interfacebase.IResourceInterfaceLogic;
import com.cells.blocks.iointerface.IIOInterfaceHost;
import com.cells.gui.QuickAddHelper;
import com.cells.gui.slots.GasFilterSlot;
import com.cells.gui.slots.GasTankSlot;
import com.cells.network.CellsNetworkHandler;
import com.cells.network.sync.PacketQuickAddFilter;
import com.cells.network.sync.ResourceType;
import com.mekeng.github.common.me.data.IAEGasStack;
import com.mekeng.github.common.me.data.impl.AEGasStack;


/**
 * GUI helper for gas-related slot creation in the I/O Interface.
 * <p>
 * Isolates all MekanismEnergistics type references so that
 * {@link com.cells.blocks.iointerface.GuiIOInterface} never directly references
 * gas classes, preventing ClassNotFoundException when MekanismEnergistics is not installed.
 * <p>
 * Only call methods on this class after verifying MekanismEnergistics is loaded.
 */
public final class IOGuiGasHelper {

    private IOGuiGasHelper() {}

    /**
     * Create a gas filter slot for the IO interface.
     */
    @Nullable
    @SuppressWarnings("rawtypes")
    public static GuiCustomSlot createGasFilterSlot(
            IIOInterfaceHost host, int displaySlot, int x, int y, IntSupplier pageOffsetSupplier) {

        IInterfaceLogic gasLogic = host.getActiveLogic();
        if (!(gasLogic instanceof IResourceInterfaceLogic)) return null;

        IResourceInterfaceLogic rawLogic = (IResourceInterfaceLogic) gasLogic;

        return new GasFilterSlot(
            slot -> (IAEGasStack) rawLogic.getFilter(slot),
            displaySlot, x, y,
            pageOffsetSupplier
        );
    }

    /**
     * Create a gas tank slot for the IO interface.
     */
    public static GuiCustomSlot createGasTankSlot(
            IIOInterfaceHost host, int displaySlot, int x, int y,
            IntSupplier pageOffsetSupplier, LongSupplier maxSlotSizeSupplier) {

        GasTankHostAdapter adapter = new GasTankHostAdapter(host);

        return new GasTankSlot<>(
            adapter, displaySlot, displaySlot, x, y,
            pageOffsetSupplier,
            maxSlotSizeSupplier
        );
    }

    /**
     * Handle gas quick-add from JEI or held item.
     *
     * @return true if the quick-add was handled (even if no gas was found)
     */
    public static boolean handleGasQuickAdd(Slot hoveredSlot) {
        GasStack gas = QuickAddHelper.getGasUnderCursor(hoveredSlot);

        if (gas != null) {
            CellsNetworkHandler.INSTANCE.sendToServer(new PacketQuickAddFilter(
                ResourceType.GAS,
                AEGasStack.of(gas))
            );
            return true;
        }

        if (QuickAddHelper.hasAnythingUnderCursor(hoveredSlot)) {
            QuickAddHelper.sendNoValidError("gas");
        }

        return true;
    }

    // ================================= Host Adapter =================================

    /**
     * Adapter wrapping the IO host's active gas logic for GasTankSlot rendering.
     */
    private static class GasTankHostAdapter implements GasTankSlot.IGasTankHost {
        private final IIOInterfaceHost host;

        GasTankHostAdapter(IIOInterfaceHost host) {
            this.host = host;
        }

        @Override
        @Nullable
        public GasStack getGasInTank(int tankIndex) {
            IInterfaceLogic logic = this.host.getActiveLogic();
            if (logic instanceof GasInterfaceLogic) {
                return ((GasInterfaceLogic) logic).getGasInTank(tankIndex);
            }
            return null;
        }

        @Override
        public long getGasAmount(int tankIndex) {
            IInterfaceLogic logic = this.host.getActiveLogic();
            if (logic instanceof IResourceInterfaceLogic) {
                return ((IResourceInterfaceLogic<?, ?>) logic).getSlotAmount(tankIndex);
            }
            return 0;
        }

        @Override
        public String getTypeName() {
            return "gas";
        }

        @Override
        public boolean isExport() {
            return this.host.isExport();
        }
    }
}
