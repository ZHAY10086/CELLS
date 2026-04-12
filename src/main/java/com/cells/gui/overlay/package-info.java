/**
 * Client-side overlay message rendering system.
 * <p>
 * Displays temporary feedback messages (success, error, warning) as colored text overlays
 * on the screen. Messages are rendered with a fade-out animation above the hotbar.
 * <p>
 * This is a better alternative to chat messages/toasts for transient feedback,
 * as the messages are more visible and less likely to be missed during focused GUI interaction.
 * Chat messages are still written for persistence, but the overlay provides immediate visual feedback.
 * <p>
 * <b>Classes:</b>
 * <ul>
 *   <li>{@link com.cells.gui.overlay.MessageHelper}: Client-side entry point that
 *       sends messages to both chat and overlay.</li>
 *   <li>{@link com.cells.gui.overlay.MessageType}: Enum for message severity/color
 *       (SUCCESS=green, ERROR=red, WARNING=yellow).</li>
 *   <li>{@link com.cells.gui.overlay.OverlayMessage}: Data class holding message text,
 *       type, creation time, and fade timing.</li>
 *   <li>{@link com.cells.gui.overlay.OverlayMessageRenderer}: Renders the active
 *       message on screen each frame.</li>
 * </ul>
 */
package com.cells.gui.overlay;
