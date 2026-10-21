package dev.markai.mixin;

import dev.markai.inventory.CompanionInventoryHolder;
import dev.markai.inventory.DogInventory;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Wolf.class)
public abstract class WolfInventoryMixin implements CompanionInventoryHolder {
    @Unique private static final EntityDataAccessor<Boolean> MARKAI_COMPANION =
        SynchedEntityData.defineId(Wolf.class, EntityDataSerializers.BOOLEAN);
    @Unique private DogInventory markai$inventory;
    @Unique private boolean markai$inventoryDropped;

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void markai$defineCompanion(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(MARKAI_COMPANION, false);
    }

    @Override public boolean markai$isCompanion() {
        return ((Wolf) (Object) this).getEntityData().get(MARKAI_COMPANION);
    }

    @Override public void markai$setCompanion(boolean companion) {
        ((Wolf) (Object) this).getEntityData().set(MARKAI_COMPANION, companion);
    }

    @Override public DogInventory markai$inventory() {
        if (markai$inventory == null) markai$inventory = new DogInventory((Wolf) (Object) this);
        return markai$inventory;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void markai$saveInventory(ValueOutput output, CallbackInfo ci) {
        if (markai$isCompanion()) output.putBoolean("markai_companion", true);
        if (markai$inventory != null && !markai$inventory.isEmpty())
            ContainerHelper.saveAllItems(output.child("markai_inventory"), markai$inventory.getItems());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void markai$loadInventory(ValueInput input, CallbackInfo ci) {
        markai$setCompanion(input.getBooleanOr("markai_companion", false));
        markai$inventory = null;
        markai$inventoryDropped = false;
        input.child("markai_inventory").ifPresent(stored ->
            ContainerHelper.loadAllItems(stored, markai$inventory().getItems()));
    }

    @Inject(method = "die", at = @At("TAIL"))
    private void markai$dropInventory(DamageSource source, CallbackInfo ci) {
        Wolf wolf = (Wolf) (Object) this;
        if (!(wolf.level() instanceof ServerLevel level) || !markai$isCompanion()
                || markai$inventory == null || markai$inventoryDropped) return;
        markai$inventoryDropped = true;
        Containers.dropContents(level, wolf, markai$inventory);
        markai$inventory.clearContent();
    }
}
