package com.cells.gui.subnetproxy;

import java.util.function.IntSupplier;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Loader;

import appeng.api.storage.data.IAEItemStack;
import appeng.fluids.items.FluidDummyItem;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;

import com.cells.gui.ResourceRenderer;
import com.cells.gui.overlay.MessageHelper;
import com.cells.gui.slots.AbstractResourceFilterSlot;
import com.cells.network.CellsNetworkHandler;
import com.cells.network.sync.PacketResourceSlot;
import com.cells.network.sync.ResourceType;


/**
 * GUI-only filter slot widget for the Subnet Proxy.
 * <p>
 * Extends {@link AbstractResourceFilterSlot} (which is a {@code GuiCustomSlot},
 * NOT a Minecraft {@code Slot}), completely bypassing vanilla container slot sync.
 * Filter state is synced exclusively via {@link PacketResourceSlot}.
 * <p>
 * All filter types (item, fluid, gas, essentia) are stored as {@link IAEItemStack}
 * in the config inventory (using dummy items for non-item types). This widget
 * converts items based on the current filter mode when the user clicks.
 * <p>
 * Rendering detects dummy items and renders the actual resource icon
 * (fluid texture, gas icon, etc.) instead of the dummy item texture.
 */
public class SubnetProxyFilterWidget extends AbstractResourceFilterSlot<IAEItemStack> {

    private final ContainerSubnetProxy container;
    private final IntSupplier pageOffsetSupplier;

    public SubnetProxyFilterWidget(
            ContainerSubnetProxy container,
            int displaySlot,
            int x, int y,
            IntSupplier pageOffsetSupplier) {
        super(displaySlot, x, y);
        this.container = container;
        this.pageOffsetSupplier = pageOffsetSupplier;
    }

    @Override
    public int getSlot() {
        return this.slot + this.pageOffsetSupplier.getAsInt();
    }

    // ==================== Resource extraction (click handling) ====================

    /**
     * Convert the clicked ItemStack to an IAEItemStack based on the current filter mode.
     * <p>
     * The filter mode determines what type the item is converted to:
     * <ul>
     *   <li>ITEM: plain ItemStack as-is</li>
     *   <li>FLUID: bucket/tank → FluidDummyItem</li>
     *   <li>GAS: gas container → ItemDummyGas</li>
     *   <li>ESSENTIA: aspect container → ItemDummyAspect</li>
     * </ul>
     */
    @Override
    @Nullable
    protected IAEItemStack extractResourceFromStack(ItemStack stack) {
        if (stack.isEmpty()) return null;

        ResourceType mode = this.container.getFilterMode();
        ItemStack converted = SlotFakeConvertingFilter.testConvertForMode(stack, mode);
        if (converted == null) return null;

        return AEItemStack.fromItemStack(converted);
    }

    @Override
    protected boolean canExtractResourceFrom(ItemStack stack) {
        if (stack.isEmpty()) return false;

        ResourceType mode = this.container.getFilterMode();
        return SlotFakeConvertingFilter.testConvertForMode(stack, mode) != null;
    }

    // ==================== Resource get/set (sync via PacketResourceSlot) ====================

    @Override
    @Nullable
    public IAEItemStack getResource() {
        return this.container.getClientFilter(getSlot());
    }

    @Override
    public void setResource(@Nullable IAEItemStack resource) {
        // Send to server via unified resource sync packet.
        // The server will store this in the config inventory.
        CellsNetworkHandler.INSTANCE.sendToServer(
            new PacketResourceSlot(ResourceType.ITEM, getSlot(), resource)
        );
    }

    // ==================== Rendering ====================

    /**
     * Render the filter content. Detects dummy items and renders the
     * actual resource (fluid, gas, essentia) instead of the dummy item icon.
     */
    @Override
    protected void drawResourceContent(Minecraft mc, int mouseX, int mouseY, float partialTicks, IAEItemStack resource) {
        ItemStack stack = resource.getDefinition();

        // Fluid dummy: render fluid texture instead of the dummy item
        if (stack.getItem() instanceof FluidDummyItem) {
            FluidStack fluid = ((FluidDummyItem) stack.getItem()).getFluidStack(stack);
            if (fluid != null) {
                ResourceRenderer.renderFluid(fluid, this.xPos(), this.yPos(), 16, 16);
                return;
            }
        }

        // Gas dummy: render gas texture
        if (Loader.isModLoaded("mekeng") && SubnetProxyGasHelper.isGasDummy(stack)) {
            SubnetProxyGasHelper.renderGas(stack, this.xPos(), this.yPos());
            return;
        }

        // Essentia dummy: render essentia texture
        if (Loader.isModLoaded("thaumicenergistics") && SubnetProxyEssentiaHelper.isEssentiaDummy(stack)) {
            SubnetProxyEssentiaHelper.renderEssentia(stack, this.xPos(), this.yPos());
            return;
        }

        // Regular item: standard item rendering with damage bar overlay
        RenderHelper.enableGUIStandardItemLighting();
        mc.getRenderItem().renderItemIntoGUI(stack, this.xPos(), this.yPos());
        mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRenderer, stack, this.xPos(), this.yPos(), null);
        RenderHelper.disableStandardItemLighting();
    }

    @Override
    protected String getResourceDisplayName(IAEItemStack resource) {
        ItemStack stack = resource.getDefinition();

        // Fluid dummy: show fluid name
        if (stack.getItem() instanceof FluidDummyItem) {
            FluidStack fluid = ((FluidDummyItem) stack.getItem()).getFluidStack(stack);
            if (fluid != null) return fluid.getLocalizedName();
        }

        // Gas dummy: show gas name
        if (Loader.isModLoaded("mekeng") && SubnetProxyGasHelper.isGasDummy(stack)) {
            String name = SubnetProxyGasHelper.getGasName(stack);
            if (name != null) return name;
        }

        // Essentia dummy: show essentia name
        if (Loader.isModLoaded("thaumicenergistics") && SubnetProxyEssentiaHelper.isEssentiaDummy(stack)) {
            String name = SubnetProxyEssentiaHelper.getEssentiaName(stack);
            if (name != null) return name;
        }

        return stack.getDisplayName();
    }

    @Override
    protected boolean resourcesEqual(@Nullable IAEItemStack a, @Nullable IAEItemStack b) {
        if (a == null) return b == null;
        if (b == null) return false;
        return a.equals(b);
    }

    // ==================== JEI conversion ====================

    /**
     * Check if the given filter stack is a duplicate of any existing filter.
     * Compares against ALL slots in the config inventory.
     */
    private boolean isDuplicate(IAEItemStack resource) {
        if (resource == null) return false;

        ItemStack filterStack = resource.getDefinition();
        int totalSlots = this.container.getTotalFilterSlots();

        for (int i = 0; i < totalSlots; i++) {
            if (i == getSlot()) continue;

            IAEItemStack existing = this.container.getClientFilter(i);
            if (existing == null) continue;

            if (Platform.itemComparisons().isSameItem(existing.getDefinition(), filterStack)) {
                return true;
            }
        }

        return false;
    }

    // ==================== Interaction overrides (error feedback) ====================

    @Override
    public void slotClicked(ItemStack clickStack, int mouseButton) {
        // Right-click: delegate to base (clear or per-slot override)
        if (mouseButton == 1) {
            super.slotClicked(clickStack, mouseButton);
            return;
        }

        // Left-click with empty hand: clear
        if (clickStack.isEmpty()) {
            setResource(null);
            return;
        }

        // Left-click with item: try to convert
        IAEItemStack resource = extractResourceFromStack(clickStack);
        if (resource == null) {
            // Show error: item is not valid for the current filter mode
            ResourceType mode = this.container.getFilterMode();
            MessageHelper.error("message.cells.not_valid_content",
                I18n.format("cells.type." + mode.name().toLowerCase()));
            return;
        }

        // Check for duplicates
        if (isDuplicate(resource)) {
            MessageHelper.warning("message.cells.filter_duplicate");
            return;
        }

        setResource(resource);
    }

    @Override
    public boolean acceptResource(Object ingredient) {
        if (ingredient == null) return false;

        IAEItemStack resource = convertToResource(ingredient);
        if (resource == null) {
            // Show error: ingredient not valid for current filter mode
            ResourceType mode = this.container.getFilterMode();
            MessageHelper.error("message.cells.not_valid_content",
                I18n.format("cells.type." + mode.name().toLowerCase()));
            return false;
        }

        // Check for duplicates
        if (isDuplicate(resource)) {
            MessageHelper.warning("message.cells.filter_duplicate");
            return false;
        }

        setResource(resource);
        return true;
    }

    /**
     * Convert a JEI ingredient to an IAEItemStack.
     * <p>
     * For JEI's '+' button, the ingredient already represents the correct type
     * (ItemStack = item, FluidStack = fluid), so we convert directly without
     * going through the filter mode.
     */
    @Override
    @Nullable
    public IAEItemStack convertToResource(Object ingredient) {
        // Direct IAEItemStack passthrough
        if (ingredient instanceof IAEItemStack) return (IAEItemStack) ingredient;

        // ItemStack: convert through filter mode
        if (ingredient instanceof ItemStack) {
            return extractResourceFromStack((ItemStack) ingredient);
        }

        // FluidStack from JEI: convert to FluidDummyItem directly
        // (JEI's '+' already knows it's a fluid, bypass filter mode)
        if (ingredient instanceof FluidStack) {
            IAEItemStack result = SubnetProxyContainerHelper.fluidToFilterAEStack((FluidStack) ingredient);
            if (result != null) return result;
        }

        // Gas from JEI: convert GasStack to ItemDummyGas directly
        // (JEI already knows it's a gas, bypass filter mode)
        if (Loader.isModLoaded("mekeng")) {
            IAEItemStack gasResult = SubnetProxyGasHelper.gasToFilterAEStack(ingredient);
            if (gasResult != null) return gasResult;
        }

        // Essentia from JEI: convert Aspect/EssentiaStack to ItemDummyAspect directly
        if (Loader.isModLoaded("thaumicenergistics")) {
            IAEItemStack essentiaResult = SubnetProxyEssentiaHelper.essentiaToFilterAEStack(ingredient);
            if (essentiaResult != null) return essentiaResult;
        }

        return null;
    }
}
