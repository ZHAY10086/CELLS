package com.cells.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.cells.gui.overlay.OverlayMessageRenderer;


/**
 * Handles rendering of HUD overlay elements (e.g., feedback messages).
 * Registered on the Forge event bus in {@link com.cells.proxy.ClientProxy}.
 * <p>
 * Uses two rendering hooks to stay visible above the darkened GUI background:
 * <ul>
 *   <li>{@code RenderGameOverlayEvent.Post(ALL)}: when no GUI screen is open (normal HUD)</li>
 *   <li>{@code GuiScreenEvent.DrawScreenEvent.Post}: when a GUI IS open, so we draw
 *       after the dark background and all GUI elements</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public class OverlayEventHandler {

    /**
     * Render overlay when no GUI screen is open.
     * When a screen IS open, the dark background would cover this, so we skip
     * and let {@link #onDrawScreen} handle it instead.
     */
    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;

        // Skip when a GUI screen is open, onDrawScreen handles that case
        if (Minecraft.getMinecraft().currentScreen != null) return;

        OverlayMessageRenderer.render(event.getResolution().getScaledWidth(), event.getResolution().getScaledHeight());
    }

    /**
     * Render overlay on top of GUI screens (after the dark background and all widgets).
     * This fires at the very end of {@code GuiScreen.drawScreen()}, so our overlay
     * is always on top.
     */
    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        ScaledResolution res = new ScaledResolution(Minecraft.getMinecraft());
        OverlayMessageRenderer.render(res.getScaledWidth(), res.getScaledHeight());
    }
}
