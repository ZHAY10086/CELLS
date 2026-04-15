package com.cells.integration.mekanismenergistics;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;

import net.minecraft.inventory.Slot;

import appeng.client.gui.widgets.GuiCustomSlot;

import mekanism.api.gas.GasStack;

import com.cells.blocks.combinedinterface.ICombinedInterfaceHost;
import com.cells.blocks.interfacebase.IInterfaceLogic;
import com.cells.blocks.interfacebase.IResourceInterfaceLogic;
import com.cells.gui.QuickAddHelper;
import com.cells.gui.slots.GasFilterSlot;
import com.cells.gui.slots.GasTankSlot;
import com.cells.network.CellsNetworkHandler;
import com.cells.network.sync.PacketQuickAddFilter;
import com.cells.network.sync.ResourceType;
import com.mekeng.github.common.me.data.IAEGasStack;
import com.mekeng.github.common.me.data.impl.AEGasStack;


/**
 * GUI helper for gas-related slot creation in the Combined Interface.
 * <p>
 * This class isolates all MekanismEnergistics type references so that
 * {@link com.cells.blocks.combinedinterface.GuiCombinedInterface} never directly
 * references gas classes, preventing {@link ClassNotFoundException} when
 * MekanismEnergistics is not installed.
 * <p>
 * Only call methods on this class after verifying
 * {@link MekanismEnergisticsIntegration#isModLoaded()}.
 */
public final class CombinedGuiGasHelper {

    private CombinedGuiGasHelper() {}

    /**
     * Create a gas filter slot for the combined interface.
     *
     * @param host The combined interface host
     * @param displaySlot The display slot index
     * @param x X position in GUI
     * @param y Y position in GUI
     * @param pageOffsetSupplier Supplier for the current page offset
     * @return The created filter slot, or null if gas logic is unavailable
     */
    @Nullable
    @SuppressWarnings("rawtypes")
    public static GuiCustomSlot createGasFilterSlot(
            ICombinedInterfaceHost host, int displaySlot, int x, int y, IntSupplier pageOffsetSupplier) {

        IInterfaceLogic gasLogic = host.getGasLogic();
        if (!(gasLogic instanceof IResourceInterfaceLogic)) return null;

        IResourceInterfaceLogic rawLogic = (IResourceInterfaceLogic) gasLogic;

        return new GasFilterSlot(
            slot -> (IAEGasStack) rawLogic.getFilter(slot),
            displaySlot, x, y,
            pageOffsetSupplier
        );
    }

    /**
     * Create a gas tank slot for the combined interface.
     *
     * @param host The combined interface host
     * @param displaySlot The display slot index
     * @param x X position in GUI
     * @param y Y position in GUI
     * @param pageOffsetSupplier Supplier for the current page offset
     * @param maxSlotSizeSupplier Supplier for the max slot size display value
     * @return The created tank slot
     */
    public static GuiCustomSlot createGasTankSlot(
            ICombinedInterfaceHost host, int displaySlot, int x, int y,
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
     * Adapter that wraps the combined host's gas logic to satisfy GasTankSlot.IGasTankHost.
     * Delegates gas tank data through the IInterfaceLogic using raw-typed access,
     * since the combined host only exposes gas logic via the untyped IInterfaceLogic interface.
     */
    private static class GasTankHostAdapter implements GasTankSlot.IGasTankHost {
        private final ICombinedInterfaceHost host;

        GasTankHostAdapter(ICombinedInterfaceHost host) {
            this.host = host;
        }

        @Override
        @Nullable
        public GasStack getGasInTank(int tankIndex) {
            IInterfaceLogic logic = this.host.getGasLogic();
            if (logic == null) return null;

            if (logic instanceof GasInterfaceLogic) {
                return ((GasInterfaceLogic) logic).getGasInTank(tankIndex);
            }

            return null;
        }

        @Override
        public long getGasAmount(int tankIndex) {
            IInterfaceLogic logic = this.host.getGasLogic();
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
