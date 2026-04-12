package com.cells.blocks.combinedinterface;

import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.Optional;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.aspects.IAspectSource;
import thaumcraft.api.aspects.IEssentiaTransport;

import com.cells.gui.CellsGuiHandler;
import com.cells.integration.thaumicenergistics.CombinedInterfaceEssentiaHelper;
import com.cells.integration.thaumicenergistics.EssentiaInterfaceLogic;


/**
 * Tile entity for the Combined Export Interface block.
 * Combines item, fluid, gas, and essentia export interfaces into a single block.
 * All resource types share the same upgrade slots and ME network connection.
 * <p>
 * Implements {@link IAspectSource} (extends IAspectContainer) and {@link IEssentiaTransport}
 * (via @Optional) so that ThaumicEnergistics, Thaumcraft infusion altars, and tubes
 * recognize this block as an essentia source.
 * All Thaumcraft interface methods delegate to the essentia logic through the
 * {@link CombinedInterfaceEssentiaHelper} to safely isolate Thaumcraft type references.
 */
@Optional.InterfaceList({
    @Optional.Interface(iface = "thaumcraft.api.aspects.IAspectSource", modid = "thaumcraft"),
    @Optional.Interface(iface = "thaumcraft.api.aspects.IEssentiaTransport", modid = "thaumcraft")
})
public class TileCombinedExportInterface extends AbstractCombinedInterfaceTile
        implements IAspectSource, IEssentiaTransport {

    @Override
    public boolean isExport() {
        return true;
    }

    @Override
    public int getMainGuiId() {
        return CellsGuiHandler.GUI_COMBINED_EXPORT_INTERFACE;
    }

    // ============================== IAspectSource / IAspectContainer ==============================

    /**
     * Not blocked - this allows infusion altars to draw essentia directly from us.
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public boolean isBlocked() {
        return false;
    }

    @Optional.Method(modid = "thaumcraft")
    @Override
    public AspectList getAspects() {
        return CombinedInterfaceEssentiaHelper.getAspects(this.essentiaLogic);
    }

    @Optional.Method(modid = "thaumcraft")
    @Override
    public void setAspects(AspectList aspects) {
        // Not implemented - we manage our own storage
    }

    @Optional.Method(modid = "thaumcraft")
    @Override
    public boolean doesContainerContain(AspectList aspects) {
        return CombinedInterfaceEssentiaHelper.doesContainerContain(this.essentiaLogic, aspects);
    }

    @Optional.Method(modid = "thaumcraft")
    @Override
    public boolean takeFromContainer(AspectList aspects) {
        // Export interface: we are a source, tubes can pull from us
        return CombinedInterfaceEssentiaHelper.takeFromContainer(this.essentiaLogic, aspects);
    }

    @Optional.Method(modid = "thaumcraft")
    @Override
    public boolean takeFromContainer(Aspect aspect, int amount) {
        // Export interface: we are a source, tubes can pull from us
        return CombinedInterfaceEssentiaHelper.takeFromContainerSingle(this.essentiaLogic, aspect, amount);
    }

    @Optional.Method(modid = "thaumcraft")
    @Override
    public int containerContains(Aspect aspect) {
        return CombinedInterfaceEssentiaHelper.containerContains(this.essentiaLogic, aspect);
    }

    @Optional.Method(modid = "thaumcraft")
    @Override
    public boolean doesContainerAccept(Aspect aspect) {
        return CombinedInterfaceEssentiaHelper.doesContainerAccept(this.essentiaLogic, aspect);
    }

    @Optional.Method(modid = "thaumcraft")
    @Override
    public int addToContainer(Aspect aspect, int amount) {
        // Export interface: we are a source, do not accept essentia from tubes
        return amount;
    }

    @Optional.Method(modid = "thaumcraft")
    @Override
    public boolean doesContainerContainAmount(Aspect aspect, int amount) {
        return CombinedInterfaceEssentiaHelper.doesContainerContainAmount(this.essentiaLogic, aspect, amount);
    }

    // ============================== IEssentiaTransport ==============================

    /**
     * Export interface has LOW suction - we are an essentia source.
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public int getMinimumSuction() {
        return EssentiaInterfaceLogic.EXPORT_SUCTION;
    }

    /**
     * Export interface has LOW suction - we are a source that provides essentia.
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public int getSuctionAmount(EnumFacing facing) {
        return EssentiaInterfaceLogic.EXPORT_SUCTION;
    }

    /**
     * Return the aspect type we have to offer (first stored type, or null for any).
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public Aspect getSuctionType(EnumFacing facing) {
        return CombinedInterfaceEssentiaHelper.getSuctionType(this.essentiaLogic);
    }

    /**
     * Export interface: tubes CANNOT push essentia to us, we are a source.
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public boolean canInputFrom(EnumFacing facing) {
        return false;
    }

    /**
     * Export interface: tubes CAN pull essentia from us, we are a source.
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public boolean canOutputTo(EnumFacing facing) {
        return true;
    }

    /**
     * We are connectable from all sides.
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public boolean isConnectable(EnumFacing facing) {
        return true;
    }

    /**
     * Set the suction type (not used - we manage our own suction).
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public void setSuction(Aspect aspect, int amount) {
        // We manage our own suction based on filters
    }

    /**
     * Return amount of the advertised essentia type.
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public int getEssentiaAmount(EnumFacing facing) {
        return CombinedInterfaceEssentiaHelper.getStoredEssentiaAmount(this.essentiaLogic);
    }

    /**
     * Return the essentia type we have available.
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public Aspect getEssentiaType(EnumFacing facing) {
        return CombinedInterfaceEssentiaHelper.getStoredEssentiaType(this.essentiaLogic);
    }

    /**
     * Add essentia from tube transport. Export interface: we are a source, tubes cannot push to us.
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public int addEssentia(Aspect aspect, int amount, EnumFacing facing) {
        return 0;
    }

    /**
     * Take essentia for tube transport. Export interface: we are a source, tubes pull from us.
     */
    @Optional.Method(modid = "thaumcraft")
    @Override
    public int takeEssentia(Aspect aspect, int amount, EnumFacing facing) {
        return CombinedInterfaceEssentiaHelper.takeEssentiaAmount(this.essentiaLogic, aspect, amount);
    }
}
