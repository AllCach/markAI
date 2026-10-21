package dev.markai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.markai.actions.ActionResult;
import dev.markai.actions.CompanionCombatRules;
import dev.markai.behavior.Activity;
import dev.markai.network.CompanionNetwork;
import dev.markai.perception.WorldObserver;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

/** Integration checks use the registered UI backend, real navigation, damage hooks and wolf mixins. */
public final class LivingCompanionGameTest {
    @GameTest(structure = "markai-test:arena", maxTicks = 120)
    public void persistentProfileAndOwnerScopedUi(GameTestHelper helper) throws Exception {
        setup(helper);
        ServerPlayer owner = player(helper, 2, 2, 2);
        command(owner, "mark spawn");
        var c = companion(owner);
        Wolf wolf = (Wolf) helper.getLevel().getEntity(c.entity);
        var backend = backend();
        helper.assertTrue(wolf.getName().getString().equals("Марк"), "Master companion is named Марк");
        backend.setting(owner, "trait_caution", "87");
        backend.setting(owner, "follow_distance", "6");
        backend.setting(owner, "hud", "false");
        command(owner, "mark sethome");
        helper.assertTrue(c.living.home != null && c.living.personality.caution == .87, "Owner settings/home must affect real saved state");
        double previousTrust = c.living.emotions.trust;
        double previousFear = c.living.emotions.fear;
        helper.assertTrue(wolf.hurtServer(helper.getLevel(), wolf.damageSources().playerAttack(owner), 2), "Real owner hit must damage companion");
        helper.assertTrue(c.living.emotions.trust < previousTrust && c.living.emotions.fear > previousFear,
            "Damage hook must cause an emotional reaction rather than random mood changes");
        c.memory.rememberStatement("Запомни приватное слово Тихолесье.", helper.getLevel().getGameTime());
        UUID privateMemory = c.memory.inspect().stream().filter(e -> e.content().contains("Тихолесье")).findFirst().orElseThrow().id();
        JsonObject ownView = JsonParser.parseString(backend.snapshot(owner)).getAsJsonObject();
        helper.assertTrue(ownView.get("entity_uuid").getAsString().equals(c.entity.toString())
            && ownView.toString().contains("Тихолесье"), "Owner UI receives own entity and memory");
        ServerPlayer stranger = player(helper, 12, 2, 12);
        backend.setting(stranger, "trait_caution", "1");
        backend.deleteMemory(stranger, privateMemory.toString());
        helper.assertTrue(c.living.personality.caution == .87
            && c.memory.inspect().stream().anyMatch(e -> e.id().equals(privateMemory)), "Another player cannot edit traits or delete owner's memories");
        String otherView = backend.snapshot(stranger);
        helper.assertTrue(!otherView.contains(c.entity.toString()) && !otherView.contains("Тихолесье"), "Other player's UI cannot disclose this companion's private state");
        command(owner, "mark stay");
        String saved = Files.readString(helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("markai.json"));
        MarkAIMod.Companion restored = java.util.Arrays.stream(new Gson().fromJson(saved, MarkAIMod.Companion[].class))
            .filter(entry -> entry.owner.equals(owner.getUUID())).findFirst().orElseThrow();
        restored.living.normalize(); restored.memory.normalize();
        helper.assertTrue(restored.entity.equals(c.entity) && restored.living.personality.caution == .87
            && restored.living.settings.followDistance == 6 && !restored.living.settings.hud,
            "Identity, personality and settings must survive world-state serialization");
        helper.assertTrue(restored.living.home.equals(c.living.home)
            && restored.living.emotions.trust == c.living.emotions.trust
            && restored.living.emotions.fear == c.living.emotions.fear, "Home and causal emotion values survive reload");
        backend.deleteMemory(owner, privateMemory.toString());
        helper.assertTrue(c.memory.inspect().stream().noneMatch(e -> e.id().equals(privateMemory)), "Owner memory-delete UI action changes durable memory");
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 500)
    public void naturalRestAndHomeUseRealActivityAndNavigation(GameTestHelper helper) throws Exception {
        setup(helper);
        ServerPlayer owner = player(helper, 2, 2, 2);
        command(owner, "mark spawn");
        var c = companion(owner);
        Wolf wolf = (Wolf) helper.getLevel().getEntity(c.entity);
        var backend = backend();
        command(owner, "mark sethome");
        helper.assertTrue(c.living.home != null, "Home must be explicitly recorded before returning");
        backend.chat(owner, "отдохни");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(c.living.activity() == Activity.REST && wolf.isInSittingPose()
                && c.living.lastAction.status() == ActionResult.Status.SUCCEEDED, "Natural rest must reach native sitting/rest activity and report completion"))
            .thenExecute(() -> {
                wolf.setPos(c.living.home.x() + 10, c.living.home.y(), c.living.home.z());
                helper.assertTrue(helper.getLevel().isPositionEntityTicking(wolf.blockPosition()),
                    "Navigation fixture must keep the relocated wolf in an entity-ticking chunk");
                backend.chat(owner, "вернись домой");
                helper.assertTrue(c.living.lastAction.status() == ActionResult.Status.ACCEPTED,
                    "Distant home request must be accepted without prematurely claiming arrival");
            })
            .thenWaitUntil(() -> helper.assertTrue(c.living.home.distanceTo(wolf.getX(), wolf.getY(), wolf.getZ()) <= 2
                && c.living.lastAction.status() == ActionResult.Status.SUCCEEDED,
                "Return-home command must navigate to the recorded coordinates before success: position=" + wolf.position()
                    + ", home=" + c.living.home + ", activity=" + c.action + ", result=" + c.living.lastAction
                    + ", ticks=" + wolf.tickCount + ", ordered=" + wolf.isOrderedToSit() + ", pose=" + wolf.isInSittingPose()
                    + ", entityTicking=" + helper.getLevel().areEntitiesActuallyLoadedAndTicking(wolf.chunkPosition())))
            .thenExecute(() -> {
                BlockPos floor = BlockPos.containing(c.living.home.x(), c.living.home.y(), c.living.home.z()).below();
                helper.getLevel().setBlockAndUpdate(floor, Blocks.MAGMA_BLOCK.defaultBlockState());
                backend.command(owner, "home");
                helper.assertTrue(c.living.lastAction.status() == ActionResult.Status.FAILED
                    && c.living.lastAction.code().equals("unsafe_home"), "Unsafe destination must produce a meaningful validated failure");
                helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            }).thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 120)
    public void combatGuardsOwnerAlliesNeutralsAndDefenseSetting(GameTestHelper helper) throws Exception {
        setup(helper);
        ServerPlayer owner = player(helper, 2, 2, 2);
        command(owner, "mark spawn");
        var c = companion(owner);
        Wolf wolf = (Wolf) helper.getLevel().getEntity(c.entity);
        var level = helper.getLevel();
        float ownerHealth = owner.getHealth();
        wolf.setTarget(owner);
        helper.assertTrue(wolf.getTarget() == null && !wolf.doHurtTarget(level, owner) && owner.getHealth() == ownerHealth,
            "Tracked wolf must refuse targeting and biting its owner");
        var ally = helper.spawn(EntityTypes.WOLF, new BlockPos(5, 2, 3)); ally.tame(owner);
        float allyHealth = ally.getHealth();
        wolf.setTarget(ally);
        helper.assertTrue(wolf.getTarget() == null && !wolf.doHurtTarget(level, ally) && ally.getHealth() == allyHealth,
            "Tracked wolf cannot bite an allied pet");
        var neutral = helper.spawn(EntityTypes.ZOMBIFIED_PIGLIN, new BlockPos(6, 2, 3)); neutral.setNoAi(true);
        wolf.setTarget(neutral);
        helper.assertTrue(wolf.getTarget() == null && !wolf.doHurtTarget(level, neutral), "Unprovoked neutral mob is protected by target and attack gates");
        var hostile = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(6, 2, 4)); hostile.setNoAi(true);
        helper.assertTrue(CompanionCombatRules.canTarget(wolf, hostile), "Enabled defense permits nearby hostile targets");
        wolf.setTarget(hostile);
        helper.assertTrue(wolf.getTarget() == hostile, "Native target selection remains functional for allowed enemies");
        backend().setting(owner, "defense", "false");
        float health = hostile.getHealth();
        wolf.setTarget(hostile);
        helper.assertTrue(!c.living.settings.defense && wolf.getTarget() == null
            && !wolf.doHurtTarget(level, hostile) && hostile.getHealth() == health,
            "Disabling defense immediately prevents both target selection and melee damage");
        neutral.discard(); hostile.discard(); ally.discard();
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void perceptionDoesNotSeeMobsOrBlocksThroughWalls(GameTestHelper helper) throws Exception {
        setup(helper);
        ServerPlayer owner = player(helper, 2, 2, 4);
        command(owner, "mark spawn");
        var c = companion(owner);
        Wolf wolf = (Wolf) helper.getLevel().getEntity(c.entity);
        Vec3 wolfPos = helper.absoluteVec(new Vec3(4.5, 2, 4.5)); wolf.setPos(wolfPos.x, wolfPos.y, wolfPos.z);
        for (int y = 2; y <= 6; y++) for (int z = 0; z <= 12; z++) helper.setBlock(new BlockPos(6, y, z), Blocks.STONE);
        var hidden = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(8, 2, 4)); hidden.setNoAi(true);
        var visible = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(4, 2, 9)); visible.setNoAi(true);
        helper.setBlock(new BlockPos(8, 2, 3), Blocks.CHEST);
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.BARREL);
        BlockPos hiddenChest = helper.absolutePos(new BlockPos(8, 2, 3));
        BlockPos visibleBarrel = helper.absolutePos(new BlockPos(3, 2, 2));
        var observer = new WorldObserver();
        var view = observer.observe(helper.getLevel(), wolf, owner);
        helper.assertTrue(view.hostiles().stream().noneMatch(e -> e.id().equals(hidden.getUUID()))
            && view.hostiles().stream().anyMatch(e -> e.id().equals(visible.getUUID())),
            "Only actually visible hostile mobs enter perception");
        helper.assertTrue(view.environment().important_blocks().stream().noneMatch(b -> b.x() == hiddenChest.getX()
                && b.y() == hiddenChest.getY() && b.z() == hiddenChest.getZ())
            && view.environment().important_blocks().stream().anyMatch(b -> b.x() == visibleBarrel.getX()
                && b.y() == visibleBarrel.getY() && b.z() == visibleBarrel.getZ()),
            "Important blocks behind a solid wall remain unknown");
        for (int sample = 0; sample < 200; sample++) observer.observe(helper.getLevel(), wolf, owner);
        var metrics = observer.metrics();
        helper.assertTrue(((Number) metrics.get("scans")).intValue() == 1 && ((Number) metrics.get("cached_observations")).intValue() == 200,
            "Repeated observations must reuse the bounded block/entity scan");
        java.nio.file.Path report = java.nio.file.Path.of("reports", "perception-performance.json");
        Files.createDirectories(report.getParent()); Files.writeString(report, new Gson().toJson(metrics));
        hidden.discard(); visible.discard();
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 200)
    public void criticalHazardOverridesSittingAndRejectsUnsafeRest(GameTestHelper helper) throws Exception {
        setup(helper);
        ServerPlayer owner = player(helper, 2, 2, 2);
        command(owner, "mark spawn");
        var c = companion(owner);
        Wolf wolf = (Wolf) helper.getLevel().getEntity(c.entity);
        command(owner, "mark stay");
        wolf.setHealth(12); wolf.setRemainingFireTicks(100);
        backend().command(owner, "rest");
        helper.assertTrue(c.living.lastAction.status() == ActionResult.Status.FAILED
            && c.living.lastAction.code().equals("unsafe_current_position"), "A burning wolf must reject a rest order with a truthful safety result");
        Vec3 start = wolf.position();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(c.living.activity() == Activity.RETREAT
            && !wolf.isOrderedToSit() && wolf.position().distanceTo(start) > .5,
            "Critical danger overrides waiting and produces real escape movement"))
            .thenExecute(wolf::clearFire).thenSucceed();
    }

    private static void setup(GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 6; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }
    private static ServerPlayer player(GameTestHelper helper, int x, int y, int z) {
        var player = helper.makeMockServerPlayerInLevel(); Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
        player.setPos(pos.x, pos.y, pos.z); return player;
    }
    private static void command(ServerPlayer owner, String command) {
        owner.level().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(), command);
    }
    private static MarkAIMod.Companion companion(ServerPlayer owner) throws Exception {
        var mod = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(m -> m instanceof MarkAIMod).findFirst().orElseThrow();
        return TestCompanions.selected(mod, owner.getUUID());
    }
    private static CompanionNetwork.Backend backend() throws Exception {
        var field = CompanionNetwork.class.getDeclaredField("backend"); field.setAccessible(true);
        return (CompanionNetwork.Backend) field.get(null);
    }
}
