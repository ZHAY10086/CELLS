package com.cells.integration.thaumicenergistics;

import net.minecraft.util.EnumFacing;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.aspects.IAspectContainer;
import thaumcraft.api.aspects.IAspectSource;
import thaumcraft.api.aspects.IEssentiaTransport;

import com.cells.blocks.iointerface.AbstractIOInterfaceTile;
import com.cells.network.sync.ResourceType;


/**
 * Tile entity for the Essentia I/O Interface block.
 * Combines an Essentia Import Interface and an Essentia Export Interface in a single block.
 * <p>
 * Implements {@link IAspectContainer} so adjacent machines can detect essentia storage.
 * Implements {@link IAspectSource} so infusion altars can draw essentia from the export side.
 * Implements {@link IEssentiaTransport} to participate in the Thaumcraft tube network.
 * <p>
 * Tube behavior: The import side acts as a SINK (high suction)
 * and the export side acts as a SOURCE (tubes can pull essentia from us).
 * Both directions are active simultaneously: {@code addEssentia} goes to the import logic,
 * {@code takeEssentia} / {@code takeFromContainer} goes to the export logic.
 */
public class TileEssentiaIOInterface extends AbstractIOInterfaceTile<EssentiaInterfaceLogic>
        implements IAspectSource, IEssentiaTransport {

    /**
     * Typed host wrapper for EssentiaInterfaceLogic.
     */
    private class EssentiaDirectionHost extends DirectionHost implements EssentiaInterfaceLogic.Host {
        EssentiaDirectionHost(boolean export) { super(export); }
    }

    public TileEssentiaIOInterface() {
        EssentiaDirectionHost importHost = new EssentiaDirectionHost(false);
        EssentiaDirectionHost exportHost = new EssentiaDirectionHost(true);

        EssentiaInterfaceLogic importLogic = new EssentiaInterfaceLogic(importHost);
        EssentiaInterfaceLogic exportLogic = new EssentiaInterfaceLogic(exportHost);

        this.initLogics(importLogic, exportLogic);
    }

    @Override
    public ResourceType getResourceType() {
        return ResourceType.ESSENTIA;
    }

    @Override
    public int getMainGuiId() {
        return EssentiaInterfaceGuiHandler.GUI_ESSENTIA_IO_INTERFACE;
    }

    // ============================== IAspectSource / IAspectContainer Implementation ==============================

    @Override
    public boolean doesContainerAccept(Aspect aspect) {
        // Import side accepts essentia
        return this.importLogic.doesContainerAccept(aspect);
    }

    @Override
    public boolean doesContainerContainAmount(Aspect aspect, int amount) {
        // Check both logics - export side provides essentia
        return this.importLogic.doesContainerContainAmount(aspect, amount)
            || this.exportLogic.doesContainerContainAmount(aspect, amount);
    }

    @Override
    public int addToContainer(Aspect aspect, int amount) {
        // Import side receives essentia
        return this.importLogic.addToContainer(aspect, amount);
    }

    /**
     * Not blocked - allows infusion altars to draw essentia from the export side.
     */
    @Override
    public boolean isBlocked() {
        return false;
    }

    @Override
    public AspectList getAspects() {
        // Combine aspects from both logics
        AspectList combined = new AspectList();
        AspectList importAspects = this.importLogic.getAspects();
        AspectList exportAspects = this.exportLogic.getAspects();

        for (Aspect aspect : importAspects.getAspects()) {
            combined.add(aspect, importAspects.getAmount(aspect));
        }
        for (Aspect aspect : exportAspects.getAspects()) {
            combined.add(aspect, exportAspects.getAmount(aspect));
        }

        return combined;
    }

    @Override
    public void setAspects(AspectList aspects) {
        // Not implemented - we manage our own storage
    }

    @Override
    public boolean doesContainerContain(AspectList aspects) {
        for (Aspect aspect : aspects.getAspects()) {
            if (!this.importLogic.containerContainsAny(aspect)
                    && !this.exportLogic.containerContainsAny(aspect)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean takeFromContainer(AspectList aspects) {
        // Export side: tubes/altars pull from us
        for (Aspect aspect : aspects.getAspects()) {
            int needed = aspects.getAmount(aspect);
            if (!this.exportLogic.doesContainerContainAmount(aspect, needed)) return false;
        }

        for (Aspect aspect : aspects.getAspects()) {
            int needed = aspects.getAmount(aspect);
            this.exportLogic.takeEssentiaAmount(aspect, needed);
        }
        return true;
    }

    @Override
    public boolean takeFromContainer(Aspect aspect, int amount) {
        // Export side: tubes/altars pull from us
        int taken = this.exportLogic.takeEssentiaAmount(aspect, amount);
        return taken >= amount;
    }

    @Override
    public int containerContains(Aspect aspect) {
        // Report total from both logics
        return this.importLogic.getEssentiaCount(aspect)
             + this.exportLogic.getEssentiaCount(aspect);
    }

    // ============================== IEssentiaTransport Implementation ==============================

    /**
     * IO Interface uses import suction (HIGH) so tubes push essentia to us.
     * The export side is accessible via takeEssentia/takeFromContainer.
     */
    @Override
    public int getMinimumSuction() {
        return EssentiaInterfaceLogic.IMPORT_SUCTION;
    }

    /**
     * High suction (import behavior).
     */
    @Override
    public int getSuctionAmount(EnumFacing facing) {
        return EssentiaInterfaceLogic.IMPORT_SUCTION;
    }

    /**
     * Return the aspect type the import side wants to receive.
     */
    @Override
    public Aspect getSuctionType(EnumFacing facing) {
        return this.importLogic.getSuctionType();
    }

    /**
     * Things can push essentia to us (import side).
     */
    @Override
    public boolean canInputFrom(EnumFacing facing) {
        return true;
    }

    /**
     * Tubes can pull essentia from us (export side).
     */
    @Override
    public boolean canOutputTo(EnumFacing facing) {
        return true;
    }

    @Override
    public boolean isConnectable(EnumFacing facing) {
        return true;
    }

    @Override
    public void setSuction(Aspect aspect, int amount) {
        // We manage our own suction based on filters
    }

    /**
     * Report how much essentia the export side has available for tube transport.
     */
    @Override
    public int getEssentiaAmount(EnumFacing facing) {
        Aspect type = this.exportLogic.getStoredEssentiaType();
        if (type == null) return 0;

        return this.exportLogic.getEssentiaCount(type);
    }

    /**
     * Report the essentia type the export side has available for tube transport.
     */
    @Override
    public Aspect getEssentiaType(EnumFacing facing) {
        return this.exportLogic.getStoredEssentiaType();
    }

    /**
     * Add essentia from tube transport → import logic (sink).
     */
    @Override
    public int addEssentia(Aspect aspect, int amount, EnumFacing facing) {
        return this.importLogic.addToContainer(aspect, amount);
    }

    /**
     * Take essentia for tube transport → export logic (source).
     */
    @Override
    public int takeEssentia(Aspect aspect, int amount, EnumFacing facing) {
        return this.exportLogic.takeEssentiaAmount(aspect, amount);
    }
}
