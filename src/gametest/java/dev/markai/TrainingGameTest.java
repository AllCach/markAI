package dev.markai;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.markai.ai.OllamaClient;
import dev.markai.training.DogTraining.Trick;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Files;
import java.util.Map;

public final class TrainingGameTest {
    @GameTest(structure = "markai-test:arena", maxTicks = 400)
    public void lessonsMemoryAndCome(GameTestHelper helper) throws Exception {
        setup(helper);
        var owner = owner(helper);
        command(owner, "walk spawn");
        var c = companion(owner);
        Wolf wolf = (Wolf) helper.getLevel().getEntity(c.entity);
        owner.getAbilities().instabuild = false;
        command(owner, "walk train голос");
        helper.assertTrue(c.training.progress(Trick.BARK) == 0, "No lesson without food");
        owner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.COOKED_BEEF, 8));
        command(owner, "walk train принеси");
        helper.assertTrue(owner.getMainHandItem().getCount() == 8, "Locked lesson must not consume treats");
        teach(owner, c, Trick.COME);
        helper.assertTrue(c.training.knows(Trick.COME) && c.training.level() == 2 && owner.getMainHandItem().getCount() == 5,
            "Three lessons consume three treats and earn level two");
        c.memory.rememberExchange("Хочу дом из берёзы с синей крышей.", new OllamaClient.Reply("say", "Запомнил."), 10);
        command(owner, "walk stay");
        String saved = Files.readString(helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("markai.json"));
        boolean restored = false;
        for (MarkAIMod.Companion decoded : new Gson().fromJson(JsonParser.parseString(saved).getAsJsonObject().getAsJsonArray("companions"), MarkAIMod.Companion[].class)) {
            if (decoded.owner.equals(owner.getUUID())) {
                restored = decoded.training.knows(Trick.COME) && decoded.training.level() == 2
                    && new Gson().toJson(decoded.memory.context("дом")).contains("синей крышей") && decoded.memory.history().size() == 2;
            }
        }
        helper.assertTrue(restored, "World save must reload training and exact remembered plan");
        owner.setPos(owner.getX() + 8, owner.getY(), owner.getZ());
        command(owner, "walk come");
        helper.assertTrue(c.trick != null, "Come must start a trick task");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(c.trick == null && wolf.distanceTo(owner) < 2, "Learned come must actually approach owner: " + debug(c, wolf, owner)))
            .thenIdle(12)
            .thenExecute(() -> {
                helper.assertTrue(!c.following && wolf.isOrderedToSit(), "Come must restore previous STAY mode");
                command(owner, "walk ai off"); command(owner, "walk ai on");
                helper.assertTrue(!c.memory.history().isEmpty(), "AI toggle must retain long-term conversation");
                command(owner, "walk forget");
                helper.assertTrue(c.memory.history().isEmpty() && c.training.knows(Trick.COME), "Forget must preserve learned tricks");
            }).thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 600)
    public void fetchDeliversRealItemAndCancellationDropsIt(GameTestHelper helper) throws Exception {
        setup(helper);
        var owner = owner(helper);
        command(owner, "walk spawn");
        var c = companion(owner);
        Wolf wolf = (Wolf) helper.getLevel().getEntity(c.entity);
        owner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.COOKED_BEEF, 12));
        teach(owner, c, Trick.BARK);
        teach(owner, c, Trick.FETCH);
        command(owner, "walk stay");
        Vec3 itemPos = wolf.position().add(6, 0, 0);
        ItemEntity first = dropped(helper, owner, itemPos, 3);
        int original = countDiamonds(owner);
        command(owner, "walk fetch");
        helper.assertTrue(c.trick != null, "Fetch must start a trick task");
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(c.trick == null && countDiamonds(owner) == original + 3, "Fetch must bring the real dropped stack to owner: " + debug(c, wolf, owner)
                + ", item=" + first.position() + ", alive=" + first.isAlive() + ", riding=" + first.isPassenger() + ", wolfItemDistance=" + wolf.distanceTo(first)))
            .thenExecute(() -> {
                helper.assertTrue(first.isRemoved(), "Delivered item entity must be removed exactly once");
                helper.assertTrue(!c.following && wolf.isOrderedToSit(), "Fetch restores sitting");
                c.nextTrickAt = 0;
                ItemEntity second = dropped(helper, owner, wolf.position().add(6, 0, 0), 2);
                c.memory.rememberEvent("test_item", second.getUUID().toString(), 0);
                command(owner, "walk fetch");
            })
            .thenWaitUntil(() -> helper.assertTrue(wolf.getPassengers().stream().anyMatch(e -> e instanceof ItemEntity), "Retrieved object must be the persistent item entity"))
            .thenExecute(() -> {
                ItemEntity carrying = (ItemEntity) wolf.getPassengers().stream().filter(e -> e instanceof ItemEntity).findFirst().orElseThrow();
                command(owner, "walk stay");
                helper.assertTrue(c.trick == null && !carrying.isPassenger() && carrying.isAlive() && carrying.getItem().getCount() == 2,
                    "Cancellation must drop the original stack without loss or duplication");
                helper.assertTrue(countDiamonds(owner) == original + 3, "Cancellation must not also give the carried item to inventory");
            }).thenSucceed();
    }

    private static void setup(GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }
    private static String debug(MarkAIMod.Companion c, Wolf wolf, ServerPlayer owner) {
        return "distance=" + wolf.distanceTo(owner) + ", wolf=" + wolf.position() + ", owner=" + owner.position() + ", task=" + c.trick + ", sit=" + wolf.isOrderedToSit()
            + ", pose=" + wolf.isInSittingPose() + ", navDone=" + wolf.getNavigation().isDone()
            + ", sight=" + wolf.hasLineOfSight(owner) + ", collision=" + wolf.horizontalCollision
            + ", goals=" + ((dev.markai.mixin.MobAccessor) wolf).markai$goals().getAvailableGoals().stream()
                .filter(g -> g.isRunning()).map(g -> g.getGoal().getClass().getSimpleName()).toList();
    }
    private static ServerPlayer owner(GameTestHelper helper) {
        var owner = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2, 2, 2));
        owner.setPos(pos.x, pos.y, pos.z);
        return owner;
    }
    private static void command(ServerPlayer owner, String command) {
        owner.level().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(), command);
    }
    private static void teach(ServerPlayer owner, MarkAIMod.Companion c, Trick trick) {
        for (int i = 0; i < 3; i++) { c.nextLessonAt = 0; command(owner, "walk train " + trick.title); }
    }
    private static ItemEntity dropped(GameTestHelper helper, ServerPlayer owner, Vec3 pos, int count) {
        var item = new ItemEntity(helper.getLevel(), pos.x, pos.y, pos.z, new ItemStack(Items.DIAMOND, count));
        item.setThrower(owner); item.setNoPickUpDelay();
        helper.getLevel().addFreshEntity(item);
        return item;
    }
    private static int countDiamonds(ServerPlayer owner) {
        int count = 0;
        for (int i = 0; i < owner.getInventory().getContainerSize(); i++)
            if (owner.getInventory().getItem(i).is(Items.DIAMOND)) count += owner.getInventory().getItem(i).getCount();
        return count;
    }
    private static MarkAIMod.Companion companion(ServerPlayer owner) throws Exception {
        var mod = (MarkAIMod) FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(m -> m instanceof MarkAIMod).findFirst().orElseThrow();
        return TestCompanions.selected(mod, owner.getUUID());
    }
}
