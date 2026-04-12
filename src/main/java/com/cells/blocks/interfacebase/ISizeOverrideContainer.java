package com.cells.blocks.interfacebase;


/**
 * Interface for containers that support per-slot size overrides.
 * Implemented by AbstractContainerInterface and ContainerCombinedInterface
 * to receive sync packets from the server.
 */
public interface ISizeOverrideContainer {

    /**
     * Receive a per-slot size override sync from the server.
     * Called on the client side when the server sends a sync packet.
     *
     * @param slot The slot index
     * @param size The override size, or -1 to clear
     */
    void receiveMaxSlotSizeOverridesync(int slot, long size);

    /**
     * Get the effective size for a specific slot.
     * Returns the per-slot override if set, otherwise the global maxSlotSize.
     */
    long getEffectiveMaxSlotSize(int slot);

    /**
     * Get the per-slot override, or -1 if no override is set.
     */
    long getSlotSizeOverride(int slot);
}
