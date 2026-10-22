package dev.markai;

import dev.markai.actions.ActionResult;
import dev.markai.inventory.CompanionInventoryHolder;
import dev.markai.inventory.DogInventory;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/** Real native taming, command combat and lossless item transfers, without a language model. */
public final class WalkOrdersGameTest {
    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void boneTamingAdoptsTwoWolvesWithoutReplacingTheFirstCompanion(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); Wolf original = wolf(helper, 5, 2, 6);
        original.setCustomName(Component.literal("Walk"));
        ItemStack keepsake = new ItemStack(Items.IRON_INGOT, 3);
        keepsake.set(DataComponents.CUSTOM_NAME, Component.literal("Existing wolf keepsake"));
        bag(original).setItem(8, keepsake.copy()); UUID originalId = original.getUUID();
        nativeBoneTame(helper, owner, original);
        MarkAIMod.Companion c = companion(owner);
        helper.assertTrue(c != null && c.entity.equals(originalId) && original.isOwnedBy(owner)
                && ((CompanionInventoryHolder) original).markai$isCompanion(),
            "Successful native bone taming must adopt that exact physical wolf for its real owner");
        helper.assertTrue(original.getName().getString().equals("Walk")
                && ItemStack.isSameItemSameComponents(bag(original).getItem(8), keepsake)
                && bag(original).getItem(8).getCount() == 3,
            "Adoption preserves the existing wolf name, item components, slot and stack size");
        Wolf second = wolf(helper, 7, 2, 6); nativeBoneTame(helper, owner, second);
        helper.assertTrue(companion(owner).entity.equals(originalId) && second.isOwnedBy(owner)
                && ((CompanionInventoryHolder) second).markai$isCompanion()
                && TestCompanions.registry(mod()).owned(owner.getUUID()).size() == 2,
            "Taming a second wolf adds its own companion while preserving the original selection and physical wolf");
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void explicitAdoptionRequiresTheLookedAtWolfsActualOwner(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper), stranger = owner(helper);
        Vec3 strangerPos = helper.absoluteVec(new Vec3(20.5, 2, 20.5)); stranger.setPos(strangerPos.x, strangerPos.y, strangerPos.z);
        Wolf foreign = wolf(helper, 4.5, 2, 8.5); foreign.tame(stranger);
        aim(owner, foreign); command(owner, "tame");
        helper.assertTrue(companion(owner) == null && foreign.isOwnedBy(stranger)
                && !((CompanionInventoryHolder) foreign).markai$isCompanion(),
            "The explicit tame command cannot steal a different player's pet");
        foreign.discard(); Wolf owned = wolf(helper, 4.5, 2, 8.5); owned.tame(owner);
        owned.setCustomName(Component.literal("Walk")); owned.setHealth(17);
        bag(owned).setItem(4, new ItemStack(Items.LEATHER, 7));
        aim(owner, owned); command(owner, "tame");
        MarkAIMod.Companion c = companion(owner);
        helper.assertTrue(c != null && c.entity.equals(owned.getUUID()) && owned.getHealth() == 17
                && owned.getName().getString().equals("Walk") && bag(owned).getItem(4).getCount() == 7,
            "An already owned wolf is adopted in place, retaining its health, name and inventory");
        Wolf replacement = wolf(helper, 4.5, 2, 10.5); replacement.tame(owner);
        // The original wolf at (4.5, 2, 8.5) otherwise occludes the ray to this one.
        // Approach from the side so /walk tame actually targets the replacement wolf.
        Vec3 side = helper.absoluteVec(new Vec3(7.5, 2, 10.5));
        owner.setPos(side.x, side.y, side.z);
        aim(owner, replacement); command(owner, "tame");
        helper.assertTrue(companion(owner).entity.equals(owned.getUUID())
                && ((CompanionInventoryHolder) replacement).markai$isCompanion()
                && TestCompanions.registry(mod()).owned(owner.getUUID()).size() == 2,
            "Explicit adoption adds the new owned wolf without changing the existing companion or selection");
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 350)
    public void attackCommandBitesTheSelectedCowThenStops(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c);
        Cow target = helper.spawn(EntityTypes.COW, new BlockPos(4, 2, 10)); target.setNoAi(true);
        aim(owner, target); command(owner, "attack");
        helper.assertTrue(c.workActive, "The actual crosshair attack command must start explicit work");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(target.getHealth() < target.getMaxHealth(),
                "An explicit order must cause real native wolf bite damage to the selected wild cow"))
            .thenExecute(() -> {
                command(owner, "stop");
                helper.assertTrue(!c.workActive && walk.getTarget() == null,
                    "Stop cancels explicit combat and clears the native target immediately");
            })
            .thenIdle(25)
            .thenExecute(() -> helper.assertTrue(!c.workActive && walk.getTarget() == null,
                "Cancelled explicit combat cannot silently reacquire its old neutral target"))
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 350)
    public void anExplicitZombieOrderWorksWithAutomaticDefenseDisabled(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c); c.living.settings.defense = false;
        Zombie target = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(4, 2, 10)); target.setNoAi(true);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        aim(owner, target); command(owner, "attack");
        helper.assertTrue(c.workActive, "Turning off unsolicited defense must still permit an explicit safe owner attack order");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(target.getHealth() < target.getMaxHealth(),
                "An explicitly selected hostile must take actual wolf bite damage with automatic defense disabled"))
            .thenExecute(() -> { command(owner, "stop"); target.discard(); })
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 120)
    public void attackCapturesAnOffCenterTargetBeyondSixteenBlocks(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c);
        Cow target = helper.spawn(EntityTypes.COW, new BlockPos(5, 2, 26)); target.setNoAi(true);
        // Aim just outside the cow's vanilla hitbox; the wider selection padding should still catch it.
        owner.lookAt(EntityAnchorArgument.Anchor.EYES, target.position().add(1.0, .2, 0));
        command(owner, "attack");
        helper.assertTrue(c.workActive && walk.getTarget() == target,
            "A visible target more than 16 blocks away and slightly beside the crosshair must be captured within the 32-block radius");
        command(owner, "stop"); helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 80)
    public void repeatedAttackMissesAreRateLimited(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        @SuppressWarnings("unchecked") var notices = (Map<UUID, Long>) field(MarkAIMod.class, "attackMissNoticeAt").get(mod());
        command(owner, "attack"); Long firstNotice = notices.get(owner.getUUID());
        command(owner, "attack");
        helper.assertTrue(firstNotice != null && firstNotice.equals(notices.get(owner.getUUID())),
            "Repeated presses without a valid target must not produce another error notice during the 1.5-second cooldown");
        helper.assertTrue(!companion(owner).workActive,
            "A missed target must not start an attack order or send a target to the companion");
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void attackOrdersRejectPlayersPetsAndOccludedTargets(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c); WolfWorkRuntime work = work();
        ServerPlayer stranger = owner(helper); Vec3 away = helper.absoluteVec(new Vec3(7, 2, 7)); stranger.setPos(away.x, away.y, away.z);
        Wolf ownPet = wolf(helper, 7, 2, 5); ownPet.tame(owner);
        Wolf foreignPet = wolf(helper, 8, 2, 5); foreignPet.tame(stranger);
        for (LivingEntity protectedEntity : new LivingEntity[] {owner, stranger, walk, ownPet, foreignPet}) {
            ActionResult result = work.attack(c, walk, owner, protectedEntity);
            helper.assertTrue(result.status() == ActionResult.Status.FAILED && !c.workActive
                    && walk.getTarget() != protectedEntity,
                "An explicit attack order must reject players, self and all already tamed pets");
        }
        Cow hidden = helper.spawn(EntityTypes.COW, new BlockPos(5, 2, 12)); hidden.setNoAi(true);
        for (int x = 0; x < 16; x++) for (int y = 2; y < 6; y++) helper.setBlock(new BlockPos(x, y, 8), net.minecraft.world.level.block.Blocks.STONE);
        helper.assertTrue(!walk.hasLineOfSight(hidden) && !owner.hasLineOfSight(hidden), "The test wall must genuinely hide the selected target");
        helper.assertTrue(work.attack(c, walk, owner, hidden).status() == ActionResult.Status.FAILED && !c.workActive,
            "Explicit attack cannot target a hidden animal through a solid wall");
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 350)
    public void gatheringMovesActualMeatLeatherAndNamedResourcesIntoTheBag(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c);
        Vec3 start = helper.absoluteVec(new Vec3(8, 2, 8)); walk.setPos(start.x, start.y, start.z);
        ItemStack named = new ItemStack(Items.IRON_INGOT, 5);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Mining expedition iron"));
        ItemEntity beef = drop(helper, start.add(.5, 0, 0), new ItemStack(Items.BEEF, 4));
        ItemEntity leather = drop(helper, start.add(0, 0, .5), new ItemStack(Items.LEATHER, 3));
        ItemEntity iron = drop(helper, start.add(.5, 0, .5), named.copy());
        command(owner, "collect");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(count(bag(walk), Items.BEEF) == 4
                    && count(bag(walk), Items.LEATHER) == 3 && count(bag(walk), Items.IRON_INGOT) == 5,
                "Gathering must transfer the real dropped meat, leather and resources into the nine-slot bag"))
            .thenExecute(() -> {
                helper.assertTrue(!beef.isAlive() && !leather.isAlive() && !iron.isAlive(),
                    "Fully transferred world items must be consumed exactly once");
                helper.assertTrue(namedCount(bag(walk), named) == 5, "Item components must survive collection");
            })
            .thenIdle(30)
            .thenExecute(() -> helper.assertTrue(count(bag(walk), Items.BEEF) == 4
                    && count(bag(walk), Items.LEATHER) == 3 && namedCount(bag(walk), named) == 5,
                "Further ticks must not duplicate already collected stacks"))
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 450)
    public void gatheringCollectsAResourceOneBlockAboveTheWolfsStartingLevel(GameTestHelper helper) throws Exception {
        setup(helper);
        ServerPlayer owner = owner(helper);
        command(owner, "spawn");
        var c = companion(owner);
        Wolf walk = companionWolf(helper, c);
        Vec3 start = helper.absoluteVec(new Vec3(6, 2, 6));
        walk.setPos(start.x, start.y, start.z);

        // A raised, walkable platform. The dropped stack rests at Y=3 while Walk starts at Y=2.
        for (int x = 9; x <= 14; x++) {
            for (int z = 5; z <= 7; z++) helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
        }
        ItemEntity resource = drop(helper, start.add(7, 1, 0), new ItemStack(Items.IRON_INGOT, 4));
        command(owner, "collect");
        helper.assertTrue(resource.isAlive() && bag(walk).isEmpty(),
            "An elevated item must not be transferred remotely when the command is issued");

        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(count(bag(walk), Items.IRON_INGOT) == 4,
                "Walk must navigate up one block and collect a visible item resting on the raised platform"))
            .thenExecute(() -> {
                helper.assertTrue(!resource.isAlive(), "The original elevated item entity must be consumed exactly once");
                helper.assertTrue(walk.getY() >= 3.0,
                    "Walk must physically reach the raised platform instead of vacuuming the item from below");
            })
            .thenIdle(20)
            .thenExecute(() -> helper.assertTrue(count(bag(walk), Items.IRON_INGOT) == 4,
                "Later ticks must not duplicate the elevated resource"))
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 350)
    public void gatheringMustWalkToAVisibleResourceBeforeTransferringIt(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c);
        Vec3 start = helper.absoluteVec(new Vec3(6, 2, 6)); walk.setPos(start.x, start.y, start.z);
        ItemEntity resource = drop(helper, start.add(7, 0, 0), new ItemStack(Items.IRON_INGOT, 4));
        helper.assertTrue(walk.hasLineOfSight(resource), "The navigation target must be genuinely visible");
        command(owner, "collect");
        helper.assertTrue(resource.isAlive() && bag(walk).isEmpty(), "Issuing a distant gather order cannot transfer items remotely");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(count(bag(walk), Items.IRON_INGOT) == 4,
                "The wolf must reach a visible resource through real native navigation and collect it"))
            .thenExecute(() -> helper.assertTrue(!resource.isAlive() && walk.position().distanceTo(start) > 4,
                "Collection must follow physical movement close to the original item, without teleporting its stack"))
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 200)
    public void gatheringRespectsAnotherPlayersThrowerAndPickupReservation(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c); ServerPlayer stranger = owner(helper);
        Vec3 away = helper.absoluteVec(new Vec3(25, 2, 25)); stranger.setPos(away.x, away.y, away.z);
        Vec3 start = helper.absoluteVec(new Vec3(8, 2, 8)); walk.setPos(start.x, start.y, start.z);
        ItemEntity own = drop(helper, start.add(.4, 0, 0), new ItemStack(Items.COBBLESTONE, 2)); own.setThrower(owner);
        ItemEntity foreign = drop(helper, start.add(0, 0, .4), new ItemStack(Items.BEEF, 5)); foreign.setThrower(stranger);
        ItemEntity reserved = drop(helper, start.add(.4, 0, .4), new ItemStack(Items.IRON_INGOT, 6)); reserved.setTarget(stranger.getUUID());
        command(owner, "collect");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(count(bag(walk), Items.COBBLESTONE) == 2 && !c.workActive,
                "The wolf must collect its owner's accessible stack and complete the bounded job"))
            .thenExecute(() -> helper.assertTrue(!own.isAlive() && foreign.isAlive() && foreign.getItem().getCount() == 5
                    && reserved.isAlive() && reserved.getItem().getCount() == 6
                    && count(bag(walk), Items.BEEF) == 0 && count(bag(walk), Items.IRON_INGOT) == 0,
                "Collection must leave another player's thrown or explicitly reserved stacks untouched"))
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 250)
    public void gatheringWaitsForANormallyThrownItemsPickupDelay(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c);
        Vec3 start = helper.absoluteVec(new Vec3(8, 2, 8)); walk.setPos(start.x, start.y, start.z);
        ItemEntity resource = drop(helper, start.add(.5, 0, 0), new ItemStack(Items.BEEF, 3));
        resource.setThrower(owner); resource.setPickUpDelay(40); command(owner, "collect");
        helper.startSequence()
            .thenIdle(20)
            .thenExecute(() -> helper.assertTrue(resource.isAlive() && resource.hasPickUpDelay() && bag(walk).isEmpty() && c.workActive,
                "A normally thrown stack must keep its native pickup delay without prematurely ending the gather job"))
            .thenWaitUntil(() -> helper.assertTrue(count(bag(walk), Items.BEEF) == 3 && !resource.isAlive(),
                "The same pending gather order must collect the actual stack once its native pickup delay expires"))
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 300)
    public void gatheringYieldsToARealHostileAttackWithoutSuppressingDefense(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c); c.living.settings.defense = true;
        Vec3 start = helper.absoluteVec(new Vec3(7, 2, 7)); walk.setPos(start.x, start.y, start.z);
        ItemEntity resource = drop(helper, start.add(7, 0, 0), new ItemStack(Items.IRON_INGOT, 4));
        command(owner, "collect");
        helper.assertTrue(c.workActive, "The collection must be running before the hostile interruption");
        Zombie attacker = EntityTypes.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        attacker.setPos(start.x, start.y, start.z + 2); attacker.setNoAi(true);
        attacker.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); helper.getLevel().addFreshEntity(attacker);
        helper.assertTrue(walk.hurtServer(helper.getLevel(), walk.damageSources().mobAttack(attacker), 1),
            "The interruption must be a real hostile hit, rather than a simulated task flag");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(!c.workActive,
                "A confirmed hostile attack must interrupt item gathering promptly"))
            .thenWaitUntil(() -> helper.assertTrue(attacker.getHealth() < attacker.getMaxHealth(),
                "Yielding collection must preserve native target acquisition and actual defensive bites"))
            .thenExecute(() -> helper.assertTrue(resource.isAlive() && resource.getItem().getCount() == 4
                    && count(bag(walk), Items.IRON_INGOT) == 0,
                "Combat interruption must leave the uncollected resource in the world without copying it"))
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 200)
    public void partialAndFullBagsKeepTheExactWorldRemainder(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c);
        Vec3 start = helper.absoluteVec(new Vec3(8, 2, 8)); walk.setPos(start.x, start.y, start.z);
        DogInventory inventory = bag(walk);
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 62));
        ItemEntity iron = drop(helper, start.add(.4, 0, 0), new ItemStack(Items.IRON_INGOT, 5));
        command(owner, "collect");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(inventory.getItem(0).getCount() == 64,
                "A partially filled compatible slot must accept only the amount it can hold"))
            .thenExecute(() -> {
                helper.assertTrue(iron.isAlive() && iron.getItem().is(Items.IRON_INGOT) && iron.getItem().getCount() == 3,
                    "The untransferred three ingots must remain as the original world item");
                command(owner, "collect");
            })
            .thenIdle(25)
            .thenExecute(() -> helper.assertTrue(count(inventory, Items.IRON_INGOT) == 64
                    && iron.isAlive() && iron.getItem().getCount() == 3,
                "Reissuing collection with a full bag must neither destroy nor duplicate its remaining stack"))
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 200)
    public void gatheringCannotTakeHiddenItemsAndStopCancelsAnOutstandingCollection(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c);
        Vec3 start = helper.absoluteVec(new Vec3(6, 2, 6)); walk.setPos(start.x, start.y, start.z);
        for (int x = 0; x < 20; x++) for (int y = 2; y < 6; y++) helper.setBlock(new BlockPos(x, y, 8), net.minecraft.world.level.block.Blocks.STONE);
        ItemEntity hidden = drop(helper, helper.absoluteVec(new Vec3(6, 2, 10)), new ItemStack(Items.BEEF, 3));
        helper.assertTrue(!walk.hasLineOfSight(hidden), "The test meat must be hidden behind a solid wall");
        command(owner, "collect");
        helper.startSequence()
            .thenIdle(25)
            .thenExecute(() -> {
                helper.assertTrue(hidden.isAlive() && hidden.getItem().getCount() == 3 && bag(walk).isEmpty(),
                    "A collect order cannot sense or vacuum up items through a solid wall");
                hidden.discard();
                ItemEntity distant = drop(helper, helper.absoluteVec(new Vec3(15, 2, 6)), new ItemStack(Items.LEATHER, 6));
                command(owner, "collect"); command(owner, "stop");
                helper.assertTrue(!c.workActive && distant.isAlive(), "Stop must cancel a collection before any remote transfer");
            })
            .thenIdle(35)
            .thenExecute(() -> helper.assertTrue(!c.workActive && bag(walk).isEmpty(),
                "A cancelled gather task must not resume picking up its former item on later ticks"))
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 350)
    public void aCommandedHuntCollectsRealLootAfterTheCowDies(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        var c = companion(owner); Wolf walk = companionWolf(helper, c);
        Cow target = helper.spawn(EntityTypes.COW, new BlockPos(4, 2, 10)); target.setNoAi(true); target.setHealth(1);
        aim(owner, target); command(owner, "attack");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(!target.isAlive(), "A hunting order must actually kill the target through native combat"))
            .thenWaitUntil(() -> helper.assertTrue(count(bag(walk), Items.BEEF) > 0,
                "After a commanded kill the wolf must collect actual cow drops instead of manufacturing resources"))
            .thenSucceed();
    }

    private static void nativeBoneTame(GameTestHelper helper, ServerPlayer owner, Wolf wolf) {
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE, 64));
        for (int attempt = 0; attempt < 64 && !wolf.isTame(); attempt++) wolf.mobInteract(owner, InteractionHand.MAIN_HAND);
        helper.assertTrue(wolf.isTame() && wolf.isOwnedBy(owner), "Native bone interaction must successfully tame the wolf");
        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private static void setup(GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 1, z), net.minecraft.world.level.block.Blocks.STONE);
            for (int y = 2; y < 7; y++) helper.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR);
        }
    }
    private static ServerPlayer owner(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        Vec3 pos = helper.absoluteVec(new Vec3(4.5, 2, 4.5)); owner.setPos(pos.x, pos.y, pos.z); return owner;
    }
    private static Wolf wolf(GameTestHelper helper, double x, double y, double z) {
        Wolf wolf = EntityTypes.WOLF.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 pos = helper.absoluteVec(new Vec3(x, y, z)); wolf.setPos(pos.x, pos.y, pos.z);
        helper.getLevel().addFreshEntity(wolf); return wolf;
    }
    private static ItemEntity drop(GameTestHelper helper, Vec3 pos, ItemStack stack) {
        ItemEntity item = new ItemEntity(helper.getLevel(), pos.x, pos.y, pos.z, stack);
        item.setNoPickUpDelay(); item.setDeltaMovement(Vec3.ZERO); helper.getLevel().addFreshEntity(item); return item;
    }
    private static void aim(ServerPlayer owner, LivingEntity target) {
        owner.lookAt(EntityAnchorArgument.Anchor.EYES, target, EntityAnchorArgument.Anchor.EYES);
    }
    private static void command(ServerPlayer owner, String action) {
        owner.level().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "walk " + action);
    }
    private static DogInventory bag(Wolf wolf) { return ((CompanionInventoryHolder) wolf).markai$inventory(); }
    private static int count(DogInventory bag, Item item) {
        int count = 0; for (int slot = 0; slot < bag.getContainerSize(); slot++) if (bag.getItem(slot).is(item)) count += bag.getItem(slot).getCount(); return count;
    }
    private static int namedCount(DogInventory bag, ItemStack expected) {
        int count = 0; for (int slot = 0; slot < bag.getContainerSize(); slot++) if (ItemStack.isSameItemSameComponents(bag.getItem(slot), expected)) count += bag.getItem(slot).getCount(); return count;
    }
    private static Wolf companionWolf(GameTestHelper helper, MarkAIMod.Companion c) {
        return (Wolf) helper.getLevel().getEntity(c.entity);
    }
    private static MarkAIMod mod() {
        return (MarkAIMod) FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream().filter(m -> m instanceof MarkAIMod).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private static MarkAIMod.Companion companion(ServerPlayer owner) throws Exception {
        return TestCompanions.selected(mod(), owner.getUUID());
    }
    private static WolfWorkRuntime work() throws Exception { return (WolfWorkRuntime) field(MarkAIMod.class, "wolfWork").get(mod()); }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
}
