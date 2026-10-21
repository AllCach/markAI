package dev.markai.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.UUID;

/** Reads the vanilla pickup reservation without copying or serializing an item entity. */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    @Accessor("target") UUID markai$getPickupTarget();
}
