package dev.markai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.markai.mixin.MobAccessor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public final class CompanionGameTest {
    @GameTest(structure = "markai-test:arena", maxTicks = 400)
    public void commandsAndOwnership(GameTestHelper helper) throws Exception {
        prepareGround(helper);
        ServerPlayer owner = owner(helper);
        var level = helper.getLevel();
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "walk spawn");
        Wolf walk = ownedWolf(helper, owner);
        helper.assertTrue(walk.isTame() && walk.isOwnedBy(owner), "Companion must be a wolf tamed to the command issuer");
        helper.assertTrue(walk.getHealth() == 40 && walk.getAttribute(Attributes.MAX_HEALTH).getBaseValue() == 40,
            "Companion must retain native tamed-wolf 40 HP");
        helper.assertTrue(walk.getName().getString().equals("Марк"), "New companion name must be Марк");
        helper.assertTrue(!walk.isSilent(), "Companion must retain its dog sounds");
        helper.assertTrue(!((MobAccessor) walk).markai$goals().getAvailableGoals().isEmpty(), "Vanilla wolf goals must remain enabled");
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark spawn");
        helper.assertTrue(level.getEntitiesOfClass(Wolf.class, owner.getBoundingBox().inflate(5)).stream()
            .filter(wolf -> wolf.isOwnedBy(owner)).count() == 1, "Repeated spawn must not duplicate companion");

        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark stay");
        Path state = server.getWorldPath(LevelResource.ROOT).resolve("markai.json");
        JsonObject saved = savedCompanion(state, owner.getUUID());
        String logicalIdentity = saved.get("id").getAsString();
        helper.assertTrue(saved.get("entity").getAsString().equals(walk.getUUID().toString()), "Both owner and wolf UUIDs must be persisted");
        helper.assertTrue(!saved.get("following").getAsBoolean() && walk.isOrderedToSit(), "Stay must persist and order the wolf to sit");
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark follow");
        helper.assertTrue(savedCompanion(state, owner.getUUID()).get("following").getAsBoolean() && !walk.isOrderedToSit(), "Follow must persist and release sitting");
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark status");

        Wolf ordinary = helper.spawn(EntityTypes.WOLF, new BlockPos(5, 2, 5));
        helper.assertTrue(!ordinary.isTame() && !((MobAccessor) ordinary).markai$goals().getAvailableGoals().isEmpty(), "Ordinary wild wolf must retain native ownership and goals");
        ordinary.discard();
        owner.setPos(owner.getX() + 9, owner.getY(), owner.getZ() + 1);
        double initialDistance = walk.distanceTo(owner);
        Vec3[] stopped = new Vec3[1];
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(walk.distanceTo(owner) < initialDistance - 1, "Companion must move toward owner"))
            .thenExecute(() -> server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark stay"))
            .thenIdle(10)
            .thenExecute(() -> {
                helper.assertTrue(walk.isOrderedToSit() && walk.isInSittingPose(), "Stay must make the native wolf sit");
                stopped[0] = walk.position();
            })
            .thenIdle(20)
            .thenExecute(() -> {
                helper.assertTrue(walk.position().distanceTo(stopped[0]) < 0.2, "Sitting wolf must stay in place without a threat");
                owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                walk.mobInteract(owner, InteractionHand.MAIN_HAND);
                helper.assertTrue(!walk.isOrderedToSit(), "Native owner right-click must release sitting");
            })
            .thenIdle(12)
            .thenExecute(() -> {
                helper.assertTrue(!walk.isOrderedToSit() && savedCompanion(state, owner.getUUID()).get("following").getAsBoolean(), "Native standing interaction must stay active and persist FOLLOW");
                walk.mobInteract(owner, InteractionHand.MAIN_HAND);
                helper.assertTrue(walk.isOrderedToSit(), "Native owner right-click must order sitting");
            })
            .thenIdle(12)
            .thenExecute(() -> {
                helper.assertTrue(walk.isOrderedToSit() && !savedCompanion(state, owner.getUUID()).get("following").getAsBoolean(), "Native sitting interaction must stay active and persist STAY");
                var oldUuid = walk.getUUID();
                walk.kill(level);
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark respawn");
                Wolf replacement = ownedWolf(helper, owner);
                helper.assertTrue(!replacement.getUUID().equals(oldUuid) && replacement.isAlive(), "Dead companion must be replaceable by a new tamed wolf");
                helper.assertTrue(savedCompanion(state, owner.getUUID()).get("entity").getAsString().equals(replacement.getUUID().toString()), "Respawn must persist the new UUID");
                helper.assertTrue(savedCompanion(state, owner.getUUID()).get("id").getAsString().equals(logicalIdentity), "Explicit revival preserves the companion's logical identity");
            })
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 400)
    public void dogFightsForOwnerAndKeepsHurtSounds(GameTestHelper helper) throws Exception {
        prepareGround(helper);
        ServerPlayer owner = owner(helper);
        owner.setGameMode(GameType.SURVIVAL);
        owner.getAbilities().invulnerable = false;
        var level = helper.getLevel();
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark spawn");
        Wolf walk = ownedWolf(helper, owner);
        Zombie enemy = zombie(helper, walk.position().add(2, 0, 0));
        float[] healthAfterOwnerAttack = new float[1];
        Zombie[] attacker = new Zombie[1];

        helper.startSequence()
            .thenIdle(2)
            .thenExecute(() -> {
                owner.attack(enemy);
                owner.setLastHurtMob(enemy);
                helper.assertTrue(enemy.getHealth() < enemy.getMaxHealth(), "Owner must actually damage the enemy first");
                healthAfterOwnerAttack[0] = enemy.getHealth();
            })
            .thenWaitUntil(() -> helper.assertTrue(enemy.getHealth() < healthAfterOwnerAttack[0], "Wolf must bite the mob attacked by its owner"))
            .thenExecute(() -> {
                enemy.discard(); walk.setTarget(null); owner.setLastHurtMob(null);
                attacker[0] = zombie(helper, walk.position().add(2, 0, 0));
            })
            .thenIdle(2)
            .thenExecute(() -> {
                float health = owner.getHealth();
                helper.assertTrue(owner.hurtServer(level, owner.damageSources().mobAttack(attacker[0]), 2), "Enemy hit must damage the owner");
                helper.assertTrue(owner.getHealth() < health && owner.getLastHurtByMob() == attacker[0], "Owner damage must retain the native attacker memory");
            })
            .thenWaitUntil(() -> helper.assertTrue(attacker[0].getHealth() < attacker[0].getMaxHealth(), "Wolf must defend its attacked owner with real bites"))
            .thenExecute(() -> {
                attacker[0].discard(); walk.setTarget(null);
                float health = walk.getHealth();
                DamageSource damage = walk.damageSources().generic();
                helper.assertTrue(walk.hurtServer(level, damage, 2) && walk.getHealth() < health, "Wolf must take damage normally");
                helper.assertTrue(!walk.isSilent(), "Damage must not silence the dog");
                try {
                    var hurtSound = Wolf.class.getDeclaredMethod("getHurtSound", DamageSource.class);
                    hurtSound.setAccessible(true);
                    SoundEvent sound = (SoundEvent) hurtSound.invoke(walk, damage);
                    helper.assertTrue(sound != null && sound.location().toString().contains("wolf") && sound.location().toString().contains("hurt"), "Wolf must retain a native canine hurt sound");
                    var ambientSound = Wolf.class.getDeclaredMethod("getAmbientSound");
                    ambientSound.setAccessible(true);
                    SoundEvent ambient = (SoundEvent) ambientSound.invoke(walk);
                    helper.assertTrue(ambient != null && ambient.location().toString().contains("wolf"), "Wolf must retain native dog ambient sounds");
                } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
            })
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void savedChickenMigratesWithoutTouchingOrdinaryAnimals(GameTestHelper helper) throws Exception {
        prepareGround(helper);
        ServerPlayer owner = owner(helper);
        var level = helper.getLevel();
        var server = level.getServer();
        Chicken previous = helper.spawn(EntityTypes.CHICKEN, new BlockPos(3, 2, 3));
        Chicken ordinary = helper.spawn(EntityTypes.CHICKEN, new BlockPos(5, 2, 5));
        UUID oldUuid = previous.getUUID();
        MarkAIMod.Companion saved = new MarkAIMod.Companion();
        saved.owner = owner.getUUID(); saved.entity = oldUuid;
        saved.dimension = level.dimension().identifier().toString();
        saved.chunkX = previous.chunkPosition().x(); saved.chunkZ = previous.chunkPosition().z();
        saved.following = false;
        MarkAIMod mod = mod();
        var companionsField = MarkAIMod.class.getDeclaredField("companions");
        companionsField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<UUID, MarkAIMod.Companion> companions = (Map<UUID, MarkAIMod.Companion>) companionsField.get(mod);
        companions.put(owner.getUUID(), saved);
        var save = MarkAIMod.class.getDeclaredMethod("save"); save.setAccessible(true); save.invoke(mod);

        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark status");
        Wolf walk = ownedWolf(helper, owner);
        helper.assertTrue(previous.isRemoved(), "Only the saved legacy companion chicken must be replaced");
        helper.assertTrue(ordinary.isAlive() && level.getEntity(ordinary.getUUID()) == ordinary, "Ordinary chicken must remain unchanged");
        helper.assertTrue(walk.isTame() && walk.isOwnedBy(owner) && walk.getName().getString().equals("Марк"), "Migrated companion must be the same owner's tamed Марк");
        helper.assertTrue(!walk.getUUID().equals(oldUuid), "Migration must assign the wolf its own UUID");
        Path state = server.getWorldPath(LevelResource.ROOT).resolve("markai.json");
        JsonObject persisted = savedCompanion(state, owner.getUUID());
        helper.assertTrue(persisted.get("entity").getAsString().equals(walk.getUUID().toString()), "Migration must persist the replacement UUID");
        helper.assertTrue(!persisted.get("following").getAsBoolean() && walk.isOrderedToSit(), "Migration must preserve the saved STAY mode");
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark spawn");
        helper.assertTrue(level.getEntitiesOfClass(Wolf.class, owner.getBoundingBox().inflate(8)).stream().filter(wolf -> wolf.isOwnedBy(owner)).count() == 1, "Repeated spawn after migration must not duplicate the wolf");
        helper.succeed();
    }

    private static ServerPlayer owner(GameTestHelper helper) {
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        owner.setInvulnerableTime(0);
        owner.setPermanentlyInvulnerable(false);
        Vec3 position = helper.absoluteVec(new Vec3(2, 2, 2));
        owner.setPos(position.x, position.y, position.z);
        return owner;
    }

    private static void prepareGround(GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
    }

    private static Wolf ownedWolf(GameTestHelper helper, ServerPlayer owner) {
        var wolves = helper.getLevel().getEntitiesOfClass(Wolf.class, owner.getBoundingBox().inflate(8)).stream()
            .filter(wolf -> wolf.isAlive() && wolf.isOwnedBy(owner)).toList();
        helper.assertTrue(wolves.size() == 1, "Exactly one living wolf must belong to the owner: " + wolves.size());
        return wolves.getFirst();
    }

    private static Zombie zombie(GameTestHelper helper, Vec3 position) {
        Zombie enemy = EntityTypes.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        enemy.setPos(position.x, position.y, position.z); enemy.setNoAi(true);
        enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        helper.getLevel().addFreshEntity(enemy);
        return enemy;
    }

    private static MarkAIMod mod() {
        return (MarkAIMod) FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(value -> value instanceof MarkAIMod).findFirst().orElseThrow();
    }

    private static JsonObject savedCompanion(Path state, UUID owner) {
        try {
            for (var entry : JsonParser.parseString(Files.readString(state)).getAsJsonArray()) {
                JsonObject companion = entry.getAsJsonObject();
                if (companion.get("owner").getAsString().equals(owner.toString())) return companion;
            }
            throw new IllegalStateException("Missing owner state " + owner);
        } catch (IOException error) { throw new RuntimeException(error); }
    }
}
