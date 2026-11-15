package dev.markai.taming;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/** Species-aware, owner-validated companion enrollment through in-world interactions. */
public final class CompanionTamingInteractions {
    public enum RegistrationResult { ADOPTED, PENDING, ALREADY_REGISTERED, REJECTED }

    public interface Registrar {
        void registerWolf(ServerPlayer owner, Wolf wolf);
        RegistrationResult registerChicken(ServerPlayer owner, Chicken chicken);
    }

    private static Registrar registrar;

    private CompanionTamingInteractions() { }

    public static void register(Registrar callback) {
        registrar = callback;
        UseEntityCallback.EVENT.register(CompanionTamingInteractions::interact);
    }

    /** Called at the head of vanilla Wolf.tryToTame, before it can consume the bone. */
    public static boolean isShiftBoneTaming(Player player, Wolf wolf) {
        return player instanceof ServerPlayer && player.isShiftKeyDown() && !player.isSpectator()
            && player.getMainHandItem().is(Items.BONE) && wolf.isAlive() && !wolf.isTame();
    }

    /** Called after vanilla has made the wolf tame; identity and vanilla ownership remain untouched. */
    public static void onVanillaWolfTamed(Player player, Wolf wolf) {
        if (registrar == null || !(player instanceof ServerPlayer owner) || owner.level().isClientSide()
                || !owner.isShiftKeyDown() || !wolf.isAlive() || !wolf.isTame() || !wolf.isOwnedBy(owner)) return;
        registrar.registerWolf(owner, wolf);
    }

    private static InteractionResult interact(Player player, Level level, InteractionHand hand, Entity entity,
                                              EntityHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || player.isSpectator())
            return InteractionResult.PASS;

        if (entity instanceof Wolf) {
            // Let vanilla perform its complete chance, sound, bone-consumption and ownership logic.
            // WolfTamingMixin registers the same entity only after that logic succeeds.
            return InteractionResult.PASS;
        }

        if (!(entity instanceof Chicken chicken) || !player.getMainHandItem().is(ItemTags.CHICKEN_FOOD))
            return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.CONSUME;
        if (!(player instanceof ServerPlayer owner) || registrar == null) return InteractionResult.FAIL;

        RegistrationResult result = registrar.registerChicken(owner, chicken);
        if (result == RegistrationResult.ADOPTED) {
            if (!owner.getAbilities().instabuild) owner.getMainHandItem().shrink(1);
            level.broadcastEntityEvent(chicken, (byte) 7);
            return InteractionResult.CONSUME;
        }
        return result == RegistrationResult.PENDING || result == RegistrationResult.ALREADY_REGISTERED
            ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }
}
