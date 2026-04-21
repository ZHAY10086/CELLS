package com.cells.parts.subnetproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.ICellProvider;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IItemList;
import appeng.tile.inventory.AppEngInternalInventory;

import thaumicenergistics.api.storage.IAEEssentiaStack;
import thaumicenergistics.api.storage.IEssentiaStorageChannel;
import thaumicenergistics.item.ItemDummyAspect;

import thaumcraft.api.aspects.Aspect;

import com.cells.integration.thaumicenergistics.EssentiaStackKey;

import net.minecraft.item.ItemStack;


/**
 * Isolates all ThaumicEnergistics class references for the Subnet Proxy.
 * <p>
 * This class is only loaded when "thaumicenergistics" is present, preventing
 * {@link ClassNotFoundException} when the mod is absent. All calls
 * must be guarded by {@code ThaumicEnergisticsIntegration.isModLoaded()}.
 */
final class SubnetProxyEssentiaHelper {

    private SubnetProxyEssentiaHelper() {}

    /** Get the essentia storage channel from AE2. */
    static IStorageChannel<IAEEssentiaStack> getChannel() {
        return AEApi.instance().storage().getStorageChannel(IEssentiaStorageChannel.class);
    }

    /** Create a new SubnetProxyInventoryHandler for essentia. */
    static SubnetProxyInventoryHandler<IAEEssentiaStack> createHandler(int priority) {
        return new SubnetProxyInventoryHandler<>(getChannel(), priority);
    }

    /** Create a new SubnetProxyInsertionHandler for essentia (front-grid &rarr; back-grid). */
    static SubnetProxyInsertionHandler<IAEEssentiaStack> createInsertionHandler() {
        return new SubnetProxyInsertionHandler<>(getChannel());
    }

    /** True if the given AE2 storage channel is the essentia channel. */
    static boolean matchesChannel(IStorageChannel<?> ch) {
        return ch == getChannel();
    }

    /** Wire the back-grid essentia monitor + read-side filter into the insertion handler. */
    static void wireInsertionHandler(
            SubnetProxyInsertionHandler<IAEEssentiaStack> insertion,
            SubnetProxyInventoryHandler<IAEEssentiaStack> read,
            IStorageGrid backStorage,
            int priority) {
        insertion.setMonitor(backStorage.getInventory(getChannel()));
        insertion.setFilter(read.getFilter());
        insertion.setPriority(priority);
    }

    /**
     * Collect local cell handlers from Grid A for the essentia channel,
     * and update the handler's sources from the grid.
     */
    @SuppressWarnings("unchecked")
    static void updateSources(
            SubnetProxyInventoryHandler<IAEEssentiaStack> handler,
            IGrid gridA,
            IStorageGrid sg) {

        IStorageChannel<IAEEssentiaStack> essentiaChannel = getChannel();
        List<IMEInventoryHandler<IAEEssentiaStack>> localEssentiaCells = new ArrayList<>();

        for (IGridNode node : gridA.getNodes()) {
            IGridHost host = node.getMachine();
            if (!(host instanceof ICellProvider)) continue;
            if (host instanceof PartSubnetProxyFront) continue;
            if (PartSubnetProxyFront.isPassthroughBusStatic(host)) continue;

            ICellProvider provider = (ICellProvider) host;
            for (IMEInventoryHandler<?> h : provider.getCellArray(essentiaChannel)) {
                localEssentiaCells.add((IMEInventoryHandler<IAEEssentiaStack>) h);
            }
        }

        handler.setLocalCells(localEssentiaCells);
    }

    /**
     * Return a singleton cell array list for getCellArray.
     */
    @SuppressWarnings("rawtypes")
    static List<IMEInventoryHandler> asCellArray(
            SubnetProxyInventoryHandler<IAEEssentiaStack> handler,
            IStorageChannel<?> requestedChannel) {

        if (requestedChannel == getChannel()) {
            return handler.asCellArray();
        }
        return Collections.emptyList();
    }

    /**
     * Check if a config slot ItemStack is an essentia dummy item.
     */
    static boolean isEssentiaDummy(ItemStack stack) {
        return stack.getItem() instanceof ItemDummyAspect;
    }

    /**
     * Extract an EssentiaStackKey from an essentia dummy ItemStack.
     */
    static EssentiaStackKey extractEssentiaKey(ItemStack stack) {
        Aspect aspect = ((ItemDummyAspect) stack.getItem()).getAspect(stack);
        return EssentiaStackKey.of(aspect);
    }

    /**
     * Build a filter predicate for essentia stacks.
     * Returns null if no filter needed (empty set + whitelist).
     */
    static Predicate<IAEEssentiaStack> buildFilter(Set<EssentiaStackKey> filterSet, boolean hasInverter) {
        if (filterSet.isEmpty() && !hasInverter) return null;

        return aeStack -> {
            if (aeStack == null) return false;

            // Match by aspect tag
            boolean matchesAny = false;
            for (EssentiaStackKey key : filterSet) {
                if (key.matches(aeStack.getStack())) {
                    matchesAny = true;
                    break;
                }
            }

            return hasInverter != matchesAny;
        };
    }

    /**
     * Scan the config inventory for essentia dummy items, build a filter set,
     * and apply the resulting predicate to the handler.
     * <p>
     * This keeps all EssentiaStackKey references isolated in this class.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void rebuildFilter(SubnetProxyInventoryHandler handler, AppEngInternalInventory config,
                              int totalSlots, boolean hasInverter) {
        Set<EssentiaStackKey> filterSet = new HashSet<>();

        for (int i = 0; i < totalSlots; i++) {
            ItemStack stack = config.getStackInSlot(i);
            if (stack.isEmpty() || !isEssentiaDummy(stack)) continue;

            EssentiaStackKey key = extractEssentiaKey(stack);
            if (key != null) filterSet.add(key);
        }

        handler.setFilter(buildFilter(filterSet, hasInverter));
    }

    // ========================= Delta Forwarding =========================

    /** Register the raw listener on Grid A's essentia monitor. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void registerListener(SubnetProxyInventoryHandler<IAEEssentiaStack> handler,
                                 IStorageGrid sg, IMEMonitorHandlerReceiver listener, Object token) {
        IMEMonitor<IAEEssentiaStack> monitor = sg.getInventory(getChannel());
        if (monitor != null) {
            handler.setRegisteredMonitor(monitor);
            monitor.addListener((IMEMonitorHandlerReceiver) listener, token);
        }
    }

    /** Take a baseline snapshot for delta comparison. */
    static void takeSnapshot(SubnetProxyInventoryHandler<IAEEssentiaStack> handler) {
        IItemList<IAEEssentiaStack> snapshot = getChannel().createList();
        handler.getAvailableItems(snapshot);
        handler.setLastSnapshot(snapshot);
    }

    /** Reset the snapshot (Grid A unavailable). */
    static void resetSnapshot(SubnetProxyInventoryHandler<IAEEssentiaStack> handler) {
        if (handler.getRegisteredMonitor() != null) handler.setRegisteredMonitor(null);
        handler.setLastSnapshot(null);
    }

    /**
     * Filter incoming essentia deltas through the handler's predicate and forward
     * matching entries to Grid B. Also updates the snapshot incrementally.
     */
    @SuppressWarnings("rawtypes")
    static void forwardFilteredDeltas(Iterable changes,
                                      SubnetProxyInventoryHandler<IAEEssentiaStack> handler,
                                      IStorageGrid gridB, IActionSource proxySource) {
        Predicate<IAEEssentiaStack> filter = handler.getFilter();
        List<IAEEssentiaStack> forwarded = new ArrayList<>();

        for (Object raw : changes) {
            IAEEssentiaStack change = (IAEEssentiaStack) raw;
            if (filter == null || filter.test(change)) {
                forwarded.add(change);
            }
        }

        if (forwarded.isEmpty()) return;

        gridB.postAlterationOfStoredItems(getChannel(), forwarded, proxySource);

        // Update snapshot incrementally
        IItemList<IAEEssentiaStack> snapshot = handler.getLastSnapshot();
        if (snapshot == null) {
            snapshot = getChannel().createList();
            handler.setLastSnapshot(snapshot);
        }
        for (IAEEssentiaStack delta : forwarded) {
            snapshot.add(delta);
        }
    }

    /** Snapshot-based delta forwarding fallback for onListUpdate. */
    static void snapshotDiffAndForward(SubnetProxyInventoryHandler<IAEEssentiaStack> handler,
                                       IStorageGrid gridB, IActionSource proxySource) {
        IStorageChannel<IAEEssentiaStack> channel = getChannel();
        IItemList<IAEEssentiaStack> previous = handler.getLastSnapshot();
        IItemList<IAEEssentiaStack> current = channel.createList();
        handler.getAvailableItems(current);

        if (previous == null) {
            handler.setLastSnapshot(current);
            return;
        }

        List<IAEEssentiaStack> changes = new ArrayList<>();
        for (IAEEssentiaStack was : previous) was.setStackSize(-was.getStackSize());
        for (IAEEssentiaStack now : current) previous.add(now);

        for (IAEEssentiaStack entry : previous) {
            if (entry.getStackSize() != 0) changes.add(entry);
        }

        if (!changes.isEmpty()) {
            gridB.postAlterationOfStoredItems(channel, changes, proxySource);
        }

        handler.setLastSnapshot(current);
    }
}
