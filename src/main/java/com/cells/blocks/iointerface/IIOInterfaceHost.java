package com.cells.blocks.iointerface;

import java.util.Arrays;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.cells.blocks.interfacebase.IInterfaceHost;
import com.cells.blocks.interfacebase.IInterfaceLogic;
import com.cells.network.sync.ResourceType;


/**
 * Interface for hosts that combine an Import and Export interface of the same resource type
 * into a single block/part with tab-based direction switching.
 * <p>
 * Unlike {@link com.cells.blocks.combinedinterface.ICombinedInterfaceHost} which switches
 * between resource types (Item/Fluid/Gas/Essentia), this switches between directions
 * (Import/Export). Each direction has its own independent logic instance, except the
 * polling rate which is shared for simplicity.
 */
public interface IIOInterfaceHost extends IInterfaceHost {

    /** Direction tab index for Import. */
    int TAB_IMPORT = 0;

    /** Direction tab index for Export. */
    int TAB_EXPORT = 1;

    /**
     * @return The import logic (always present, direction = import).
     */
    @Nonnull
    IInterfaceLogic getImportLogic();

    /**
     * @return The export logic (always present, direction = export).
     */
    @Nonnull
    IInterfaceLogic getExportLogic();

    /**
     * @return Both logics in order: [import, export].
     */
    default List<IInterfaceLogic> getAllLogics() {
        return Arrays.asList(getImportLogic(), getExportLogic());
    }

    /**
     * @return The logic for the given direction tab index (0=import, 1=export).
     */
    @Nullable
    default IInterfaceLogic getLogicForTab(int tab) {
        switch (tab) {
            case TAB_IMPORT: return getImportLogic();
            case TAB_EXPORT: return getExportLogic();
            default: return null;
        }
    }

    /**
     * Get the logic for the currently active direction tab.
     */
    @Nonnull
    default IInterfaceLogic getActiveLogic() {
        IInterfaceLogic logic = getLogicForTab(getActiveDirectionTab());
        return logic != null ? logic : getImportLogic();
    }

    /**
     * @return The currently active direction tab (0=import, 1=export).
     */
    int getActiveDirectionTab();

    /**
     * Set the active direction tab.
     *
     * @param tab 0=import, 1=export
     */
    void setActiveDirectionTab(int tab);

    /**
     * @return The resource type this IO interface handles (ITEM, FLUID, GAS, ESSENTIA).
     */
    ResourceType getResourceType();

    // ============================== IInterfaceHost overrides ==============================

    /**
     * Whether the active tab is the export direction.
     * This delegates to the active tab so sub-GUIs (maxSlotSize, pollingRate)
     * show the correct direction string.
     */
    @Override
    default boolean isExport() {
        return getActiveDirectionTab() == TAB_EXPORT;
    }

    /**
     * Direction string based on active tab. Used for GUI titles and lang keys.
     */
    @Override
    default String getDirectionString() {
        return getActiveDirectionTab() == TAB_EXPORT ? "export" : "import";
    }
}
