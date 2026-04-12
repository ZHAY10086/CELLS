package com.cells.blocks.combinedinterface;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Optional;

import appeng.api.AEApi;
import appeng.api.parts.IPart;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.client.gui.widgets.GuiCustomSlot;
import appeng.fluids.util.AEFluidStack;

import com.cells.blocks.interfacebase.AbstractResourceInterfaceGui;
import com.cells.blocks.interfacebase.IInterfaceLogic;
import com.cells.blocks.interfacebase.IResourceInterfaceLogic;
import com.cells.gui.CellsGuiHandler;
import com.cells.gui.QuickAddHelper;
import com.cells.gui.slots.EssentiaFilterSlot;
import com.cells.gui.slots.EssentiaTankSlot;
import com.cells.gui.slots.FluidFilterSlot;
import com.cells.gui.slots.FluidTankSlot;
import com.cells.gui.slots.GasFilterSlot;
import com.cells.gui.slots.GasTankSlot;
import com.cells.gui.slots.ItemFilterSlot;
import com.cells.gui.slots.ItemStorageSlot;
import com.cells.network.CellsNetworkHandler;
import com.cells.network.packets.PacketSwitchTab;
import com.cells.network.sync.PacketQuickAddFilter;
import com.cells.network.sync.ResourceType;


/**
 * GUI for the Combined Import/Export Interface.
 * <p>
 * Displays icon-based tabs above the main GUI area (DiskTerminal visual style) for
 * switching between resource types (Item, Fluid, Gas, Essentia). Each tab shows a
 * representative 16x16 item icon (chest, bucket, gas tank, jar) in a 22x22 tab with
 * 3D borders. The selected tab's bottom border merges with the main GUI panel.
 * <p>
 * When a tab is switched, the filter and storage slots are rebuilt to reflect the
 * new resource type.
 */
public class GuiCombinedInterface
        extends AbstractResourceInterfaceGui<ICombinedInterfaceHost, ContainerCombinedInterface> {

    // ================================= Tab Constants (DiskTerminal style) =================================

    /** Width and height of each tab drawn above the GUI (matches DiskTerminal). */
    private static final int TAB_SIZE = 22;

    /** Y offset for tabs, sits directly above the GUI. */
    private static final int TAB_Y_OFFSET = -22;

    /** Horizontal spacing between tabs. */
    private static final int TAB_SPACING = 2;

    /** Left offset from GUI edge to the first tab. */
    private static final int TAB_LEFT_OFFSET = 4;

    /** Inset of the 16x16 icon within the 22x22 tab. */
    private static final int TAB_ICON_INSET = 3;

    /** Tab background colors matching DiskTerminal visual style. */
    private static final int COLOR_TAB_NORMAL   = 0xFF8B8B8B;
    private static final int COLOR_TAB_HOVER    = 0xFFA0A0A0;
    private static final int COLOR_TAB_SELECTED = 0xFFC6C6C6;

    /** Track the last known activeTabOrdinal to detect @GuiSync changes. */
    private int lastActiveTabOrdinal = -1;

    /** Index of the currently hovered tab, or -1 if none. Updated each frame during drawBG. */
    private int hoveredTabIndex = -1;

    /** Cached icon ItemStacks for each available tab. Initialized once in initGui. */
    private ItemStack[] tabIcons;

    // ================================= Constructors =================================

    public GuiCombinedInterface(final InventoryPlayer inventoryPlayer, final TileEntity tile) {
        super(
            new ContainerCombinedInterface(inventoryPlayer, tile),
            (ICombinedInterfaceHost) tile
        );
    }

    public GuiCombinedInterface(final InventoryPlayer inventoryPlayer, final IPart part) {
        super(
            new ContainerCombinedInterface(inventoryPlayer, part),
            (ICombinedInterfaceHost) part
        );
    }

    // ================================= Abstract method implementations =================================

    @Override
    protected int getCurrentPage() {
        return this.container.currentPage;
    }

    @Override
    protected int getTotalPages() {
        return this.container.totalPages;
    }

    @Override
    protected long getMaxSlotSize() {
        return this.container.maxSlotSize;
    }

    @Override
    protected long getPollingRate() {
        return this.container.pollingRate;
    }

    @Override
    protected void nextPage() {
        this.container.nextPage();
    }

    @Override
    protected void prevPage() {
        this.container.prevPage();
    }

    @Override
    protected int getMaxSlotSizeGuiId() {
        return this.host.isPart() ? CellsGuiHandler.GUI_PART_MAX_SLOT_SIZE : CellsGuiHandler.GUI_MAX_SLOT_SIZE;
    }

    @Override
    protected int getPollingRateGuiId() {
        return this.host.isPart() ? CellsGuiHandler.GUI_PART_POLLING_RATE : CellsGuiHandler.GUI_POLLING_RATE;
    }

    /**
     * Return the active tab's type name so tooltip units show "item"/"gas"/etc instead of "combined".
     */
    @Override
    protected String getUnitTypeName() {
        return getActiveTabFromContainer().name().toLowerCase();
    }

    // ================================= Slot creation =================================

    @Override
    @SuppressWarnings("rawtypes")
    protected GuiCustomSlot createFilterSlotForIndex(int displaySlot, int x, int y) {
        ResourceType activeTab = getActiveTabFromContainer();

        switch (activeTab) {
            case FLUID:
                return new FluidFilterSlot(
                    slot -> this.host.getFluidLogic().getFilter(slot),
                    displaySlot, x, y,
                    () -> this.container.currentPage * SLOTS_PER_PAGE
                );

            case GAS: {
                IInterfaceLogic gasLogic = this.host.getGasLogic();
                if (gasLogic instanceof IResourceInterfaceLogic) {
                    IResourceInterfaceLogic rawLogic = (IResourceInterfaceLogic) gasLogic;
                    return new GasFilterSlot(
                        slot -> (com.mekeng.github.common.me.data.IAEGasStack) rawLogic.getFilter(slot),
                        displaySlot, x, y,
                        () -> this.container.currentPage * SLOTS_PER_PAGE
                    );
                }
                break;
            }

            case ESSENTIA: {
                IInterfaceLogic essentiaLogic = this.host.getEssentiaLogic();
                if (essentiaLogic instanceof IResourceInterfaceLogic) {
                    IResourceInterfaceLogic rawLogic = (IResourceInterfaceLogic) essentiaLogic;
                    return new EssentiaFilterSlot(
                        slot -> (thaumicenergistics.api.storage.IAEEssentiaStack) rawLogic.getFilter(slot),
                        displaySlot, x, y,
                        () -> this.container.currentPage * SLOTS_PER_PAGE
                    );
                }
                break;
            }

            case ITEM:
            default:
                return new ItemFilterSlot(
                    slot -> this.host.getItemLogic().getFilter(slot),
                    displaySlot, x, y,
                    () -> this.container.currentPage * SLOTS_PER_PAGE
                );
        }

        // Fallback: if gas/essentia logic was unexpectedly null, use item slots
        return new ItemFilterSlot(
            slot -> this.host.getItemLogic().getFilter(slot),
            displaySlot, x, y,
            () -> this.container.currentPage * SLOTS_PER_PAGE
        );
    }

    @Override
    protected GuiCustomSlot createTankSlotForIndex(int displaySlot, int x, int y) {
        ResourceType activeTab = getActiveTabFromContainer();

        switch (activeTab) {
            case FLUID: {
                FluidTankHostAdapter fluidAdapter = new FluidTankHostAdapter(this.host);
                return new FluidTankSlot<>(
                    fluidAdapter, displaySlot, displaySlot, x, y,
                    () -> this.container.currentPage * SLOTS_PER_PAGE,
                    () -> getEffectiveMaxSlotSizeForDisplay(displaySlot)
                );
            }

            case GAS: {
                GasTankHostAdapter gasAdapter = new GasTankHostAdapter(this.host);
                return new GasTankSlot<>(
                    gasAdapter, displaySlot, displaySlot, x, y,
                    () -> this.container.currentPage * SLOTS_PER_PAGE,
                    () -> getEffectiveMaxSlotSizeForDisplay(displaySlot)
                );
            }

            case ESSENTIA: {
                EssentiaTankHostAdapter essentiaAdapter = new EssentiaTankHostAdapter(this.host);
                return new EssentiaTankSlot<>(
                    essentiaAdapter, displaySlot, displaySlot, x, y,
                    () -> this.container.currentPage * SLOTS_PER_PAGE,
                    () -> getEffectiveMaxSlotSizeForDisplay(displaySlot)
                );
            }

            case ITEM:
            default: {
                ItemStorageHostAdapter itemAdapter = new ItemStorageHostAdapter(this.host);
                return new ItemStorageSlot<>(
                    itemAdapter, displaySlot, displaySlot, x, y,
                    () -> this.container.currentPage * SLOTS_PER_PAGE,
                    () -> getEffectiveMaxSlotSizeForDisplay(displaySlot)
                );
            }
        }
    }

    // ================================= Init =================================

    @Override
    public void initGui() {
        super.initGui();

        // Store current tab ordinal so we can detect @GuiSync changes in drawScreen
        this.lastActiveTabOrdinal = this.container.activeTabOrdinal;

        // Initialize tab icons (chest, bucket, gas tank, jar) for the available tabs
        initTabIcons();
    }

    // ================================= Draw =================================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Detect @GuiSync tab changes from the server and rebuild slots only.
        // This handles server-initiated tab changes and the case where the server's sync
        // overwrites our optimistic client-side update (race between packet and @GuiSync).
        if (this.container.activeTabOrdinal != this.lastActiveTabOrdinal) {
            this.lastActiveTabOrdinal = this.container.activeTabOrdinal;
            this.guiSlots.clear();
            this.createResourceSlots();
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        // Draw tab tooltip for the hovered tab
        if (this.hoveredTabIndex >= 0) {
            List<ResourceType> tabs = this.host.getAvailableTabs();

            if (this.hoveredTabIndex < tabs.size()) {
                String tabName = I18n.format("cells.type." + tabs.get(this.hoveredTabIndex).name().toLowerCase());
                this.drawHoveringText(Collections.singletonList(tabName), mouseX, mouseY);
            }
        }
    }

    @Override
    public void drawBG(int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawBG(offsetX, offsetY, mouseX, mouseY);

        // Draw icon-based tabs above the GUI (DiskTerminal style)
        drawTabs(offsetX, offsetY, mouseX, mouseY);
    }

    // ================================= Tab click handling =================================

    @Override
    protected void mouseClicked(int xCoord, int yCoord, int btn) throws IOException {
        // Handle tab clicks before anything else (left-click only)
        if (btn == 0 && handleTabClick(xCoord, yCoord)) return;

        super.mouseClicked(xCoord, yCoord, btn);
    }

    // ================================= Quick-add =================================

    @Override
    protected boolean handleQuickAdd(Slot hoveredSlot) {
        ResourceType activeTab = getActiveTabFromContainer();

        switch (activeTab) {
            case ITEM: {
                ItemStack item = QuickAddHelper.getItemUnderCursor(hoveredSlot);
                if (!item.isEmpty()) {
                    IAEItemStack iaeItem = AEApi.instance().storage()
                        .getStorageChannel(IItemStorageChannel.class).createStack(item);
                    CellsNetworkHandler.INSTANCE.sendToServer(new PacketQuickAddFilter(ResourceType.ITEM, iaeItem));
                    return true;
                }
                break;
            }

            case FLUID: {
                FluidStack fluid = QuickAddHelper.getFluidUnderCursor(hoveredSlot);
                if (fluid != null) {
                    CellsNetworkHandler.INSTANCE.sendToServer(
                        new PacketQuickAddFilter(ResourceType.FLUID, AEFluidStack.fromFluidStack(fluid))
                    );
                    return true;
                }
                if (QuickAddHelper.hasAnythingUnderCursor(hoveredSlot)) {
                    QuickAddHelper.sendNoValidError("fluid");
                }
                return true;
            }

            case GAS: {
                return handleGasQuickAdd(hoveredSlot);
            }

            case ESSENTIA: {
                return handleEssentiaQuickAdd(hoveredSlot);
            }

            default:
                break;
        }

        return false;
    }

    /**
     * Handle gas quick-add. Isolated with @Optional.Method to prevent class loading
     * issues when MekanismEnergistics is not present.
     */
    @Optional.Method(modid = "mekeng")
    private boolean handleGasQuickAdd(Slot hoveredSlot) {
        mekanism.api.gas.GasStack gas = QuickAddHelper.getGasUnderCursor(hoveredSlot);

        if (gas != null) {
            CellsNetworkHandler.INSTANCE.sendToServer(new PacketQuickAddFilter(
                ResourceType.GAS,
                com.mekeng.github.common.me.data.impl.AEGasStack.of(gas))
            );
            return true;
        }

        if (QuickAddHelper.hasAnythingUnderCursor(hoveredSlot)) {
            QuickAddHelper.sendNoValidError("gas");
        }

        return true;
    }

    /**
     * Handle essentia quick-add. Isolated with @Optional.Method to prevent class loading
     * issues when ThaumicEnergistics is not present.
     */
    @Optional.Method(modid = "thaumicenergistics")
    private boolean handleEssentiaQuickAdd(Slot hoveredSlot) {
        thaumicenergistics.api.EssentiaStack essentia = QuickAddHelper.getEssentiaUnderCursor(hoveredSlot);

        if (essentia != null) {
            CellsNetworkHandler.INSTANCE.sendToServer(new PacketQuickAddFilter(
                ResourceType.ESSENTIA,
                thaumicenergistics.integration.appeng.AEEssentiaStack.fromEssentiaStack(essentia)
            ));
            return true;
        }

        if (QuickAddHelper.hasAnythingUnderCursor(hoveredSlot)) {
            QuickAddHelper.sendNoValidError("essentia");
        }

        return true;
    }

    // ================================= Helpers =================================

    /**
     * Get the active tab from the container's @GuiSync field.
     */
    private ResourceType getActiveTabFromContainer() {
        int ordinal = this.container.activeTabOrdinal;

        if (ordinal >= 0 && ordinal < ResourceType.values().length) {
            return ResourceType.values()[ordinal];
        }

        return ResourceType.ITEM;
    }

    // ================================= Tab Drawing =================================

    /**
     * Draw icon-based tabs above the GUI using the DiskTerminal visual style.
     * Each tab shows a 16x16 item icon centered in a 22x22 tab with 3D borders.
     * The selected tab's bottom border is erased to visually merge with the GUI panel.
     */
    private void drawTabs(int offsetX, int offsetY, int mouseX, int mouseY) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();

        List<ResourceType> tabs = this.host.getAvailableTabs();
        int tabY = offsetY + TAB_Y_OFFSET;
        this.hoveredTabIndex = -1;

        for (int i = 0; i < tabs.size(); i++) {
            int tabX = offsetX + TAB_LEFT_OFFSET + (i * (TAB_SIZE + TAB_SPACING));
            boolean isSelected = tabs.get(i).ordinal() == this.container.activeTabOrdinal;
            boolean isHovered = mouseX >= tabX && mouseX < tabX + TAB_SIZE
                && mouseY >= tabY && mouseY < tabY + TAB_SIZE;

            if (isHovered) this.hoveredTabIndex = i;

            // Tab background color
            int bgColor;
            if (isSelected) {
                bgColor = COLOR_TAB_SELECTED;
            } else if (isHovered) {
                bgColor = COLOR_TAB_HOVER;
            } else {
                bgColor = COLOR_TAB_NORMAL;
            }
            drawRect(tabX, tabY, tabX + TAB_SIZE, tabY + TAB_SIZE, bgColor);

            // 3D border effect: white highlight on top+left, dark shadow on right+bottom
            drawRect(tabX, tabY, tabX + TAB_SIZE, tabY + 1, 0xFFFFFFFF);                       // top
            drawRect(tabX, tabY, tabX + 1, tabY + TAB_SIZE, 0xFFFFFFFF);                        // left
            drawRect(tabX + TAB_SIZE - 1, tabY, tabX + TAB_SIZE, tabY + TAB_SIZE, 0xFF555555);  // right

            // Selected tab: erase bottom border to merge with the GUI panel; else draw bottom shadow
            if (isSelected) {
                drawRect(tabX + 1, tabY + TAB_SIZE - 1, tabX + TAB_SIZE - 1, tabY + TAB_SIZE, COLOR_TAB_SELECTED);
            } else {
                drawRect(tabX, tabY + TAB_SIZE - 1, tabX + TAB_SIZE, tabY + TAB_SIZE, 0xFF555555);
            }

            // Render the item icon (16x16) centered within the 22x22 tab
            if (this.tabIcons != null && i < this.tabIcons.length && !this.tabIcons[i].isEmpty()) {
                RenderHelper.enableGUIStandardItemLighting();
                this.itemRender.renderItemIntoGUI(this.tabIcons[i], tabX + TAB_ICON_INSET, tabY + TAB_ICON_INSET);
                RenderHelper.disableStandardItemLighting();
                GlStateManager.disableLighting();
            }
        }

        // Restore GL state
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
    }

    /**
     * Handle a click in the tab area. Checks if the click falls on any tab and
     * switches to it if so.
     *
     * @return true if a tab was clicked (consuming the click), false otherwise
     */
    private boolean handleTabClick(int mouseX, int mouseY) {
        List<ResourceType> tabs = this.host.getAvailableTabs();
        int tabY = this.guiTop + TAB_Y_OFFSET;

        // Quick rejection: click is not in the tab row
        if (mouseY < tabY || mouseY >= tabY + TAB_SIZE) return false;

        for (int i = 0; i < tabs.size(); i++) {
            int tabX = this.guiLeft + TAB_LEFT_OFFSET + (i * (TAB_SIZE + TAB_SPACING));

            if (mouseX >= tabX && mouseX < tabX + TAB_SIZE) {
                ResourceType newTab = tabs.get(i);

                // Already on this tab, consume the click but do nothing
                if (newTab.ordinal() == this.container.activeTabOrdinal) return true;

                // Send tab switch to server
                CellsNetworkHandler.INSTANCE.sendToServer(new PacketSwitchTab(newTab));

                // Optimistically update client-side to avoid visual delay
                this.host.setActiveTab(newTab);
                this.container.activeTabOrdinal = newTab.ordinal();
                this.lastActiveTabOrdinal = newTab.ordinal();

                // Rebuild resource slots for the new tab
                this.guiSlots.clear();
                this.createResourceSlots();
                return true;
            }
        }

        return false;
    }

    // ================================= Tab Icons =================================

    /**
     * Initialize the tab icon ItemStacks for all available tabs.
     * Uses representative items: chest for items, bucket for fluids,
     * gas tank for gas (Mekanism), jar for essentia (Thaumcraft).
     * Falls back to glass bottles when optional mods are absent.
     */
    private void initTabIcons() {
        List<ResourceType> tabs = this.host.getAvailableTabs();
        this.tabIcons = new ItemStack[tabs.size()];

        for (int i = 0; i < tabs.size(); i++) {
            this.tabIcons[i] = getIconForResourceType(tabs.get(i));
        }
    }

    /**
     * Get the icon ItemStack for a given resource type.
     */
    private static ItemStack getIconForResourceType(ResourceType type) {
        switch (type) {
            case ITEM:
                return new ItemStack(Blocks.CHEST);
            case FLUID:
                return new ItemStack(Items.WATER_BUCKET);
            case GAS:
                if (Loader.isModLoaded("mekanism")) return getGasTabIcon();
                return new ItemStack(Items.GLASS_BOTTLE);
            case ESSENTIA:
                if (Loader.isModLoaded("thaumcraft")) return getEssentiaTabIcon();
                return new ItemStack(Items.GLASS_BOTTLE);
            default:
                return new ItemStack(Blocks.BARRIER);
        }
    }

    /**
     * Get the Mekanism gas tank icon. Isolated in its own method with @Optional.Method
     * so class loading doesn't fail when Mekanism is absent at runtime.
     */
    @Optional.Method(modid = "mekanism")
    private static ItemStack getGasTabIcon() {
        return new ItemStack(mekanism.common.MekanismBlocks.GasTank);
    }

    /**
     * Get the Thaumcraft jar icon. Isolated in its own method with @Optional.Method
     * so class loading doesn't fail when Thaumcraft is absent at runtime.
     */
    @Optional.Method(modid = "thaumcraft")
    private static ItemStack getEssentiaTabIcon() {
        net.minecraft.block.Block jar = thaumcraft.api.blocks.BlocksTC.jarNormal;
        if (jar != null) return new ItemStack(jar);
        return new ItemStack(Items.GLASS_BOTTLE);
    }

    // ================================= Host Adapters =================================

    /**
     * Adapter that wraps the combined host's fluid logic to satisfy FluidTankSlot.IFluidTankHost.
     */
    private static class FluidTankHostAdapter implements FluidTankSlot.IFluidTankHost {
        private final ICombinedInterfaceHost host;

        FluidTankHostAdapter(ICombinedInterfaceHost host) {
            this.host = host;
        }

        @Override
        public FluidStack getFluidInTank(int tankIndex) {
            return this.host.getFluidLogic().getFluidInTank(tankIndex);
        }

        @Override
        public long getFluidAmount(int tankIndex) {
            return this.host.getFluidLogic().getSlotAmount(tankIndex);
        }

        @Override
        public String getTypeName() {
            return "fluid";
        }

        @Override
        public boolean isExport() {
            return this.host.isExport();
        }
    }

    /**
     * Adapter that wraps the combined host's item logic to satisfy ItemStorageSlot.IItemStorageHost.
     */
    private static class ItemStorageHostAdapter implements ItemStorageSlot.IItemStorageHost {
        private final ICombinedInterfaceHost host;

        ItemStorageHostAdapter(ICombinedInterfaceHost host) {
            this.host = host;
        }

        @Override
        public ItemStack getItemInStorage(int slotIndex) {
            return this.host.getItemLogic().getItemInSlot(slotIndex);
        }

        @Override
        public long getItemAmount(int slotIndex) {
            return this.host.getItemLogic().getSlotAmount(slotIndex);
        }

        @Override
        public String getTypeName() {
            return "item";
        }

        @Override
        public boolean isExport() {
            return this.host.isExport();
        }
    }

    /**
     * Adapter that wraps the combined host's gas logic to satisfy GasTankSlot.IGasTankHost.
     * Delegates gas tank data through the IInterfaceLogic using raw-typed access,
     * since the combined host only exposes gas logic via the untyped IInterfaceLogic interface.
     */
    @Optional.Interface(iface = "com.cells.gui.slots.GasTankSlot$IGasTankHost", modid = "mekeng")
    private static class GasTankHostAdapter implements GasTankSlot.IGasTankHost {
        private final ICombinedInterfaceHost host;

        GasTankHostAdapter(ICombinedInterfaceHost host) {
            this.host = host;
        }

        @Override
        @Optional.Method(modid = "mekeng")
        public mekanism.api.gas.GasStack getGasInTank(int tankIndex) {
            IInterfaceLogic logic = this.host.getGasLogic();
            if (logic == null) return null;

            // GasInterfaceLogic exposes getGasInTank; access via cast
            if (logic instanceof com.cells.integration.mekanismenergistics.GasInterfaceLogic) {
                return ((com.cells.integration.mekanismenergistics.GasInterfaceLogic) logic).getGasInTank(tankIndex);
            }

            return null;
        }

        @Override
        public long getGasAmount(int tankIndex) {
            IInterfaceLogic logic = this.host.getGasLogic();
            if (logic instanceof IResourceInterfaceLogic) {
                return ((IResourceInterfaceLogic<?, ?>) logic).getSlotAmount(tankIndex);
            }
            return 0;
        }

        @Override
        public String getTypeName() {
            return "gas";
        }

        @Override
        public boolean isExport() {
            return this.host.isExport();
        }
    }

    /**
     * Adapter that wraps the combined host's essentia logic to satisfy EssentiaTankSlot.IEssentiaTankHost.
     * Delegates essentia slot data through the IInterfaceLogic using raw-typed access.
     */
    @Optional.Interface(iface = "com.cells.gui.slots.EssentiaTankSlot$IEssentiaTankHost", modid = "thaumicenergistics")
    private static class EssentiaTankHostAdapter implements EssentiaTankSlot.IEssentiaTankHost {
        private final ICombinedInterfaceHost host;

        EssentiaTankHostAdapter(ICombinedInterfaceHost host) {
            this.host = host;
        }

        @Override
        @Optional.Method(modid = "thaumicenergistics")
        public thaumicenergistics.api.EssentiaStack getEssentiaInSlot(int slotIndex) {
            IInterfaceLogic logic = this.host.getEssentiaLogic();
            if (logic == null) return null;

            // EssentiaInterfaceLogic exposes getEssentiaInSlot; access via cast
            if (logic instanceof com.cells.integration.thaumicenergistics.EssentiaInterfaceLogic) {
                return ((com.cells.integration.thaumicenergistics.EssentiaInterfaceLogic) logic).getEssentiaInSlot(slotIndex);
            }

            return null;
        }

        @Override
        public long getEssentiaAmount(int slotIndex) {
            IInterfaceLogic logic = this.host.getEssentiaLogic();
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
