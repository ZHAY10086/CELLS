package com.cells.parts.subnetproxy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
import appeng.api.implementations.IPowerChannelState;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
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

import net.minecraftforge.fluids.Fluid;
import appeng.items.parts.PartModels;
import appeng.capabilities.Capabilities;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.MachineSource;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;
import appeng.parts.automation.UpgradeInventory;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.util.inv.IAEAppEngInventory;
import appeng.util.inv.InvOperation;
import appeng.util.item.AEItemStack;

import com.cells.Tags;
import com.cells.config.CellsConfig;
import com.cells.gui.CellsGuiHandler;
import com.cells.integration.mekanismenergistics.MekanismEnergisticsIntegration;
import com.cells.integration.thaumicenergistics.ThaumicEnergisticsIntegration;
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
        implements IPowerChannelState, ICellContainer, IAEAppEngInventory, IGridTickable {

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
     */
    private final IActionSource proxySource = new MachineSource(this);

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
     * Previous snapshot of items visible through our handler (local cells + filter).
     * Used to compute deltas for forwarding to Grid B. Null before first snapshot.
     */
    private IItemList<IAEItemStack> lastItemSnapshot;

    /** Previous snapshot of fluids visible through our handler. */
    private IItemList<IAEFluidStack> lastFluidSnapshot;

    /**
     * Dirty flag for delta forwarding. Set when Grid A's monitor fires a change
     * notification. Cleared after the snapshot diff runs in {@link #tickingRequest}.
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
                return 0;
            }
        };

        // Allocate config inventory for all pages. At max upgrades this can be 63 * (1 + upgradeSlots)
        // slots in memory, but NBT serialization is sparse (only non-empty slots written).
        int totalSlots = SLOTS_PER_PAGE * getMaxPages();
        this.config = new AppEngInternalInventory(this, totalSlots, 1);

        // Create passthrough handlers
        this.itemHandler = new SubnetProxyInventoryHandler<>(
            AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class), this.priority);
        this.fluidHandler = new SubnetProxyInventoryHandler<>(
            AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class), this.priority);

        // Optional mod handlers (created only if the mod is loaded to avoid class loading errors)
        if (MekanismEnergisticsIntegration.isModLoaded()) {
            this.gasHandler = SubnetProxyGasHelper.createHandler(this.priority);
        }
        if (ThaumicEnergisticsIntegration.isModLoaded()) {
            this.essentiaHandler = SubnetProxyEssentiaHelper.createHandler(this.priority);
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

        if (channel == AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class)) {
            return this.itemHandler.asCellArray();
        }

        if (channel == AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class)) {
            return this.fluidHandler.asCellArray();
        }

        // Gas channel (MekanismEnergistics)
        if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
            List<IMEInventoryHandler> result = SubnetProxyGasHelper.asCellArray(this.gasHandler, channel);
            if (!result.isEmpty()) return result;
        }

        // Essentia channel (ThaumicEnergistics)
        if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
            List<IMEInventoryHandler> result = SubnetProxyEssentiaHelper.asCellArray(this.essentiaHandler, channel);
            if (!result.isEmpty()) return result;
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
            this.lastItemSnapshot = null;
            this.lastFluidSnapshot = null;
            if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
                SubnetProxyGasHelper.resetSnapshot(this.gasHandler);
            }
            if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
                SubnetProxyEssentiaHelper.resetSnapshot(this.essentiaHandler);
            }

            return;
        }

        try {
            IGrid gridA = back.getProxy().getGrid();
            IStorageGrid sg = gridA.getCache(IStorageGrid.class);

            IItemStorageChannel itemChannel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
            IFluidStorageChannel fluidChannel = AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class);

            // Collect cell handlers from non-passthrough providers only
            List<IMEInventoryHandler<IAEItemStack>> localItemCells = new ArrayList<>();
            List<IMEInventoryHandler<IAEFluidStack>> localFluidCells = new ArrayList<>();

            for (IGridNode node : gridA.getNodes()) {
                IGridHost host = node.getMachine();
                if (!(host instanceof ICellProvider)) continue;

                // Skip our own proxy to prevent proxy-to-proxy chains
                if (host instanceof PartSubnetProxyFront) continue;

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

            this.itemHandler.setLocalCells(localItemCells);
            this.itemHandler.setMonitor(sg.getInventory(itemChannel));

            this.fluidHandler.setLocalCells(localFluidCells);
            this.fluidHandler.setMonitor(sg.getInventory(fluidChannel));

            // Gas channel (MekanismEnergistics)
            if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
                SubnetProxyGasHelper.updateSources(this.gasHandler, gridA, sg);
            }

            // Essentia channel (ThaumicEnergistics)
            if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
                SubnetProxyEssentiaHelper.updateSources(this.essentiaHandler, gridA, sg);
            }

            // Register on Grid A's monitors for delta forwarding.
            // The listener signals us to wake up and diff on Grid B's tick manager.
            registerGridAListeners(sg.getInventory(itemChannel), sg.getInventory(fluidChannel));

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
            this.lastItemSnapshot = takeSnapshot(this.itemHandler, itemChannel);
            this.lastFluidSnapshot = takeSnapshot(this.fluidHandler, fluidChannel);

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

            this.lastItemSnapshot = null;
            this.lastFluidSnapshot = null;
        }
    }

    /** Take a snapshot of the handler's current listing for delta comparison. */
    private <T extends IAEStack<T>> IItemList<T> takeSnapshot(
            SubnetProxyInventoryHandler<T> handler,
            IStorageChannel<T> channel) {
        IItemList<T> snapshot = channel.createList();
        handler.getAvailableItems(snapshot);
        return snapshot;
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
     * When Grid A's storage changes, this listener fires and signals the front part
     * to wake up on Grid B's tick manager. The actual diff computation + forwarding
     * happens in {@link #tickingRequest}, which diffs our handler's
     * {@code getAvailableItems()} against the previous snapshot.
     * <p>
     * This design guarantees anti-loop safety: the diff reads only from local cells
     * (same as the handler's listing), so passthrough-originated changes on Grid A
     * (storage bus → ME Interface → another grid) are never forwarded to Grid B.
     * Only items physically stored in Grid A's local cells pass through.
     */
    @SuppressWarnings("rawtypes")
    private class GridAListener implements IMEMonitorHandlerReceiver {

        @SuppressWarnings("unchecked")
        @Override
        public void postChange(final IBaseMonitor monitor, final Iterable change, final IActionSource actionSource) {
            // Quick filter check: if the proxy has a filter, only wake up when
            // at least one changed item matches the filter. This avoids O(N) diffs
            // when Grid A is busy with items the subnet doesn't care about.
            boolean relevant;
            if (monitor == registeredItemMonitor) {
                relevant = itemHandler.matchesAny(change);
            } else if (monitor == registeredFluidMonitor) {
                relevant = fluidHandler.matchesAny(change);
            } else {
                // Gas or essentia channel, assume relevant (uncommon, cheap to diff)
                relevant = true;
            }

            if (!relevant) return;

            deltasDirty = true;
            alertGridBTick();
        }

        @Override
        public void onListUpdate() {
            // Full list reset on Grid A (e.g. power loss/restore). Must re-diff.
            deltasDirty = true;
            alertGridBTick();
        }

        @Override
        public boolean isValid(final Object verificationToken) {
            return verificationToken == listenerToken;
        }
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
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerGridAListeners(IMEMonitor itemMonitor, IMEMonitor fluidMonitor) {
        unregisterGridAListeners();

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

        // Alertable so the Grid A listener can wake us. Sleep when not dirty.
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
        computeAndForwardDeltas();

        return TickRateModulation.SLEEP;
    }

    /**
     * Compute snapshot diffs for each channel and forward non-empty deltas to Grid B.
     * <p>
     * Each channel's handler is queried via {@code getAvailableItems()}, which reads
     * only from local cells with the current filter applied. The diff against the
     * previous snapshot produces exactly the set of changes that Grid B should see.
     * <p>
     * <b>Anti-loop guarantee:</b> Since {@code getAvailableItems()} excludes passthrough
     * buses by design, items that Grid A sees through bridges to other grids never
     * appear in the snapshot and are never forwarded. This prevents ghost counting
     * in all chain topologies (A→B→A, A→B→C→A, etc.).
     */
    @SuppressWarnings("unchecked")
    private void computeAndForwardDeltas() {
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
        this.lastItemSnapshot = computeChannelDelta(
            this.itemHandler, itemChannel, this.lastItemSnapshot, gridB);

        // Fluid channel delta
        this.lastFluidSnapshot = computeChannelDelta(
            this.fluidHandler, fluidChannel, this.lastFluidSnapshot, gridB);

        // Gas channel (MekanismEnergistics)
        if (this.gasHandler != null && MekanismEnergisticsIntegration.isModLoaded()) {
            SubnetProxyGasHelper.computeAndForwardDelta(
                this.gasHandler, gridB, this.proxySource);
        }

        // Essentia channel (ThaumicEnergistics)
        if (this.essentiaHandler != null && ThaumicEnergisticsIntegration.isModLoaded()) {
            SubnetProxyEssentiaHelper.computeAndForwardDelta(
                this.essentiaHandler, gridB, this.proxySource);
        }
    }

    /**
     * Compute the delta between the handler's current listing and the previous
     * snapshot, forward non-empty diffs to Grid B, and return the new snapshot.
     *
     * @param handler  the passthrough handler to query
     * @param channel  the storage channel
     * @param previous the previous snapshot (null on first run)
     * @param gridB    Grid B's storage grid for posting deltas
     * @return the new snapshot (to replace {@code previous})
     */
    private <T extends IAEStack<T>> IItemList<T> computeChannelDelta(
            SubnetProxyInventoryHandler<T> handler,
            IStorageChannel<T> channel,
            IItemList<T> previous,
            IStorageGrid gridB) {

        // Take current snapshot from the handler (reads local cells + filter only)
        IItemList<T> current = channel.createList();
        handler.getAvailableItems(current);

        if (previous == null) {
            // First snapshot: no delta to forward, just establish baseline.
            // Grid B already got the full listing via getCellArray → getAvailableItems.
            return current;
        }

        // Diff: negate previous, merge current, collect non-zero entries
        List<T> changes = new ArrayList<>();
        for (T was : previous) was.setStackSize(-was.getStackSize());

        for (T now : current) previous.add(now);

        for (T entry : previous) {
            if (entry.getStackSize() != 0) changes.add(entry);
        }

        if (!changes.isEmpty()) {
            gridB.postAlterationOfStoredItems(channel, changes, this.proxySource);
        }

        return current;
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

    public net.minecraft.world.World getHostWorld() {
        TileEntity te = this.getHost() != null ? this.getHost().getTile() : null;
        return te != null ? te.getWorld() : null;
    }

    public net.minecraft.util.math.BlockPos getHostPos() {
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
