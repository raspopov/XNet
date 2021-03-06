package mcjty.xnet.apiimpl.items;

import mcjty.lib.tools.ItemStackTools;
import mcjty.lib.varia.WorldTools;
import mcjty.xnet.api.channels.IChannelSettings;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.channels.IControllerContext;
import mcjty.xnet.api.gui.IEditorGui;
import mcjty.xnet.api.gui.IndicatorIcon;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.apiimpl.DefaultChannelSettings;
import mcjty.xnet.blocks.controller.gui.GuiController;
import mcjty.xnet.config.GeneralConfiguration;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class ItemChannelSettings extends DefaultChannelSettings implements IChannelSettings {

    public static final String TAG_MODE = "mode";

    // Cache data
    private Map<SidedConsumer, ItemConnectorSettings> itemExtractors = null;
    private List<Pair<SidedConsumer, ItemConnectorSettings>> itemConsumers = null;


    enum ChannelMode {
        PRIORITY,
        ROUNDROBIN
    }

    private ChannelMode channelMode = ChannelMode.PRIORITY;
    private int delay = 0;
    private int roundRobinOffset = 0;

    public ChannelMode getChannelMode() {
        return channelMode;
    }

    @Override
    public int getColors() {
        return 0;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        channelMode = ChannelMode.values()[tag.getByte("mode")];
        delay = tag.getInteger("delay");
        roundRobinOffset = tag.getInteger("offset");
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setByte("mode", (byte) channelMode.ordinal());
        tag.setInteger("delay", delay);
        tag.setInteger("offset", roundRobinOffset);
    }

    private static class MInteger {
        private int i;

        public MInteger(int i) {
            this.i = i;
        }

        public int get() {
            return i;
        }

        public void set(int i) {
            this.i = i;
        }

        public void inc() {
            i++;
        }
    }

    @Override
    public void tick(int channel, IControllerContext context) {
        delay--;
        if (delay <= 0) {
            delay = 200*6;      // Multiply of the different speeds we have
        }
        if (delay % 10 != 0) {
            return;
        }
        int d = delay/10;

        updateCache(channel, context);
        // @todo optimize
        World world = context.getControllerWorld();
        for (Map.Entry<SidedConsumer, ItemConnectorSettings> entry : itemExtractors.entrySet()) {
            ItemConnectorSettings settings = entry.getValue();
            if (d % settings.getSpeed() != 0) {
                continue;
            }

            BlockPos extractorPos = context.findConsumerPosition(entry.getKey().getConsumerId());
            if (extractorPos != null) {
                EnumFacing side = entry.getKey().getSide();
                BlockPos pos = extractorPos.offset(side);
                if (!WorldTools.chunkLoaded(world, pos)) {
                    continue;
                }

                TileEntity te = world.getTileEntity(pos);
                IItemHandler handler = getItemHandlerAt(te, settings.getFacing());
                // @todo report error somewhere?
                if (handler != null) {
                    if (checkRedstone(world, settings, extractorPos)) {
                        continue;
                    }
                    if (!context.matchColor(settings.getColorsMask())) {
                        continue;
                    }
                    Predicate<ItemStack> extractMatcher = settings.getMatcher();

                    Integer count = settings.getCount();
                    int amount = 0;
                    if (count != null) {
                        amount = countItems(handler, extractMatcher);
                        if (amount < count) {
                            continue;
                        }
                    }
                    MInteger index = new MInteger(0);
                    while (true) {
                        ItemStack stack = fetchItem(handler, true, extractMatcher, settings.getStackMode(), 64, index);
                        if (ItemStackTools.isValid(stack)) {
                            // Now that we have a stack we first reduce the amount of the stack if we want to keep a certain
                            // number of items
                            int toextract = ItemStackTools.getStackSize(stack);
                            if (count != null) {
                                int canextract = amount-count;
                                if (canextract <= 0) {
                                    index.inc();
                                    continue;
                                }
                                if (canextract < toextract) {
                                    toextract = canextract;
                                    ItemStackTools.setStackSize(stack, toextract);
                                }
                            }

                            List<Pair<SidedConsumer, ItemConnectorSettings>> inserted = new ArrayList<>();
                            int remaining = insertStackSimulate(inserted, context, stack);
                            if (!inserted.isEmpty()) {
                                if (context.checkAndConsumeRF(GeneralConfiguration.controllerOperationRFT)) {
                                    insertStackReal(context, inserted, fetchItem(handler, false, extractMatcher, settings.getStackMode(), toextract-remaining, index));
                                }
                                break;
                            } else {
                                index.inc();
                            }
                        } else {
                            break;
                        }
                    }
                }
            }
        }
    }

    // Returns what could not be inserted
    private int insertStackSimulate(@Nonnull List<Pair<SidedConsumer, ItemConnectorSettings>> inserted, @Nonnull IControllerContext context, @Nonnull ItemStack stack) {
        World world = context.getControllerWorld();
        if (channelMode == ChannelMode.PRIORITY) {
            roundRobinOffset = 0;       // Always start at 0
        }
        int total = ItemStackTools.getStackSize(stack);
        for (int j = 0 ; j < itemConsumers.size() ; j++) {
            int i = (j + roundRobinOffset) % itemConsumers.size();
            Pair<SidedConsumer, ItemConnectorSettings> entry = itemConsumers.get(i);
            ItemConnectorSettings settings = entry.getValue();

            if (settings.getMatcher().test(stack)) {
                BlockPos consumerPos = context.findConsumerPosition(entry.getKey().getConsumerId());
                if (consumerPos != null) {
                    if (!WorldTools.chunkLoaded(world, consumerPos)) {
                        continue;
                    }

                    if (checkRedstone(world, settings, consumerPos)) {
                        continue;
                    }
                    if (!context.matchColor(settings.getColorsMask())) {
                        continue;
                    }

                    EnumFacing side = entry.getKey().getSide();
                    BlockPos pos = consumerPos.offset(side);
                    TileEntity te = world.getTileEntity(pos);
                    IItemHandler handler = getItemHandlerAt(te, settings.getFacing());
                    // @todo report error somewhere?
                    if (handler != null) {
                        int toinsert = total;
                        Integer count = settings.getCount();
                        if (count != null) {
                            int amount = countItems(handler, settings.getMatcher());
                            int caninsert = count-amount;
                            if (caninsert <= 0) {
                                continue;
                            }
                            toinsert = Math.min(toinsert, caninsert);
                            ItemStackTools.setStackSize(stack, toinsert);
                        }
                        stack = ItemHandlerHelper.insertItem(handler, stack, true);
                        int actuallyinserted = toinsert - ItemStackTools.getStackSize(stack);

                        if (actuallyinserted > 0) {
                            inserted.add(entry);
                            total -= actuallyinserted;
                            if (total <= 0) {
                                return 0;
                            }
                        }
                    }
                }
            }
        }
        return total;
    }

    private void insertStackReal(@Nonnull IControllerContext context, @Nonnull List<Pair<SidedConsumer, ItemConnectorSettings>> inserted, @Nonnull ItemStack stack) {
        int total = ItemStackTools.getStackSize(stack);
        for (Pair<SidedConsumer, ItemConnectorSettings> entry : inserted) {
            BlockPos consumerPosition = context.findConsumerPosition(entry.getKey().getConsumerId());
            EnumFacing side = entry.getKey().getSide();
            ItemConnectorSettings settings = entry.getValue();
            BlockPos pos = consumerPosition.offset(side);
            TileEntity te = context.getControllerWorld().getTileEntity(pos);
            IItemHandler handler = getItemHandlerAt(te, settings.getFacing());

            int toinsert = total;
            Integer count = settings.getCount();
            if (count != null) {
                int amount = countItems(handler, settings.getMatcher());
                int caninsert = count-amount;
                if (caninsert <= 0) {
                    continue;
                }
                toinsert = Math.min(toinsert, caninsert);
                ItemStackTools.setStackSize(stack, toinsert);
            }
            stack = ItemHandlerHelper.insertItem(handler, stack, false);
            int actuallyinserted = toinsert - ItemStackTools.getStackSize(stack);
            if (actuallyinserted > 0) {
                roundRobinOffset = (roundRobinOffset+1) % itemConsumers.size();
                total -= actuallyinserted;
                if (total <= 0) {
                    return;
                }
            }
        }
    }

    private int countItems(IItemHandler handler, Predicate<ItemStack> matcher) {
        int cnt = 0;
        for (int i = 0 ; i < handler.getSlots() ; i++) {
            ItemStack s = handler.getStackInSlot(i);
            if (ItemStackTools.isValid(s)) {
                if (matcher.test(s)) {
                    cnt += ItemStackTools.getStackSize(s);
                }
            }
        }
        return cnt;
    }

    private ItemStack fetchItem(IItemHandler handler, boolean simulate, Predicate<ItemStack> matcher, ItemConnectorSettings.StackMode stackMode, int maxamount, MInteger index) {
        for (int i = index.get(); i < handler.getSlots() ; i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (ItemStackTools.isValid(stack)) {
                int s = (stackMode == ItemConnectorSettings.StackMode.SINGLE) ? 1 : stack.getMaxStackSize();
                s = Math.min(s, maxamount);
                stack = handler.extractItem(i, s, simulate);
                if (ItemStackTools.isValid(stack) && matcher.test(stack)) {
                    index.set(i);
                    return stack;
                }
            }
        }
        return ItemStackTools.getEmptyStack();
    }


    private void updateCache(int channel, IControllerContext context) {
        if (itemExtractors == null) {
            itemExtractors = new HashMap<>();
            itemConsumers = new ArrayList<>();
            Map<SidedConsumer, IConnectorSettings> connectors = context.getConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
                ItemConnectorSettings con = (ItemConnectorSettings) entry.getValue();
                if (con.getItemMode() == ItemConnectorSettings.ItemMode.EXT) {
                    itemExtractors.put(entry.getKey(), con);
                } else {
                    itemConsumers.add(Pair.of(entry.getKey(), con));
                }
            }
            connectors = context.getRoutedConnectors(channel);
            for (Map.Entry<SidedConsumer, IConnectorSettings> entry : connectors.entrySet()) {
                ItemConnectorSettings con = (ItemConnectorSettings) entry.getValue();
                if (con.getItemMode() == ItemConnectorSettings.ItemMode.INS) {
                    itemConsumers.add(Pair.of(entry.getKey(), con));
                }
            }

            itemConsumers.sort((o1, o2) -> o2.getRight().getPriority().compareTo(o1.getRight().getPriority()));
        }
    }

    @Override
    public void cleanCache() {
        itemExtractors = null;
        itemConsumers = null;
    }

    @Override
    public boolean isEnabled(String tag) {
        return true;
    }

    @Nullable
    @Override
    public IndicatorIcon getIndicatorIcon() {
        return new IndicatorIcon(GuiController.iconGuiElements, 0, 80, 11, 10);
    }

    @Nullable
    @Override
    public String getIndicator() {
        return null;
    }

    @Override
    public void createGui(IEditorGui gui) {
        gui.nl().choices(TAG_MODE, "Item distribution mode", channelMode, ChannelMode.values());
    }

    @Override
    public void update(Map<String, Object> data) {
        channelMode = ChannelMode.valueOf(((String)data.get(TAG_MODE)).toUpperCase());
        roundRobinOffset = 0;
    }

    @Nullable
    public static IItemHandler getItemHandlerAt(@Nullable TileEntity te, EnumFacing intSide) {
        if (te != null && te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, intSide)) {
            IItemHandler handler = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, intSide);
            if (handler != null) {
                return handler;
            }
        } else if (te instanceof ISidedInventory) {
            // Support for old inventory
            ISidedInventory sidedInventory = (ISidedInventory) te;
            return new SidedInvWrapper(sidedInventory, intSide);
        } else if (te instanceof IInventory) {
            // Support for old inventory
            IInventory inventory = (IInventory) te;
            return new InvWrapper(inventory);
        }
        return null;
    }


}
