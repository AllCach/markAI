package dev.markai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Verifies audible replies remain available when companion language generation is disabled. */
public final class VocalFallbackGameTest {
    @GameTest(structure = "markai-test:arena", maxTicks = 240)
    public void disabledAiUsesRateLimitedWolfVocalizations(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++)
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);

        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        Vec3 position = helper.absoluteVec(new Vec3(2, 2, 2));
        owner.setPos(position.x, position.y, position.z);
        command(owner, "mark spawn");

        MarkAIMod mod = (MarkAIMod) FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(MarkAIMod.class::isInstance).map(MarkAIMod.class::cast).findFirst().orElseThrow();
        MarkAIMod.Companion companion = TestCompanions.selected(mod, owner.getUUID());
        Wolf wolf = companion == null ? null : (Wolf) helper.getLevel().getEntity(companion.entity);
        helper.assertTrue(companion != null && wolf != null && wolf.isAlive(), "Spawn creates a live wolf for vocal fallback");

        command(owner, "mark ai off");
        long firstTick = helper.getLevel().getGameTime();
        command(owner, "mark ask Hello, Walk");
        long firstDeadline = companion.nextFallbackVocalAt;
        helper.assertTrue(firstDeadline == firstTick + 60,
            "A direct message to a companion with AI disabled triggers one native wolf vocalization");
        command(owner, "mark ask Another message");
        helper.assertTrue(companion.nextFallbackVocalAt == firstDeadline,
            "Repeated messages inside the three-second cooldown do not cause another bark");

        long[] groupDeadline = {0};
        helper.startSequence()
            .thenIdle(61)
            .thenExecute(() -> {
                command(owner, "ma Walk hello everyone");
                groupDeadline[0] = companion.nextFallbackVocalAt;
                helper.assertTrue(groupDeadline[0] > firstDeadline,
                    "An explicitly named AI-disabled companion also acknowledges the group chat by sound");
            })
            .thenSucceed();
    }

    private static void command(ServerPlayer owner, String command) {
        owner.level().getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(), command);
    }
}
