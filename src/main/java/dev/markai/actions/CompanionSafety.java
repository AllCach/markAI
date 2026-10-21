package dev.markai.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared game-thread safety checks using loaded blocks and their actual collision shapes. */
public final class CompanionSafety {
    private CompanionSafety() {}

    public static boolean critical(Wolf wolf) {
        return wolf.isInLava() || wolf.isOnFire() || wolf.isInWall() || wolf.getAirSupply() < 40
            || wolf.getY() < wolf.level().getMinY() - 1;
    }

    public static boolean harmful(BlockState state) {
        return state.is(BlockTags.DANGEROUS_FOR_TELEPORTATION) || state.is(BlockTags.FIRE)
            || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW);
    }

    /**
     * Validates the destination's support without requiring a clear body box: native navigation may
     * approach an observed chest or other occupied target from an adjacent block.
     */
    public static boolean safeNavigationTarget(ServerLevel level, Vec3 pos) {
        if (level == null || pos == null || !Double.isFinite(pos.x) || !Double.isFinite(pos.y)
                || !Double.isFinite(pos.z)) return false;
        BlockPos feet = BlockPos.containing(pos);
        if (!level.isInWorldBounds(feet) || !level.hasChunkAt(feet)) return false;
        AABB support = new AABB(pos.x - .25, pos.y - .0625, pos.z - .25,
            pos.x + .25, pos.y, pos.z + .25);
        if (!level.getWorldBorder().isWithinBounds(support)) return false;
        // A footprint can straddle a chunk edge. Reading collision shapes must remain wholly loaded.
        for (BlockPos corner : BlockPos.betweenClosed(
                BlockPos.containing(support.minX, pos.y, support.minZ),
                BlockPos.containing(support.maxX, pos.y, support.maxZ)))
            if (!level.hasChunkAt(corner)) return false;
        if (harmful(level.getBlockState(feet)) || harmful(level.getBlockState(feet.below()))
                || harmful(level.getBlockState(feet.above()))
                || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) return false;
        // getChunkForCollisions requests FULL with create=false; this does not load neighboring chunks.
        return level.getBlockCollisions(null, support).iterator().hasNext();
    }
}
