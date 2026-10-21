package dev.markai;

import dev.markai.inventory.CompanionInventoryHolder;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public final class InventoryGameTest {
    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void shiftClickAccessAndRealMenuTransfers(GameTestHelper helper) {
        setup(helper);
        ServerPlayer owner = owner(helper);
        command(owner, "walk spawn");
        command(owner, "walk stay");
        Wolf wolf = companion(helper, owner);
        CompanionInventoryHolder holder = (CompanionInventoryHolder) wolf;
        helper.assertTrue(holder.markai$isCompanion() && holder.markai$inventory().getContainerSize() == 9,
            "Only the controlled wolf must expose a nine-slot bag");

        owner.getAbilities().instabuild = false;
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COOKED_BEEF, 2));
        wolf.setHealth(wolf.getMaxHealth() - 2);
        float health = wolf.getHealth();
        boolean sitting = wolf.isOrderedToSit();
        owner.setShiftKeyDown(true);
        InteractionResult opened = use(owner, wolf);
        helper.assertTrue(opened != InteractionResult.PASS && owner.containerMenu instanceof ChestMenu,
            "Shift plus right-click must open the actual server inventory menu");
        ChestMenu menu = (ChestMenu) owner.containerMenu;
        helper.assertTrue(menu.getRowCount() == 1 && menu.getContainer() == holder.markai$inventory(),
            "The menu must operate on the wolf's persistent nine slots");
        helper.assertTrue(wolf.isOrderedToSit() == sitting && wolf.getHealth() == health && owner.getMainHandItem().getCount() == 2,
            "Opening the bag must neither toggle sitting nor feed the wolf");

        owner.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 5));
        menu.quickMoveStack(owner, 9);
        helper.assertTrue(countDiamonds(owner) == 0 && countDiamonds(holder) == 5,
            "Shift-transfer from the player's inventory must move the real stack into the bag exactly once");
        menu.quickMoveStack(owner, 0);
        helper.assertTrue(countDiamonds(owner) == 5 && countDiamonds(holder) == 0,
            "Shift-transfer back must return all items without duplication");

        ServerPlayer other = owner(helper);
        other.setShiftKeyDown(true);
        use(other, wolf);
        helper.assertTrue(other.containerMenu == other.inventoryMenu && !menu.stillValid(other),
            "A different player must neither open nor validate the owner's bag");
        helper.assertTrue(menu.stillValid(owner), "The nearby living owner must retain access");
        Vec3 position = owner.position();
        owner.setPos(position.x + 20, position.y, position.z);
        helper.assertTrue(!menu.stillValid(owner), "Walking away must invalidate the open inventory");
        owner.setPos(position.x, position.y, position.z);
        wolf.setOwner(other);
        helper.assertTrue(!menu.stillValid(owner), "A player who no longer owns the wolf must lose access");
        wolf.setOwner(owner);
        owner.closeContainer();

        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        owner.setShiftKeyDown(false);
        helper.assertTrue(use(owner, wolf) == InteractionResult.PASS, "Normal right-click must continue to vanilla wolf interaction");
        wolf.mobInteract(owner, InteractionHand.MAIN_HAND);
        helper.assertTrue(wolf.isOrderedToSit() != sitting, "Normal right-click must still toggle sitting");
        wolf.mobInteract(owner, InteractionHand.MAIN_HAND);

        Wolf ordinary = helper.spawn(EntityTypes.WOLF, new BlockPos(5, 2, 5));
        ordinary.tame(owner);
        owner.setShiftKeyDown(true);
        helper.assertTrue(!((CompanionInventoryHolder) ordinary).markai$isCompanion() && use(owner, ordinary) == InteractionResult.PASS
                && owner.containerMenu == owner.inventoryMenu,
            "An ordinary tamed wolf must retain vanilla interaction without this bag");
        ordinary.discard();
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void nativeEntitySavePreservesItemsAndDeathDropsOnce(GameTestHelper helper) {
        setup(helper);
        ServerPlayer owner = owner(helper);
        command(owner, "walk spawn");
        command(owner, "walk stay");
        Wolf wolf = companion(helper, owner);
        CompanionInventoryHolder holder = (CompanionInventoryHolder) wolf;
        ItemStack named = new ItemStack(Items.DIAMOND, 5);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("walk: " + owner.getUUID()));
        holder.markai$inventory().setItem(8, named.copy());

        var level = helper.getLevel();
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        wolf.saveWithoutId(output);
        Wolf restored = EntityTypes.WOLF.create(level, EntitySpawnReason.LOAD);
        helper.assertTrue(restored != null, "A wolf must be available for the native persistence round trip");
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
        CompanionInventoryHolder restoredHolder = (CompanionInventoryHolder) restored;
        ItemStack restoredItem = restoredHolder.markai$inventory().getItem(8);
        helper.assertTrue(restoredHolder.markai$isCompanion() && restoredHolder.markai$inventory().getContainerSize() == 9,
            "Native entity save/load must retain the companion marker and bag size");
        helper.assertTrue(restoredItem.getCount() == 5 && ItemStack.isSameItemSameComponents(restoredItem, named)
                && restoredHolder.markai$inventory().getItem(0).isEmpty(),
            "Native entity save/load must preserve slot numbers, stack count and item components");
        restored.discard(); // This copy is never added to the level and must not create dropped items.

        owner.setShiftKeyDown(true);
        use(owner, wolf);
        helper.assertTrue(owner.containerMenu instanceof ChestMenu, "The real companion bag must open before the death check");
        ChestMenu menu = (ChestMenu) owner.containerMenu;
        helper.assertTrue(menu.stillValid(owner), "A living nearby companion must allow its menu");
        Vec3 position = wolf.position();
        helper.assertTrue(droppedCount(helper, position, named) == 0, "The named test stack must not already exist in the world");
        wolf.kill(level);
        helper.assertTrue(!menu.stillValid(owner), "A dead companion must invalidate its open bag immediately");
        helper.assertTrue(holder.markai$inventory().isEmpty() && droppedCount(helper, position, named) == 5,
            "Death must empty the bag and drop the stored stack once, retaining its item components");
        owner.closeContainer();
        command(owner, "walk respawn");
        Wolf replacement = companion(helper, owner);
        helper.assertTrue(!replacement.getUUID().equals(wolf.getUUID()) && ((CompanionInventoryHolder) replacement).markai$inventory().isEmpty(),
            "A replacement wolf must start with an empty bag");
        helper.assertTrue(droppedCount(helper, position, named) == 5, "Respawn must not duplicate or remove the dead companion's dropped items");
        helper.succeed();
    }

    private static InteractionResult use(ServerPlayer player, Wolf wolf) {
        return UseEntityCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, wolf, new EntityHitResult(wolf));
    }

    private static int countDiamonds(ServerPlayer owner) {
        int count = 0;
        for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
            ItemStack stack = owner.getInventory().getItem(i);
            if (stack.is(Items.DIAMOND)) count += stack.getCount();
        }
        return count;
    }

    private static int countDiamonds(CompanionInventoryHolder holder) {
        int count = 0;
        for (int i = 0; i < holder.markai$inventory().getContainerSize(); i++) {
            ItemStack stack = holder.markai$inventory().getItem(i);
            if (stack.is(Items.DIAMOND)) count += stack.getCount();
        }
        return count;
    }

    private static int droppedCount(GameTestHelper helper, Vec3 position, ItemStack expected) {
        var area = new net.minecraft.world.phys.AABB(position.x - 4, position.y - 3, position.z - 4,
            position.x + 4, position.y + 3, position.z + 4);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream().filter(ItemEntity::isAlive)
            .map(ItemEntity::getItem).filter(stack -> ItemStack.isSameItemSameComponents(stack, expected)).mapToInt(ItemStack::getCount).sum();
    }

    private static Wolf companion(GameTestHelper helper, ServerPlayer owner) {
        var wolves = helper.getLevel().getEntitiesOfClass(Wolf.class, owner.getBoundingBox().inflate(8)).stream()
            .filter(wolf -> wolf.isAlive() && wolf.isOwnedBy(owner) && ((CompanionInventoryHolder) wolf).markai$isCompanion()).toList();
        helper.assertTrue(wolves.size() == 1, "Exactly one controlled living wolf must belong to the owner");
        return wolves.getFirst();
    }

    private static void setup(GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static ServerPlayer owner(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Vec3 position = helper.absoluteVec(new Vec3(2, 2, 2));
        owner.setPos(position.x, position.y, position.z);
        return owner;
    }

    private static void command(ServerPlayer owner, String command) {
        owner.level().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(), command);
    }
}
