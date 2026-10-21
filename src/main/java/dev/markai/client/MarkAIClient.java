package dev.markai.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.markai.network.CompanionNetwork;
import dev.markai.network.NetworkRules;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.animal.wolf.Wolf;

@Environment(EnvType.CLIENT)
public final class MarkAIClient implements ClientModInitializer {
    public static final ClientCompanionState STATE = new ClientCompanionState();
    private static KeyMapping open, dialogue, quick;
    private static final java.util.Map<KeyMapping, String> commandKeys = new java.util.LinkedHashMap<>();
    private static int ticks;
    private static Wolf preview;
    private static boolean connected;

    @Override public void onInitializeClient() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("markai", "companion"));
        open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.markai.open", InputConstants.KEY_G, category));
        dialogue = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.markai.dialogue", InputConstants.KEY_H, category));
        quick = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.markai.quick", InputConstants.KEY_V, category));
        commandKey("attack", InputConstants.KEY_R, category);
        commandKey("collect", InputConstants.KEY_J, category);
        commandKey("stop", InputConstants.KEY_K, category);
        commandKey("come", InputConstants.KEY_B, category);
        commandKey("stay", InputConstants.KEY_N, category);
        commandKey("follow", InputConstants.KEY_M, category);
        commandKey("inventory", InputConstants.KEY_I, category);
        ClientPlayNetworking.registerGlobalReceiver(CompanionNetwork.Snapshot.TYPE, (payload, context) -> STATE.update(payload.json()));
        ClientPlayNetworking.registerGlobalReceiver(CompanionNetwork.Notice.TYPE, (payload, context) -> STATE.notice(payload.kind(), payload.text()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) {
                for (var key : commandKeys.keySet()) while (key.consumeClick()) { }
                if (connected) { STATE.clear(); preview = null; connected = false; }
                return;
            }
            connected = true;
            while (open.consumeClick()) if (client.gui.screen() == null) show(CompanionScreen.Tab.PROFILE, false);
            while (dialogue.consumeClick()) if (client.gui.screen() == null) show(CompanionScreen.Tab.DIALOGUE, false);
            while (quick.consumeClick()) if (client.gui.screen() == null) show(CompanionScreen.Tab.COMMANDS, true);
            for (var binding : commandKeys.entrySet()) {
                boolean pressed = false;
                while (binding.getKey().consumeClick()) pressed = true;
                if (pressed && client.gui.screen() == null) action(binding.getValue());
            }
            if (++ticks % 20 == 0 && available() && (client.gui.screen() instanceof CompanionScreen
                    || ClientCompanionState.bool(STATE.settings(), "hud", true) || !STATE.received())) {
                request(); refreshPreview(client);
            }
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("markai", "companion_status"), (graphics, delta) -> {
            var client = Minecraft.getInstance();
            if (client.player == null || client.gui.screen() != null || !STATE.received()
                    || !ClientCompanionState.bool(STATE.settings(), "hud", true) || STATE.string("entity_uuid", "").isBlank()) return;
            String health = String.format(java.util.Locale.ROOT, "%.0f / %.0f", STATE.number("health", 0), STATE.number("max_health", 40));
            String line = STATE.string("name", "Mark") + "  " + health + "  " + CompanionScreen.localizedState("activity", STATE.string("activity", "IDLE"));
            int w = Math.min(graphics.guiWidth() - 12, Math.max(150, client.font.width(line) + 12));
            graphics.fill(6, 6, 6 + w, 40, 0xB0182026);
            graphics.text(client.font, client.font.plainSubstrByWidth(line, w - 12), 12, 12, 0xFFEAF2EC);
            String status = STATE.notice().isBlank() ? STATE.string("ai_status", "") : STATE.notice();
            graphics.text(client.font, client.font.plainSubstrByWidth(status, w - 12), 12, 26, STATE.errorNotice() ? 0xFFFF9999 : 0xFFAAC7B7);
        });
    }

    private static void commandKey(String action, int defaultKey, KeyMapping.Category category) {
        commandKeys.put(KeyMappingHelper.registerKeyMapping(new KeyMapping("key.markai." + action, defaultKey, category)), action);
    }

    public static boolean available() { return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(CompanionNetwork.Request.TYPE); }
    public static void show(CompanionScreen.Tab tab, boolean quickMenu) {
        Minecraft.getInstance().gui.setScreen(new CompanionScreen(tab, quickMenu));
        request();
    }
    public static void request() {
        if (available()) ClientPlayNetworking.send(new CompanionNetwork.Request());
        else STATE.notice("error", Component.translatable("markai.ui.no_server").getString());
    }
    public static void action(String action) { if (NetworkRules.action(action)) send(new CompanionNetwork.Action(action)); }
    public static boolean chat(String message) {
        if (NetworkRules.chat(message) && send(new CompanionNetwork.Chat(message))) {
            STATE.notice("status", Component.translatable("markai.ui.sent").getString());
            return true;
        }
        return false;
    }
    public static void cancel() { send(new CompanionNetwork.Cancel()); }
    public static void deleteMemory(String id) { if (NetworkRules.memoryId(id)) send(new CompanionNetwork.MemoryDelete(id)); }
    public static void setting(String key, String value) { if (NetworkRules.setting(key, value)) send(new CompanionNetwork.Setting(key, value)); }
    private static boolean send(CustomPacketPayload payload) {
        if (available()) { ClientPlayNetworking.send(payload); return true; }
        STATE.notice("error", Component.translatable("markai.ui.no_server").getString());
        return false;
    }
    public static Wolf preview() {
        return preview != null && preview.isAlive() && !preview.isRemoved() && preview.level() == Minecraft.getInstance().level
            && preview.getUUID().toString().equals(STATE.string("entity_uuid", "")) ? preview : null;
    }
    private static void refreshPreview(Minecraft client) {
        if (client.level == null) { preview = null; return; }
        String uuid = STATE.string("entity_uuid", "");
        int id = (int) STATE.number("entity_id", -1);
        if (id >= 0 && client.level.getEntity(id) instanceof Wolf wolf && wolf.getUUID().toString().equals(uuid)) { preview = wolf; return; }
        preview = null;
        if (uuid.isBlank()) return;
        for (var entity : client.level.entitiesForRendering()) {
            if (entity instanceof Wolf wolf && wolf.getUUID().toString().equals(uuid)) { preview = wolf; return; }
        }
    }
}
