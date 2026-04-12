package com.cells.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Optional;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import appeng.capabilities.Capabilities;
import appeng.util.Platform;

import com.jaquadro.minecraft.storagedrawers.api.capabilities.IItemRepository;


/**
 * /inspectSlot [resource1] [resource2] ...
 * <p>
 * Inspects the block the player is looking at, queries ALL available capabilities
 * and displays each one's slots/tanks sequentially, separated by blank lines.
 * <p>
 * Capability order: IItemRepository → IItemHandler → IFluidHandler → IGasHandler → IAspectContainer.
 * <p>
 * Supports 0 or more resource arguments. Tab-completion offers all resource types
 * that are applicable to the detected capabilities.
 * All output is localized via TextComponentTranslation.
 */
public class InspectSlotCommand extends CommandBase {

    // Mod IDs for optional integrations
    private static final String MOD_MEKANISM = "mekanism";
    private static final String MOD_THAUMCRAFT = "thaumcraft";

    // Block interaction reach distance (server-side)
    private static final double REACH_DISTANCE = 5.0D;

    @Override
    @Nonnull
    public String getName() {
        return "inspectSlots";
    }

    @Override
    @Nonnull
    public String getUsage(@Nonnull ICommandSender sender) {
        return "commands.cells.inspect_slots.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    // ================================= Execution =================================

    @Override
    public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args) {
        if (!(sender.getCommandSenderEntity() instanceof EntityPlayerMP)) {
            sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.not_player"));
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) sender.getCommandSenderEntity();

        // Ray trace from the player's eyes
        RayTraceResult hit = rayTrace(player);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.no_block"));
            return;
        }

        BlockPos pos = hit.getBlockPos();
        TileEntity te = player.world.getTileEntity(pos);
        if (te == null) {
            sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.no_tile", pos.getX(), pos.getY(), pos.getZ()));
            return;
        }

        // Determine the face we hit so we can query capabilities from that side
        EnumFacing hitFace = hit.sideHit;

        // Send header
        sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.header",
                pos.getX(), pos.getY(), pos.getZ()));

        // Try ALL capabilities sequentially. Each detected capability section is shown
        // separated by a blank line. This way blocks that expose multiple capabilities
        // (e.g. a Combined Interface with items + fluids + gas) show everything at once.
        boolean anyFound = false;

        // 1. IItemRepository (slotless bulk storage, e.g. Storage Drawers)
        if (Capabilities.ITEM_REPOSITORY_CAPABILITY != null
                && te.hasCapability(Capabilities.ITEM_REPOSITORY_CAPABILITY, hitFace)) {
            IItemRepository repo = te.getCapability(Capabilities.ITEM_REPOSITORY_CAPABILITY, hitFace);
            if (repo != null) {
                if (anyFound) sender.sendMessage(new TextComponentTranslation(""));
                inspectItemRepository(sender, repo, args);
                anyFound = true;
            }
        }

        // 2. IItemHandler (standard Forge item capability)
        if (te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, hitFace)) {
            IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, hitFace);
            if (handler != null && handler.getSlots() > 0) {
                if (anyFound) sender.sendMessage(new TextComponentTranslation(""));
                inspectItemHandler(sender, handler, args);
                anyFound = true;
            }
        }

        // 3. IFluidHandler
        if (te.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, hitFace)) {
            IFluidHandler handler = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, hitFace);
            if (handler != null) {
                if (anyFound) sender.sendMessage(new TextComponentTranslation(""));
                inspectFluidHandler(sender, handler, args);
                anyFound = true;
            }
        }

        // 4. IGasHandler (Mekanism)
        if (Platform.isModLoaded(MOD_MEKANISM)) {
            if (tryInspectGas(sender, te, hitFace, args, anyFound)) anyFound = true;
        }

        // 5. IAspectContainer (Thaumcraft essentia, not a Forge capability, uses instanceof)
        if (Loader.isModLoaded(MOD_THAUMCRAFT)) {
            if (tryInspectEssentia(sender, te, args, anyFound)) anyFound = true;
        }

        if (!anyFound) {
            sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.no_capability"));
        }
    }

    // ================================= Ray Trace =================================

    /**
     * Server-side ray trace from the player's eyes toward where they're looking.
     */
    private static RayTraceResult rayTrace(EntityPlayerMP player) {
        Vec3d start = player.getPositionEyes(1.0F);
        Vec3d lookVec = player.getLookVec();
        Vec3d end = start.add(lookVec.scale(REACH_DISTANCE));

        return player.world.rayTraceBlocks(start, end, false, false, true);
    }

    // ================================= IItemRepository =================================

    /**
     * Inspect a slotless IItemRepository (e.g. Storage Drawers).
     * Since there are no real "slots", we list each unique item record as a virtual slot,
     * then show simulated insertion for requested items.
     */
    private void inspectItemRepository(ICommandSender sender, IItemRepository repo, String[] args) {
        sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.type.item_repository"));

        NonNullList<IItemRepository.ItemRecord> records = repo.getAllItems();
        if (records.isEmpty()) {
            sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.empty_inventory"));
        }

        // Display each record as a "virtual slot"
        int index = 0;
        for (IItemRepository.ItemRecord record : records) {
            ItemStack proto = record.itemPrototype;
            int count = record.count;
            int capacity = repo.getItemCapacity(proto);
            String itemName = proto.getDisplayName();

            sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.slot_content",
                    index, itemName, count, capacity));
            index++;
        }

        // Simulated insertion for each argument
        for (String arg : args) {
            Item item = Item.getByNameOrId(arg);
            if (item == null) {
                sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.unknown_item", arg));
                continue;
            }

            ItemStack testStack = new ItemStack(item, 1);
            // Simulate inserting a full stack to see how much would be accepted
            ItemStack bigStack = new ItemStack(item, item.getItemStackLimit(testStack));
            ItemStack remainder = repo.insertItem(bigStack, true);
            int canInsert = bigStack.getCount() - remainder.getCount();

            if (canInsert > 0) {
                sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.can_insert_global",
                        testStack.getDisplayName(), canInsert));
            } else {
                sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.cannot_insert_global",
                        testStack.getDisplayName()));
            }
        }
    }

    // ================================= IItemHandler =================================

    /**
     * Inspect a standard Forge IItemHandler (slot-based item storage).
     */
    private void inspectItemHandler(ICommandSender sender, IItemHandler handler, String[] args) {
        sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.type.item_handler"));

        // Parse the item arguments into stacks for insertion simulation
        List<ItemStack> testStacks = new ArrayList<>();
        for (String arg : args) {
            Item item = Item.getByNameOrId(arg);
            if (item == null) {
                sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.unknown_item", arg));
                continue;
            }

            testStacks.add(new ItemStack(item, Integer.MAX_VALUE));
        }

        int slotCount = handler.getSlots();
        for (int slot = 0; slot < slotCount; slot++) {
            ItemStack current = handler.getStackInSlot(slot);
            int limit = handler.getSlotLimit(slot);

            // Build the list of items that can be inserted into this slot
            List<String> insertable = new ArrayList<>();
            for (ItemStack testStack : testStacks) {
                ItemStack simulated = handler.insertItem(slot, testStack.copy(), true);
                if (simulated.isEmpty() || simulated.getCount() < testStack.getCount()) {
                    insertable.add(testStack.getDisplayName());
                }
            }

            if (current.isEmpty()) {
                // Empty slot
                if (insertable.isEmpty()) {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.slot_empty",
                            slot, limit));
                } else {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.slot_empty_can_insert",
                            slot, limit, String.join(", ", insertable)));
                }
            } else {
                // Slot with content
                String itemName = current.getDisplayName();
                int count = current.getCount();

                if (insertable.isEmpty()) {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.slot_content",
                            slot, itemName, count, limit));
                } else {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.slot_content_can_insert",
                            slot, itemName, count, limit, String.join(", ", insertable)));
                }
            }
        }
    }

    // ================================= IFluidHandler =================================

    /**
     * Inspect a Forge IFluidHandler (tank-based fluid storage).
     */
    private void inspectFluidHandler(ICommandSender sender, IFluidHandler handler, String[] args) {
        sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.type.fluid_handler"));

        // Parse fluid arguments for insertion simulation
        List<FluidStack> testFluids = new ArrayList<>();
        for (String arg : args) {
            net.minecraftforge.fluids.Fluid fluid = FluidRegistry.getFluid(arg);
            if (fluid == null) {
                sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.unknown_fluid", arg));
                continue;
            }

            testFluids.add(new FluidStack(fluid, 1000));
        }

        IFluidTankProperties[] tanks = handler.getTankProperties();
        for (int i = 0; i < tanks.length; i++) {
            IFluidTankProperties tank = tanks[i];
            FluidStack contents = tank.getContents();
            int capacity = tank.getCapacity();

            // Check which test fluids can be inserted
            List<String> insertable = new ArrayList<>();
            for (FluidStack testFluid : testFluids) {
                int filled = handler.fill(testFluid, false);
                if (filled > 0) {
                    insertable.add(testFluid.getLocalizedName());
                }
            }

            if (contents == null || contents.amount <= 0) {
                // Empty tank
                if (insertable.isEmpty()) {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.tank_empty",
                            i, capacity));
                } else {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.tank_empty_can_insert",
                            i, capacity, String.join(", ", insertable)));
                }
            } else {
                // Tank with content
                String fluidName = contents.getLocalizedName();
                int amount = contents.amount;

                if (insertable.isEmpty()) {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.tank_content",
                            i, fluidName, amount, capacity));
                } else {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.tank_content_can_insert",
                            i, fluidName, amount, capacity, String.join(", ", insertable)));
                }
            }
        }
    }

    // ================================= Gas (Mekanism) =================================

    /**
     * Try to inspect IGasHandler tanks. Returns true if the TE has a gas capability.
     *
     * @param anyPrevious Whether a previous capability section was already printed (for blank line separator)
     */
    @Optional.Method(modid = MOD_MEKANISM)
    private boolean tryInspectGas(ICommandSender sender, TileEntity te, EnumFacing face, String[] args, boolean anyPrevious) {
        if (!te.hasCapability(mekanism.common.capabilities.Capabilities.GAS_HANDLER_CAPABILITY, face)) return false;

        mekanism.api.gas.IGasHandler handler = te.getCapability(
                mekanism.common.capabilities.Capabilities.GAS_HANDLER_CAPABILITY, face);
        if (handler == null) return false;

        if (anyPrevious) sender.sendMessage(new TextComponentTranslation(""));
        sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.type.gas_handler"));

        // Parse gas arguments for insertion simulation
        List<mekanism.api.gas.GasStack> testGases = new ArrayList<>();
        for (String arg : args) {
            mekanism.api.gas.Gas gas = mekanism.api.gas.GasRegistry.getGas(arg);
            if (gas == null) {
                sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.unknown_gas", arg));
                continue;
            }

            testGases.add(new mekanism.api.gas.GasStack(gas, 1000));
        }

        mekanism.api.gas.GasTankInfo[] tanks = handler.getTankInfo();
        for (int i = 0; i < tanks.length; i++) {
            mekanism.api.gas.GasTankInfo tank = tanks[i];
            mekanism.api.gas.GasStack contents = tank.getGas();
            int maxGas = tank.getMaxGas();

            // Check which test gases can be received
            List<String> insertable = new ArrayList<>();
            for (mekanism.api.gas.GasStack testGas : testGases) {
                if (handler.canReceiveGas(face, testGas.getGas())) {
                    insertable.add(testGas.getGas().getLocalizedName());
                }
            }

            if (contents == null || contents.amount <= 0) {
                if (insertable.isEmpty()) {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.tank_empty",
                            i, maxGas));
                } else {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.tank_empty_can_insert",
                            i, maxGas, String.join(", ", insertable)));
                }
            } else {
                String gasName = contents.getGas().getLocalizedName();
                int amount = contents.amount;

                if (insertable.isEmpty()) {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.tank_content",
                            i, gasName, amount, maxGas));
                } else {
                    sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.tank_content_can_insert",
                            i, gasName, amount, maxGas, String.join(", ", insertable)));
                }
            }
        }

        return true;
    }

    // ================================= Essentia (Thaumcraft) =================================

    /**
     * Try to inspect IAspectContainer essentia. Returns true if the TE is an essentia container.
     * Essentia does NOT use Forge capabilities, it's an instanceof check.
     *
     * @param anyPrevious Whether a previous capability section was already printed (for blank line separator)
     */
    @Optional.Method(modid = MOD_THAUMCRAFT)
    private boolean tryInspectEssentia(ICommandSender sender, TileEntity te, String[] args, boolean anyPrevious) {
        if (!(te instanceof thaumcraft.api.aspects.IAspectContainer)) return false;

        thaumcraft.api.aspects.IAspectContainer container = (thaumcraft.api.aspects.IAspectContainer) te;

        if (anyPrevious) sender.sendMessage(new TextComponentTranslation(""));
        sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.type.essentia_container"));

        thaumcraft.api.aspects.AspectList aspects = container.getAspects();
        thaumcraft.api.aspects.Aspect[] aspectArray = aspects.getAspects();

        if (aspectArray == null || aspectArray.length == 0) {
            sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.empty_inventory"));
        } else {
            // Display each aspect as a virtual "slot"
            int index = 0;
            for (thaumcraft.api.aspects.Aspect aspect : aspectArray) {
                if (aspect == null) continue;

                int amount = aspects.getAmount(aspect);
                String name = aspect.getName();

                // Essentia containers don't expose per-slot capacity, so show just the amount
                sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.essentia_content",
                        index, name, amount));
                index++;
            }
        }

        // Simulated insertion for each argument
        for (String arg : args) {
            thaumcraft.api.aspects.Aspect aspect = thaumcraft.api.aspects.Aspect.getAspect(arg);
            if (aspect == null) {
                sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.unknown_aspect", arg));
                continue;
            }

            if (container.doesContainerAccept(aspect)) {
                sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.can_insert_global",
                        aspect.getName(), ""));
            } else {
                sender.sendMessage(new TextComponentTranslation("commands.cells.inspect_slots.cannot_insert_global",
                        aspect.getName()));
            }
        }

        return true;
    }

    // ================================= Tab Completion =================================

    @Override
    @Nonnull
    public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
                                          @Nonnull String[] args, @Nullable BlockPos pos) {
        if (!(sender.getCommandSenderEntity() instanceof EntityPlayerMP)) {
            return Collections.emptyList();
        }

        EntityPlayerMP player = (EntityPlayerMP) sender.getCommandSenderEntity();
        RayTraceResult hit = rayTrace(player);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            return Collections.emptyList();
        }

        TileEntity te = player.world.getTileEntity(hit.getBlockPos());
        if (te == null) return Collections.emptyList();

        EnumFacing hitFace = hit.sideHit;
        String lastArg = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

        return getResourceCompletions(te, hitFace, lastArg);
    }

    /**
     * Get resource completions for ALL detected capability types.
     * Since the command now shows all capabilities, tab-complete should offer
     * resources from every applicable type (items, fluids, gases, essentia).
     */
    private List<String> getResourceCompletions(TileEntity te, EnumFacing hitFace, String lastArg) {
        List<String> completions = new ArrayList<>();

        // Item completions (IItemRepository or IItemHandler)
        boolean hasItems = false;
        if (Capabilities.ITEM_REPOSITORY_CAPABILITY != null
                && te.hasCapability(Capabilities.ITEM_REPOSITORY_CAPABILITY, hitFace)) {
            hasItems = true;
        }
        if (!hasItems && te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, hitFace)) {
            hasItems = true;
        }
        if (hasItems) {
            completions.addAll(getItemCompletions(lastArg));
        }

        // Fluid completions
        if (te.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, hitFace)) {
            completions.addAll(getFluidCompletions(lastArg));
        }

        // Gas completions (Mekanism)
        if (Platform.isModLoaded(MOD_MEKANISM)) {
            List<String> gasCompletions = getGasCompletions(te, hitFace, lastArg);
            if (gasCompletions != null) completions.addAll(gasCompletions);
        }

        // Essentia completions (Thaumcraft)
        if (Loader.isModLoaded(MOD_THAUMCRAFT)) {
            List<String> essentiaCompletions = getEssentiaCompletions(te, lastArg);
            if (essentiaCompletions != null) completions.addAll(essentiaCompletions);
        }

        return completions;
    }

    /**
     * Item registry name completions (for IItemRepository and IItemHandler).
     */
    private List<String> getItemCompletions(String prefix) {
        List<String> ret = new ArrayList<>();
        for (ResourceLocation rl : Item.REGISTRY.getKeys()) {
            String name = rl.toString();
            if (name.toLowerCase().startsWith(prefix)) ret.add(name);
        }

        return ret;
    }

    /**
     * Fluid registry name completions (for IFluidHandler).
     */
    private List<String> getFluidCompletions(String prefix) {
        List<String> ret = new ArrayList<>();
        for (String fname : FluidRegistry.getRegisteredFluids().keySet()) {
            if (fname.toLowerCase().startsWith(prefix)) ret.add(fname);
        }

        return ret;
    }

    /**
     * Gas name completions (for IGasHandler). Returns null if the TE doesn't have gas capability.
     */
    @Nullable
    @Optional.Method(modid = MOD_MEKANISM)
    private List<String> getGasCompletions(TileEntity te, EnumFacing face, String prefix) {
        if (!te.hasCapability(mekanism.common.capabilities.Capabilities.GAS_HANDLER_CAPABILITY, face)) return null;

        List<String> ret = new ArrayList<>();
        for (mekanism.api.gas.Gas gas : mekanism.api.gas.GasRegistry.getRegisteredGasses()) {
            String name = gas.getName();
            if (name.toLowerCase().startsWith(prefix)) ret.add(name);
        }

        return ret;
    }

    /**
     * Essentia aspect tag completions (for IAspectContainer). Returns null if the TE isn't an essentia container.
     */
    @Nullable
    @Optional.Method(modid = MOD_THAUMCRAFT)
    private List<String> getEssentiaCompletions(TileEntity te, String prefix) {
        if (!(te instanceof thaumcraft.api.aspects.IAspectContainer)) return null;

        List<String> ret = new ArrayList<>();
        for (String tag : thaumcraft.api.aspects.Aspect.aspects.keySet()) {
            if (tag.toLowerCase().startsWith(prefix)) ret.add(tag);
        }

        return ret;
    }
}
