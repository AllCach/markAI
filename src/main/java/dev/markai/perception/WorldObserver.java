package dev.markai.perception;

import dev.markai.actions.CompanionSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Main-thread perception. Block/entity scans are cached for one second and never load chunks. */
public final class WorldObserver {
    private record Cached(long tick, EnvironmentSnapshot environment, List<EntityObservation> entities) {}
    private final Map<UUID, Cached> cache = new HashMap<>();
    private int entityRadius = 32;
    private long observations, scans, totalNanos, maximumNanos;
    public void clear() { cache.clear(); observations = scans = totalNanos = maximumNanos = 0; }
    public void remove(UUID id) { cache.remove(id); }
    public void setEntityRadius(int blocks) {
        if (blocks < 8 || blocks > 64) throw new IllegalArgumentException("Entity radius must be between 8 and 64 blocks");
        entityRadius = blocks; cache.clear();
    }
    public Map<String, Object> metrics() {
        return Map.of("observations", observations, "scans", scans, "cached_observations", observations - scans,
            "mean_microseconds", observations == 0 ? 0 : totalNanos / observations / 1000.0,
            "maximum_microseconds", maximumNanos / 1000.0, "entity_radius", entityRadius, "entity_limit", 12,
            "block_scan_limit", 405, "scan_interval_ticks", 20);
    }

    public WorldState observe(ServerLevel level, Wolf wolf, ServerPlayer owner) {
        long start = System.nanoTime();
        long now = level.getGameTime();
        boolean critical = CompanionSafety.critical(wolf);
        Cached old = cache.get(wolf.getUUID());
        if (old == null || now < old.tick || now - old.tick >= 20
                || !old.environment.dimension().equals(level.dimension().identifier().toString())) {
            scans++;
            double radiusSquared = (double) entityRadius * entityRadius;
            var entities = level.getEntities(wolf, wolf.getBoundingBox().inflate(entityRadius),
                    e -> e.isAlive() && !e.isInvisible() && wolf.distanceToSqr(e) <= radiusSquared && wolf.hasLineOfSight(e))
                .stream().sorted(Comparator.comparingDouble(wolf::distanceToSqr)).limit(12)
                .map(e -> new EntityObservation(e.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                    e.getType().getDescription().getString(), e.getX(), e.getY(), e.getZ(), wolf.distanceTo(e), true, e instanceof Enemy)).toList();
            var important = new ArrayList<EnvironmentSnapshot.BlockObservation>();
            var hazards = new ArrayList<EnvironmentSnapshot.BlockObservation>();
            BlockPos feet = wolf.blockPosition();
            for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-4, -1, -4), feet.offset(4, 3, 4))) {
                if (!level.hasChunkAt(pos)) continue;
                var state = level.getBlockState(pos);
                String type = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                boolean dangerous = CompanionSafety.harmful(state);
                boolean notable = type.endsWith(":chest") || type.endsWith(":barrel") || type.endsWith("_bed")
                    || type.endsWith(":crafting_table") || type.endsWith(":furnace") || type.endsWith("diamond_ore");
                if ((!dangerous && !notable) || !visible(level, wolf, pos)) continue;
                var block = new EnvironmentSnapshot.BlockObservation(type, pos.getX(), pos.getY(), pos.getZ());
                if (dangerous && hazards.size() < 8) hazards.add(block);
                if (notable && important.size() < 8) important.add(block);
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = feet.relative(direction);
                if (level.hasChunkAt(next) && level.getBlockState(next.below()).isAir()
                        && level.getBlockState(next.below(2)).isAir() && level.getBlockState(next.below(3)).isAir()) {
                    if (hazards.size() < 8) hazards.add(new EnvironmentSnapshot.BlockObservation("large_drop", next.getX(), next.getY(), next.getZ()));
                }
            }
            int light = level.getMaxLocalRawBrightness(feet);
            var environment = new EnvironmentSnapshot(level.dimension().identifier().toString(),
                level.getBiome(feet).unwrapKey().map(k -> k.identifier().toString()).orElse("unknown"),
                now, level.getDefaultClockTime() % 24000, level.isRaining(), level.isThundering(), light,
                !level.canSeeSky(feet) && light < 7, List.copyOf(important), List.copyOf(hazards), critical);
            old = new Cached(now, environment, entities);
            cache.put(wolf.getUUID(), old);
        } else if (old.environment.critical_hazard() != critical) {
            EnvironmentSnapshot environment = old.environment;
            old = new Cached(old.tick, new EnvironmentSnapshot(environment.dimension(), environment.biome(),
                environment.game_time(), environment.time_of_day(), environment.raining(), environment.thundering(),
                environment.light(), environment.dangerous_cave(), environment.important_blocks(), environment.hazards(),
                critical), old.entities);
            cache.put(wolf.getUUID(), old);
        }
        var vehicle = owner.getVehicle();
        String vehicleType = vehicle == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString();
        var armor = new ArrayList<String>();
        for (var slot : List.of(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET))
            armor.add(BuiltInRegistries.ITEM.getKey(owner.getItemBySlot(slot).getItem()).toString());
        var player = new PlayerSnapshot(owner.getUUID(), owner.getGameProfile().name(), position(owner.blockPosition()),
            owner.level().dimension().identifier().toString(), owner.getHealth(), owner.getFoodData().getFoodLevel(),
            owner.getActiveEffects().stream().limit(8).map(e -> BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).toString()).toList(),
            List.copyOf(armor), BuiltInRegistries.ITEM.getKey(owner.getMainHandItem().getItem()).toString(),
            BuiltInRegistries.ITEM.getKey(owner.getOffhandItem().getItem()).toString(), wolf.distanceTo(owner),
            owner.getDeltaMovement().lengthSqr() > 0.0025, owner.isSprinting(), owner.isCrouching(), owner.isSleeping(),
            owner.isInWater(), vehicleType.contains("boat"), vehicleType);
        long elapsed = System.nanoTime() - start;
        observations++; totalNanos += elapsed; maximumNanos = Math.max(maximumNanos, elapsed);
        return new WorldState(player, old.environment, old.entities, elapsed);
    }

    private static boolean visible(ServerLevel level, Wolf wolf, BlockPos pos) {
        var hit = level.clip(new ClipContext(wolf.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, wolf));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }
    public static Map<String, Integer> position(BlockPos pos) { return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()); }
}
