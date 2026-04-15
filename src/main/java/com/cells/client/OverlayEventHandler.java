package com.cells.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.cells.gui.overlay.OverlayMessageRenderer;


/**
 * Handles rendering of HUD overlay elements (e.g., feedback messages).
 * Registered on the Forge event bus in {@link com.cells.proxy.ClientProxy}.
 * <p>
 * Only renders the overlay when a GUI screen is open, since chat messages are
 * already visible when no GUI is present. Uses {@code GuiScreenEvent.DrawScreenEvent.Post}
 * to draw after the dark background and all GUI widgets.
 */
@SideOnly(Side.CLIENT)
public class OverlayEventHandler {

    /**
     * Render overlay on top of GUI screens (after the dark background and all widgets).
     * This fires at the very end of {@code GuiScreen.drawScreen()}, so our overlay
     * is always on top.
     * <p>
     * When no GUI is open, the overlay is not shown because chat messages are
     * already visible and sufficient for feedback.
     */
    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        ScaledResolution res = new ScaledResolution(Minecraft.getMinecraft());
        OverlayMessageRenderer.render(res.getScaledWidth(), res.getScaledHeight());
    }
}
