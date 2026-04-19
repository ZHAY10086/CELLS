package com.cells.integration.thaumicenergistics;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;

import net.minecraft.inventory.Slot;

import appeng.client.gui.widgets.GuiCustomSlot;

import thaumicenergistics.api.EssentiaStack;
import thaumicenergistics.api.storage.IAEEssentiaStack;
import thaumicenergistics.integration.appeng.AEEssentiaStack;

import com.cells.blocks.interfacebase.IInterfaceLogic;
import com.cells.blocks.interfacebase.IResourceInterfaceLogic;
import com.cells.blocks.iointerface.IIOInterfaceHost;
import com.cells.gui.QuickAddHelper;
import com.cells.gui.slots.EssentiaFilterSlot;
import com.cells.gui.slots.EssentiaTankSlot;
import com.cells.network.CellsNetworkHandler;
import com.cells.network.sync.PacketQuickAddFilter;
import com.cells.network.sync.ResourceType;


/**
 * GUI helper for essentia-related slot creation in the I/O Interface.
 * <p>
 * Isolates all ThaumicEnergistics type references so that
 * {@link com.cells.blocks.iointerface.GuiIOInterface} never directly references
 * essentia classes, preventing ClassNotFoundException when ThaumicEnergistics is not installed.
 * <p>
 * Only call methods on this class after verifying ThaumicEnergistics is loaded.
 */
public final class IOGuiEssentiaHelper {

    private IOGuiEssentiaHelper() {}

    /**
     * Create an essentia filter slot for the IO interface.
     */
    @Nullable
    @SuppressWarnings("rawtypes")
    public static GuiCustomSlot createEssentiaFilterSlot(
            IIOInterfaceHost host, int displaySlot, int x, int y, IntSupplier pageOffsetSupplier) {

        IInterfaceLogic essentiaLogic = host.getActiveLogic();
        if (!(essentiaLogic instanceof IResourceInterfaceLogic)) return null;

        IResourceInterfaceLogic rawLogic = (IResourceInterfaceLogic) essentiaLogic;

        return new EssentiaFilterSlot(
            slot -> (IAEEssentiaStack) rawLogic.getFilter(slot),
            displaySlot, x, y,
            pageOffsetSupplier
        );
    }

    /**
     * Create an essentia tank slot for the IO interface.
     */
    public static GuiCustomSlot createEssentiaTankSlot(
            IIOInterfaceHost host, int displaySlot, int x, int y,
            IntSupplier pageOffsetSupplier, LongSupplier maxSlotSizeSupplier) {

        EssentiaTankHostAdapter adapter = new EssentiaTankHostAdapter(host);

        return new EssentiaTankSlot<>(
            adapter, displaySlot, displaySlot, x, y,
            pageOffsetSupplier,
            maxSlotSizeSupplier
        );
    }

    /**
     * Handle essentia quick-add from JEI or held item.
     *
     * @return true if the quick-add was handled (even if no essentia was found)
     */
    public static boolean handleEssentiaQuickAdd(Slot hoveredSlot) {
        EssentiaStack essentia = QuickAddHelper.getEssentiaUnderCursor(hoveredSlot);

        if (essentia != null) {
            CellsNetworkHandler.INSTANCE.sendToServer(new PacketQuickAddFilter(
                ResourceType.ESSENTIA,
                AEEssentiaStack.fromEssentiaStack(essentia)
            ));
            return true;
        }

        if (QuickAddHelper.hasAnythingUnderCursor(hoveredSlot)) {
            QuickAddHelper.sendNoValidError("essentia");
        }

        return true;
    }

    // ================================= Host Adapter =================================

    /**
     * Adapter wrapping the IO host's active essentia logic for EssentiaTankSlot rendering.
     */
    private static class EssentiaTankHostAdapter implements EssentiaTankSlot.IEssentiaTankHost {
        private final IIOInterfaceHost host;

        EssentiaTankHostAdapter(IIOInterfaceHost host) {
            this.host = host;
        }

        @Override
        @Nullable
        public EssentiaStack getEssentiaInSlot(int slotIndex) {
            IInterfaceLogic logic = this.host.getActiveLogic();
            if (logic instanceof EssentiaInterfaceLogic) {
                return ((EssentiaInterfaceLogic) logic).getEssentiaInSlot(slotIndex);
            }
            return null;
        }

        @Override
        public long getEssentiaAmount(int slotIndex) {
            IInterfaceLogic logic = this.host.getActiveLogic();
            if (logic instanceof IResourceInterfaceLogic) {
                return ((IResourceInterfaceLogic<?, ?>) logic).getSlotAmount(slotIndex);
            }
            return 0;
        }

        @Override
        public String getTypeName() {
            return "essentia";
        }

        @Override
        public boolean isExport() {
            return this.host.isExport();
        }
    }
}
