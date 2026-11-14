package dev.markai.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.markai.client.MarkAIClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lets Ctrl+B reach MarkAI's normal key mapping without also toggling vanilla narration. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @ModifyExpressionValue(method = "keyPress", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/GameNarrator;isActive()Z"))
    private boolean markai$keepNarratorOffForComeCommand(boolean narratorAvailable) {
        return narratorAvailable && !MarkAIClient.usesComeNarratorShortcut(Minecraft.getInstance());
    }
}
