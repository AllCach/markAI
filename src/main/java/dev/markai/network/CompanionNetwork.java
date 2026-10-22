package dev.markai.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Typed owner-scoped UI transport. Fabric invokes all server handlers on the server thread. */
public final class CompanionNetwork {
    public static final int MAX_SNAPSHOT = 24576;
    private static final Logger LOG = LoggerFactory.getLogger("markai-network");
    private static final Map<UUID, Limits> LIMITS = new HashMap<>();
    private static Backend backend;

    public interface Backend {
        String snapshot(ServerPlayer player);
        void command(ServerPlayer player, String action);
        void chat(ServerPlayer player, String message);
        void groupChat(ServerPlayer player, String message);
        void cancel(ServerPlayer player);
        void deleteMemory(ServerPlayer player, String id);
        void setting(ServerPlayer player, String key, String value);
        void select(ServerPlayer player, String companionId);
        void remove(ServerPlayer player, String companionId);
        void journalNote(ServerPlayer player, String speciesId, String text, String noteId);
    }
    private static final class Limits {
        long refreshAt, actionAt, chatAt, rateNoticeAt;
    }
    private CompanionNetwork() {}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("markai", name));
    }

    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = CompanionNetwork.type("ui_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.unit(new Request());
        @Override public Type<Request> type() { return TYPE; }
    }
    public record Action(String action) implements CustomPacketPayload {
        public static final Type<Action> TYPE = CompanionNetwork.type("ui_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(32), Action::action, Action::new);
        @Override public Type<Action> type() { return TYPE; }
    }
    public record Chat(String message) implements CustomPacketPayload {
        public static final Type<Chat> TYPE = CompanionNetwork.type("ui_chat");
        public static final StreamCodec<RegistryFriendlyByteBuf, Chat> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(512), Chat::message, Chat::new);
        @Override public Type<Chat> type() { return TYPE; }
    }
    public record Cancel() implements CustomPacketPayload {
        public static final Type<Cancel> TYPE = CompanionNetwork.type("ui_cancel");
        public static final StreamCodec<RegistryFriendlyByteBuf, Cancel> CODEC = StreamCodec.unit(new Cancel());
        @Override public Type<Cancel> type() { return TYPE; }
    }
    public record GroupChat(String message) implements CustomPacketPayload {
        public static final Type<GroupChat> TYPE = CompanionNetwork.type("ui_group_chat");
        public static final StreamCodec<RegistryFriendlyByteBuf, GroupChat> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(512), GroupChat::message, GroupChat::new);
        @Override public Type<GroupChat> type() { return TYPE; }
    }
    public record MemoryDelete(String id) implements CustomPacketPayload {
        public static final Type<MemoryDelete> TYPE = CompanionNetwork.type("ui_memory_delete");
        public static final StreamCodec<RegistryFriendlyByteBuf, MemoryDelete> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(36), MemoryDelete::id, MemoryDelete::new);
        @Override public Type<MemoryDelete> type() { return TYPE; }
    }
    public record JournalNote(String speciesId, String text, String noteId) implements CustomPacketPayload {
        public static final Type<JournalNote> TYPE = CompanionNetwork.type("ui_journal_note");
        public static final StreamCodec<RegistryFriendlyByteBuf, JournalNote> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(128), JournalNote::speciesId,
            ByteBufCodecs.stringUtf8(512), JournalNote::text,
            ByteBufCodecs.stringUtf8(20), JournalNote::noteId, JournalNote::new);
        @Override public Type<JournalNote> type() { return TYPE; }
    }
    public record Select(String companionId) implements CustomPacketPayload {
        public static final Type<Select> TYPE = CompanionNetwork.type("ui_select");
        public static final StreamCodec<RegistryFriendlyByteBuf, Select> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(36), Select::companionId, Select::new);
        @Override public Type<Select> type() { return TYPE; }
    }
    public record Remove(String companionId) implements CustomPacketPayload {
        public static final Type<Remove> TYPE = CompanionNetwork.type("ui_remove");
        public static final StreamCodec<RegistryFriendlyByteBuf, Remove> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(36), Remove::companionId, Remove::new);
        @Override public Type<Remove> type() { return TYPE; }
    }
    public record Setting(String key, String value) implements CustomPacketPayload {
        public static final Type<Setting> TYPE = CompanionNetwork.type("ui_setting");
        public static final StreamCodec<RegistryFriendlyByteBuf, Setting> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(32), Setting::key,
            ByteBufCodecs.stringUtf8(64), Setting::value, Setting::new);
        @Override public Type<Setting> type() { return TYPE; }
    }
    public record Snapshot(String json) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = CompanionNetwork.type("ui_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_SNAPSHOT), Snapshot::json, Snapshot::new);
        @Override public Type<Snapshot> type() { return TYPE; }
    }
    public record Notice(String kind, String text) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = CompanionNetwork.type("ui_notice");
        public static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(16), Notice::kind,
            ByteBufCodecs.stringUtf8(256), Notice::text, Notice::new);
        @Override public Type<Notice> type() { return TYPE; }
    }
    public record Popup(String kind, String text) implements CustomPacketPayload {
        public static final Type<Popup> TYPE = CompanionNetwork.type("ui_popup");
        public static final StreamCodec<RegistryFriendlyByteBuf, Popup> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(16), Popup::kind,
            ByteBufCodecs.stringUtf8(256), Popup::text, Popup::new);
        @Override public Type<Popup> type() { return TYPE; }
    }

    public static void registerServer(Backend implementation) {
        if (backend != null) throw new IllegalStateException("Companion networking already initialized");
        backend = implementation;
        var c2s = PayloadTypeRegistry.serverboundPlay();
        c2s.register(Request.TYPE, Request.CODEC); c2s.register(Action.TYPE, Action.CODEC);
        c2s.register(Chat.TYPE, Chat.CODEC); c2s.register(Cancel.TYPE, Cancel.CODEC);
        c2s.register(GroupChat.TYPE, GroupChat.CODEC);
        c2s.register(MemoryDelete.TYPE, MemoryDelete.CODEC); c2s.register(Setting.TYPE, Setting.CODEC);
        c2s.register(JournalNote.TYPE, JournalNote.CODEC);
        c2s.register(Select.TYPE, Select.CODEC); c2s.register(Remove.TYPE, Remove.CODEC);
        var s2c = PayloadTypeRegistry.clientboundPlay();
        s2c.register(Snapshot.TYPE, Snapshot.CODEC); s2c.register(Notice.TYPE, Notice.CODEC); s2c.register(Popup.TYPE, Popup.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            Limits limits = LIMITS.computeIfAbsent(player.getUUID(), key -> new Limits());
            long now = System.nanoTime();
            if (now < limits.refreshAt) return;
            limits.refreshAt = now + 500_000_000L;
            sendSnapshot(player);
        });
        ServerPlayNetworking.registerGlobalReceiver(Action.TYPE, (payload, context) -> mutate(context.player(), () -> {
            if (!NetworkRules.action(payload.action())) throw new IllegalArgumentException("Unsupported action");
            backend.command(context.player(), payload.action());
        }));
        ServerPlayNetworking.registerGlobalReceiver(Chat.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            Limits limits = LIMITS.computeIfAbsent(player.getUUID(), key -> new Limits());
            long now = System.nanoTime();
            if (now < limits.chatAt) { rateLimited(player, limits, now); return; }
            limits.chatAt = now + 1_000_000_000L;
            mutate(player, () -> {
                if (!NetworkRules.chat(payload.message())) throw new IllegalArgumentException("Invalid chat message");
                backend.chat(player, payload.message());
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(Cancel.TYPE, (payload, context) -> mutate(context.player(), () -> backend.cancel(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(GroupChat.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            Limits limits = LIMITS.computeIfAbsent(player.getUUID(), key -> new Limits());
            long now = System.nanoTime();
            if (now < limits.chatAt) { rateLimited(player, limits, now); return; }
            limits.chatAt = now + 1_000_000_000L;
            mutate(player, () -> {
                if (!NetworkRules.chat(payload.message())) throw new IllegalArgumentException("Invalid group chat message");
                backend.groupChat(player, payload.message());
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(MemoryDelete.TYPE, (payload, context) -> mutate(context.player(), () -> {
            if (!NetworkRules.memoryId(payload.id())) throw new IllegalArgumentException("Invalid memory id");
            backend.deleteMemory(context.player(), payload.id());
        }));
        ServerPlayNetworking.registerGlobalReceiver(JournalNote.TYPE, (payload, context) -> mutate(context.player(), () -> {
            if (!NetworkRules.journalNote(payload.speciesId(), payload.text(), payload.noteId()))
                throw new IllegalArgumentException("Invalid field journal note");
            backend.journalNote(context.player(), payload.speciesId(), payload.text(), payload.noteId());
        }));
        ServerPlayNetworking.registerGlobalReceiver(Select.TYPE, (payload, context) -> mutate(context.player(), () -> {
            if (!NetworkRules.companionId(payload.companionId())) throw new IllegalArgumentException("Invalid companion id");
            backend.select(context.player(), payload.companionId());
        }));
        ServerPlayNetworking.registerGlobalReceiver(Remove.TYPE, (payload, context) -> mutate(context.player(), () -> {
            if (!NetworkRules.companionId(payload.companionId())) throw new IllegalArgumentException("Invalid companion id");
            backend.remove(context.player(), payload.companionId());
        }));
        ServerPlayNetworking.registerGlobalReceiver(Setting.TYPE, (payload, context) -> mutate(context.player(), () -> {
            if (!NetworkRules.setting(payload.key(), payload.value())) throw new IllegalArgumentException("Invalid setting");
            backend.setting(context.player(), payload.key(), payload.value());
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LIMITS.remove(handler.player.getUUID()));
    }

    private static void mutate(ServerPlayer player, Runnable operation) {
        Limits limits = LIMITS.computeIfAbsent(player.getUUID(), key -> new Limits());
        long now = System.nanoTime();
        if (now < limits.actionAt) { rateLimited(player, limits, now); return; }
        limits.actionAt = now + 250_000_000L;
        try { operation.run(); sendSnapshot(player); }
        catch (IllegalArgumentException error) { notice(player, "error", "Недопустимый запрос к компаньону."); }
        catch (RuntimeException error) {
            LOG.warn("Companion UI operation failed ({})", error.getClass().getSimpleName());
            notice(player, "error", "Не удалось выполнить действие. См. журнал MarkAI.");
        }
    }

    private static void rateLimited(ServerPlayer player, Limits limits, long now) {
        if (now < limits.rateNoticeAt) return;
        limits.rateNoticeAt = now + 1_000_000_000L;
        notice(player, "status", "Слишком часто. Повторите действие через секунду.");
    }

    public static void sendSnapshot(ServerPlayer player) {
        if (backend == null || !ServerPlayNetworking.canSend(player, Snapshot.TYPE)) return;
        try {
            String json = backend.snapshot(player);
            if (json == null || json.length() > MAX_SNAPSHOT) {
                notice(player, "error", "Слишком большой снимок состояния компаньона."); return;
            }
            ServerPlayNetworking.send(player, new Snapshot(json));
        } catch (RuntimeException error) {
            LOG.warn("Companion UI snapshot failed ({})", error.getClass().getSimpleName());
            notice(player, "error", "Состояние компаньона временно недоступно.");
        }
    }

    public static void notice(ServerPlayer player, String kind, String text) {
        if (!ServerPlayNetworking.canSend(player, Notice.TYPE)) return;
        ServerPlayNetworking.send(player, new Notice(kind.length() > 16 ? kind.substring(0, 16) : kind,
            text.length() > 256 ? text.substring(0, 256) : text));
    }

    public static void popup(ServerPlayer player, String kind, String text) {
        if (!ServerPlayNetworking.canSend(player, Popup.TYPE)) return;
        ServerPlayNetworking.send(player, new Popup(kind.length() > 16 ? kind.substring(0, 16) : kind,
            text.length() > 256 ? text.substring(0, 256) : text));
    }
}
