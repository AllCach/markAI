package dev.markai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.markai.companion.CompanionRegistry;
import dev.markai.network.CompanionNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
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

        command(owner, "mark spawn");
        MarkAIMod.Companion first = registry.selected(owner.getUUID());
        helper.assertTrue(first != null, "First spawn registers a selected companion");
        Wolf firstWolf = (Wolf) helper.getLevel().getEntity(first.entity);
        helper.assertTrue(firstWolf != null && firstWolf.isOwnedBy(owner), "First wolf belongs to its owner");

        first.memory.rememberStatement("Remember alpha_companion_secret_2026", helper.getLevel().getGameTime());
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
