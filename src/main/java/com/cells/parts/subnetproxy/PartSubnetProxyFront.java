package com.cells.parts.subnetproxy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import net.minecraftforge.items.IItemHandler;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.config.Upgrades;
import appeng.api.config.Actionable;
import appeng.api.implementations.IPowerChannelState;
import appeng.api.implementations.items.IUpgradeModule;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartModel;
import appeng.api.parts.IPart;
import appeng.api.storage.ICellContainer;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellProvider;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.api.util.AECableType;
import appeng.api.util.AEPartLocation;
import appeng.fluids.items.FluidDummyItem;
import appeng.fluids.util.AEFluidStack;
import appeng.core.sync.GuiBridge;
import appeng.helpers.IPriorityHost;
import appeng.items.parts.PartModels;
import appeng.capabilities.Capabilities;
import appeng.me.GridAccessException;
import appeng.me.helpers.MachineSource;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;
import appeng.parts.automation.UpgradeInventory;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.util.inv.IAEAppEngInventory;
import appeng.util.inv.InvOperation;
import appeng.util.inv.filter.IAEItemFilter;
import appeng.util.item.AEItemStack;

import com.cells.Tags;
import com.cells.config.CellsConfig;
import com.cells.gui.CellsGuiHandler;
import com.cells.integration.mekanismenergistics.MekanismEnergisticsIntegration;
import com.cells.integration.thaumicenergistics.ThaumicEnergisticsIntegration;
import com.cells.items.ItemInsertionCard;
import com.cells.network.sync.ResourceType;
import com.cells.parts.CellsPartType;
import com.cells.parts.ItemCellsPart;


/**
 * Front half of the Subnet Proxy block.
 * <p>
 * Connects to the "front" cable (Grid B) via an outer proxy, and exposes
 * a filtered, read-only view of Grid A's storage (via the back part) to Grid B.
 * <p>
 * Implements {@link ICellContainer} so Grid B automatically discovers
 * the passthrough storage. Since the inner proxy is orphaned (no center cable),
 * only Grid B will see this ICellContainer, no double registration.
 * <p>
 * The filter configuration, upgrades, page state, and filter mode are all
 * managed here and persisted in NBT.
 */
public class PartSubnetProxyFront extends AEBasePart
        implements IPowerChannelState, ICellContainer, IAEAppEngInventory, IGridTickable, IPriorityHost {

    // LED state flags
    protected static final int POWERED_FLAG = 1;
    protected static final int CHANNEL_FLAG = 2;
    protected static final int BOTH_PARTS_FLAG = 4;

    @PartModels
    public static final ResourceLocation MODEL_BASE = new ResourceLocation(Tags.MODID, "part/subnet_proxy_front/base");
    @PartModels
    public static final ResourceLocation MODEL_STATUS_OFF = new ResourceLocation(Tags.MODID, "part/subnet_proxy_front/status_off");
    @PartModels
    public static final ResourceLocation MODEL_STATUS_ON = new ResourceLocation(Tags.MODID, "part/subnet_proxy_front/status_on");
    @PartModels
    public static final ResourceLocation MODEL_STATUS_HAS_CHANNEL = new ResourceLocation(Tags.MODID, "part/subnet_proxy_front/status_active");

    public static final IPartModel MODELS_OFF = new PartModel(MODEL_BASE, MODEL_STATUS_OFF);
    public static final IPartModel MODELS_ON = new PartModel(MODEL_BASE, MODEL_STATUS_ON);
    public static final IPartModel MODELS_HAS_CHANNEL = new PartModel(MODEL_BASE, MODEL_STATUS_HAS_CHANNEL);

    /** Slots per page of filter config: 9 columns × 7 rows */
    public static final int SLOTS_PER_PAGE = 63;

    /**
     * Filter config inventory. Total size = SLOTS_PER_PAGE * maxPages.
     * Items represent filters: plain ItemStacks for item filters,
     * FluidDummyItem stacks for fluid filters.
     */
    private AppEngInternalInventory config;

    /**
     * Upgrade inventory. Slot count comes from config.
     * Supports: Capacity (adds pages), Fuzzy (fuzzy matching), Inverter (blacklist).
     */
    private final UpgradeInventory upgrades;

    /** Current page index (0-based). Persisted in NBT, synced to GUI. */
    private int currentPage = 0;

    /**
     * Filter mode: determines how items dragged into filter slots are converted.
     * ITEM = store as ItemStack, FLUID = convert to FluidDummyItem, etc.
     * Persisted in NBT, synced to GUI.
     */
    private ResourceType filterMode = ResourceType.ITEM;

    /**
     * Fuzzy mode for item matching when a fuzzy card is installed.
     * Persisted in NBT, synced to GUI.
     */
    private FuzzyMode fuzzyMode = FuzzyMode.IGNORE_ALL;

    /** Client-side LED state */
    private int clientFlags = 0;

    /** Cached item-channel passthrough handler */
    private SubnetProxyInventoryHandler<IAEItemStack> itemHandler;

    /** Cached fluid-channel passthrough handler */
    private SubnetProxyInventoryHandler<IAEFluidStack> fluidHandler;

    // ========================= Insertion Handlers (front-grid → back-grid) =========================

    /**
     * Item insertion handler exposed on the FRONT-grid when an Insertion Card is
     * installed. Forwards matching injections to the BACK-grid's storage monitor,
     * implementing the reverse direction from the read view.
     * <p>
     * Filter is shared with {@link #itemHandler} (read-side filter): items the
     * proxy exposes from back-grid for reading are also the items it accepts for
     * insertion in the opposite direction.
     */
    private final SubnetProxyInsertionHandler<IAEItemStack> itemInsertionHandler;

    /** Fluid insertion handler. See {@link #itemInsertionHandler}. */
    private final SubnetProxyInsertionHandler<IAEFluidStack> fluidInsertionHandler;

    /**
     * Gas insertion handler (raw type to avoid loading Mekanism classes when
     * mekeng is absent). Allocated only when MekanismEnergistics is loaded.
     * See {@link #itemInsertionHandler}.
     */
    @SuppressWarnings("rawtypes")
    private SubnetProxyInsertionHandler gasInsertionHandler;

    /**
     * Essentia insertion handler (raw type to avoid loading TE classes when
     * thaumicenergistics is absent). Allocated only when ThaumicEnergistics
     * is loaded. See {@link #itemInsertionHandler}.
     */
    @SuppressWarnings("rawtypes")
    private SubnetProxyInsertionHandler essentiaInsertionHandler;

    /**
     * Cached: whether the insertion card was present at the last
     * {@link #updateInsertionHandlers()} call. Used by {@link #getCellArray}
     * to decide whether to include the insertion handlers without re-scanning
     * upgrade slots on every grid query.
     */
    private boolean insertionActive = false;

    /**
     * Cached gas-channel passthrough handler (null if MekanismEnergistics not loaded).
     * Typed as raw to avoid class loading; the actual generic type is IAEGasStack.
     */
    @SuppressWarnings("rawtypes")
    private SubnetProxyInventoryHandler gasHandler;

    /**
     * Cached essentia-channel passthrough handler (null if ThaumicEnergistics not loaded).
     * Typed as raw to avoid class loading; the actual generic type is IAEEssentiaStack.
     */
    @SuppressWarnings("rawtypes")
    private SubnetProxyInventoryHandler essentiaHandler;

    /** Priority for the passthrough storage (default 0) */
    private int priority = 0;

    /** Debounce for markDirtyAndSave */
    private long lastSaveTick = -1;

    // ========================= Delta Forwarding State =========================

    /**
     * Action source identifying this proxy as the originator of delta events
     * posted to Grid B. Using a dedicated source prevents the anti-recursion
     * guard in NetworkMonitor from confusing proxy-forwarded deltas with
     * deltas from other machines.
     * <p>
     * Used only for non-event paths (e.g. legacy gas/essentia helpers).
     * Cross-hub delta forwarding wraps each event individually with
     * {@link SubnetProxyEventSource} so origin-grid and event-UUID metadata
     * survive the chain.
     */
    private final IActionSource proxySource = new MachineSource(this);

    // ========================= Peer aggregation / coordinator state =========================

    /**
     * Other {@link PartSubnetProxyFront} instances on our back-grid (NOT on
     * our front-grid). Each one's back-grid is a candidate "peer origin" we
     * may publish into our front-grid (subject to election in the front-grid
     * coordinator). Recomputed in {@link #updatePassthroughSources}.
     * <p>
     * Self is excluded. Peers whose back-grid equals our front-grid are kept
     * in this list but skipped at listing/forwarding time as immediate loop-backs.
     */
    private List<PartSubnetProxyFront> peerFronts = new ArrayList<>();

    /**
     * Origin-grids this front can publish on its front-grid: own back-grid +
     * each peer's back-grid. Used by the front-grid coordinator's election
     * (which origins this front competes for) and by {@link GridAListener}
     * (whether to forward an incoming delta whose origin we know).
     * <p>
     * Always includes our own back-grid (when set). Recomputed in
     * {@link #updatePassthroughSources}.
     */
    private Set<IGrid> exposedOrigins = new HashSet<>();

    /**
     * The {@link SubnetProxyGridCoordinator} currently registered for our
     * front-grid, or null if not registered (front-grid unavailable).
     * Tracked so we can unregister deterministically when the front-grid
     * changes or the part is removed from the world.
     */
    private SubnetProxyGridCoordinator currentFrontGridCoord;

    /**
     * Listener registered on Grid A's IMEMonitors (all channels).
     * Typed as raw to handle multiple storage channels with a single instance.
     * Sets {@link #deltasDirty} and alerts Grid B's tick manager on change.
     */
    private final GridAListener gridAListener = new GridAListener();

    /**
     * Stable token for {@link IMEMonitorHandlerReceiver#isValid}.
     * Invalidated on removal from world; recreated on next listener registration.
     */
    private Object listenerToken = new Object();

    /**
     * Grid A's item monitor we are currently registered on, or null.
     * Stored for unregistration when sources change or part is removed.
     */
    @SuppressWarnings("rawtypes")
    private IMEMonitor registeredItemMonitor;

    /** Grid A's fluid monitor we are currently registered on, or null. */
    @SuppressWarnings("rawtypes")
    private IMEMonitor registeredFluidMonitor;

    /**
     * Grid A reference, stored when listeners are registered.
     * Used by {@link #isLocalSource} to distinguish local cell changes from
     * passthrough-forwarded changes (storage bus → ME Interface → remote grid).
     */
    private IGrid gridA;

    /**
     * Dirty flag for snapshot-based delta forwarding. Set only by
     * {@link GridAListener#onListUpdate()} (full monitor reset, e.g. power
     * loss/restore). Normal per-item deltas are forwarded immediately in
     * {@link GridAListener#postChange} and do NOT set this flag.
     * Cleared after the snapshot diff runs in {@link #tickingRequest}.
     */
    private boolean deltasDirty = false;

    /**
     * Dirty flag for passthrough sources (local cells from Grid A).
     * Set by the back part when Grid A fires MENetworkCellArrayUpdate,
     * and on initial placement. Cleared after updatePassthroughSources() runs.
     */
    private boolean sourcesDirty = true;

    /**
     * Dirty flag for filter predicates.
     * Set when config inventory or upgrades change. Cleared after rebuildFilters() runs.
     */
    private boolean filtersDirty = true;

    /** Cached inverter state at time of last filter rebuild */
    private boolean cachedHasInverter = false;

    /** Cached fuzzy state at time of last filter rebuild */
    private boolean cachedHasFuzzy = false;

    /**
     * Cached server-side flag: whether the back counterpart exists in the adjacent block.
     * Updated on neighbor changes and placement, used in writeToStream to avoid
     * a tile entity lookup every tick.
     */
    private boolean cachedHasBack = false;

    /**
     * World tick when this part was placed. Used to suppress spurious
     * activation caused by off-hand fall-through on the same tick as
     * placement (AE2's client-side PartPlacement returns PASS, so
     * Minecraft tries the off-hand which triggers onPartActivate).
     */
    private long placedTick = -1;

    public PartSubnetProxyFront(final ItemStack is) {
        super(is);

        // Inner proxy connects to Grid B (the cable bus this part sits on).
        // Grid B discovers this ICellContainer via the inner proxy node.
        this.getProxy().setFlags(GridFlags.REQUIRE_CHANNEL);
        this.getProxy().setIdlePowerUsage(1.0);

        // Upgrade inventory with configurable slot count
        int upgradeSlots = CellsConfig.subnetProxyUpgradeSlots;
        this.upgrades = new UpgradeInventory(this, upgradeSlots) {
            @Override
            public int getMaxInstalled(Upgrades u) {
                // We only limit capacity cards by the number of slots
                if (u == Upgrades.CAPACITY) return Integer.MAX_VALUE;
                if (u == Upgrades.FUZZY) return 1;
                if (u == Upgrades.INVERTER) return 1;
                // QUANTUM_LINK is the placeholder type for custom CELLS upgrades.
                // We allow 1 for the Insertion Card. The filter below restricts
                // which specific item classes are accepted.
                if (u == Upgrades.QUANTUM_LINK) return 1;
                return 0;
            }
        };

        // Restrict the QUANTUM_LINK upgrade slot to only accept InsertionCard,
        // preventing other custom CELLS upgrades from being placed here.
        this.upgrades.setFilter(new IAEItemFilter() {
            @Override
            public boolean allowExtract(IItemHandler inv, int slot, int amount) {
                return true;
            }

            @Override
            public boolean allowInsert(IItemHandler inv, int slot, ItemStack itemstack) {
                if (itemstack.isEmpty()) return false;
                if (!(itemstack.getItem() instanceof IUpgradeModule)) return false;

                Upgrades u = ((IUpgradeModule) itemstack.getItem()).getType(itemstack);
                if (u == null) return false;

                // Only allow InsertionCard for the custom upgrade slot
                if (u == Upgrades.QUANTUM_LINK && !(itemstack.getItem() instanceof ItemInsertionCard)) {
                    return false;
                }

                return upgrades.getInstalledUpgrades(u) < upgrades.getMaxInstalled(u);
            }
        });

        // Allocate config inventory for all pages. At max upgrades this can be 63 * (1 + upgradeSlots)
        // slots in memory, but NBT serialization is sparse (only non-empty slots written).
        int totalSlots = SLOTS_PER_PAGE * getMaxPages();
        this.config = new AppEngInternalInventory(this, totalSlots, 1);

        // Create passthrough handlers
        this.itemHandler = new SubnetProxyInventoryHandler<>(
            AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class), this.priority);
        this.fluidHandler = new SubnetProxyInventoryHandler<>(
            AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class), this.priority);

        // Wire owning front part so handlers can fetch peer items via
        // appendPeerItemsForListing during getAvailableItems().
        this.itemHandler.setFrontPart(this);
        this.fluidHandler.setFrontPart(this);

        // Create insertion handlers (always allocated; activated only when card is installed)
        this.itemInsertionHandler = new SubnetProxyInsertionHandler<>(
            AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class));
        this.fluidInsertionHandler = new SubnetProxyInsertionHandler<>(
            AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class));

        // Optional mod handlers (created only if the mod is loaded to avoid class loading errors)
        if (MekanismEnergisticsIntegration.isModLoaded()) {
            this.gasHandler = SubnetProxyGasHelper.createHandler(this.priority);
            this.gasHandler.setFrontPart(this);
            this.gasInsertionHandler = SubnetProxyGasHelper.createInsertionHandler();
        }
        if (ThaumicEnergisticsIntegration.isModLoaded()) {
            this.essentiaHandler = SubnetProxyEssentiaHelper.createHandler(this.priority);
            this.essentiaHandler.setFrontPart(this);
            this.essentiaInsertionHandler = SubnetProxyEssentiaHelper.createInsertionHandler();
        }
    }

    // ========================= Page Management =========================

    /** Maximum pages = 1 (base) + max capacity cards */
    public int getMaxPages() {
        return 1 + CellsConfig.subnetProxyUpgradeSlots;
    }

    /** Actual pages available = 1 + installed capacity cards */
    public int getTotalPages() {
        return 1 + this.upgrades.getInstalledUpgrades(Upgrades.CAPACITY);
    }

    public int getCurrentPage() {
        return this.currentPage;
    }

    public void setCurrentPage(int page) {
        this.currentPage = Math.max(0, Math.min(page, getTotalPages() - 1));
    }

    // ========================= Filter Mode =========================

    public ResourceType getFilterMode() {
        return this.filterMode;
    }

    public void setFilterMode(ResourceType mode) {
        this.filterMode = mode;
        this.markHostDirty();
    }

    /** Cycle to the next available filter mode (skipping unavailable mods) */
    public void cycleFilterMode() {
        ResourceType[] available = ResourceType.getAvailableTypes();
        int idx = 0;
        for (int i = 0; i < available.length; i++) {
            if (available[i] == this.filterMode) {
                idx = i;
                break;
            }
        }

        idx = (idx + 1) % available.length;
        setFilterMode(available[idx]);
    }

    // ========================= Fuzzy Mode =========================

    public FuzzyMode getFuzzyMode() {
        return this.fuzzyMode;
    }

    public void setFuzzyMode(FuzzyMode mode) {
        this.fuzzyMode = mode;
        this.filtersDirty = true;
        this.notifyGridOfChange();
        this.markHostDirty();
    }

    // ========================= Config & Upgrade Access =========================

    public AppEngInternalInventory getConfigInventory() {
        return this.config;
    }

    public UpgradeInventory getUpgradeInventory() {
        return this.upgrades;
    }

    public int getInstalledUpgrades(Upgrades u) {
        return this.upgrades.getInstalledUpgrades(u);
    }

    /**
     * Check if an Insertion Card is installed in the upgrade inventory.
     * Scans upgrade slots for an {@link ItemInsertionCard} instance.
     */
    public boolean hasInsertionCard() {
        for (int i = 0; i < this.upgrades.getSlots(); i++) {
            ItemStack stack = this.upgrades.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() instanceof ItemInsertionCard) return true;
        }

        return false;
    }

    // ========================= IPriorityHost =========================

    @Override
    public void setPriority(int newValue) {
        this.priority = newValue;
        this.markHostDirty();

        // Keep every channel handler's priority in sync with the part. AE2
        // re-sorts cells at the next MENetworkCellArrayUpdate, so we post
        // that event via notifyGridOfChange() below.
        this.itemHandler.setPriority(newValue);
        this.fluidHandler.setPriority(newValue);

        if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
            this.gasHandler.setPriority(newValue);
        }
        if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
            this.essentiaHandler.setPriority(newValue);
        }

        // Insertion handlers also use this priority for AE2 routing.
        if (this.insertionActive) {
            this.itemInsertionHandler.setPriority(newValue);
            this.fluidInsertionHandler.setPriority(newValue);
            if (this.gasInsertionHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
                this.gasInsertionHandler.setPriority(newValue);
            }
            if (this.essentiaInsertionHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
                this.essentiaInsertionHandler.setPriority(newValue);
            }
        }

        // Cell array on front-grid changed (priority is part of the cell identity).
        notifyGridOfChange();
    }

    @Override
    public ItemStack getItemStackRepresentation() {
        return AEApi.instance().definitions().parts().cableAnchor().maybeStack(1).orElse(ItemStack.EMPTY);
    }

    @Override
    public GuiBridge getGuiBridge() {
        // Returning null means the priority GUI won't show a "back" button.
        // The player closes the priority GUI with Escape and re-opens the proxy.
        // This is required because the proxy GUI uses CELLS' own GUI system,
        // not AE2's GuiBridge enum.
        return null;
    }

    @Override
    public IItemHandler getInventoryByName(final String name) {
        if ("config".equals(name)) return this.config;
        if ("upgrades".equals(name)) return this.upgrades;
        return null;
    }

    // ========================= Dual Proxy Lifecycle =========================

    @Override
    public void addToWorld() {
        super.addToWorld();
        this.sourcesDirty = true;
        this.filtersDirty = true;
        this.cachedHasBack = findBackPart() != null;

        // Wire insertion handlers if the card is already installed (NBT-loaded part).
        // Safe to call even if back-grid isn't ready yet; will be a no-op and
        // re-attempted via markSourcesDirty path when back becomes available.
        updateInsertionHandlers();
    }

    @Override
    public void onPlacement(EntityPlayer player, EnumHand hand, ItemStack held, AEPartLocation side) {
        super.onPlacement(player, hand, held, side);
        TileEntity te = this.getHost() != null ? this.getHost().getTile() : null;
        if (te != null && te.getWorld() != null) {
            this.placedTick = te.getWorld().getTotalWorldTime();
        }
    }

    @Override
    public void removeFromWorld() {
        unregisterGridAListeners();
        // Drop back-grid monitor reference held by insertion handlers
        this.itemInsertionHandler.clearMonitor();
        this.fluidInsertionHandler.clearMonitor();
        if (this.gasInsertionHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
            this.gasInsertionHandler.clearMonitor();
        }
        if (this.essentiaInsertionHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
            this.essentiaInsertionHandler.clearMonitor();
        }
        this.insertionActive = false;
        // Drop coordinator registration (if any) so dead fronts don't linger
        // in election tables on whichever front-grid we last belonged to.
        if (this.currentFrontGridCoord != null) {
            this.currentFrontGridCoord.unregisterFront(this);
            this.currentFrontGridCoord = null;
        }
        this.peerFronts = new ArrayList<>();
        this.exposedOrigins = new HashSet<>();
        super.removeFromWorld();
        this.cachedHasBack = false;
    }

    @Override
    public void readFromNBT(final NBTTagCompound data) {
        super.readFromNBT(data);

        // Sparse config deserialization: only non-empty slots were written
        if (data.hasKey("config")) readSparseConfig(data.getCompoundTag("config"));

        this.upgrades.readFromNBT(data, "upgrades");
        this.currentPage = data.getInteger("currentPage");
        this.filterMode = ResourceType.fromOrdinal(data.getInteger("filterMode"));
        this.fuzzyMode = FuzzyMode.values()[Math.max(0, Math.min(
            data.getInteger("fuzzyMode"), FuzzyMode.values().length - 1))];
        this.priority = data.getInteger("priority");
        this.filtersDirty = true;
    }

    @Override
    public void writeToNBT(final NBTTagCompound data) {
        super.writeToNBT(data);

        // Sparse config serialization: only write non-empty slots to avoid
        // 1.6k empty slot entries in NBT. Each entry is keyed by slot index.
        writeSparseConfig(data);

        this.upgrades.writeToNBT(data, "upgrades");
        data.setInteger("currentPage", this.currentPage);
        data.setInteger("filterMode", this.filterMode.ordinal());
        data.setInteger("fuzzyMode", this.fuzzyMode.ordinal());
        data.setInteger("priority", this.priority);
    }

    /**
     * Write only non-empty config slots to NBT in a sparse format.
     * Each slot is stored as a sub-compound keyed by its index string.
     */
    private void writeSparseConfig(final NBTTagCompound data) {
        NBTTagCompound sparse = new NBTTagCompound();

        for (int i = 0; i < this.config.getSlots(); i++) {
            ItemStack stack = this.config.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            NBTTagCompound slotTag = new NBTTagCompound();
            stack.writeToNBT(slotTag);
            sparse.setTag(Integer.toString(i), slotTag);
        }

        data.setTag("config", sparse);
    }

    /**
     * Read sparse config from NBT. Clears all slots first, then fills
     * only the slots that were serialized.
     */
    private void readSparseConfig(final NBTTagCompound sparse) {
        // Clear all slots
        for (int i = 0; i < this.config.getSlots(); i++) {
            this.config.setStackInSlot(i, ItemStack.EMPTY);
        }

        // Read non-empty slots
        for (String key : sparse.getKeySet()) {
            int slot;
            try {
                slot = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }

            if (slot < 0 || slot >= this.config.getSlots()) continue;

            ItemStack stack = new ItemStack(sparse.getCompoundTag(key));
            if (!stack.isEmpty()) this.config.setStackInSlot(slot, stack);
        }
    }

    // ========================= Network Events =========================

    /**
     * These subscribe to events from any grid the part's nodes belong to.
     * The inner proxy is on Grid B, so these fire for Grid B events.
     */
    @MENetworkEventSubscribe
    public void stateChange(final MENetworkChannelsChanged c) {
        // When Grid B first assigns a channel (e.g. after initial placement),
        // re-discover Grid A's storage so it becomes visible immediately
        // without requiring a world reload or cell array change.
        this.sourcesDirty = true;
        this.notifyGridOfChange();
        this.getHost().markForUpdate();
    }

    @MENetworkEventSubscribe
    public void stateChange(final MENetworkPowerStatusChange c) {
        this.getHost().markForUpdate();
    }

    // ========================= Neighbor Updates =========================

    @Override
    public void onNeighborChanged(IBlockAccess w, BlockPos pos, BlockPos neighbor) {
        // TODO: could exist early if neighbor != adjacentPos
        boolean hasBack = findBackPart() != null;

        if (hasBack != this.cachedHasBack) {
            this.cachedHasBack = hasBack;
            this.getHost().markForUpdate();
        }
    }

    // ========================= LED State from Outer Proxy =========================

    @Override
    public void writeToStream(final ByteBuf data) throws IOException {
        int flags = 0;
        try {
            if (this.getProxy().getEnergy().isNetworkPowered()) {
                flags |= POWERED_FLAG;
            }
            if (this.getProxy().getNode() != null && this.getProxy().getNode().meetsChannelRequirements()) {
                flags |= CHANNEL_FLAG;
            }
        } catch (final GridAccessException e) {
            // No grid yet
        }

        // Use cached counterpart presence (updated on neighbor changes)
        if (this.cachedHasBack) flags |= BOTH_PARTS_FLAG;

        data.writeByte((byte) flags);
    }

    @Override
    public boolean readFromStream(final ByteBuf data) throws IOException {
        final int old = this.clientFlags;
        this.clientFlags = data.readByte();
        return old != this.clientFlags;
    }

    @Override
    public boolean isPowered() {
        return (this.clientFlags & POWERED_FLAG) == POWERED_FLAG;
    }

    @Override
    public boolean isActive() {
        // Active only if we have a channel AND the back counterpart is present
        return (this.clientFlags & CHANNEL_FLAG) == CHANNEL_FLAG
            && (this.clientFlags & BOTH_PARTS_FLAG) == BOTH_PARTS_FLAG;
    }

    // ========================= ICellContainer =========================

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public List<IMEInventoryHandler> getCellArray(final IStorageChannel channel) {
        // Only rebuild when invalidated by grid events or config changes
        if (this.sourcesDirty) this.updatePassthroughSources();
        if (this.filtersDirty) this.rebuildFilters();

        IItemStorageChannel itemCh = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
        IFluidStorageChannel fluidCh = AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);

        if (channel == itemCh) {
            // Combine read-side handler with insertion handler when active.
            // Both are exposed on the front-grid: the read handler shows back-grid
            // items (filtered) for extraction; the insertion handler accepts items
            // matching the same filter and forwards them to back-grid storage.
            List<IMEInventoryHandler> read = this.itemHandler.asCellArray();
            if (!this.insertionActive) return read;

            List<IMEInventoryHandler> insert = this.itemInsertionHandler.asCellArray();
            if (insert.isEmpty()) return read;
            if (read.isEmpty()) return insert;

            List<IMEInventoryHandler> combined = new ArrayList<>(read.size() + insert.size());
            combined.addAll(read);
            combined.addAll(insert);
            return combined;
        }

        if (channel == fluidCh) {
            List<IMEInventoryHandler> read = this.fluidHandler.asCellArray();
            if (!this.insertionActive) return read;

            List<IMEInventoryHandler> insert = this.fluidInsertionHandler.asCellArray();
            if (insert.isEmpty()) return read;
            if (read.isEmpty()) return insert;

            List<IMEInventoryHandler> combined = new ArrayList<>(read.size() + insert.size());
            combined.addAll(read);
            combined.addAll(insert);
            return combined;
        }

        // Gas channel (MekanismEnergistics)
        if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
            List<IMEInventoryHandler> result = SubnetProxyGasHelper.asCellArray(this.gasHandler, channel);
            if (!result.isEmpty()) {
                if (!this.insertionActive || this.gasInsertionHandler == null) return result;
                List<IMEInventoryHandler> insert = this.gasInsertionHandler.asCellArray();
                if (insert.isEmpty()) return result;
                List<IMEInventoryHandler> combined = new ArrayList<>(result.size() + insert.size());
                combined.addAll(result);
                combined.addAll(insert);
                return combined;
            }
        }

        // Essentia channel (ThaumicEnergistics)
        if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
            List<IMEInventoryHandler> result = SubnetProxyEssentiaHelper.asCellArray(this.essentiaHandler, channel);
            if (!result.isEmpty()) {
                if (!this.insertionActive || this.essentiaInsertionHandler == null) return result;
                List<IMEInventoryHandler> insert = this.essentiaInsertionHandler.asCellArray();
                if (insert.isEmpty()) return result;
                List<IMEInventoryHandler> combined = new ArrayList<>(result.size() + insert.size());
                combined.addAll(result);
                combined.addAll(insert);
                return combined;
            }
        }

        return Collections.emptyList();
    }

    @Override
    public int getPriority() {
        return this.priority;
    }

    @Override
    public void blinkCell(int slot) {
        // No visual blinking for passthrough
    }

    // ISaveProvider (from ICellContainer)
    @Override
    public void saveChanges(@Nullable ICellInventory<?> cellInventory) {
        this.markHostDirty();
    }

    private void markHostDirty() {
        TileEntity te = this.getHost() != null ? this.getHost().getTile() : null;
        if (te == null) return;

        World w = te.getWorld();
        //noinspection ConstantValue
        if (w == null || w.isRemote) return;

        // Debounce to once per tick
        long currentTick = w.getTotalWorldTime();
        if (this.lastSaveTick == currentTick) return;
        this.lastSaveTick = currentTick;

        w.markChunkDirty(te.getPos(), te);
    }

    // IAEAppEngInventory (for config/upgrades inventory change callbacks)
    @Override
    public void onChangeInventory(IItemHandler inv, int slot, InvOperation mc, ItemStack removed, ItemStack added) {
        // When upgrades change, the number of pages may change
        if (inv == this.upgrades) {
            // Clamp current page if capacity cards removed
            setCurrentPage(this.currentPage);
            this.filtersDirty = true;
            this.notifyGridOfChange();

            // If the insertion card was added or removed, rewire local insertion
            // handlers (their monitor points at back-grid). The cell array on
            // front-grid will reflect the new state via notifyGridOfChange below.
            boolean removedInsertion = !removed.isEmpty() && removed.getItem() instanceof ItemInsertionCard;
            boolean addedInsertion = !added.isEmpty() && added.getItem() instanceof ItemInsertionCard;
            if (removedInsertion || addedInsertion) {
                updateInsertionHandlers();
            }
        }

        // When config changes, filters need rebuilding
        if (inv == this.config) {
            this.filtersDirty = true;
            this.notifyGridOfChange();
        }

        this.markHostDirty();
        this.getHost().markForUpdate();
    }

    /** Guard against recursive markSourcesDirty → notifyGridOfChange → cellUpdate → markSourcesDirty loops */
    private boolean inMarkSourcesDirty = false;

    /**
     * Called by the back part when Grid A fires {@link MENetworkCellArrayUpdate}.
     * Invalidates the cached passthrough sources so the next getCellArray() call
     * will rebuild them.
     * <p>
     * Guarded against recursion: notifyGridOfChange posts MENetworkCellArrayUpdate
     * on Grid B, which may synchronously cascade back to Grid A (via storage bus
     * on an ME Interface) and re-trigger cellUpdate on the back part.
     */
    public void markSourcesDirty() {
        if (this.inMarkSourcesDirty) return;

        this.inMarkSourcesDirty = true;
        try {
            this.sourcesDirty = true;
            // Back-grid topology may have changed; re-check insertion wiring
            // (handles back part appearing/disappearing or its grid changing).
            updateInsertionHandlers();
            this.notifyGridOfChange();
        } finally {
            this.inMarkSourcesDirty = false;
        }
    }

    // ========================= Passthrough Logic =========================

    /**
     * Find the back part in the adjacent block facing this front part.
     * <p>
     * Layout: [Grid B cable + front(EAST)] | [back(WEST) + Grid A cable]
     * The parts face each other across the block boundary, like a storage
     * bus on an interface. The front faces EAST, the back faces WEST.
     */
    public PartSubnetProxyBack findBackPart() {
        TileEntity selfTile = this.getHost() != null ? this.getHost().getTile() : null;
        if (selfTile == null || selfTile.getWorld() == null) return null;

        // The back part is in the adjacent block in our facing direction,
        // on the opposite side (facing back toward us).
        EnumFacing facing = this.getSide().getFacing();
        BlockPos adjacentPos = selfTile.getPos().offset(facing);
        TileEntity adjacentTile = selfTile.getWorld().getTileEntity(adjacentPos);
        if (!(adjacentTile instanceof IPartHost)) return null;

        IPart candidate = ((IPartHost) adjacentTile).getPart(AEPartLocation.fromFacing(facing.getOpposite()));
        if (candidate instanceof PartSubnetProxyBack) return (PartSubnetProxyBack) candidate;

        return null;
    }

    /**
     * Rebuild the source cell handlers on the passthrough handlers.
     * <p>
     * For anti-looping logic, see {@link SubnetProxyInventoryHandler}.
     * <p>
     * Only called when {@code sourcesDirty} is true (Grid A cell array update
     * forwarded by the back part, or initial placement). Cost: O(nodes) per
     * invocation, amortized to O(1) between grid events.
     */
    @SuppressWarnings("unchecked")
    private void updatePassthroughSources() {
        this.sourcesDirty = false;

        // Unregister from previous Grid A monitors before potentially switching grids
        unregisterGridAListeners();

        PartSubnetProxyBack back = findBackPart();
        if (back == null) {
            this.itemHandler.clearSources();
            this.fluidHandler.clearSources();
            if (this.gasHandler != null) this.gasHandler.clearSources();
            if (this.essentiaHandler != null) this.essentiaHandler.clearSources();

            // Reset snapshots so next connection starts clean
            this.itemHandler.setLastSnapshot(null);
            this.fluidHandler.setLastSnapshot(null);
            if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
                SubnetProxyGasHelper.resetSnapshot(this.gasHandler);
            }
            if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
                SubnetProxyEssentiaHelper.resetSnapshot(this.essentiaHandler);
            }

            // No back: drop peers/origins/coord. Election on whichever
            // front-grid we previously belonged to must drop us so other
            // fronts can take over publication of any origins we held.
            this.peerFronts = new ArrayList<>();
            this.exposedOrigins = new HashSet<>();
            refreshCoordinatorRegistration();

            return;
        }

        try {
            IGrid gridA = back.getProxy().getGrid();
            IStorageGrid sg = gridA.getCache(IStorageGrid.class);

            IItemStorageChannel itemChannel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
            IFluidStorageChannel fluidChannel = AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);

            // Collect cell handlers from non-passthrough providers only.
            // Also collect peer subnet-proxy fronts on the back-grid: their
            // back-grids become candidate origins we may publish on our
            // front-grid (subject to election in the front-grid coordinator).
            List<IMEInventoryHandler<IAEItemStack>> localItemCells = new ArrayList<>();
            List<IMEInventoryHandler<IAEFluidStack>> localFluidCells = new ArrayList<>();
            List<PartSubnetProxyFront> newPeers = new ArrayList<>();

            for (IGridNode node : gridA.getNodes()) {
                IGridHost host = node.getMachine();
                if (!(host instanceof ICellProvider)) continue;

                // Skip our own proxy to prevent proxy-to-proxy chains.
                // Other PartSubnetProxyFront on this back-grid are PEERS:
                // their back-grids become candidate origins for us to expose,
                // but they MUST be excluded from local-cell collection (their
                // ICellContainer would otherwise add their items here doubled).
                if (host instanceof PartSubnetProxyFront) {
                    PartSubnetProxyFront peer = (PartSubnetProxyFront) host;
                    if (peer != this) newPeers.add(peer);
                    continue;
                }

                // Skip back parts: their insertion handlers are write-only wrappers
                // for Grid B's storage, not local storage on Grid A.
                if (host instanceof PartSubnetProxyBack) continue;

                // For cable-bus parts (storage buses and similar), check if their
                // target tile provides STORAGE_MONITORABLE_ACCESSOR. If so, the
                // part is bridging to another ME network (i.e. subnet passthrough)
                // and must be excluded to prevent loop inflation.
                if (isPassthroughBusStatic(host)) continue;

                ICellProvider provider = (ICellProvider) host;

                for (IMEInventoryHandler<?> h : provider.getCellArray(itemChannel)) {
                    localItemCells.add((IMEInventoryHandler<IAEItemStack>) h);
                }

                for (IMEInventoryHandler<?> h : provider.getCellArray(fluidChannel)) {
                    localFluidCells.add((IMEInventoryHandler<IAEFluidStack>) h);
                }
            }

            // Publish updated peer/origin sets BEFORE refreshing the coordinator
            // so election sees the new exposed-origins set.
            this.peerFronts = newPeers;
            this.exposedOrigins = computeExposedOrigins(gridA, newPeers);
            refreshCoordinatorRegistration();

            this.itemHandler.setLocalCells(localItemCells);
            this.fluidHandler.setLocalCells(localFluidCells);

            // Gas channel (MekanismEnergistics)
            if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
                SubnetProxyGasHelper.updateSources(this.gasHandler, gridA, sg);
            }

            // Essentia channel (ThaumicEnergistics)
            if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
                SubnetProxyEssentiaHelper.updateSources(this.essentiaHandler, gridA, sg);
            }

            // Register on Grid A's monitors for immediate delta forwarding.
            // The listener checks source grid membership and forwards local
            // changes directly to Grid B (no deferred snapshot diff).
            registerGridAListeners(gridA, sg.getInventory(itemChannel), sg.getInventory(fluidChannel));

            // Gas/essentia listeners
            if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
                SubnetProxyGasHelper.registerListener(this.gasHandler, sg, this.gridAListener, this.listenerToken);
            }
            if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
                SubnetProxyEssentiaHelper.registerListener(this.essentiaHandler, sg, this.gridAListener, this.listenerToken);
            }

            // Take baseline snapshots. Grid B will get the full listing via
            // getCellArray → getAvailableItems (triggered by notifyGridOfChange),
            // so the snapshot must match what the handlers return right now.
            takeSnapshot(this.itemHandler, itemChannel);
            takeSnapshot(this.fluidHandler, fluidChannel);

            if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
                SubnetProxyGasHelper.takeSnapshot(this.gasHandler);
            }
            if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
                SubnetProxyEssentiaHelper.takeSnapshot(this.essentiaHandler);
            }
        } catch (final GridAccessException e) {
            this.itemHandler.clearSources();
            this.fluidHandler.clearSources();
            if (this.gasHandler != null) this.gasHandler.clearSources();
            if (this.essentiaHandler != null) this.essentiaHandler.clearSources();

            this.itemHandler.setLastSnapshot(null);
            this.fluidHandler.setLastSnapshot(null);

            // Back-grid unavailable: also drop peer/origin set & coord
            this.peerFronts = new ArrayList<>();
            this.exposedOrigins = new HashSet<>();
            refreshCoordinatorRegistration();
        }
    }

    /** Take a snapshot of the handler's current listing for delta comparison. */
    private <T extends IAEStack<T>> void takeSnapshot(
            SubnetProxyInventoryHandler<T> handler,
            IStorageChannel<T> channel) {
        IItemList<T> snapshot = channel.createList();
        handler.getAvailableItems(snapshot);
        handler.setLastSnapshot(snapshot);
    }

    /**
     * Checks if a grid host is a cable-bus part (storage bus or similar) whose
     * target tile entity provides {@code Capabilities.STORAGE_MONITORABLE_ACCESSOR}.
     * <p>
     * This capability is the AE2 standard for exposing a grid's full storage to
     * an adjacent block. It is provided by:
     * <ul>
     *   <li>{@code DualityInterface} / {@code DualityFluidInterface}: ME Interfaces</li>
     *   <li>{@code DualityGasInterface}: MekanismEnergistics gas interfaces</li>
     *   <li>{@code TileChest}: ME Chests (but their items are already on the
     *       grid via their own ICellContainer, so excluding them is harmless)</li>
     * </ul>
     * Any storage bus whose target has this capability is a subnet passthrough
     * and should be excluded from the proxy's listing view.
     *
     * @return true if the host is a passthrough bus that should be skipped
     */
    static boolean isPassthroughBusStatic(IGridHost host) {
        if (!(host instanceof AEBasePart)) return false;

        AEBasePart part = (AEBasePart) host;
        TileEntity selfTile = part.getHost().getTile();
        if (selfTile == null || selfTile.getWorld() == null) return false;

        EnumFacing facing = part.getSide().getFacing();
        BlockPos targetPos = selfTile.getPos().offset(facing);
        TileEntity target = selfTile.getWorld().getTileEntity(targetPos);

        return target != null
            && target.hasCapability(Capabilities.STORAGE_MONITORABLE_ACCESSOR, facing.getOpposite());
    }

    /**
     * Rebuild the filter sets and predicates on the passthrough handlers
     * based on the current config inventory content.
     * <p>
     * Only called when {@code filtersDirty} is true (config/upgrade change).
     * Uses AE2's native {@link IItemList#findPrecise} for precise matching,
     * which leverages interned {@code AESharedItemStack} reference lookups
     * (identity hash, zero NBT cost). Fuzzy matching uses a {@code Map<Item,
     * List<IAEItemStack>>} index for O(1) candidate lookup + damage-range
     * comparisons.
     */
    private void rebuildFilters() {
        this.filtersDirty = false;

        this.cachedHasInverter = this.upgrades.getInstalledUpgrades(Upgrades.INVERTER) > 0;
        this.cachedHasFuzzy = this.upgrades.getInstalledUpgrades(Upgrades.FUZZY) > 0;
        FuzzyMode fuzzyMode = this.fuzzyMode;

        final boolean hasInverter = this.cachedHasInverter;
        final boolean hasFuzzy = this.cachedHasFuzzy;

        // Collect all non-empty filter entries across all accessible pages
        int totalSlots = Math.min(getTotalPages() * SLOTS_PER_PAGE, this.config.getSlots());

        // Use AE2's native IItemList for precise matching. ItemList uses
        // Reference2ObjectOpenHashMap keyed by interned AESharedItemStack,
        // so findPrecise() is an identity-hash lookup with 0 NBT cost.
        IItemList<IAEItemStack> itemFilterList = AEApi.instance().storage()
            .getStorageChannel(IItemStorageChannel.class).createList();
        IItemList<IAEFluidStack> fluidFilterList = AEApi.instance().storage()
            .getStorageChannel(IFluidStorageChannel.class).createList();
        boolean hasItemFilters = false;
        boolean hasFluidFilters = false;

        // For fuzzy fluid matching, we index by Fluid to ignore NBT.
        // AEFluidStack.fuzzyComparison() only checks fluid type identity,
        // so a Set<Fluid> gives us O(1) lookup with the same semantics.
        Set<Fluid> fuzzyFluidIndex = hasFuzzy ? new HashSet<>() : null;

        // For fuzzy item matching, we index filters by Item to avoid O(F) per-item scans.
        // Only the filters for the matching Item need to be checked per incoming stack.
        Map<Item, List<IAEItemStack>> fuzzyItemIndex = hasFuzzy ? new HashMap<>() : null;

        for (int i = 0; i < totalSlots; i++) {
            ItemStack stack = this.config.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof FluidDummyItem) {
                IAEFluidStack aeFluid = AEFluidStack.fromFluidStack(
                    ((FluidDummyItem) stack.getItem()).getFluidStack(stack));
                if (aeFluid != null) {
                    fluidFilterList.add(aeFluid);
                    hasFluidFilters = true;

                    // Index by Fluid type for fuzzy matching (ignores NBT)
                    if (hasFuzzy) fuzzyFluidIndex.add(aeFluid.getFluid());
                }
            } else {
                IAEItemStack aeFilter = AEItemStack.fromItemStack(stack);
                if (aeFilter != null) {
                    itemFilterList.add(aeFilter);
                    hasItemFilters = true;

                    if (hasFuzzy) {
                        // Index by Item for O(1) lookup of candidate filters per incoming stack
                        fuzzyItemIndex.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(aeFilter);
                    }
                }
            }
        }

        // Build item filter predicate
        if (!hasItemFilters && !hasInverter) {
            // No filters + whitelist = pass everything
            this.itemHandler.setFilter(null);
        } else if (hasFuzzy && fuzzyItemIndex != null && !fuzzyItemIndex.isEmpty()) {
            // Fuzzy matching: look up candidate filters by Item, then check damage range.
            // O(1) map lookup + O(K) fuzzy checks where K = filters for the same Item.
            // There should not be many filters per Item, except with metadata-items (Thermal Expansion materials, etc.)
            this.itemHandler.setFilter(aeStack -> {
                if (aeStack == null) return false;

                Item item = aeStack.getDefinition().getItem();
                List<IAEItemStack> candidates = fuzzyItemIndex.get(item);

                boolean matchesAny = false;
                if (candidates != null) {
                    for (IAEItemStack aeFilter : candidates) {
                        if (aeStack.fuzzyComparison(aeFilter, fuzzyMode)) {
                            matchesAny = true;
                            break;
                        }
                    }
                }

                return hasInverter != matchesAny;
            });
        } else {
            // Precise matching: O(1) per item via IItemList.findPrecise(), which uses
            // interned AESharedItemStack identity lookups (zero NBT hashing).
            this.itemHandler.setFilter(aeStack -> {
                if (aeStack == null) return false;

                boolean matchesAny = itemFilterList.findPrecise(aeStack) != null;

                return hasInverter != matchesAny;
            });
        }

        // Build fluid filter predicate.
        // Precise mode: findPrecise() checks both fluid type AND NBT (via equals+hashCode).
        // Fuzzy mode: matches by fluid type only, ignoring NBT. Aligns with AE2's
        // PartFluidStorageBus which uses FuzzyPriorityList with fuzzyComparison().
        if (!hasFluidFilters && !hasInverter) {
            this.fluidHandler.setFilter(null);
        } else if (hasFuzzy && fuzzyFluidIndex != null && !fuzzyFluidIndex.isEmpty()) {
            // Fuzzy matching: check fluid type only (ignoring NBT),
            // matching AEFluidStack.fuzzyComparison() semantics.
            this.fluidHandler.setFilter(aeStack -> {
                if (aeStack == null) return false;

                boolean matchesAny = fuzzyFluidIndex.contains(aeStack.getFluid());

                return hasInverter != matchesAny;
            });
        } else {
            // Precise matching: findPrecise() checks fluid type + NBT.
            this.fluidHandler.setFilter(aeStack -> {
                if (aeStack == null) return false;

                boolean matchesAny = fluidFilterList.findPrecise(aeStack) != null;

                return hasInverter != matchesAny;
            });
        }

        // Build gas filter predicate (MekanismEnergistics)
        if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
            SubnetProxyGasHelper.rebuildFilter(this.gasHandler, this.config, totalSlots, hasInverter);
        }

        // Build essentia filter predicate (ThaumicEnergistics)
        if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
            SubnetProxyEssentiaHelper.rebuildFilter(this.essentiaHandler, this.config, totalSlots, hasInverter);
        }

        // Insertion handlers share the read-side filter; refresh after each rebuild.
        // Cheap: just re-grabs the predicate references.
        if (this.insertionActive) {
            this.itemInsertionHandler.setFilter(this.itemHandler.getFilter());
            this.fluidInsertionHandler.setFilter(this.fluidHandler.getFilter());
        }
    }

    // ========================= Insertion Card Support (front-grid → back-grid) =========================

    /**
     * (Re)wire the insertion handlers based on the current upgrade-slot state.
     * <p>
     * When the Insertion Card is installed AND a back part is present AND its
     * grid is reachable, the insertion handlers' monitors are pointed at the
     * BACK-grid's storage. Items on the front-grid that match the proxy's
     * filter and are routed to the insertion handler will be injected into
     * the back-grid's storage, the reverse direction from the read view.
     * <p>
     * If any prerequisite fails, the handlers are deactivated (cleared monitor)
     * and {@link #insertionActive} is set to false so {@link #getCellArray} stops
     * including them.
     * <p>
     * Filters are sourced from the read-side handlers ({@link #itemHandler},
     * {@link #fluidHandler}); call this method again after {@link #rebuildFilters}
     * if filters may have changed.
     */
    private void updateInsertionHandlers() {
        // Ensure read-side filters are current (they're shared with insertion)
        if (this.filtersDirty) rebuildFilters();

        boolean shouldBeActive = hasInsertionCard();

        if (!shouldBeActive) {
            if (this.insertionActive) {
                this.itemInsertionHandler.clearMonitor();
                this.fluidInsertionHandler.clearMonitor();
                if (this.gasInsertionHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
                    this.gasInsertionHandler.clearMonitor();
                }
                if (this.essentiaInsertionHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
                    this.essentiaInsertionHandler.clearMonitor();
                }
                this.insertionActive = false;
            }
            return;
        }

        // Need the back part's grid to wire the monitors
        PartSubnetProxyBack back = findBackPart();
        if (back == null) {
            if (this.insertionActive) {
                this.itemInsertionHandler.clearMonitor();
                this.fluidInsertionHandler.clearMonitor();
                if (this.gasInsertionHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
                    this.gasInsertionHandler.clearMonitor();
                }
                if (this.essentiaInsertionHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
                    this.essentiaInsertionHandler.clearMonitor();
                }
                this.insertionActive = false;
            }
            return;
        }

        try {
            IGrid backGrid = back.getProxy().getGrid();
            IStorageGrid backStorage = backGrid.getCache(IStorageGrid.class);

            IItemStorageChannel itemChannel = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class);
            IFluidStorageChannel fluidChannel = AEApi.instance().storage()
                .getStorageChannel(IFluidStorageChannel.class);

            this.itemInsertionHandler.setMonitor(backStorage.getInventory(itemChannel));
            this.itemInsertionHandler.setFilter(this.itemHandler.getFilter());
            this.itemInsertionHandler.setPriority(this.priority);

            this.fluidInsertionHandler.setMonitor(backStorage.getInventory(fluidChannel));
            this.fluidInsertionHandler.setFilter(this.fluidHandler.getFilter());
            this.fluidInsertionHandler.setPriority(this.priority);

            // Optional channels: only wire when the integration mod is loaded
            // AND the corresponding read-side handler exists. Both helpers
            // isolate mod-specific class loads so this stays NoClassDefFoundError-safe.
            if (this.gasInsertionHandler != null && this.gasHandler != null
                    && MekanismEnergisticsIntegration.isModLoaded()) {
                wireGasInsertion(backStorage);
            }
            if (this.essentiaInsertionHandler != null && this.essentiaHandler != null
                    && ThaumicEnergisticsIntegration.isModLoaded()) {
                wireEssentiaInsertion(backStorage);
            }

            this.insertionActive = true;
        } catch (final GridAccessException e) {
            // Back grid not available
            this.itemInsertionHandler.clearMonitor();
            this.fluidInsertionHandler.clearMonitor();
            if (this.gasInsertionHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
                this.gasInsertionHandler.clearMonitor();
            }
            if (this.essentiaInsertionHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
                this.essentiaInsertionHandler.clearMonitor();
            }
            this.insertionActive = false;
        }
    }

    /**
     * Wire the gas insertion handler. Isolated to keep Mekanism class loads
     * out of the main {@link #updateInsertionHandlers} path; only invoked
     * when the mod is loaded.
     */
    @SuppressWarnings("unchecked")
    private void wireGasInsertion(IStorageGrid backStorage) {
        SubnetProxyGasHelper.wireInsertionHandler(
            this.gasInsertionHandler, this.gasHandler, backStorage, this.priority);
    }

    /**
     * Wire the essentia insertion handler. Isolated to keep ThaumicEnergistics
     * class loads out of the main {@link #updateInsertionHandlers} path;
     * only invoked when the mod is loaded.
     */
    @SuppressWarnings("unchecked")
    private void wireEssentiaInsertion(IStorageGrid backStorage) {
        SubnetProxyEssentiaHelper.wireInsertionHandler(
            this.essentiaInsertionHandler, this.essentiaHandler, backStorage, this.priority);
    }

    /** Notify Grid B that our cell array has changed */
    private void notifyGridOfChange() {
        IGridNode node = this.getProxy().getNode();
        if (node != null && node.getGrid() != null) {
            node.getGrid().postEvent(new MENetworkCellArrayUpdate());
        }
    }

    // ========================= Grid A Listener (Delta Forwarding) =========================

    /**
     * Raw IMEMonitorHandlerReceiver registered on Grid A's monitors (all channels).
     * <p>
     * <b>Immediate forwarding with source-based anti-loop:</b> Filters incoming
     * deltas by checking the {@link IActionSource}'s grid membership and forwards
     * matching deltas to Grid B immediately. This gives O(δ) per event (same as
     * classic Storage Bus on Interface).
     * <p>
     * <b>Anti-loop guarantee:</b> Changes from passthrough storage buses arrive
     * with a {@link MachineSource} whose machine is on a <em>remote</em> grid
     * (the original source is preserved through {@code MEMonitorPassThrough}).
     * Our {@link #isLocalSource} check rejects these because the machine's
     * {@link IGridNode#getGrid()} differs from Grid A. Changes from other
     * {@link PartSubnetProxyFront} instances are also rejected, preventing
     * A↔B bidirectional loops. Only changes originating from machines physically
     * on Grid A (drives, chests, local storage buses) are forwarded.
     * <p>
     * <b>onListUpdate fallback:</b> Full monitor resets (power loss/restore,
     * force-update from AE2's nesting detection) set {@link #deltasDirty} and
     * defer to the tick-based snapshot diff path, since no per-item deltas
     * are available in that case.
     */
    @SuppressWarnings("rawtypes")
    private class GridAListener implements IMEMonitorHandlerReceiver {

        @SuppressWarnings("unchecked")
        @Override
        public void postChange(final IBaseMonitor monitor, final Iterable change, final IActionSource actionSource) {
            // ---- Determine the ORIGIN grid for this delta ----
            // 1) If the source is already a SubnetProxyEventSource, the upstream
            //    proxy chain has tagged the original origin-grid; use it verbatim
            //    (preserves the same identity through any number of hops).
            // 2) Otherwise, this is a real machine change on our back-grid;
            //    origin = back-grid (gridA) UNLESS the source machine is on a
            //    different grid (passthrough storage bus → ME Interface), in
            //    which case we'd be inflating the loop and must reject.
            IGrid origin = SubnetProxyEventSource.extractOriginGrid(actionSource);
            if (origin == null) {
                if (gridA == null) return;
                if (!isPlainLocalSource(actionSource)) return;
                origin = gridA;
            }

            // ---- Loop-back rejection ----
            // Never forward a delta back into the grid where it originated.
            // (Catches A↔B bidirectional pairs cleanly: B→A's listener
            // hears A's local change with origin=A; B→A's front-grid is A;
            // origin == front-grid -> reject.)
            IGrid frontGrid = getFrontGridLive();
            if (frontGrid != null && origin == frontGrid) return;

            // ---- Reach-set check (1-hop visibility) ----
            // Forward only if our published listing actually exposes this
            // origin's items. Otherwise we'd be forwarding ghost deltas
            // for items we don't even list.
            if (!exposedOrigins.contains(origin)) return;

            // ---- Election: only one front per origin publishes on a given grid ----
            // Diamond topologies (A→B→D + A→C→D) are dedup'd here:
            // exactly one of B→D / C→D is elected to forward A's deltas.
            SubnetProxyGridCoordinator coord = currentFrontGridCoord;
            if (coord != null && !coord.isElected(PartSubnetProxyFront.this, origin)) return;

            // ---- Event-UUID dedup (belt-and-suspenders) ----
            // Reuse the upstream UUID so a single logical event keeps the same
            // identity through the chain; generate a fresh one for new origins.
            UUID eventId = SubnetProxyEventSource.extractEventId(actionSource);
            if (eventId == null) eventId = UUID.randomUUID();
            if (coord != null && !coord.tryAccept(eventId)) return;

            // Ensure filters are current before testing deltas
            if (filtersDirty) rebuildFilters();

            try {
                IStorageGrid gridB = getProxy().getStorage();
                IActionSource wrapped = new SubnetProxyEventSource(
                    PartSubnetProxyFront.this, eventId, origin);

                if (monitor == registeredItemMonitor) {
                    IStorageChannel<IAEItemStack> ch = AEApi.instance().storage()
                        .getStorageChannel(IItemStorageChannel.class);
                    forwardFilteredDeltas(change, itemHandler, ch, gridB, wrapped);
                } else if (monitor == registeredFluidMonitor) {
                    IStorageChannel<IAEFluidStack> ch = AEApi.instance().storage()
                        .getStorageChannel(IFluidStorageChannel.class);
                    forwardFilteredDeltas(change, fluidHandler, ch, gridB, wrapped);
                } else if (gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()
                           && monitor == gasHandler.getRegisteredMonitor()) {
                    // TODO: gas/essentia helpers still use the legacy
                    // proxySource and don't participate in cross-hub UUID
                    // dedup. Migrate them to take a wrapped source param.
                    SubnetProxyGasHelper.forwardFilteredDeltas(change, gasHandler, gridB, wrapped);
                } else if (essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()
                           && monitor == essentiaHandler.getRegisteredMonitor()) {
                    SubnetProxyEssentiaHelper.forwardFilteredDeltas(change, essentiaHandler, gridB, wrapped);
                }
            } catch (final GridAccessException e) {
                // Grid B not available
            }
        }

        @Override
        public void onListUpdate() {
            // Full list reset on Grid A (e.g. power loss/restore). No per-item
            // deltas available, must fall back to snapshot diff on the next tick.
            deltasDirty = true;
            alertGridBTick();
        }

        @Override
        public boolean isValid(final Object verificationToken) {
            return verificationToken == listenerToken;
        }
    }

    /**
     * Whether an unwrapped (non-{@link SubnetProxyEventSource}) source represents
     * a real machine local to our back-grid. Used to filter out deltas that
     * arrive at our back-grid via passthrough storage buses (storage bus -> ME
     * Interface) whose machine() points at the remote source on another grid.
     * <p>
     * Wrapped sources are handled separately in {@link GridAListener#postChange}
     * (their {@code originGrid} is trusted directly).
     */
    private boolean isPlainLocalSource(IActionSource source) {
        if (this.gridA == null) return false;

        if (!source.machine().isPresent()) {
            // Sources without machine info (BaseActionSource) come from
            // GridStorageCache when a non-IActionHost provider is registered.
            // These are always local to Grid A.
            return true;
        }

        IActionHost machine = source.machine().get();

        // A subnet-proxy front as the source means this came from a forwarding
        // chain that didn't wrap (legacy/gas/essentia). Reject defensively.
        if (machine instanceof PartSubnetProxyFront) return false;

        IGridNode node = machine.getActionableNode();
        return node != null && node.getGrid() == this.gridA;
    }

    /**
     * Filter incoming deltas through the handler's predicate and forward
     * matching entries to Grid B with the supplied (already-wrapped) source.
     * Also updates the snapshot incrementally so the onListUpdate fallback
     * can diff correctly.
     *
     * @param changes  raw deltas from Grid A's monitor (already signed)
     * @param handler  the proxy handler with filter predicate
     * @param channel  the storage channel
     * @param gridB    Grid B's storage grid for posting
     * @param source   wrapped source carrying eventId + originGrid metadata
     */
    private <T extends IAEStack<T>> void forwardFilteredDeltas(
            Iterable<T> changes,
            SubnetProxyInventoryHandler<T> handler,
            IStorageChannel<T> channel,
            IStorageGrid gridB,
            IActionSource source) {

        Predicate<T> filter = handler.getFilter();
        List<T> forwarded = new ArrayList<>();

        for (T change : changes) {
            if (filter == null || filter.test(change)) forwarded.add(change);
        }

        if (forwarded.isEmpty()) return;

        gridB.postAlterationOfStoredItems(channel, forwarded, source);

        // Update snapshot incrementally so onListUpdate diffs remain accurate
        updateSnapshotIncremental(handler, channel, forwarded);
    }

    /**
     * Apply forwarded deltas to the handler's snapshot. Creates the snapshot
     * if it doesn't exist yet (first-run edge case during listener setup).
     */
    private <T extends IAEStack<T>> void updateSnapshotIncremental(
            SubnetProxyInventoryHandler<T> handler,
            IStorageChannel<T> channel,
            List<T> deltas) {

        IItemList<T> snapshot = handler.getLastSnapshot();
        if (snapshot == null) {
            snapshot = channel.createList();
            handler.setLastSnapshot(snapshot);
        }

        for (T delta : deltas) snapshot.add(delta);
    }

    /** Wake up Grid B's tick manager so we compute the snapshot diff. */
    private void alertGridBTick() {
        try {
            this.getProxy().getTick().alertDevice(this.getProxy().getNode());
        } catch (final GridAccessException e) {
            // Grid B not available yet
        }
    }

    /**
     * Register the Grid A listener on the given monitors. Unregisters from any
     * previously registered monitors first.
     *
     * @param gridA       Grid A's grid reference, stored for {@link #isLocalSource}
     * @param itemMonitor Grid A's item monitor
     * @param fluidMonitor Grid A's fluid monitor
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerGridAListeners(IGrid gridA, IMEMonitor itemMonitor, IMEMonitor fluidMonitor) {
        unregisterGridAListeners();

        this.gridA = gridA;

        // Fresh token so any stale listener references auto-expire via isValid
        this.listenerToken = new Object();

        if (itemMonitor != null) {
            this.registeredItemMonitor = itemMonitor;
            itemMonitor.addListener(this.gridAListener, this.listenerToken);
        }

        if (fluidMonitor != null) {
            this.registeredFluidMonitor = fluidMonitor;
            fluidMonitor.addListener(this.gridAListener, this.listenerToken);
        }

        // Gas/essentia listeners are registered via their respective helpers
        // (see updatePassthroughSources)
    }

    /** Unregister from all Grid A monitors we're currently listening on. */
    @SuppressWarnings("unchecked")
    private void unregisterGridAListeners() {
        if (this.registeredItemMonitor != null) {
            this.registeredItemMonitor.removeListener(this.gridAListener);
            this.registeredItemMonitor = null;
        }

        if (this.registeredFluidMonitor != null) {
            this.registeredFluidMonitor.removeListener(this.gridAListener);
            this.registeredFluidMonitor = null;
        }

        this.gridA = null;

        // Invalidate the token so any lingering references auto-expire
        this.listenerToken = new Object();
    }

    // ========================= IGridTickable (on Grid B) =========================

    @Nonnull
    @Override
    public TickingRequest getTickingRequest(@Nonnull final IGridNode node) {
        // Only tick for the inner proxy node (Grid B).
        if (node != this.getProxy().getNode()) {
            return new TickingRequest(20, 20, true, false);
        }

        // Alertable so the Grid A listener's onListUpdate can wake us for
        // snapshot diff. Normal deltas are forwarded immediately in postChange,
        // so ticking is only needed for rare full-reset events.
        return new TickingRequest(
            CellsConfig.subnetProxyMinTickRate,
            CellsConfig.subnetProxyMaxTickRate,
            !this.deltasDirty, true);
    }

    @Nonnull
    @Override
    public TickRateModulation tickingRequest(@Nonnull final IGridNode node, final int ticksSinceLastCall) {
        if (node != this.getProxy().getNode()) return TickRateModulation.SLEEP;
        if (!this.deltasDirty) return TickRateModulation.SLEEP;

        this.deltasDirty = false;
        snapshotDiffAndForward();

        return TickRateModulation.SLEEP;
    }

    /**
     * Snapshot-based delta forwarding, used only as a fallback for
     * {@link GridAListener#onListUpdate()} (full monitor resets such as
     * power loss/restore or AE2's nesting-triggered force-update).
     * <p>
     * Normal per-item deltas are forwarded immediately in
     * {@link GridAListener#postChange} and never reach this path.
     * <p>
     * Reads the handler's current listing via {@code getAvailableItems()}
     * (local cells + filter only), diffs against the incrementally maintained
     * snapshot, and forwards non-zero changes to Grid B.
     */
    @SuppressWarnings("unchecked")
    private void snapshotDiffAndForward() {
        // Ensure sources and filters are up to date before snapshotting
        if (this.sourcesDirty) this.updatePassthroughSources();
        if (this.filtersDirty) this.rebuildFilters();

        IStorageGrid gridB;
        try {
            gridB = this.getProxy().getStorage();
        } catch (final GridAccessException e) {
            return;
        }

        IItemStorageChannel itemChannel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
        IFluidStorageChannel fluidChannel = AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);

        // Item channel delta
        snapshotDiffChannel(this.itemHandler, itemChannel, gridB);

        // Fluid channel delta
        snapshotDiffChannel(this.fluidHandler, fluidChannel, gridB);

        // Gas channel (MekanismEnergistics)
        if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
            SubnetProxyGasHelper.snapshotDiffAndForward(
                this.gasHandler, gridB, this.proxySource);
        }

        // Essentia channel (ThaumicEnergistics)
        if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
            SubnetProxyEssentiaHelper.snapshotDiffAndForward(
                this.essentiaHandler, gridB, this.proxySource);
        }
    }

    /**
     * Compute the delta between the handler's current listing and its
     * incrementally maintained snapshot, forward non-empty diffs to Grid B,
     * and replace the snapshot with the current state.
     *
     * @param handler  the passthrough handler to query
     * @param channel  the storage channel
     * @param gridB    Grid B's storage grid for posting deltas
     */
    private <T extends IAEStack<T>> void snapshotDiffChannel(
            SubnetProxyInventoryHandler<T> handler,
            IStorageChannel<T> channel,
            IStorageGrid gridB) {

        // Take current snapshot from the handler (reads local cells + filter only)
        IItemList<T> current = channel.createList();
        handler.getAvailableItems(current);

        IItemList<T> previous = handler.getLastSnapshot();
        if (previous == null) {
            // First snapshot: no delta to forward, just establish baseline.
            // Grid B already got the full listing via getCellArray → getAvailableItems.
            handler.setLastSnapshot(current);
            return;
        }

        // Diff: negate previous, merge current, collect non-zero entries
        List<T> changes = new ArrayList<>();
        for (T was : previous) was.setStackSize(-was.getStackSize());

        for (T now : current) previous.add(now);

        for (T entry : previous) {
            if (entry.getStackSize() != 0) changes.add(entry);
        }

        if (!changes.isEmpty()) {
            // Wrap as a SubnetProxyEventSource so downstream proxies see the
            // origin as our back-grid (gridA) and apply normal loop/election
            // checks. Snapshot-diff is a fallback path (rare full resets), so
            // generating a fresh UUID per call is fine; the per-grid LRU dedup
            // is purely a safety net here.
            IActionSource src = (this.gridA != null)
                ? new SubnetProxyEventSource(this, UUID.randomUUID(), this.gridA)
                : this.proxySource;
            gridB.postAlterationOfStoredItems(channel, changes, src);
        }

        handler.setLastSnapshot(current);
    }

    // ========================= Peer aggregation / coordinator helpers =========================

    /**
     * Live lookup of our front-grid (the grid the inner proxy node belongs to).
     * Returns null if the node has no grid yet (early lifecycle / disconnected).
     * Distinct from {@link #gridA} (which is back-grid).
     */
    private IGrid getFrontGridLive() {
        IGridNode node = this.getProxy().getNode();
        return node != null ? node.getGrid() : null;
    }

    /**
     * Build the exposed-origins set: own back-grid + each peer's back-grid.
     * Filters out nulls (peers in transient states with no grid yet).
     */
    private Set<IGrid> computeExposedOrigins(IGrid backGrid, List<PartSubnetProxyFront> peers) {
        Set<IGrid> set = new HashSet<>();
        if (backGrid != null) set.add(backGrid);

        for (PartSubnetProxyFront p : peers) {
            IGrid pBack = p.getBackGrid();
            if (pBack != null) set.add(pBack);
        }

        return set;
    }

    /**
     * (Re)synchronize our coordinator membership with our current front-grid.
     * <ul>
     *   <li>If front-grid changed, unregister from old coord and register with new.</li>
     *   <li>If front-grid is the same, just notify the existing coord that our
     *       exposed-origins set may have changed (election recompute).</li>
     * </ul>
     * Safe to call repeatedly; cheap when nothing has changed.
     */
    private void refreshCoordinatorRegistration() {
        IGrid frontGrid = getFrontGridLive();
        SubnetProxyGridCoordinator newCoord = (frontGrid != null)
            ? SubnetProxyGridCoordinator.getOrCreate(frontGrid)
            : null;

        if (newCoord != this.currentFrontGridCoord) {
            if (this.currentFrontGridCoord != null) {
                this.currentFrontGridCoord.unregisterFront(this);
            }

            this.currentFrontGridCoord = newCoord;
            if (newCoord != null) newCoord.registerFront(this);
        } else if (newCoord != null) {
            // Same coord, but our exposedOrigins likely changed; re-elect.
            newCoord.onPeersChanged();
        }
    }

    /**
     * @return our back-grid (Grid A), or null if not currently wired.
     *         Stable accessor for peers and the coordinator.
     */
    public IGrid getBackGrid() {
        return this.gridA;
    }

    /**
     * @return the current exposed-origins set (own back-grid + each peer's
     *         back-grid). Read-only; do not mutate. Used by the coordinator
     *         during election.
     */
    public Set<IGrid> getExposedOrigins() {
        return this.exposedOrigins;
    }

    /**
     * Append peer fronts' filtered local items into {@code out}, then re-filter
     * through {@code myFilter}. Called by {@link SubnetProxyInventoryHandler#getAvailableItems}
     * to enable 1-hop peer aggregation:
     * <ul>
     *   <li>For each peer front on our back-grid:</li>
     *   <li>Skip if peer's back-grid is null or equals our front-grid (loop-back).</li>
     *   <li>Skip if we are not the elected publisher for the peer's back-grid
     *       (handles diamond dedup).</li>
     *   <li>Otherwise, fetch the peer's <em>local-only</em> filtered items
     *       (NOT the peer's full peer-aware listing, which would expose
     *       2-hop items and break the 1-hop visibility rule).</li>
     *   <li>Re-filter with our own filter (conjunction: peer's filter AND ours)
     *       and add to {@code out}.</li>
     * </ul>
     *
     * @param out      output list to append into
     * @param channel  storage channel being queried
     * @param myFilter our own filter (may be null = pass all)
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public <T extends IAEStack<T>> void appendPeerItemsForListing(
            IItemList<T> out,
            IStorageChannel<T> channel,
            Predicate<T> myFilter) {

        if (this.peerFronts.isEmpty()) return;

        IGrid frontGrid = getFrontGridLive();
        if (frontGrid == null) return;

        SubnetProxyGridCoordinator coord = SubnetProxyGridCoordinator.getOrNull(frontGrid);
        if (coord == null) return;

        // Resolve channel identity once per call to avoid per-peer lookups.
        IItemStorageChannel itemCh = AEApi.instance().storage()
            .getStorageChannel(IItemStorageChannel.class);
        IFluidStorageChannel fluidCh = AEApi.instance().storage()
            .getStorageChannel(IFluidStorageChannel.class);

        for (PartSubnetProxyFront peer : this.peerFronts) {
            IGrid peerOrigin = peer.getBackGrid();
            if (peerOrigin == null) continue;
            if (peerOrigin == frontGrid) continue; // immediate loop-back
            if (!coord.isElected(this, peerOrigin)) continue;

            // Get peer's local (back-grid only, no recursion into its peers).
            // Peer's filter is applied here; ours is applied in the merge below.
            SubnetProxyInventoryHandler peerHandler = resolvePeerHandler(peer, channel, itemCh, fluidCh);
            if (peerHandler == null) continue;

            IItemList<T> peerLocal = channel.createList();
            peerHandler.appendLocalAvailableItems(peerLocal);

            for (T s : peerLocal) {
                if (myFilter == null || myFilter.test(s)) out.add(s);
            }
        }
    }

    /**
     * Resolve which of the peer's per-channel handlers to use for the given
     * AE2 storage channel. Centralized so listing and extract paths stay in
     * sync, and so optional channels (gas/essentia) are added in exactly
     * one place. Returns null when the channel is unsupported or the peer
     * lacks a handler for it (e.g. integration mod missing on this side).
     */
    @SuppressWarnings("rawtypes")
    private static SubnetProxyInventoryHandler resolvePeerHandler(
            PartSubnetProxyFront peer,
            IStorageChannel<?> channel,
            IItemStorageChannel itemCh,
            IFluidStorageChannel fluidCh) {

        if (channel == itemCh) return peer.itemHandler;
        if (channel == fluidCh) return peer.fluidHandler;

        if (peer.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()
                && SubnetProxyGasHelper.matchesChannel(channel)) {
            return peer.gasHandler;
        }
        if (peer.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()
                && SubnetProxyEssentiaHelper.matchesChannel(channel)) {
            return peer.essentiaHandler;
        }
        return null;
    }

    /**
     * Symmetric counterpart to {@link #appendPeerItemsForListing} for the
     * extraction path. Iterates peer fronts on our back-grid and, when we are
     * the elected publisher for a peer's origin, extracts from the peer's
     * local cells (not the peer's full peer-aware handler, and not the back
     * grid's monitor). Preserves the 1-hop visibility rule in the extract
     * surface the same way listing does.
     *
     * @param request  remaining request (caller should pass a copy with the
     *                 outstanding amount, not the original).
     * @param myFilter our own filter; peer's filter is applied independently
     *                 by {@link SubnetProxyInventoryHandler#extractFromLocalCells}.
     * @return combined extracted stack, or null if nothing extracted.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public <T extends IAEStack<T>> T extractFromPeerLocals(
            T request,
            Actionable type,
            IActionSource src,
            IStorageChannel<T> channel,
            Predicate<T> myFilter) {

        if (this.peerFronts.isEmpty()) return null;
        if (request == null || request.getStackSize() <= 0) return null;
        if (myFilter != null && !myFilter.test(request)) return null;

        IGrid frontGrid = getFrontGridLive();
        if (frontGrid == null) return null;

        SubnetProxyGridCoordinator coord = SubnetProxyGridCoordinator.getOrNull(frontGrid);
        if (coord == null) return null;

        IItemStorageChannel itemCh = AEApi.instance().storage()
            .getStorageChannel(IItemStorageChannel.class);
        IFluidStorageChannel fluidCh = AEApi.instance().storage()
            .getStorageChannel(IFluidStorageChannel.class);

        long remaining = request.getStackSize();
        T extracted = null;

        // Sort peers by their proxy priority (desc) so extraction respects
        // user-configured priority across the diamond/chain. Each peer's
        // priority is the same value propagated to all its handlers, so the
        // proxy-level getPriority() suffices and avoids per-handler lookup.
        List<PartSubnetProxyFront> orderedPeers = sortedPeersByPriorityDesc(this.peerFronts);

        for (PartSubnetProxyFront peer : orderedPeers) {
            if (remaining <= 0) break;

            IGrid peerOrigin = peer.getBackGrid();
            if (peerOrigin == null) continue;
            if (peerOrigin == frontGrid) continue; // immediate loop-back
            if (!coord.isElected(this, peerOrigin)) continue;

            SubnetProxyInventoryHandler peerHandler = resolvePeerHandler(peer, channel, itemCh, fluidCh);
            if (peerHandler == null) continue;

            T sub = request.copy();
            sub.setStackSize(remaining);
            T got = (T) peerHandler.extractFromLocalCells(sub, type, src);
            if (got == null || got.getStackSize() <= 0) continue;

            remaining -= got.getStackSize();
            if (extracted == null) {
                extracted = got;
            } else {
                extracted.incStackSize(got.getStackSize());
            }
        }

        return extracted;
    }

    /**
     * Return {@code peers} sorted by descending priority. Avoids allocations
     * for the common 0/1-peer case (small networks). The proxy-level priority
     * is identical to its handler priority since {@link #setPriority} keeps
     * them in sync, so {@link #getPriority()} is the canonical sort key.
     */
    private static List<PartSubnetProxyFront> sortedPeersByPriorityDesc(List<PartSubnetProxyFront> peers) {
        if (peers.size() < 2) return peers;
        List<PartSubnetProxyFront> sorted = new ArrayList<>(peers);
        sorted.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        return sorted;
    }

    // ========================= Collision & Cable =========================

    @Override
    public void getBoxes(final IPartCollisionHelper bch) {
        // 5 units deep from the face (z=11..16 in part-local coords)
        bch.addBox(0, 0, 11, 16, 16, 16);
    }

    @Override
    public AECableType getCableConnectionType(final AEPartLocation dir) {
        return AECableType.SMART;
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return 2;
    }

    // ========================= Right-click handling =========================

    @Override
    public boolean onPartActivate(final EntityPlayer player, final EnumHand hand, final Vec3d pos) {
        // If the player is holding the complementary (back) part, attempt to place it
        // on the adjacent block's opposite face instead of activating this part.
        if (isHoldingComplementaryPart(player, hand, CellsPartType.SUBNET_PROXY_BACK)) {
            return tryPlaceComplementaryPart(player, hand);
        }

        // Suppress activation on the same tick the part was placed.
        // AE2's client-side PartPlacement returns PASS, causing Minecraft to
        // try the off-hand, which triggers onPartActivate on the just-placed part.
        TileEntity te = this.getHost() != null ? this.getHost().getTile() : null;
        if (te != null && te.getWorld() != null && te.getWorld().getTotalWorldTime() == this.placedTick) return false;

        if (player.world.isRemote) return true;

        // Check that the back part is present
        if (findBackPart() == null) {
            player.sendMessage(new TextComponentTranslation("chat.cells.subnet_proxy.need_back"));
            return true;
        }

        // Open GUI
        TileEntity guiTe = this.getHost().getTile();
        CellsGuiHandler.openPartGui(player, guiTe, this.getSide(), CellsGuiHandler.GUI_PART_SUBNET_PROXY);

        return true;
    }

    /**
     * Check if the player is holding a specific CELLS part type.
     */
    private boolean isHoldingComplementaryPart(EntityPlayer player, EnumHand hand, CellsPartType expectedType) {
        ItemStack held = player.getHeldItem(hand);
        if (held.isEmpty()) return false;
        if (!(held.getItem() instanceof ItemCellsPart)) return false;

        CellsPartType type = CellsPartType.getById(held.getItemDamage());
        return type == expectedType;
    }

    /**
     * Attempt to place the complementary part on the adjacent block's opposite face.
     * Returns true to consume the click regardless of success.
     */
    private boolean tryPlaceComplementaryPart(EntityPlayer player, EnumHand hand) {
        if (player.world.isRemote) return true;

        EnumFacing facing = this.getSide().getFacing();
        TileEntity selfTile = this.getHost().getTile();
        BlockPos adjacentPos = selfTile.getPos().offset(facing);

        // Delegate to AE2's part placement helper
        AEApi.instance().partHelper().placeBus(
            player.getHeldItem(hand), adjacentPos,
            facing.getOpposite(), player, hand, player.world);

        return true;
    }

    // ========================= Drop handling =========================

    @Override
    public void getDrops(final List<ItemStack> drops, final boolean wrenched) {
        for (int i = 0; i < this.upgrades.getSlots(); i++) {
            ItemStack stack = this.upgrades.getStackInSlot(i);
            if (!stack.isEmpty()) drops.add(stack);
        }
    }

    // ========================= Host access for container/GUI =========================

    public World getHostWorld() {
        TileEntity te = this.getHost() != null ? this.getHost().getTile() : null;
        return te != null ? te.getWorld() : null;
    }

    public BlockPos getHostPos() {
        return this.getHost() != null ? this.getHost().getLocation().getPos() : null;
    }

    // ========================= Model =========================

    @Nonnull
    @Override
    public IPartModel getStaticModels() {
        if (this.isActive() && this.isPowered()) return MODELS_HAS_CHANNEL;
        if (this.isPowered()) return MODELS_ON;
        return MODELS_OFF;
    }
}
