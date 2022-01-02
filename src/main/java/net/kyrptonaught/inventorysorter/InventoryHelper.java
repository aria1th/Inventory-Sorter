package net.kyrptonaught.inventorysorter;

import net.kyrptonaught.inventorysorter.interfaces.SortableContainer;
import net.kyrptonaught.inventorysorter.mixin.ScreenHandlerTypeAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.InventoryProvider;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.screen.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

public class InventoryHelper {

    public static Text sortBlock(World world, BlockPos blockPos, ServerPlayerEntity player, SortCases.SortType sortType) {
        // Inventory to sort
        Inventory inventory = null;
        // Screen to open and check
        NamedScreenHandlerFactory namedScreenHandlerFactory = null;

        BlockState blockState = world.getBlockState(blockPos);
        Block block = blockState.getBlock();

        // check that block has block entity
        if (blockState.hasBlockEntity()) {
            BlockEntity blockEntity = world.getBlockEntity(blockPos);

            // blockEntity needs to be an inventory
            if (blockEntity instanceof Inventory) {
                // Chest blocks have a special of way of being handled due to double chests being a thing
                if (blockEntity instanceof ChestBlockEntity && block instanceof ChestBlock) {
                    inventory = ChestBlock.getInventory((ChestBlock) block, blockState, world, blockPos, true);
                    namedScreenHandlerFactory = ((ChestBlock) block).createScreenHandlerFactory(blockState, world, blockPos);
                } else if(blockEntity instanceof NamedScreenHandlerFactory) {
                    // if it's not a chest, it might still be openable
                    inventory = (Inventory) blockEntity;
                    namedScreenHandlerFactory = (NamedScreenHandlerFactory) blockEntity;
                }
            }
        }

        // fail if either is not present
        if(inventory == null || namedScreenHandlerFactory == null) {
            return new LiteralText("Not looking at an inventory");
        }

        // open screen to perform validation
        OptionalInt syncId = player.openHandledScreen(namedScreenHandlerFactory);
        if(syncId.isPresent()) {
            // get screenHandler from syncId
            ScreenHandler screenHandler = namedScreenHandlerFactory.createMenu(syncId.getAsInt(), player.getInventory(), player);
            try {
                // check if inventory is sortable
                if (canSortInventory(player, screenHandler)) {
                    // actually sort inv
                    sortInv(inventory, 0, inventory.size(), sortType);
                    player.closeHandledScreen();
                    return new LiteralText("Sorted inventory");
                } else {
                    player.closeHandledScreen();
                    return new LiteralText("This inventory is not sortable");
                }
            } catch (Exception ex) {
                player.closeHandledScreen();
                player.sendMessage(new LiteralText("Sorting inventory failed"), false);
            }
        }

        return new LiteralText("Could not sort inventory");
    }

    public static boolean sortInv(PlayerEntity player, boolean sortPlayerInv, SortCases.SortType sortType) {
        if (sortPlayerInv) {
            sortInv(player.getInventory(), 9, 27, sortType);
            return true;
        } else if (canSortInventory(player)) {
            Inventory inv = ((SortableContainer) player.currentScreenHandler).getInventory();
            if (inv != null) {
                sortInv(inv, 0, inv.size(), sortType);
                return true;
            }
        }
        return false;
    }

    static void sortInv(Inventory inv, int startSlot, int invSize, SortCases.SortType sortType) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < invSize; i++)
            addStackWithMerge(stacks, inv.getStack(startSlot + i));

        stacks.sort(Comparator.comparing(stack -> SortCases.getStringForSort(stack, sortType)));
        if (stacks.size() == 0) return;
        for (int i = 0; i < invSize; i++)
            inv.setStack(startSlot + i, i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY);
        inv.markDirty();
    }

    private static void addStackWithMerge(List<ItemStack> stacks, ItemStack newStack) {
        if (newStack.getItem() == Items.AIR) return;
        if (newStack.isStackable() && newStack.getCount() != newStack.getMaxCount())
            for (int j = stacks.size() - 1; j >= 0; j--) {
                ItemStack oldStack = stacks.get(j);
                if (canMergeItems(newStack, oldStack)) {
                    combineStacks(newStack, oldStack);
                    if (oldStack.getItem() == Items.AIR || oldStack.getCount() == 0) stacks.remove(j);
                }
            }
        stacks.add(newStack);
    }

    private static void combineStacks(ItemStack stack, ItemStack stack2) {
        if (stack.getMaxCount() >= stack.getCount() + stack2.getCount()) {
            stack.increment(stack2.getCount());
            stack2.setCount(0);
        }
        int maxInsertAmount = Math.min(stack.getMaxCount() - stack.getCount(), stack2.getCount());
        stack.increment(maxInsertAmount);
        stack2.decrement(maxInsertAmount);
    }

    private static boolean canMergeItems(ItemStack itemStack_1, ItemStack itemStack_2) {
        if (!itemStack_1.isStackable() || !itemStack_2.isStackable())
            return false;
        if (itemStack_1.getCount() == itemStack_1.getMaxCount() || itemStack_2.getCount() == itemStack_2.getMaxCount())
            return false;
        if (itemStack_1.getItem() != itemStack_2.getItem())
            return false;
        if (itemStack_1.getDamage() != itemStack_2.getDamage())
            return false;
        return ItemStack.areNbtEqual(itemStack_1, itemStack_2);
    }

    public static boolean shouldDisplayBtns(PlayerEntity player) {
        if (player.currentScreenHandler == null || !player.currentScreenHandler.canUse(player) || player.currentScreenHandler instanceof PlayerScreenHandler)
            return true;
        ScreenHandlerType<?> type = ((ScreenHandlerTypeAccessor) player.currentScreenHandler).gettype();
        if (type == null) return true;
        Identifier id = Registry.SCREEN_HANDLER.getId(type);
        if (id == null) return true;
        return !InventorySorterMod.getBlackList().isDisplayBlacklisted(id);
    }

    public static boolean canSortInventory(PlayerEntity player) {
        if(player.currentScreenHandler instanceof PlayerScreenHandler) return false;

        return canSortInventory(player, player.currentScreenHandler);
    }

    public static boolean canSortInventory(PlayerEntity player, ScreenHandler screenHandler) {
        if (screenHandler == null || !screenHandler.canUse(player))
            return false;
        ScreenHandlerType<?> type = ((ScreenHandlerTypeAccessor) screenHandler).gettype();
        if (type == null) return false;
        Identifier id = Registry.SCREEN_HANDLER.getId(type);
        if (id == null) return false;
        return isSortableContainer(screenHandler, id);
    }

    private static boolean isSortableContainer(ScreenHandler screenHandler, Identifier screenID) {
        if (InventorySorterMod.getBlackList().isSortBlackListed(screenID))
            return false;
        if (!((SortableContainer) screenHandler).hasSlots())
            return false;

        int numSlots = screenHandler.slots.size();
        if (numSlots <= 36) return false;
        return numSlots - 36 >= 9;
    }

}