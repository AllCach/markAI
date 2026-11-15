package dev.markai.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Server-authoritative MarkAI preferences, opened from Minecraft's Options screen. */
@Environment(EnvType.CLIENT)
public final class MarkAISettingsScreen extends Screen {
    private final Screen parent;
    private Button memory;

    public MarkAISettingsScreen(Screen parent) {
        super(Component.translatable("markai.options.title"));
        this.parent = parent;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        int x = (width - Math.min(340, width - 24)) / 2;
        int w = Math.min(340, width - 24);
        int y = Math.max(36, height / 2 - 40);
        memory = addRenderableWidget(Button.builder(Component.empty(), button -> {
            if (MarkAIClient.available() && MarkAIClient.STATE.bool("can_manage_memory_persistence", false)) {
                boolean enabled = MarkAIClient.STATE.bool("memory_persistence", true);
                MarkAIClient.setting("memory_persistence", Boolean.toString(!enabled));
            }
        }).bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
            .bounds(x, y + 75, w, 20).build());
        if (MarkAIClient.available()) MarkAIClient.request();
        updateButton();
    }

    @Override public void tick() { super.tick(); updateButton(); }

    private void updateButton() {
        if (memory == null) return;
        boolean allowed = MarkAIClient.available() && MarkAIClient.STATE.received()
            && MarkAIClient.STATE.bool("can_manage_memory_persistence", false);
        memory.active = allowed;
        memory.setMessage(Component.translatable("markai.options.save_memory",
            Component.translatable(MarkAIClient.STATE.bool("memory_persistence", true)
                ? "markai.ui.on" : "markai.ui.off")));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xDC10181B);
        int x = (width - Math.min(340, width - 24)) / 2;
        int w = Math.min(340, width - 24);
        int y = Math.max(36, height / 2 - 40);
        graphics.text(font, title, x, y - 24, 0xFFE8EFEA);
        Component hint = Component.translatable(MarkAIClient.available()
            ? MarkAIClient.STATE.bool("can_manage_memory_persistence", false)
                ? "markai.options.save_memory_hint" : "markai.options.operator_only"
            : "markai.options.in_world");
        int line = 0;
        for (var part : font.split(hint, w)) {
            graphics.text(font, part, x, y + 31 + line++ * 11, 0xFF9DB4A8);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
