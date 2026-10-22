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
import net.minecraft.world.entity.LivingEntity;

@Environment(EnvType.CLIENT)
public final class MarkAIClient implements ClientModInitializer {
    public static final ClientCompanionState STATE = new ClientCompanionState();
    private static KeyMapping open, dialogue, quick, hub, groupChat;
    private static final java.util.Map<KeyMapping, String> commandKeys = new java.util.LinkedHashMap<>();
    private static final java.util.Set<KeyMapping> commandKeysDown = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static int ticks;
    private static LivingEntity preview;
    private static boolean connected;
    private static boolean keyMigrationChecked;
    private static long lastCycleAt;

    @Override public void onInitializeClient() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("markai", "companion"));
        open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.markai.open", InputConstants.KEY_G, category));
        dialogue = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.markai.dialogue", InputConstants.KEY_H, category));
        quick = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.markai.quick", InputConstants.KEY_V, category));
        hub = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.markai.hub", InputConstants.KEY_U, category));
        groupChat = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.markai.group_chat", InputConstants.KEY_Y, category));
        commandKey("attack", InputConstants.KEY_R, category);
        commandKey("collect", InputConstants.KEY_J, category);
        commandKey("stop", InputConstants.KEY_K, category);
        commandKey("come", InputConstants.KEY_B, category);
        commandKey("follow", InputConstants.KEY_M, category);
        commandKey("stay", InputConstants.KEY_N, category);
        commandKey("rest", InputConstants.KEY_O, category);
        commandKey("sethome", InputConstants.KEY_P, category);
        commandKey("inventory", InputConstants.KEY_I, category);
        ClientPlayNetworking.registerGlobalReceiver(CompanionNetwork.Snapshot.TYPE, (payload, context) -> {
            STATE.update(payload.json());
            refreshPreview(Minecraft.getInstance());
            if (Minecraft.getInstance().gui.screen() instanceof CompanionScreen screen) screen.selectionUpdated();
        });
        ClientPlayNetworking.registerGlobalReceiver(CompanionNetwork.Notice.TYPE, (payload, context) -> STATE.notice(payload.kind(), payload.text()));
        ClientPlayNetworking.registerGlobalReceiver(CompanionNetwork.Popup.TYPE, (payload, context) -> STATE.popupNotice(payload.kind(), payload.text()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!keyMigrationChecked) migrateLegacyKeyDefaults(client);
            if (client.player == null || client.level == null) {
                for (var key : commandKeys.keySet()) {
                    while (key.consumeClick()) { }
                    if (key.isDown()) commandKeysDown.add(key); else commandKeysDown.remove(key);
                }
                while (groupChat.consumeClick()) { }
                if (connected) { STATE.clear(); preview = null; connected = false; lastCycleAt = 0; }
                return;
            }
            connected = true;
            while (open.consumeClick()) if (client.gui.screen() == null) show(CompanionScreen.Tab.PROFILE, false);
            while (dialogue.consumeClick()) if (client.gui.screen() == null) show(CompanionScreen.Tab.DIALOGUE, false);
            while (hub.consumeClick()) if (client.gui.screen() == null) showHub();
            while (groupChat.consumeClick()) if (client.gui.screen() == null) showHub(CompanionHubScreen.Tab.CHAT);
            while (quick.consumeClick()) {
                if (client.gui.screen() != null) continue;
                // Reuse the existing V binding rather than reserving Q/E or hotbar keys.
                if (client.hasShiftDown()) cycleCompanion(1);
                else show(CompanionScreen.Tab.COMMANDS, true);
            }
            for (var binding : commandKeys.entrySet()) {
                boolean pressed = false;
                while (binding.getKey().consumeClick()) pressed = true;
                boolean wasDown = commandKeysDown.contains(binding.getKey());
                if (binding.getKey().isDown()) commandKeysDown.add(binding.getKey());
                else commandKeysDown.remove(binding.getKey());
                if (pressed && !wasDown && client.gui.screen() == null) {
                    if (client.hasControlDown()) groupAction(binding.getValue());
                    else action(binding.getValue());
                }
            }
            if (++ticks % 20 == 0 && available() && (client.gui.screen() instanceof CompanionScreen || client.gui.screen() instanceof CompanionHubScreen
                    || ClientCompanionState.bool(STATE.settings(), "hud", true) || !STATE.received())) {
                request(); refreshPreview(client);
            }
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("markai", "companion_status"), (graphics, delta) -> {
            var client = Minecraft.getInstance();
            if (client.player == null || client.gui.screen() != null) return;
            if (STATE.received() && ClientCompanionState.bool(STATE.settings(), "hud", true)
                    && !STATE.string("entity_uuid", "").isBlank()) {
                String health = String.format(java.util.Locale.ROOT, "%.0f / %.0f", STATE.number("health", 0), STATE.number("max_health", 40));
                String line = STATE.string("name", "Mark") + "  " + health + "  " + CompanionScreen.localizedState("activity", STATE.string("activity", "IDLE"));
                int w = Math.min(graphics.guiWidth() - 12, Math.max(150, client.font.width(line) + 12));
                graphics.fill(6, 6, 6 + w, 40, 0xB0182026);
                graphics.text(client.font, client.font.plainSubstrByWidth(line, w - 12), 12, 12, 0xFFEAF2EC);
                String status = STATE.notice().isBlank() ? STATE.string("ai_status", "") : STATE.notice();
                graphics.text(client.font, client.font.plainSubstrByWidth(status, w - 12), 12, 26, STATE.errorNotice() ? 0xFFFF9999 : 0xFFAAC7B7);
            }
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("markai", "companion_popup"), (graphics, delta) -> {
            var client = Minecraft.getInstance();
            if (client.player == null || client.gui.screen() != null) return;
            String popup = STATE.popup();
            if (!popup.isBlank()) {
                String line = client.font.plainSubstrByWidth(popup, Math.max(80, graphics.guiWidth() - 48));
                int width = Math.min(graphics.guiWidth() - 24, client.font.width(line) + 24);
                int x = (graphics.guiWidth() - width) / 2;
                int y = 8;
                int border = STATE.popupError() ? 0xFFE08A8A : 0xFF77B995;
                graphics.fill(x, y, x + width, y + 28, 0xE0182528);
                graphics.outline(x, y, width, 28, border);
                graphics.text(client.font, line, x + 12, y + 10, STATE.popupError() ? 0xFFFFB0B0 : 0xFFE8EFEA);
            }
        });
    }

    private static void commandKey(String action, int defaultKey, KeyMapping.Category category) {
        commandKeys.put(KeyMappingHelper.registerKeyMapping(new KeyMapping("key.markai." + action, defaultKey, category)), action);
    }

    private static void migrateLegacyKeyDefaults(Minecraft client) {
        keyMigrationChecked = true;
        boolean changed = remapDefault(lookupCommandKey("come"), "key.keyboard.m", "key.keyboard.b")
            | remapDefault(lookupCommandKey("follow"), "key.keyboard.o", "key.keyboard.m")
            | remapDefault(lookupCommandKey("stay"), "key.keyboard.x", "key.keyboard.n");
        if (changed) client.options.save();
    }

    private static KeyMapping lookupCommandKey(String action) {
        return commandKeys.entrySet().stream().filter(entry -> entry.getValue().equals(action))
            .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static boolean remapDefault(KeyMapping mapping, String previous, String replacement) {
        if (!mapping.matches(InputConstants.getKey(previous))) return false;
        mapping.setKey(InputConstants.getKey(replacement));
        return true;
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
    public static void showHub() { showHub(CompanionHubScreen.Tab.COMPANIONS); }
    public static void showHub(CompanionHubScreen.Tab tab) { Minecraft.getInstance().gui.setScreen(new CompanionHubScreen(tab)); request(); }
    public static void action(String action) { if (NetworkRules.action(action)) send(new CompanionNetwork.Action(action)); }

    /** Ctrl applies one supported command to the entire owner roster, on the server. */
    public static void groupAction(String action) {
        if (!NetworkRules.groupAction(action)) {
            STATE.notice("error", Component.translatable("markai.ui.group_only").getString());
            return;
        }
        send(new CompanionNetwork.Action("all:" + action));
    }

    /** Shift+V switches the server-selected companion without opening a screen. */
    public static void cycleCompanion(int direction) {
        if (direction == 0 || !available()) return;
        var roster = STATE.companions();
        if (roster.size() < 2) {
            STATE.notice("status", "Пока нет других компаньонов.");
            return;
        }
        long now = System.nanoTime();
        if (lastCycleAt != 0 && now - lastCycleAt < 300_000_000L) return;
        int current = -1;
        String selected = STATE.companionId();
        for (int i = 0; i < roster.size(); i++) {
            if (ClientCompanionState.string(roster.get(i).getAsJsonObject(), "id", "").equals(selected)) {
                current = i;
                break;
            }
        }
        int next = current < 0 ? 0 : Math.floorMod(current + Integer.signum(direction), roster.size());
        var target = roster.get(next).getAsJsonObject();
        String id = ClientCompanionState.string(target, "id", "");
        if (id.equals(selected)) return;
        if (select(id)) {
            lastCycleAt = now;
            STATE.notice("status", "Выбран: " + ClientCompanionState.string(target, "name", "компаньон"));
        }
    }
    public static boolean select(String companionId) { return NetworkRules.companionId(companionId) && send(new CompanionNetwork.Select(companionId)); }
    public static boolean removeCompanion(String companionId) {
        return NetworkRules.companionId(companionId) && send(new CompanionNetwork.Remove(companionId));
    }
    public static boolean chat(String message) {
        if (NetworkRules.chat(message) && send(new CompanionNetwork.Chat(message))) {
            STATE.notice("status", Component.translatable("markai.ui.sent").getString());
            return true;
        }
        return false;
    }
    public static void cancel() { send(new CompanionNetwork.Cancel()); }
    public static boolean groupChat(String message) {
        return NetworkRules.chat(message) && send(new CompanionNetwork.GroupChat(message));
    }
    public static void deleteMemory(String id) { if (NetworkRules.memoryId(id)) send(new CompanionNetwork.MemoryDelete(id)); }
    public static void setting(String key, String value) { if (NetworkRules.setting(key, value)) send(new CompanionNetwork.Setting(key, value)); }
    private static boolean send(CustomPacketPayload payload) {
        if (available()) { ClientPlayNetworking.send(payload); return true; }
        STATE.notice("error", Component.translatable("markai.ui.no_server").getString());
        return false;
    }
    public static LivingEntity preview() { return preview(STATE.string("entity_uuid", "")); }

    /** Resolves the selected companion's real living entity, including non-wolf species. */
    public static LivingEntity preview(String entityUuid) {
        var client = Minecraft.getInstance();
        if (client.level == null || entityUuid == null || entityUuid.isBlank()) return null;
        if (preview != null && preview.isAlive() && !preview.isRemoved() && preview.level() == client.level
                && preview.getUUID().toString().equals(entityUuid)) return preview;
        for (var entity : client.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && living.isAlive() && !living.isRemoved()
                    && living.getUUID().toString().equals(entityUuid)) return living;
        }
        return null;
    }
    private static void refreshPreview(Minecraft client) {
        if (client.level == null) { preview = null; return; }
        String uuid = STATE.string("entity_uuid", "");
        int id = (int) STATE.number("entity_id", -1);
        if (id >= 0 && client.level.getEntity(id) instanceof LivingEntity living && living.getUUID().toString().equals(uuid)) { preview = living; return; }
        preview = null;
        if (uuid.isBlank()) return;
        for (var entity : client.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && living.getUUID().toString().equals(uuid)) { preview = living; return; }
        }
    }
}
