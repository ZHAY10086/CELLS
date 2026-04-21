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
 * Write-only insertion handler for the Subnet Proxy's Insertion Card feature.
 * <p>
 * Exposed on the FRONT-grid (alongside the read-side filter handler) when an
 * Insertion Card is installed. Forwards injections to the BACK-grid's storage
 * monitor, implementing the reverse direction from the read view: items on the
 * front-grid that match the proxy's filter and are routed here will be injected
 * into the back-grid's storage.
 * <p>
 * Symmetric setup with the read side: e.g. a {@code B → A} proxy (back on B,
 * front on A) exposes "what B produces" to A for reading; with an insertion
 * card, A can also push matching items back into B through this handler.
 * <p>
 * This handler is strictly write-only:
 * <ul>
 *   <li>{@link #injectItems} forwards to the back-grid's monitor</li>
 *   <li>{@link #extractItems} always rejects (returns null)</li>
 *   <li>{@link #getAvailableItems} returns nothing (empty listing)</li>
 *   <li>{@link #getAccess} returns {@link AccessRestriction#WRITE}</li>
 * </ul>
 *
 * @param <T> The AE stack type (IAEItemStack, IAEFluidStack, etc.)
 */
public class SubnetProxyInsertionHandler<T extends IAEStack<T>> implements IMEInventoryHandler<T> {

    private final IStorageChannel<T> channel;

    /**
     * Grid B's full network monitor. Used for injection so the grid
     * receives proper notifications when items are added.
     * May be null if Grid B is unavailable.
     */
    private IMEMonitor<T> monitor;

    /**
     * Filter predicate from the front part: returns true if the given stack
     * should be accepted for insertion. Null means "allow all".
     */
    private Predicate<T> filter;

    /**
     * Priority for insertion routing. Higher priority means AE2 will prefer
     * inserting into this handler over lower-priority storage.
     * Sourced from the front part's priority field.
     */
    private int priority;

    public SubnetProxyInsertionHandler(IStorageChannel<T> channel) {
        this.channel = channel;
    }

    /**
     * Global per-thread depth counter: tracks how many
     * {@link SubnetProxyInsertionHandler} invocations are currently on the
     * call stack, across ALL handler instances on this thread.
     * <p>
     * Strict 1-hop rule: an insertion that has already traversed one proxy
     * (depth > 0) MUST NOT be forwarded through another proxy. This prevents
     * both direct cycles (A→B / B→A both insertion-carded) and multi-hop
     * chains (A→B / B→C both insertion-carded, A's insert reaches B but
     * does NOT propagate to C).
     * <p>
     * Granularity is deliberately global (all handler instances share the
     * counter) rather than per-handler.
     */
    private static final ThreadLocal<int[]> INSERT_DEPTH =
        ThreadLocal.withInitial(() -> new int[1]);

    public void setMonitor(IMEMonitor<T> monitor) {
        this.monitor = monitor;
    }

    public void setFilter(Predicate<T> filter) {
        this.filter = filter;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    /** Clear the monitor reference (Grid B unavailable). */
    public void clearMonitor() {
        this.monitor = null;
    }

    // ========================= IMEInventory =========================

    @Override
    public T injectItems(final T input, final Actionable type, final IActionSource src) {
        if (this.monitor == null) return input;
        if (this.filter != null && !this.filter.test(input)) return input;

        // Strict 1-hop: if ANY SubnetProxyInsertionHandler is already on this
        // thread's call stack, reject the insertion. Forwarded insertions stop
        // at the first proxy; they are never chained through a second one.
        // This also covers direct A→B→A cycles as the degenerate case.
        int[] depth = INSERT_DEPTH.get();
        if (depth[0] > 0) return input;

        depth[0]++;
        try {
            return this.monitor.injectItems(input, type, src);
        } finally {
            depth[0]--;
        }
    }

    @Override
    public T extractItems(final T request, final Actionable type, final IActionSource src) {
        // Write-only: no extraction allowed
        return null;
    }

    @Override
    public IItemList<T> getAvailableItems(final IItemList<T> out) {
        // Write-only: nothing to list (Grid A should not see Grid B's items)
        return out;
    }

    @Override
    public IStorageChannel<T> getChannel() {
        return this.channel;
    }

    // ========================= IMEInventoryHandler =========================

    @Override
    public AccessRestriction getAccess() {
        return AccessRestriction.WRITE;
    }

    @Override
    public boolean isPrioritized(final T input) {
        // Items matching the filter are prioritized for insertion.
        // This makes AE2 prefer this handler over non-prioritized storage
        // at the same priority level.
        if (this.filter == null) return false;

        return this.filter.test(input);
    }

    @Override
    public boolean canAccept(final T input) {
        if (this.monitor == null) return false;
        if (this.filter != null && !this.filter.test(input)) return false;

        return true;
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
        // Pass 1 = prioritized/filtered items, pass 2 = fallback.
        // If we have a filter, only accept on pass 1 (prioritized).
        // If no filter, accept on pass 2 (general fallback).
        if (this.filter != null) return i == 1;

        return i == 2;
    }

    // ========================= Utility =========================

    /**
     * Wrap this handler in a singleton list for ICellContainer.getCellArray().
     * Returns empty list if no monitor is available (Grid B unavailable).
     */
    @SuppressWarnings("rawtypes")
    public List<IMEInventoryHandler> asCellArray() {
        if (this.monitor == null) return Collections.emptyList();

        return Collections.singletonList(this);
    }
}
