package com.cells.integration.mekanismenergistics;

import javax.annotation.Nullable;

import net.minecraftforge.common.capabilities.Capability;

import appeng.tile.inventory.AppEngInternalInventory;

import mekanism.common.capabilities.Capabilities;

import com.cells.blocks.interfacebase.AbstractResourceInterfaceLogic;


/**
 * Helper for creating gas interface logics in combined interfaces.
 * This class lives in the MekanismEnergistics integration package to safely reference
 * Mekanism classes without causing ClassNotFoundException when the mod isn't loaded.
 * <p>
 * Only call methods on this class after verifying {@code MekanismEnergisticsIntegration.isModLoaded()}.
 */
public class CombinedInterfaceGasHelper {

    private CombinedInterfaceGasHelper() {}

    /**
     * Create a gas interface logic with its own upgrade inventory.
     */
    public static GasInterfaceLogic createGasLogic(AbstractResourceInterfaceLogic.Host host) {
        return new GasInterfaceLogic(host);
    }

    /**
     * Create a gas interface logic sharing an existing upgrade inventory.
     */
    public static GasInterfaceLogic createGasLogic(AbstractResourceInterfaceLogic.Host host,
                                                    AppEngInternalInventory sharedUpgradeInventory) {
        return new GasInterfaceLogic(host, sharedUpgradeInventory);
    }

    /**
     * Check if the given capability is the gas handler capability.
     */
    public static boolean hasGasCapability(Capability<?> capability) {
        return capability == Capabilities.GAS_HANDLER_CAPABILITY;
    }

    /**
     * Get the gas handler capability from the given gas logic.
     *
     * @return The capability cast to T, or null if the capability doesn't match
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> T getGasCapability(GasInterfaceLogic gasLogic, Capability<T> capability) {
        if (capability == Capabilities.GAS_HANDLER_CAPABILITY) {
            return (T) gasLogic.getExternalHandler();
        }
        return null;
    }
}
