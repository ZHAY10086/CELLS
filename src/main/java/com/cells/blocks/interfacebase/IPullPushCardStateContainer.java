package com.cells.blocks.interfacebase;


/**
 * Provides the Pull/Push Card settings synchronized by an interface container.
 */
public interface IPullPushCardStateContainer {

    /**
     * @return The installed Pull/Push Card interval, or -1 when no card is installed.
     */
    int getAutoPullPushCardInterval();

    /**
     * @return The quantity configured on the installed Pull/Push Card.
     */
    int getAutoPushPullQuantity();
}
