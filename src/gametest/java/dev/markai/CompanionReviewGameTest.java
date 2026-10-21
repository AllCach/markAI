package dev.markai;

import com.google.gson.Gson;
import dev.markai.ai.AIConfig;
import dev.markai.ai.DialogueContext;
import dev.markai.ai.OllamaClient;
import dev.markai.actions.ActionResult;
import dev.markai.actions.ActionValidator;
import dev.markai.actions.CompanionAction;
import dev.markai.actions.CompanionSafety;
import dev.markai.core.HomeLocation;
import dev.markai.events.WorldEvent;
import dev.markai.mixin.MobAccessor;
import dev.markai.network.CompanionNetwork;
import dev.markai.perception.WorldObserver;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Regressions found in the complete-mod review, using real entities and the authoritative input boundary. */
public final class CompanionReviewGameTest {
    @GameTest(structure = "markai-test:arena", maxTicks = 300)
    public void summonKeepsOneGoalAndConfiguresCrossDimensionReplacement(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        MarkAIMod mod = mod(); var c = companion(mod, owner); Wolf original = wolf(helper.getLevel(), c);
        long goals = livingGoals(original);
        helper.assertTrue(goals == 1, "New companion has exactly one living navigation goal");
        command(owner, "summon"); command(owner, "summon"); command(owner, "summon");
        helper.assertTrue(wolf(helper.getLevel(), c) == original && livingGoals(original) == goals,
            "Repeated same-dimension summon cannot add duplicate behavior goals");
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        boolean forcedByTest = nether.setChunkForced(0, 0, true);
        Runnable cleanup = () -> { if (forcedByTest) nether.setChunkForced(0, 0, false); };
        ((GameTestInfo) field(GameTestHelper.class, "testInfo").get(helper)).addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo, GameTestRunner runner) { cleanup.run(); }
        });
        nether.getChunk(0, 0);
        for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
            nether.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState());
            for (int y = 64; y <= 67; y++) nether.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        var transferred = original.teleport(new TeleportTransition(nether, new Vec3(2.5, 64, 2.5), Vec3.ZERO,
            0, 0, Set.of(), TeleportTransition.DO_NOTHING));
        helper.assertTrue(transferred instanceof Wolf && transferred != original && original.isRemoved(),
            "Native dimension transfer must return a non-null replacement wolf");
        Wolf distant = (Wolf) transferred;
        Wolf[] arrived = new Wolf[1];
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(nether.getEntity(c.entity) == distant,
                "The replacement must be tracked in the destination before summoning it back"))
            .thenExecute(() -> {
                command(owner, "summon"); arrived[0] = wolf(helper.getLevel(), c);
                helper.assertTrue(arrived[0] != null && arrived[0] != distant && distant.isRemoved()
                    && arrived[0].getUUID().equals(c.entity) && livingGoals(arrived[0]) == 1,
                    "Cross-dimension summon configures the actual arriving wolf, preserving UUID and one living goal");
                helper.assertTrue(c.dimension.equals(helper.getLevel().dimension().identifier().toString()) && arrived[0].isOwnedBy(owner),
                    "Arrival updates the saved dimension and ownership");
                command(owner, "stay");
            })
            .thenWaitUntil(() -> helper.assertTrue(arrived[0].isInSittingPose(), "Arriving wolf responds to real commands"))
            .thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void partialSurfaceHomesAndCachedDangerUseActualPhysicalState(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn"); command(owner, "stay");
        var level = helper.getLevel(); var c = companion(mod(), owner); Wolf wolf = wolf(level, c);
        BlockPos floor = helper.absolutePos(new BlockPos(6, 1, 6));
        level.setBlockAndUpdate(floor, Blocks.STONE_SLAB.defaultBlockState());
        Vec3 slabTop = new Vec3(floor.getX() + .5, floor.getY() + .5, floor.getZ() + .5);
        wolf.setPos(slabTop.x, slabTop.y, slabTop.z);
        helper.assertTrue(CompanionSafety.safeNavigationTarget(level, slabTop)
            && ActionValidator.isSafeHome(wolf, new HomeLocation(level.dimension().identifier().toString(), slabTop.x, slabTop.y, slabTop.z)),
            "Bottom slab collision supports navigation and a collision-free home at the real half-block height");
        level.setBlockAndUpdate(floor, Blocks.SOUL_SAND.defaultBlockState());
        Vec3 soulSandTop = new Vec3(slabTop.x, floor.getY() + .875, slabTop.z);
        wolf.setPos(soulSandTop.x, soulSandTop.y, soulSandTop.z);
        helper.assertTrue(CompanionSafety.safeNavigationTarget(level, soulSandTop)
            && ActionValidator.isSafeHome(wolf, new HomeLocation(level.dimension().identifier().toString(), soulSandTop.x, soulSandTop.y, soulSandTop.z)),
            "Soul sand uses its real support collision rather than its full-block rendering shape");
        level.setBlockAndUpdate(floor.above(), Blocks.CAMPFIRE.defaultBlockState());
        helper.assertTrue(!CompanionSafety.safeNavigationTarget(level, soulSandTop), "A dangerous block at the destination invalidates partial-surface support");
        level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());

        WorldObserver observer = new WorldObserver();
        helper.assertTrue(!observer.observe(level, wolf, owner).environment().critical_hazard(), "An uninjured wolf starts outside critical danger");
        wolf.setRemainingFireTicks(80);
        helper.assertTrue(observer.observe(level, wolf, owner).environment().critical_hazard(),
            "Fire is observable immediately even while expensive world perception is cached");
        wolf.clearFire();
        helper.assertTrue(!observer.observe(level, wolf, owner).environment().critical_hazard(), "Cleared danger also updates without waiting for the next scan");
        helper.assertTrue(((Number) observer.metrics().get("scans")).intValue() == 1,
            "Refreshing immediate physical danger must retain the cached block and entity scan");
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void disabledMemoryExcludesHistoryAndStopsNewCollection(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        MarkAIMod mod = mod(); var c = companion(mod, owner); Wolf wolf = wolf(helper.getLevel(), c);
        String secret = "Секретная берёзовая башня Тихолесья";
        c.memory.rememberExchange("Запомни: " + secret, new OllamaClient.Reply("say", "Запомнил."), helper.getLevel().getGameTime());
        c.memory.rememberStatement("Запомни: " + secret, helper.getLevel().getGameTime());
        c.living.onEvent(WorldEvent.of(WorldEvent.Type.OWNER_AFFECTION, helper.getLevel().getGameTime(), secret));
        AIConfig config = (AIConfig) field(MarkAIMod.class, "aiConfig").get(mod); boolean previous = config.memoryEnabled;
        int oldEntries = c.memory.inspect().size(), oldHistory = c.memory.history().size();
        config.memoryEnabled = false;
        try {
            var method = MarkAIMod.class.getDeclaredMethod("dialogueContext", MarkAIMod.Companion.class, Wolf.class, ServerPlayer.class, String.class);
            method.setAccessible(true);
            DialogueContext input = (DialogueContext) method.invoke(mod, c, wolf, owner, "Что помнишь?");
            helper.assertTrue(input.history().isEmpty() && !input.observation().contains(secret),
                "Disabled memory cannot send saved dialogue, extracted facts or recent saved events to the provider");
            var ambientMethod = MarkAIMod.class.getDeclaredMethod("ambientDialogueContext", MarkAIMod.Companion.class, Wolf.class, ServerPlayer.class);
            ambientMethod.setAccessible(true);
            DialogueContext ambientInput = (DialogueContext) ambientMethod.invoke(mod, c, wolf, owner);
            helper.assertTrue(ambientInput.history().isEmpty() && !ambientInput.observation().contains(secret),
                "Disabled memory cannot leak a retained private event through automatic speech either");
            LivingCompanionRuntime runtime = (LivingCompanionRuntime) field(MarkAIMod.class, "livingRuntime").get(mod);
            runtime.event(c, wolf, WorldEvent.Type.ENTERED_NEW_BIOME, "Новая приватная запись не должна сохраниться.", 1);
            backend().chat(owner, "иди за мной");
            helper.assertTrue(c.memory.inspect().size() == oldEntries && c.memory.history().size() == oldHistory,
                "Disabled memory collects neither a new world-memory record nor a command exchange");
            helper.assertTrue(!new Gson().toJson(c.living.context()).contains("Новая приватная запись"),
                "Disabled memory cannot retain event prose in saved state for later disclosure");
        } finally { config.memoryEnabled = previous; }
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void closeHomeCompletesAndDeletedEventCannotRemainInDialogueContext(GameTestHelper helper) throws Exception {
        setup(helper); ServerPlayer owner = owner(helper); command(owner, "spawn");
        MarkAIMod mod = mod(); var c = companion(mod, owner); Wolf wolf = wolf(helper.getLevel(), c);
        command(owner, "sethome"); command(owner, "rest"); command(owner, "home");
        helper.assertTrue(c.living.lastAction.status() == ActionResult.Status.SUCCEEDED && c.requestedAction == null,
            "Already-at-home action remains confirmed, never overwritten as indefinitely accepted");
        command(owner, "follow");
        LivingCompanionRuntime actionRuntime = (LivingCompanionRuntime) field(MarkAIMod.class, "livingRuntime").get(mod);
        actionRuntime.request(c, wolf, owner, CompanionAction.IDLE);
        helper.assertTrue(c.living.lastAction.status() == ActionResult.Status.SUCCEEDED,
            "An actual stopped pause must finish successfully rather than time out as a failed path");
        LivingCompanionRuntime runtime = (LivingCompanionRuntime) field(MarkAIMod.class, "livingRuntime").get(mod);
        String privateEvent = "Секретное событие у северной башни.";
        runtime.event(c, wolf, WorldEvent.Type.ACTION_FAILED, privateEvent, 1);
        var entry = c.memory.inspect().stream().filter(memory -> memory.content().equals(privateEvent)).findFirst().orElseThrow();
        backend().deleteMemory(owner, entry.id().toString());
        helper.assertTrue(!new Gson().toJson(c.living.context()).contains(privateEvent)
            && !new Gson().toJson(c.memory.context(privateEvent)).contains(privateEvent),
            "Deleting an observed memory also removes its duplicate from recent dialogue event context");
        helper.succeed();
    }

    private static void setup(GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 6; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }
    private static ServerPlayer owner(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel(); Vec3 pos = helper.absoluteVec(new Vec3(2, 2, 2));
        player.setPos(pos.x, pos.y, pos.z); return player;
    }
    private static void command(ServerPlayer owner, String command) { owner.level().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark " + command); }
    private static Wolf wolf(ServerLevel level, MarkAIMod.Companion c) { return (Wolf) level.getEntity(c.entity); }
    private static long livingGoals(Wolf wolf) { return ((MobAccessor)wolf).markai$goals().getAvailableGoals().stream().filter(goal -> goal.getGoal().getClass().getName().contains("LivingCompanionRuntime")).count(); }
    private static MarkAIMod mod() { return (MarkAIMod) FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream().filter(m -> m instanceof MarkAIMod).findFirst().orElseThrow(); }
    @SuppressWarnings("unchecked") private static MarkAIMod.Companion companion(MarkAIMod mod, ServerPlayer owner) throws Exception { return ((Map<UUID, MarkAIMod.Companion>)field(MarkAIMod.class, "companions").get(mod)).get(owner.getUUID()); }
    private static CompanionNetwork.Backend backend() throws Exception { return (CompanionNetwork.Backend)field(CompanionNetwork.class, "backend").get(null); }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
}
