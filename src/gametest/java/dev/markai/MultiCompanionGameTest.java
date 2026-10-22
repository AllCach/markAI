package dev.markai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.markai.companion.CompanionRegistry;
import dev.markai.inventory.CompanionInventoryHolder;
import dev.markai.network.CompanionNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Integration regressions for owner-scoped selection, unique entities and schema-v1 persistence. */
public final class MultiCompanionGameTest {
    @GameTest(structure = "markai-test:arena", maxTicks = 200)
    public void twoCompanionsKeepSeparateStateAndSelection(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++)
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);

        ServerPlayer owner = player(helper, 2, 2, 2);
        var server = helper.getLevel().getServer();
        MarkAIMod mod = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(MarkAIMod.class::isInstance).map(MarkAIMod.class::cast).findFirst().orElseThrow();
        CompanionRegistry<MarkAIMod.Companion> registry = TestCompanions.registry(mod);
        CompanionNetwork.Backend backend = backend();

        var dispatcher = server.getCommands().getDispatcher().getRoot();
        helper.assertTrue(dispatcher.getChild("ma") != null && dispatcher.getChild("mo") != null
                && dispatcher.getChild("m1") == null,
            "The short chat commands expose /ma and /mo only; /m1 is not registered");

        command(owner, "mark spawn");
        MarkAIMod.Companion first = registry.selected(owner.getUUID());
        helper.assertTrue(first != null, "First spawn registers a selected companion");
        Wolf firstWolf = (Wolf) helper.getLevel().getEntity(first.entity);
        helper.assertTrue(firstWolf != null && firstWolf.isOwnedBy(owner), "First wolf belongs to its owner");

        // Use a supported explicit fact prefix so the deterministic memory extractor stores it.
        first.memory.rememberStatement("Запомни alpha_companion_secret_2026", helper.getLevel().getGameTime());
        command(owner, "mark spawn");
        MarkAIMod.Companion second = registry.selected(owner.getUUID());
        helper.assertTrue(second != null && second != first && !second.id.equals(first.id),
            "Second spawn creates and selects an independent logical identity");
        helper.assertTrue(!second.entity.equals(first.entity) && registry.owned(owner.getUUID()).size() == 2,
            "Both registered companions have distinct entity identities");
        Wolf secondWolf = (Wolf) helper.getLevel().getEntity(second.entity);
        helper.assertTrue(secondWolf != null && secondWolf.isOwnedBy(owner) && firstWolf.isAlive(),
            "Both tamed wolves stay alive in the same world");

        JsonObject selectedView = JsonParser.parseString(backend.snapshot(owner)).getAsJsonObject();
        helper.assertTrue(selectedView.get("companion_id").getAsString().equals(second.id.toString())
            && selectedView.getAsJsonArray("companions").size() == 2,
            "Owner UI exposes both companions and the active selection");
        JsonObject neighborCard = selectedView.getAsJsonArray("companions").get(0).getAsJsonObject();
        helper.assertTrue(neighborCard.get("id").getAsString().equals(first.id.toString())
                && neighborCard.get("entity_uuid").getAsString().equals(first.entity.toString())
                && neighborCard.get("health").getAsDouble() > 0
                && neighborCard.get("max_health").getAsDouble() >= firstWolf.getMaxHealth()
                && neighborCard.has("activity"),
            "Neighbor preview is populated with real entity, health and activity data");
        helper.assertTrue(!selectedView.toString().contains("alpha_companion_secret_2026"),
            "First companion's memory does not leak into the second companion's view");

        backend.select(owner, first.id.toString());
        JsonObject firstView = JsonParser.parseString(backend.snapshot(owner)).getAsJsonObject();
        helper.assertTrue(registry.selected(owner.getUUID()) == first
            && firstView.get("companion_id").getAsString().equals(first.id.toString())
            && firstView.toString().contains("alpha_companion_secret_2026"),
            "Switching restores the first companion's own memory and profile");

        backend.select(owner, second.id.toString());
        command(owner, "mark stay");
        helper.assertTrue(registry.selected(owner.getUUID()) == second && !second.following && first.following,
            "Movement orders apply only to the selected companion");

        Path path = server.getWorldPath(LevelResource.ROOT).resolve("markai.json");
        JsonObject state = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        helper.assertTrue(state.get("schemaVersion").getAsInt() == 1
            && state.getAsJsonArray("companions").size() >= 2,
            "Versioned save contains both companions");
        helper.assertTrue(state.getAsJsonObject("selected").get(owner.getUUID().toString()).getAsString().equals(second.id.toString()),
            "Selected companion identity is persisted");

        ServerPlayer stranger = player(helper, 12, 2, 12);
        backend.select(stranger, first.id.toString());
        helper.assertTrue(registry.selected(stranger.getUUID()) == null
            && registry.selected(owner.getUUID()) == second,
            "A second player cannot select someone else's companion");

        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 200)
    public void ctrlGroupOrdersAffectOwnWolvesWithoutChangingSelection(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++)
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        ServerPlayer owner = player(helper, 2, 2, 2);
        MarkAIMod mod = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(MarkAIMod.class::isInstance).map(MarkAIMod.class::cast).findFirst().orElseThrow();
        var registry = TestCompanions.registry(mod);
        CompanionNetwork.Backend backend = backend();

        command(owner, "mark spawn");
        MarkAIMod.Companion first = registry.selected(owner.getUUID());
        helper.assertTrue(first != null, "First wolf registered for group orders");
        command(owner, "mark spawn");
        MarkAIMod.Companion second = registry.selected(owner.getUUID());
        helper.assertTrue(second != null && second != first, "Second wolf registered for group orders");

        ServerPlayer stranger = player(helper, 20, 2, 20);
        command(stranger, "mark spawn");
        MarkAIMod.Companion foreign = registry.selected(stranger.getUUID());
        helper.assertTrue(foreign != null && foreign.following, "Other owner has a separate active wolf");

        backend.command(owner, "all:stay");
        helper.assertTrue(!first.following && !second.following && foreign.following,
            "Ctrl+stay affects all owned wolves but does not touch another owner's wolf");
        helper.assertTrue(registry.selected(owner.getUUID()) == second,
            "Group orders never change active selection");

        backend.command(owner, "all:follow");
        helper.assertTrue(first.following && second.following && foreign.following,
            "Ctrl+follow restores both owned wolves independently");

        Cow target = helper.spawn(EntityTypes.COW, new BlockPos(2, 2, 10));
        target.setNoAi(true);
        owner.lookAt(EntityAnchorArgument.Anchor.EYES, target, EntityAnchorArgument.Anchor.EYES);
        backend.command(owner, "all:attack");
        Wolf firstWolf = (Wolf) helper.getLevel().getEntity(first.entity);
        Wolf secondWolf = (Wolf) helper.getLevel().getEntity(second.entity);
        helper.assertTrue(firstWolf != null && secondWolf != null
                && first.workActive && second.workActive
                && firstWolf.getTarget() == target && secondWolf.getTarget() == target,
            "Ctrl+attack assigns one captured visible target to every owned wolf");
        helper.assertTrue(!foreign.workActive && registry.selected(owner.getUUID()) == second,
            "Attack never affects a foreign companion or active selection");

        backend.command(owner, "all:stop");
        helper.assertTrue(!first.workActive && !second.workActive
                && firstWolf.getTarget() == null && secondWolf.getTarget() == null,
            "Ctrl+stop cancels both native combat jobs");
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void removeCompanionKeepsTameWolfAndDropsBagItems(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++)
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        ServerPlayer owner = player(helper, 2, 2, 2);
        MarkAIMod mod = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(MarkAIMod.class::isInstance).map(MarkAIMod.class::cast).findFirst().orElseThrow();
        command(owner, "mark spawn");
        var registry = TestCompanions.registry(mod);
        var companion = registry.selected(owner.getUUID());
        Wolf wolf = (Wolf) helper.getLevel().getEntity(companion.entity);
        var holder = (CompanionInventoryHolder) wolf;
        holder.markai$inventory().setItem(0, new ItemStack(Items.BEEF, 4));

        backend().remove(owner, companion.id.toString());

        helper.assertTrue(registry.owned(owner.getUUID()).isEmpty() && registry.selected(owner.getUUID()) == null,
            "Removal clears the companion roster and selected identity");
        helper.assertTrue(wolf.isAlive() && wolf.isTame() && wolf.isOwnedBy(owner) && !holder.markai$isCompanion(),
            "Removal leaves the same living vanilla-tame wolf with its owner");
        int dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, wolf.getBoundingBox().inflate(4)).stream()
            .filter(ItemEntity::isAlive).map(ItemEntity::getItem).filter(stack -> stack.is(Items.BEEF))
            .mapToInt(ItemStack::getCount).sum();
        helper.assertTrue(dropped == 4 && holder.markai$inventory().isEmpty(),
            "Removing the MarkAI companion releases its bag items without losing or duplicating them");
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 100)
    public void cannotRemoveUnloadedCompanionAndLoseItsBag(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++)
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        ServerPlayer owner = player(helper, 2, 2, 2);
        MarkAIMod mod = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(MarkAIMod.class::isInstance).map(MarkAIMod.class::cast).findFirst().orElseThrow();
        command(owner, "mark spawn");
        var registry = TestCompanions.registry(mod);
        var companion = registry.selected(owner.getUUID());
        Wolf wolf = (Wolf) helper.getLevel().getEntity(companion.entity);
        var holder = (CompanionInventoryHolder) wolf;
        holder.markai$inventory().setItem(0, new ItemStack(Items.BEEF, 4));

        UUID entityId = companion.entity;
        companion.entity = UUID.randomUUID(); // Simulate an entity outside the server's loaded chunks.
        backend().remove(owner, companion.id.toString());

        helper.assertTrue(registry.owned(owner.getUUID()).size() == 1 && registry.selected(owner.getUUID()) == companion,
            "An unloaded companion remains in its owner's roster when removal cannot reach its wolf");
        helper.assertTrue(wolf.isAlive() && holder.markai$isCompanion() && holder.markai$inventory().getItem(0).getCount() == 4,
            "An unloaded wolf stays registered with its bag intact instead of becoming an orphan");
        companion.entity = entityId;
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 300)
    public void groupCollectionTransfersOneRealStackExactlyOnce(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        ServerPlayer owner = player(helper, 2, 2, 2);
        MarkAIMod mod = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(MarkAIMod.class::isInstance).map(MarkAIMod.class::cast).findFirst().orElseThrow();
        command(owner, "mark spawn"); command(owner, "mark spawn");
        var roster = TestCompanions.registry(mod).owned(owner.getUUID());
        var first = roster.get(0); var second = roster.get(1);
        Wolf a = (Wolf) helper.getLevel().getEntity(first.entity), b = (Wolf) helper.getLevel().getEntity(second.entity);
        var pos = helper.absoluteVec(new Vec3(2, 2, 5));
        var drop = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), pos.x, pos.y, pos.z,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BEEF, 9));
        drop.setNoPickUpDelay(); drop.setDeltaMovement(Vec3.ZERO); helper.getLevel().addFreshEntity(drop);
        backend().command(owner, "all:collect");
        helper.assertTrue(first.workActive && second.workActive, "One group command starts both independent collection jobs");
        helper.startSequence().thenWaitUntil(() -> {
            int total = 0;
            for (Wolf wolf : java.util.List.of(a, b)) {
                var bag = ((dev.markai.inventory.CompanionInventoryHolder) wolf).markai$inventory();
                for (int i = 0; i < bag.getContainerSize(); i++) if (bag.getItem(i).is(net.minecraft.world.item.Items.BEEF)) total += bag.getItem(i).getCount();
            }
            helper.assertTrue(drop.isRemoved() && total == 9, "Two wolves must transfer exactly the original nine meat items, with no duplication");
        }).thenExecute(() -> {
            try { backend().command(owner, "all:stop"); } catch (Exception error) { throw new RuntimeException(error); }
            helper.assertTrue(!first.workActive && !second.workActive, "Group stop clears both remaining jobs");
        }).thenSucceed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 160)
    public void commonSettingsAffectOnlyOwnedCompanionsAndPersist(GameTestHelper helper) throws Exception {
        for (int x=0;x<32;x++) for(int z=0;z<32;z++) helper.setBlock(new BlockPos(x,1,z),Blocks.STONE);
        ServerPlayer owner=player(helper,2,2,2), stranger=player(helper,12,2,12);
        MarkAIMod mod=FabricLoader.getInstance().getEntrypoints("main",ModInitializer.class).stream()
            .filter(MarkAIMod.class::isInstance).map(MarkAIMod.class::cast).findFirst().orElseThrow();
        command(owner,"mark spawn");command(owner,"mark spawn");command(stranger,"mark spawn");
        var registry=TestCompanions.registry(mod);var owned=registry.owned(owner.getUUID());
        var foreign=registry.selected(stranger.getUUID());UUID selected=registry.selected(owner.getUUID()).id;
        foreign.living.settings.defense=true;foreign.living.settings.hud=true;foreign.living.settings.followDistance=4;
        owned.getFirst().living.settings.hud=false;owned.getLast().living.settings.hud=true;
        JsonObject before=JsonParser.parseString(backend().snapshot(owner)).getAsJsonObject();
        helper.assertTrue(before.getAsJsonObject("common_settings").get("hud").getAsString().equals("mixed"),"Mixed preferences are shown honestly");
        backend().setting(owner,"all:defense","false");backend().setting(owner,"all:hud","false");
        backend().setting(owner,"all:follow_distance","9");backend().setting(owner,"mutual_replies","false");
        helper.assertTrue(owned.stream().allMatch(c->!c.living.settings.defense&&!c.living.settings.hud&&c.living.settings.followDistance==9),"One bulk setting applies to both owned identities");
        helper.assertTrue(foreign.living.settings.defense&&foreign.living.settings.hud&&foreign.living.settings.followDistance==4,"Bulk settings cannot affect another owner");
        helper.assertTrue(registry.selected(owner.getUUID()).id.equals(selected),"Bulk configuration does not switch selection");
        String originalName=owned.getFirst().living.name;
        backend().setting(owner,"all:name","Wrong name");backend().setting(owner,"all:trait_trust","0");
        helper.assertTrue(owned.getFirst().living.name.equals(originalName),"Names and personalities cannot be overwritten through bulk preference keys");
        Path path=helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("markai.json");
        var saved=new dev.markai.companion.CompanionStore<>(MarkAIMod.Companion.class).read(path);
        helper.assertTrue(!saved.groups().get(owner.getUUID()).mutualReplies(),"Owner group preference survives a real store read");
        JsonObject strangerView=JsonParser.parseString(backend().snapshot(stranger)).getAsJsonObject();
        helper.assertTrue(strangerView.getAsJsonObject("group").get("mutual_replies").getAsBoolean(),"A different owner's group preferences remain separate");
        helper.succeed();
    }

    @GameTest(structure = "markai-test:arena", maxTicks = 200)
    public void wolfAndChickenCompanionsCoexistWithoutCrossSpeciesOrders(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++)
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);

        ServerPlayer owner = player(helper, 2, 2, 2);
        MarkAIMod mod = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(MarkAIMod.class::isInstance).map(MarkAIMod.class::cast).findFirst().orElseThrow();
        var registry = TestCompanions.registry(mod);

        command(owner, "mark spawn");
        MarkAIMod.Companion wolfCompanion = registry.selected(owner.getUUID());
        helper.assertTrue(wolfCompanion != null, "The wolf companion is registered first");
        Wolf wolf = (Wolf) helper.getLevel().getEntity(wolfCompanion.entity);
        helper.assertTrue(wolf != null && wolf.isAlive(), "The first companion is a real living wolf");

        command(owner, "mark spawn chicken");
        MarkAIMod.Companion chickenCompanion = registry.selected(owner.getUUID());
        helper.assertTrue(chickenCompanion != null && chickenCompanion != wolfCompanion,
            "Chicken creation registers a separate logical companion");
        Chicken chicken = (Chicken) helper.getLevel().getEntity(chickenCompanion.entity);
        helper.assertTrue(chicken != null && chicken.isAlive() && wolf.isAlive(),
            "The chicken and wolf entities coexist without replacing one another");
        helper.assertTrue(!wolfCompanion.entity.equals(chickenCompanion.entity)
                && registry.owned(owner.getUUID()).size() == 2
                && "minecraft:wolf".equals(wolfCompanion.species)
                && "minecraft:chicken".equals(chickenCompanion.species),
            "Both species have separate entity UUIDs and species identities");

        command(owner, "mark stay");
        helper.assertTrue(!chickenCompanion.following && wolfCompanion.following,
            "Stay applies only to the selected chicken");
        backend().select(owner, wolfCompanion.id.toString());
        command(owner, "mark stay");
        helper.assertTrue(!wolfCompanion.following && !chickenCompanion.following,
            "Selecting the wolf changes only the wolf's movement state");
        backend().select(owner, chickenCompanion.id.toString());
        command(owner, "mark follow");
        helper.assertTrue(chickenCompanion.following && !wolfCompanion.following,
            "Following the chicken does not restart the wolf");

        Path path = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("markai.json");
        JsonObject state = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        boolean savedWolf = false;
        boolean savedChicken = false;
        for (var element : state.getAsJsonArray("companions")) {
            JsonObject saved = element.getAsJsonObject();
            String species = saved.has("species") ? saved.get("species").getAsString() : "minecraft:wolf";
            if (wolfCompanion.id.toString().equals(saved.get("id").getAsString()))
                savedWolf = "minecraft:wolf".equals(species);
            if (chickenCompanion.id.toString().equals(saved.get("id").getAsString()))
                savedChicken = "minecraft:chicken".equals(species);
        }
        helper.assertTrue(savedWolf && savedChicken,
            "The real world save persists both species identities without converting the chicken to a wolf");

        helper.startSequence().thenIdle(30).thenExecute(() -> {
            helper.assertTrue(wolf.isAlive() && chicken.isAlive()
                    && helper.getLevel().getEntity(wolfCompanion.entity) == wolf
                    && helper.getLevel().getEntity(chickenCompanion.entity) == chicken,
                "Species-specific ticking keeps both original entities alive");
        }).thenSucceed();
    }

    private static ServerPlayer player(GameTestHelper helper, int x, int y, int z) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
        player.setPos(pos.x, pos.y, pos.z);
        return player;
    }

    private static void command(ServerPlayer player, String command) {
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static CompanionNetwork.Backend backend() throws ReflectiveOperationException {
        var field = CompanionNetwork.class.getDeclaredField("backend");
        field.setAccessible(true);
        return (CompanionNetwork.Backend) field.get(null);
    }
}
