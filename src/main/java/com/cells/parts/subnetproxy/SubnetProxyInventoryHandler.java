package com.cells.parts.subnetproxy;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;


/**
 * Filtered, read-only passthrough handler for the Subnet Proxy.
 * <p>
 * Exposes a filtered, extract-only view of Grid A's storage to Grid B.
 * Items not matching the filter are invisible and cannot be extracted.
 * Injection is always rejected (read-only).
 * <p>
 * <b>Anti-loop design:</b> To prevent subnet loops, listing
 * ({@link #getAvailableItems}) reads only from <em>local cell handlers</em>
 * collected by the front part. These are cell arrays from Grid A's providers
 * that do NOT have a {@code STORAGE_MONITORABLE_ACCESSOR}-based passthrough
 * target (i.e. drives, ME chests, and storage buses pointing at vanilla
 * inventories), but NOT storage buses pointing at ME Interfaces
 * (which would create passthrough to another grid).
 * <p>
 * Extraction ({@link #extractItems}) uses Grid A's full {@link IMEMonitor}
 * so the grid receives proper notifications when items are removed.
 *
 * @param <T> The AE stack type (IAEItemStack, IAEFluidStack, etc.)
 */
public class SubnetProxyInventoryHandler<T extends IAEStack<T>> implements IMEInventoryHandler<T> {

    private final IStorageChannel<T> channel;
    private final int priority;

    /**
     * Cell handlers from Grid A's non-passthrough providers (drives, chests,
     * storage buses on local inventories). Used for listing only, to prevent
     * subnet loop inflation. Rebuilt on grid events by the front part.
     */
    private List<IMEInventoryHandler<T>> localCells = Collections.emptyList();

    /**
     * The full network monitor from Grid A. Used for extraction only,
     * so the grid receives proper notifications when items are removed.
     * May be null if Grid A is unavailable.
     */
    private IMEMonitor<T> monitor;

    /**
     * Filter predicate: returns true if the given stack should pass through.
     * Null means "allow all" (no filter configured).
     */
    private Predicate<T> filter;

    /**
     * Previous snapshot of this handler's listing, used for delta computation.
     * Managed by the front part's delta-forwarding logic. Null before first snapshot.
     */
    private IItemList<T> lastSnapshot;

    /**
     * Grid A's monitor this handler's listener is registered on, for unregistration.
     * Only used by gas/essentia helpers; item/fluid are tracked on the front part.
     */
    private IMEMonitor<T> registeredMonitor;

    public SubnetProxyInventoryHandler(IStorageChannel<T> channel, int priority) {
        this.channel = channel;
        this.priority = priority;
    }

    /**
     * Set the local cell handlers from Grid A's non-passthrough providers.
     * These are used for listing to prevent loop inflation.
     */
    public void setLocalCells(List<IMEInventoryHandler<T>> cells) {
        this.localCells = cells != null ? cells : Collections.emptyList();
    }

    /**
     * Set the full network monitor from Grid A.
     * Used for extraction (so the grid gets proper notifications).
     */
    public void setMonitor(IMEMonitor<T> monitor) {
        this.monitor = monitor;
    }

    /** Clear all sources (Grid A unavailable). */
    public void clearSources() {
        this.localCells = Collections.emptyList();
        this.monitor = null;
    }

    public void setFilter(java.util.function.Predicate<T> filter) {
        this.filter = filter;
    }

    // ========================= Snapshot / Monitor for Delta Forwarding =========================

    public IItemList<T> getLastSnapshot() {
        return this.lastSnapshot;
    }

    public void setLastSnapshot(IItemList<T> snapshot) {
        this.lastSnapshot = snapshot;
    }

    public IMEMonitor<T> getRegisteredMonitor() {
        return this.registeredMonitor;
    }

    public void setRegisteredMonitor(IMEMonitor<T> monitor) {
        this.registeredMonitor = monitor;
    }

    /**
     * Quick scan to check if any item in the given iterable passes this handler's filter.
     * Used by the Grid A listener to avoid unnecessary wake-ups for non-matching changes.
     *
     * @return true if at least one item in {@code changes} matches the filter (or no filter is set)
     */
    public boolean matchesAny(Iterable<T> changes) {
        if (this.filter == null) return true;

        for (T change : changes) {
            if (this.filter.test(change)) return true;
        }

        return false;
    }

    // ========================= IMEInventory =========================

    @Override
    public T injectItems(final T input, final Actionable type, final IActionSource src) {
        // Read-only: reject all injections by returning the full input
        return input;
    }

    @Override
    public T extractItems(final T request, final Actionable type, final IActionSource src) {
        // Extraction goes through the full monitor for proper grid notifications
        if (this.monitor == null) return null;
        if (this.filter != null && !this.filter.test(request)) return null;

        return this.monitor.extractItems(request, type, src);
    }

    @Override
    public IItemList<T> getAvailableItems(final IItemList<T> out) {
        // List from local cells only (not the full monitor) to prevent subnet
        // loop inflation. Items that Grid A sees through passthrough storage buses
        // (storage bus -> ME Interface) are NOT listed here, which breaks feedback
        // loops like A->B->proxy->A.
        if (this.filter == null) {
            // No filter: write directly into output list, zero extra allocations
            for (IMEInventoryHandler<T> cell : this.localCells) {
                cell.getAvailableItems(out);
            }
        } else {
            // Filtered: need a temporary list per cell to apply the predicate,
            // since cells write additively into the output and we can't undo.
            for (IMEInventoryHandler<T> cell : this.localCells) {
                IItemList<T> cellItems = this.channel.createList();
                cell.getAvailableItems(cellItems);

                for (T item : cellItems) {
                    if (this.filter.test(item)) out.add(item);
                }
            }
        }

        return out;
    }

    @Override
    public IStorageChannel<T> getChannel() {
        return this.channel;
    }

    // ========================= IMEInventoryHandler =========================

    @Override
    public AccessRestriction getAccess() {
        // Read-only: only extraction is allowed
        return AccessRestriction.READ;
    }

    @Override
    public boolean isPrioritized(final T input) {
        return false;
    }

    @Override
    public boolean canAccept(final T input) {
        // No injection allowed
        return false;
    }

    @Override
    public int getPriority() {
        return this.priority;
    }

    @Override
    public int getSlot() {
        return 0;
    }

    @Override
    public boolean validForPass(final int i) {
        // Passes only matter for injection routing in NetworkInventoryHandler
        // (pass 1 = prioritized/filtered, pass 2 = non-prioritized fallback).
        // Since this proxy is read-only (canAccept returns false, getAccess returns READ),
        // injection never reaches us, so the pass value is irrelevant.
        return true;
    }

    // ========================= Utility =========================

    /**
     * Wrap this handler in a singleton list for ICellProvider.getCellArray().
     * Returns empty list if no sources are available (Grid A unavailable).
     */
    @SuppressWarnings("rawtypes")
    public List<IMEInventoryHandler> asCellArray() {
        if (this.localCells.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(this);
    }
}
