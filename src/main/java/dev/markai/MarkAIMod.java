package dev.markai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.markai.ai.AIConfig;
import dev.markai.ai.OllamaClient;
import dev.markai.ai.DialogueIntent;
import dev.markai.ai.DialogueContext;
import dev.markai.ai.LlmProvider;
import dev.markai.ai.AmbientConversation;
import dev.markai.actions.*;
import dev.markai.core.*;
import dev.markai.companion.CompanionRegistry;
import dev.markai.companion.CompanionStore;
import dev.markai.behavior.Activity;
import dev.markai.events.WorldEvent;
import dev.markai.network.CompanionNetwork;
import dev.markai.network.NetworkRules;
import dev.markai.chat.ChatJournal;
import dev.markai.memory.CompanionMemory;
import dev.markai.training.DogTraining;
import dev.markai.training.DogTraining.Trick;
import dev.markai.taming.WolfTamingHooks;
import dev.markai.inventory.CompanionInventoryHolder;
import dev.markai.mixin.MobAccessor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.ItemTags;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.arguments.StringArgumentType;

public final class MarkAIMod implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("markai");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson CHAT_JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String AMBIENT_PROMPT = "[Самостоятельная реплика: игрок сейчас ничего не спрашивал. "
        + "Коротко отреагируй на свежую обстановку, событие или настроение из текущего снимка. "
        + "Не продолжай старый разговор и не повторяй recent_lines. Не придумывай близость дома, "
        + "не отдавай команды игроку и не обещай новое действие. Только action=say, без вопроса.]";
    private final CompanionRegistry<Companion> companions = new CompanionRegistry<>(c -> c.id, c -> c.owner, c -> c.entity);
    private final CompanionStore<Companion> companionStore = new CompanionStore<>(Companion.class);
    private final Set<UUID> configured = new HashSet<>();
    private MinecraftServer server;
    private int ticks;
    private boolean storageHealthy;
    private AIConfig aiConfig;
    private LlmProvider ollama;
    private ChatJournal chatJournal;
    private final Map<UUID, AISession> aiSessions = new HashMap<>();
    private final LivingCompanionRuntime livingRuntime = new LivingCompanionRuntime(this::memoryEnabled);
    private final WolfWorkRuntime wolfWork = new WolfWorkRuntime();
    private final Map<UUID, Integer> memoryPages = new HashMap<>();
    private final Map<UUID, Long> ownerAutomaticAt = new HashMap<>();

    private static final class AISession {
        boolean enabled = true;
        long generation;
        long nextRequest;
        long idleUntil, wanderUntil, lookUntil, fleeUntil;
        CompletableFuture<OllamaClient.Reply> pending;
        boolean pendingAutomatic;
        Runnable cancelTrace;
        String error = "";
        long lastLatencyMillis;
    }

    public static final class Companion {
        UUID id;
        UUID owner;
        UUID entity;
        String species = "minecraft:wolf";
        String dimension;
        int chunkX, chunkZ;
        boolean dead;
        boolean following = true;
        boolean dismissed;
        LivingState living = new LivingState();
        CompanionMemory memory = new CompanionMemory();
        DogTraining training = new DogTraining();
        String lastObservedPlace;
        transient long nextMessage;
        transient long nextLessonAt, nextTrickAt;
        transient TrickTask trick;
        transient boolean ownerUnavailable;
        transient String action = "IDLE";
        transient CompanionAction requestedAction;
        transient long commandUntil;
        transient boolean automaticSit;
        transient boolean expectedSit, sitObserved;
        transient long nextAutomaticConversation;
        transient AmbientConversation autoConversation = new AmbientConversation();
        transient boolean ownerDied;
        transient boolean workActive;
    }

    private static final class TrickTask {
        final Trick kind;
        final long deadline;
        final UUID item;
        boolean carrying;
        TrickTask(Trick kind, long deadline, UUID item) {
            this.kind = kind; this.deadline = deadline; this.item = item;
        }
    }

    @Override public void onInitialize() {
        WolfTamingHooks.register(this::adoptWolf);
        CompanionNetwork.registerServer(new CompanionNetwork.Backend() {
            public String snapshot(ServerPlayer player) { return uiSnapshot(player); }
            public void command(ServerPlayer player, String action) {
                try {
                    if (action.startsWith("all:")) performGroupCommand(player, action.substring(4));
                    else performCommand(player, action);
                }
                catch (Exception error) { CompanionNetwork.notice(player, "error", "Команда не выполнена; см. журнал сервера."); LOG.warn("Companion UI command failed", error); }
            }
            public void chat(ServerPlayer player, String message) { ask(player, message); }
            public void select(ServerPlayer player, String id) { selectCompanion(player, id); }
            public void cancel(ServerPlayer player) { cancelRequest(player); }
            public void deleteMemory(ServerPlayer player, String id) {
                Companion c = companions.selected(player.getUUID());
                if (c != null && NetworkRules.memoryId(id)) {
                    UUID target = UUID.fromString(id);
                    var forgotten = c.memory.inspect().stream().filter(entry -> entry.id().equals(target)).findFirst();
                    if (c.memory.delete(target)) forgotten.ifPresent(entry -> c.living.events.forget(entry.gameTime(), entry.content()));
                    save();
                }
            }
            public void setting(ServerPlayer player, String key, String value) { updateSetting(player, key, value); }
        });
        CompanionCombatRules.register((wolf, target) -> companions.values().stream().filter(c -> c.entity.equals(wolf.getUUID()))
            .anyMatch(c -> c.living.settings.defense && !c.dismissed && LivingCompanionRuntime.validTarget(c, target)));
        CompanionCombatRules.registerExplicit((wolf, target) -> companions.values().stream().filter(c -> c.entity.equals(wolf.getUUID()))
            .anyMatch(c -> wolfWork.mayAttack(c, wolf, target)));
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            companions.clear();
            configured.clear();
            ticks = 0;
            aiSessions.clear();
            memoryPages.clear(); ownerAutomaticAt.clear(); livingRuntime.clear(); wolfWork.clear();
            chatJournal = new ChatJournal(FabricLoader.getInstance().getGameDir().resolve("logs"));
            try {
                aiConfig = AIConfig.load(FabricLoader.getInstance().getConfigDir().resolve("markai.json"));
                if (aiConfig.voiceEnabled) LOG.warn("Voice backend is not installed; dialogue and native wolf sounds remain available");
                ollama = aiConfig.createProvider();
            } catch (Exception e) {
                ollama = null;
                LOG.error("Cannot initialize local AI; deterministic companion behavior remains available", e);
            }
            storageHealthy = true;
            Path path = statePath();
            if (Files.exists(path)) {
                try {
                    var saved = companionStore.read(path);
                    var restored = new CompanionRegistry<Companion>(c -> c.id, c -> c.owner, c -> c.entity);
                    for (Companion c : saved.companions()) {
                        if (c == null || c.owner == null || c.entity == null || c.dimension == null)
                            throw new IOException("Invalid companion state");
                        if (c.species == null) c.species = "minecraft:wolf";
                        if (!c.species.equals("minecraft:wolf")) throw new IOException("Unsupported companion species");
                        if (c.memory == null) c.memory = new CompanionMemory();
                        if (c.training == null) c.training = new DogTraining();
                        if (c.living == null) c.living = new LivingState();
                        if (c.id == null) c.id = c.entity;
                        c.living.normalize(); c.memory.bind(c.id);
                        c.memory.normalize(); c.training.normalize();
                        restored.add(c);
                    }
                    restored.restoreSelections(saved.selected());
                    for (Companion c : restored.values()) companions.add(c);
                    companions.restoreSelections(restored.selections());
                } catch (Exception e) {
                    storageHealthy = false;
                    LOG.error("Cannot load MarkAI state; spawning disabled to prevent duplicates", e);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            wolfWork.clear();
            for (Companion c : companions.values()) cancelTrick(c);
            save();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            for (AISession session : aiSessions.values()) invalidate(session);
            aiSessions.clear();
            if (ollama != null) { ollama.close(); ollama = null; }
            if (chatJournal != null) { chatJournal.close(); chatJournal = null; }
            CompanionMemory.shutdownWorkers();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            companions.clear(); configured.clear(); livingRuntime.clear(); wolfWork.clear(); memoryPages.clear(); ownerAutomaticAt.clear(); server = null;
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            invalidateOwnerSessions(entity.getUUID());
            for (Companion c : companions.values()) if (entity.getUUID().equals(c.entity)) {
                wolfWork.remove(c.entity);
                cancelTrick(c);
                remember(c, entity, level); configured.remove(c.entity); save();
                livingRuntime.remove(c.entity);
                AISession session = aiSessions.get(c.id);
                if (session != null) invalidate(session);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            for (Companion c : companions.values()) if (entity.getUUID().equals(c.owner)) {
                c.ownerDied = true;
                Wolf witness = find(c);
                if (witness != null && witness.level() == entity.level() && witness.distanceTo(entity) <= 16)
                    livingRuntime.event(c, witness, WorldEvent.Type.PLAYER_DIED, "Хозяин погиб рядом со мной.", 20);
            }
            invalidateOwnerSessions(entity.getUUID());
            for (Companion c : companions.values()) if (entity.getUUID().equals(c.entity)) {
                wolfWork.remove(c.entity);
                c.dead = true; configured.remove(c.entity); save();
                AISession session = aiSessions.get(c.id);
                if (session != null) invalidate(session);
            }
            if (entity instanceof Enemy) for (Companion c : companions.values()) {
                if (source.getEntity() != null && c.entity.equals(source.getEntity().getUUID())) {
                    Wolf witness = find(c);
                    if (witness != null) livingRuntime.event(c, witness, WorldEvent.Type.COMBAT_WON,
                        "Я победил противника: " + entity.getType().getDescription().getString() + ".", 1);
                    save();
                }
            }
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            if (blocked || damage <= 0) return;
            for (Companion c : companions.values()) {
                Wolf witness = find(c);
                if (witness != null) livingRuntime.damage(c, witness, entity, source.getEntity(), damage);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (++ticks % 10 != 0) return;
            for (Companion c : companions.values()) tick(c);
            if (ticks % 200 == 0) {
                save();
                if (aiConfig != null && aiConfig.debugMode) LOG.info("Companion perception metrics: {}", CHAT_JSON.toJson(livingRuntime.observer.metrics()));
            }
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.signedContent().strip();
            String lower = text.toLowerCase(Locale.ROOT);
            Companion named = companions.selected(sender.getUUID());
            if (named != null && named.living != null) {
                String custom = named.living.name.toLowerCase(Locale.ROOT);
                if (lower.startsWith(custom + ",") || lower.startsWith(custom + ":")) {
                    ask(sender, text.substring(custom.length() + 1).strip()); return;
                }
            }
            for (String prefix : List.of("walk,", "walk:", "волк,", "волк:", "марк,", "марк:", "mark,", "mark:")) {
                if (lower.startsWith(prefix)) {
                    ask(sender, text.substring(prefix.length()).strip());
                    break;
                }
            }
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!level.isClientSide() && !player.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND
                    && entity instanceof Wolf wolf && player instanceof ServerPlayer owner) {
                Companion c = companions.byEntity(wolf.getUUID());
                if (c != null && c.owner.equals(owner.getUUID()) && wolf.isOwnedBy(owner)) {
                    wolfWork.cancel(c, wolf);
                    c.requestedAction = null; c.automaticSit = false;
                    c.living.behavior.cancel(level.getGameTime());
                    AISession session = aiSessions.get(c.id); if (session != null) invalidate(session);
                    if (owner.getMainHandItem().is(ItemTags.WOLF_FOOD) && wolf.getHealth() < wolf.getMaxHealth())
                        livingRuntime.event(c, wolf, WorldEvent.Type.OWNER_HEALED_WOLF, "Хозяин покормил меня, когда я был ранен.", 3);
                    else if (owner.getMainHandItem().isEmpty()) livingRuntime.event(c, wolf, WorldEvent.Type.OWNER_AFFECTION, "Хозяин уделил мне внимание.", 1);
                }
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!(entity instanceof Wolf wolf) || hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()
                    || !((CompanionInventoryHolder) wolf).markai$isCompanion() || player.isSpectator())
                return InteractionResult.PASS;
            // The synced marker lets the client suppress feeding/sitting before the menu arrives.
            if (level.isClientSide()) return InteractionResult.CONSUME;
            if (!(player instanceof ServerPlayer owner)) return InteractionResult.FAIL;
            Companion c = companions.byEntity(wolf.getUUID());
            if (!storageHealthy || c == null || !c.owner.equals(owner.getUUID()) || c.dead || !wolf.isAlive()
                    || !wolf.isOwnedBy(owner) || owner.level() != wolf.level() || owner.distanceToSqr(wolf) > 64) {
                owner.sendSystemMessage(Component.literal("Сумка доступна только хозяину рядом с живым компаньоном."));
                return InteractionResult.FAIL;
            }
            cancelTrick(c);
            wolfWork.cancel(c, wolf);
            var inventory = ((CompanionInventoryHolder) wolf).markai$inventory();
            owner.openMenu(new SimpleMenuProvider((id, playerInventory, menuOwner) ->
                new ChestMenu(MenuType.GENERIC_9x1, id, playerInventory, inventory, 1),
                Component.literal(c.living.name + " — инвентарь")));
            return InteractionResult.CONSUME;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            var root = Commands.literal("mark").executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "/walk tame | attack | collect | stop | spawn | follow | stay | come | rest | wander | sethome | home | summon | inventory | status | train <приём> | ask <текст> | ai on/off"), false);
                return 1;
            });
            for (String name : List.of("spawn", "respawn", "follow", "stay", "status", "skills", "forget", "bark", "come", "fetch",
                    "rest", "wander", "home", "sethome", "investigate", "summon", "dismiss", "inventory", "debug", "brain", "mood", "memory", "cancel", "tame", "attack", "collect", "stop")) {
                root.then(Commands.literal(name).executes(ctx -> command(ctx, name)));
            }
            root.then(Commands.literal("train").then(Commands.argument("trick", StringArgumentType.greedyString())
                .executes(ctx -> train(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "trick")))));
            root.then(Commands.literal("ask").then(Commands.argument("message", StringArgumentType.greedyString())
                .executes(ctx -> ask(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "message")))));
            var ai = Commands.literal("ai").executes(ctx -> aiCommand(ctx.getSource().getPlayerOrException(), "status"));
            for (String mode : List.of("on", "off", "status"))
                ai.then(Commands.literal(mode).executes(ctx -> aiCommand(ctx.getSource().getPlayerOrException(), mode)));
            root.then(ai);
            var registered = dispatcher.register(root);
            dispatcher.register(Commands.literal("walk").executes(registered.getCommand()).redirect(registered));
        });
    }

    private int command(CommandContext<CommandSourceStack> context, String command) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return performCommand(context.getSource().getPlayerOrException(), command);
    }

    private int performCommand(ServerPlayer owner, String command) {
        if (!storageHealthy) {
            owner.sendSystemMessage(Component.literal("MarkAI: ошибка файла состояния; см. журнал. Создание отключено."));
            return 0;
        }
        Companion c = companions.selected(owner.getUUID());
        if (command.equals("tame")) {
            LivingEntity lookedAt = pointedLiving(owner, 8);
            if (!(lookedAt instanceof Wolf wolf)) {
                return wolfNotice(owner, "Посмотрите на своего приручённого волка рядом. Дикому сначала дайте кости.", false);
            }
            return adoptWolf(owner, wolf);
        }
        if (command.equals("spawn") || command.equals("respawn")) {
            if (!owner.isAlive() || owner.isSpectator()) return 0;
            // Legacy /mark spawn still revives the selected dead companion.
            boolean respawn = command.equals("respawn") || c != null && c.dead;
            if (respawn && c == null)
                return wolfNotice(owner, "Нет компаньона для возрождения.", false);
            if (respawn && c != null && !c.dead) {
                // Resolve the saved chunk before deciding anything about an unloaded companion.
                for (ServerLevel level : server.getAllLevels()) {
                    if (level.dimension().identifier().toString().equals(c.dimension))
                        level.getChunk(c.chunkX, c.chunkZ);
                }
                Wolf existing = find(c);
                owner.sendSystemMessage(Component.literal(existing != null
                    ? c.living.name + " уже существует: " + c.entity
                    : "Компаньон сохранён, но пока не найден. Повторный спавн запрещён: " + c.entity));
                return existing != null ? 1 : 0;
            }
            if (!respawn && companions.owned(owner.getUUID()).size() >= CompanionRegistry.MAX_COMPANIONS_PER_OWNER)
                return wolfNotice(owner, "Достигнут предел компаньонов: " + CompanionRegistry.MAX_COMPANIONS_PER_OWNER, false);
            if (respawn && c != null && find(c) != null)
                return wolfNotice(owner, "Сущность компаньона ещё существует; возрождение запрещено.", false);
            ServerLevel level = (ServerLevel) owner.level();
            Wolf wolf = EntityTypes.WOLF.create(level, EntitySpawnReason.COMMAND);
            if (wolf == null) return 0;
            // Multiple wolves cannot all spawn at the exact same position.
            Vec3 origin = owner.position();
            boolean freePosition = false;
            for (int[] offset : new int[][] {
                    {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
                    {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                    {2, 2}, {-2, 2}, {2, -2}, {-2, -2}
            }) {
                wolf.setPos(origin.x + offset[0], origin.y, origin.z + offset[1]);
                if (level.noCollision(wolf) && level.getFluidState(wolf.blockPosition()).isEmpty()) {
                    freePosition = true;
                    break;
                }
            }
            if (!freePosition) {
                owner.sendSystemMessage(Component.literal("Для волка нужно свободное сухое место рядом с игроком."));
                return 0;
            }
            int ownedCount = companions.owned(owner.getUUID()).size();
            String wolfName = respawn ? c.living.name : ownedCount == 0 ? "Марк" : "Марк " + (ownedCount + 1);
            wolf.setCustomName(Component.literal(wolfName));
            wolf.setCustomNameVisible(true);
            wolf.tame(owner);
            wolf.setHealth(wolf.getMaxHealth());
            wolf.setPersistenceRequired();
            wolf.setAge(0);
            if (!level.addFreshEntity(wolf)) return 0;
            Companion previousCompanion = respawn ? c : null;
            c = new Companion(); c.owner = owner.getUUID(); c.entity = wolf.getUUID();
            c.id = previousCompanion == null ? UUID.randomUUID() : previousCompanion.id == null ? previousCompanion.entity : previousCompanion.id;
            c.living = previousCompanion == null ? LivingState.forIdentity(c.id) : previousCompanion.living;
            if (previousCompanion == null && ownedCount > 0) c.living.name = wolfName;
            if (previousCompanion != null) { c.memory = previousCompanion.memory; c.training = previousCompanion.training; }
            if (previousCompanion == null && aiConfig != null) {
                c.living.settings.followDistance = aiConfig.followDistance;
                c.living.settings.defense = aiConfig.defensiveBehavior;
                c.living.settings.hud = aiConfig.hudVisible;
            }
            c.memory.bind(c.id);
            remember(c, wolf, level);
            companions.add(c);
            if (!respawn) companions.select(owner.getUUID(), c.id);
            AISession previous = aiSessions.remove(c.id);
            if (previous != null) invalidate(previous);
            configure(c, wolf);
            save();
            owner.sendSystemMessage(Component.literal(c.living.name + " создан. Владелец: " + c.owner));
            return 1;
        }
        if (c == null) {
            owner.sendSystemMessage(Component.literal("Сначала выполните /mark spawn.")); return 0;
        }
        if (command.equals("memory_prev") || command.equals("memory_next")) {
            int current = memoryPages.getOrDefault(c.id, 0);
            memoryPages.put(c.id, Math.max(0, current + (command.endsWith("next") ? 1 : -1)));
            return 1;
        }
        if (command.equals("cancel") || command.equals("stop")) { cancelRequest(owner); return 1; }
        if (command.equals("attack") || command.equals("collect")) return startWolfOrder(c, owner, command);
        if (command.equals("debug") || command.equals("brain") || command.equals("mood") || command.equals("memory")) {
            AISession session = aiSessions.get(c.id);
            owner.sendSystemMessage(Component.literal(command.equals("memory") ? CHAT_JSON.toJson(c.memory.inspect())
                : command.equals("mood") ? CHAT_JSON.toJson(c.living.emotions.context())
                : command.equals("debug") ? CHAT_JSON.toJson(Map.of("state", c.living.context(), "perception", livingRuntime.observer.metrics(), "runtime", livingRuntime.debug(c, find(c)),
                    "ai_latency_ms", session == null ? 0 : session.lastLatencyMillis, "pending_requests", aiSessions.values().stream().filter(s -> s.pending != null).count(),
                    "storage_healthy", storageHealthy)) : CHAT_JSON.toJson(c.living.context())));
            return 1;
        }
        if (command.equals("inventory")) {
            Wolf wolf = find(c);
            if (!owner.isAlive() || owner.isSpectator() || wolf == null || owner.level() != wolf.level() || owner.distanceToSqr(wolf) > 64 || !wolf.isOwnedBy(owner)) return 0;
            cancelTrick(c);
            wolfWork.cancel(c, wolf);
            owner.openMenu(new SimpleMenuProvider((id, inventory, player) -> new ChestMenu(MenuType.GENERIC_9x1, id, inventory,
                ((CompanionInventoryHolder) wolf).markai$inventory(), 1), Component.literal(c.living.name + " — инвентарь")));
            return 1;
        }
        if (command.equals("sethome")) {
            Wolf wolf = find(c);
            if (wolf == null || wolf.level() != owner.level() || wolf.distanceTo(owner) > 8
                    || !LivingCompanionRuntime.safePathTarget((ServerLevel) wolf.level(), wolf.position())) {
                owner.sendSystemMessage(Component.literal("Отметить дом можно рядом с волком на безопасной земле.")); return 0;
            }
            c.living.home = new HomeLocation(wolf.level().dimension().identifier().toString(), wolf.getX(), wolf.getY(), wolf.getZ()); save();
            owner.sendSystemMessage(Component.literal("Место дома сохранено.")); return 1;
        }
        if (command.equals("summon")) return summon(c, owner);
        if (command.equals("dismiss")) {
            cancelRequest(owner); cancelTrick(c); c.dismissed = true; c.following = false;
            Wolf wolf = find(c); if (wolf != null) { wolf.setOrderedToSit(true); wolf.setTarget(null); wolf.getNavigation().stop(); }
            save(); owner.sendSystemMessage(Component.literal(c.living.name + " остаётся ждать. /mark summon — позвать обратно.")); return 1;
        }
        CompanionAction naturalAction = switch (command) {
            case "follow" -> CompanionAction.FOLLOW; case "stay" -> CompanionAction.STAY;
            case "rest" -> CompanionAction.REST; case "wander" -> CompanionAction.WANDER;
            case "home" -> CompanionAction.RETURN_HOME; case "investigate" -> CompanionAction.INVESTIGATE;
            case "come" -> c.training.knows(Trick.COME) ? null : CompanionAction.COME;
            default -> null;
        };
        if (naturalAction != null) return performAction(c, owner, naturalAction);
        if (command.equals("skills")) {
            owner.sendSystemMessage(Component.literal(c.training.status()));
        } else if (command.equals("forget")) {
            invalidate(aiSessions.computeIfAbsent(c.id, key -> new AISession()));
            c.memory.clear(); c.living.events.clear(); save();
            owner.sendSystemMessage(Component.literal(c.living.name + ": память разговоров и приключений очищена. Навыки сохранены."));
        } else if (List.of("bark", "come", "fetch").contains(command)) {
            return performTrick(owner, Trick.valueOf(command.toUpperCase(Locale.ROOT)));
        } else if (command.equals("status")) {
            Wolf wolf = find(c);
            owner.sendSystemMessage(Component.literal(c.living.name + " | owner=" + c.owner + " | entity=" + c.entity
                + " | minecraft:wolf | HP=" + (wolf == null ? "?" : wolf.getHealth())
                + "/" + (wolf == null ? "?" : wolf.getMaxHealth()) + " | " + (c.dead ? "DEAD" : wolf == null ? "UNLOADED" : c.action)
                + " | mode=" + (c.following ? "FOLLOW" : "STAY") + " | dimension=" + c.dimension));
            owner.sendSystemMessage(Component.literal(c.training.status()));
        } else return 0;
        return 1;
    }

    /** Keeps the actual vanilla-tamed animal and its entity inventory; never substitutes a spawn. */
    private int adoptWolf(ServerPlayer owner, Wolf wolf) {
        if (!storageHealthy || !owner.isAlive() || owner.isSpectator() || wolf == null || !wolf.isAlive()
                || wolf.level() != owner.level() || owner.distanceToSqr(wolf) > 64) return 0;
        if (!wolf.isTame() || !wolf.isOwnedBy(owner)) {
            return wolfNotice(owner, "Сначала приручи дикого волка костями. Чужих питомцев присвоить нельзя.", false);
        }
        Companion registered = companions.byEntity(wolf.getUUID());
        if (registered != null) {
            if (!registered.owner.equals(owner.getUUID()))
                return wolfNotice(owner, "Этот волк принадлежит другому компаньону.", false);
            companions.select(owner.getUUID(), registered.id);
            configure(registered, wolf);
            save();
            return wolfNotice(owner, registered.living.name + " уже твой компаньон.", true);
        }
        int ownedCount = companions.owned(owner.getUUID()).size();
        if (ownedCount >= CompanionRegistry.MAX_COMPANIONS_PER_OWNER)
            return wolfNotice(owner, "Достигнут предел компаньонов: " + CompanionRegistry.MAX_COMPANIONS_PER_OWNER, false);
        Companion companion = new Companion(); companion.owner = owner.getUUID(); companion.entity = wolf.getUUID();
        companion.id = UUID.randomUUID();
        companion.living = LivingState.forIdentity(companion.id);
        if (wolf.getCustomName() != null) {
            String name = wolf.getCustomName().getString().strip();
            if (!name.isBlank() && name.codePoints().noneMatch(Character::isISOControl)) companion.living.name = name.substring(0, Math.min(32, name.length()));
        } else companion.living.name = ownedCount == 0 ? "Walk" : "Walk " + (ownedCount + 1);
        companion.living.normalize();
        if (aiConfig != null) {
            companion.living.settings.followDistance = aiConfig.followDistance;
            companion.living.settings.defense = aiConfig.defensiveBehavior;
            companion.living.settings.hud = aiConfig.hudVisible;
        }
        companion.following = !wolf.isOrderedToSit(); companion.memory.bind(companion.id);
        remember(companion, wolf, (ServerLevel) wolf.level()); companions.add(companion);
        // A new tame joins the owner's roster without stealing focus from Walk.
        // The registry selects it automatically only when this is the first companion.
        configure(companion, wolf); save();
        return wolfNotice(owner, companion.living.name + " стал твоим компаньоном. Shift + ПКМ — сумка; /walk attack — атаковать цель; /walk collect — собрать предметы.", true);
    }

    private int wolfNotice(ServerPlayer owner, String message, boolean success) {
        owner.sendSystemMessage(Component.literal(message));
        CompanionNetwork.notice(owner, success ? "status" : "error", message);
        return success ? 1 : 0;
    }

    /** Server-side ray picks the nearest visible living entity; a packet never supplies an attack UUID. */
    private LivingEntity pointedLiving(ServerPlayer owner, double range) {
        if (!owner.isAlive() || owner.isSpectator() || !(owner.level() instanceof ServerLevel level)) return null;
        Vec3 from = owner.getEyePosition(), to = from.add(owner.getLookAngle().scale(range));
        var blockHit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        double closest = from.distanceToSqr(blockHit.getLocation()); LivingEntity result = null;
        AABB bounds = owner.getBoundingBox().expandTowards(owner.getLookAngle().scale(range)).inflate(1);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, bounds,
                entity -> entity != owner && entity.isAlive() && !entity.isSpectator() && !entity.isInvisible())) {
            AABB box = entity.getBoundingBox().inflate(.15);
            Vec3 hit = box.contains(from) ? from : box.clip(from, to).orElse(null);
            if (hit == null) continue;
            double distance = from.distanceToSqr(hit);
            if (distance <= closest && owner.hasLineOfSight(entity)) { closest = distance; result = entity; }
        }
        return result;
    }

    /**
     * A group command is one owner-scoped operation, not repeated selection changes.
     * The look target is captured once so every eligible wolf receives the same target.
     * Keep one save and one status result for the entire group.
     */
    private int performGroupCommand(ServerPlayer owner, String command) {
        if (!NetworkRules.groupAction(command) || !storageHealthy || !owner.isAlive() || owner.isSpectator())
            return wolfNotice(owner, "Групповая команда недоступна.", false);
        List<Companion> owned = companions.owned(owner.getUUID());
        if (owned.isEmpty()) return wolfNotice(owner, "У тебя нет компаньонов.", false);

        LivingEntity target = command.equals("attack") ? pointedLiving(owner, 16) : null;
        if (command.equals("attack") && target == null)
            return wolfNotice(owner, "Для общей атаки укажи взглядом на противника в пределах 16 блоков.", false);

        int accepted = 0, skipped = 0;
        for (Companion companion : owned) {
            // Cancellation must also work when a companion is temporarily unloaded.
            if (command.equals("stop")) {
                cancelCompanionAction(companion, owner);
                accepted++;
                continue;
            }
            Wolf wolf = find(companion);
            if (companion.dead || wolf == null || !wolf.isAlive() || !wolf.isOwnedBy(owner)
                    || wolf.level() != owner.level() || owner.distanceToSqr(wolf) > 256) {
                skipped++;
                continue;
            }
            if (command.equals("attack") && !CompanionCombatRules.commandTargetAllowed(wolf, owner, target)) {
                skipped++;
                continue;
            }
            cancelTrick(companion);
            invalidate(aiSessions.computeIfAbsent(companion.id, key -> new AISession()));
            ActionResult result;
            if (command.equals("attack")) {
                result = wolfWork.attack(companion, wolf, owner, target);
            } else if (command.equals("collect")) {
                result = wolfWork.collect(companion, wolf, owner);
            } else {
                CompanionAction action = switch (command) {
                    case "follow" -> CompanionAction.FOLLOW;
                    case "stay" -> CompanionAction.STAY;
                    case "come" -> CompanionAction.COME;
                    case "rest" -> CompanionAction.REST;
                    case "wander" -> CompanionAction.WANDER;
                    case "home" -> CompanionAction.RETURN_HOME;
                    case "investigate" -> CompanionAction.INVESTIGATE;
                    default -> throw new IllegalArgumentException("Unsupported group action");
                };
                wolfWork.cancel(companion, wolf);
                result = livingRuntime.request(companion, wolf, owner, action);
            }
            if (result.status() == ActionResult.Status.FAILED) skipped++;
            else accepted++;
        }
        if (accepted > 0) save();
        return wolfNotice(owner, "Команда всем: " + command + " — выполнено " + accepted
            + " из " + owned.size() + (skipped > 0 ? ", пропущено " + skipped : ""), accepted > 0);
    }

    private int startWolfOrder(Companion companion, ServerPlayer owner, String command) {
        Wolf wolf = find(companion);
        if (wolf == null || !owner.isAlive() || owner.isSpectator() || wolf.level() != owner.level()
                || owner.distanceToSqr(wolf) > 256) {
            return wolfNotice(owner, "Для задания нужен твой живой волк рядом, не дальше 16 блоков.", false);
        }
        LivingEntity target = command.equals("attack") ? pointedLiving(owner, 16) : null;
        if (command.equals("attack") && !CompanionCombatRules.commandTargetAllowed(wolf, owner, target)) {
            return wolfNotice(owner, "Укажи взглядом на врага или дикое животное в пределах 16 блоков. Владельца, игроков и питомцев атаковать нельзя.", false);
        }
        cancelTrick(companion); invalidate(aiSessions.computeIfAbsent(companion.id, key -> new AISession()));
        ActionResult result = command.equals("attack") ? wolfWork.attack(companion, wolf, owner, target) : wolfWork.collect(companion, wolf, owner);
        owner.sendSystemMessage(Component.literal(companion.living.name + ": " + result.message()));
        CompanionNetwork.notice(owner, result.status() == ActionResult.Status.FAILED ? "error" : "status", result.message());
        save(); return result.status() == ActionResult.Status.FAILED ? 0 : 1;
    }

    private void configure(Companion c, Wolf wolf) {
        if (c.id == null) c.id = c.entity;
        c.memory.bind(c.id);
        if (configured.add(wolf.getUUID())) {
            ((CompanionInventoryHolder) wolf).markai$setCompanion(true);
            if (!wolf.isTame()) wolf.setTame(true, true);
            wolf.setOwnerReference(EntityReference.of(c.owner));
            wolf.setCustomName(Component.literal(c.living.name));
            wolf.setCustomNameVisible(true);
            wolf.setPersistenceRequired();
            var goals = ((MobAccessor) wolf).markai$goals();
            // Keep the wolf's melee, owner defense, swimming, sitting and natural sounds.
            goals.removeAllGoals(goal -> goal instanceof FollowOwnerGoal);
            goals.addGoal(1, livingRuntime.goal(c, wolf));
            goals.addGoal(3, new DogTrickGoal(c, wolf));
            wolfWork.configure(c, wolf);
            // A carried item is an ordinary persistent passenger, never a copied inventory item.
            for (Entity passenger : List.copyOf(wolf.getPassengers()))
                if (passenger instanceof ItemEntity item) { item.stopRiding(); item.setDefaultPickUpDelay(); }
            wolf.setOrderedToSit(!c.following);
            c.expectedSit = wolf.isOrderedToSit(); c.sitObserved = true;
        }
    }

    private Wolf find(Companion c) {
        if (c.dead) return null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(c.entity);
            if (entity instanceof Wolf wolf && wolf.isAlive()) return wolf;
            if (entity instanceof Chicken chicken && chicken.isAlive() && storageHealthy)
                return migrate(c, chicken, level);
        }
        return null;
    }

    private Wolf migrate(Companion c, Chicken chicken, ServerLevel level) {
        Wolf wolf = EntityTypes.WOLF.create(level, EntitySpawnReason.CONVERSION);
        if (wolf == null) return null;
        wolf.setPos(chicken.getX(), chicken.getY(), chicken.getZ());
        // The wolf is larger: wait until there is room instead of suffocating on conversion.
        if (!level.noCollision(wolf)) return null;
        wolf.setTame(true, true);
        wolf.setOwnerReference(EntityReference.of(c.owner));
        wolf.setHealth(wolf.getMaxHealth());
        configure(c, wolf);
        if (!level.addFreshEntity(wolf)) { configured.remove(wolf.getUUID()); return null; }
        UUID previousUuid = c.entity;
        c.entity = wolf.getUUID();
        remember(c, wolf, level);
        save();
        if (!storageHealthy) {
            c.entity = previousUuid;
            configured.remove(wolf.getUUID());
            wolf.discard();
            return null;
        }
        AISession session = aiSessions.remove(c.id);
        if (session != null) invalidate(session);
        configured.remove(previousUuid);
        chicken.discard();
        LOG.info("Converted companion {} to tamed wolf {} for owner {}", previousUuid, c.entity, c.owner);
        return wolf;
    }

    private void tick(Companion c) {
        Wolf wolf = find(c);
        if (wolf == null) return;
        configure(c, wolf);
        ServerLevel level = (ServerLevel) wolf.level();
        remember(c, wolf, level);
        ServerPlayer owner = server.getPlayerList().getPlayer(c.owner);
        if (owner == null || !owner.isAlive() || owner.level() != level) {
            wolfWork.cancel(c, wolf);
            cancelTrick(c);
            if (!c.ownerUnavailable) livingRuntime.event(c, wolf, WorldEvent.Type.PLAYER_LEFT_COMPANION, "Хозяина сейчас нет рядом в этом мире.", 1);
            c.ownerUnavailable = true;
            livingRuntime.unavailable(c, wolf); return;
        }
        if (c.ownerUnavailable) {
            c.ownerUnavailable = false;
            livingRuntime.event(c, wolf, WorldEvent.Type.COMPANION_REUNITED, "Мы снова встретились с хозяином.", 1);
            if (c.ownerDied) {
                livingRuntime.event(c, wolf, WorldEvent.Type.PLAYER_RESPAWNED, "Хозяин снова жив, и я встретил его после гибели.", 1);
                c.ownerDied = false;
            }
            if (c.living.emotions.fear < 40 && wolf.getTarget() == null) bark(wolf);
            wolf.setOrderedToSit(!c.following);
        } else if (c.trick == null && c.sitObserved && c.expectedSit != wolf.isOrderedToSit()) {
            wolfWork.cancel(c, wolf);
            // Respect the owner's normal right-click and the wolf standing up to retaliate.
            c.following = !wolf.isOrderedToSit();
            c.requestedAction = null; c.automaticSit = false; c.dismissed = false;
            c.living.behavior.cancel(level.getGameTime());
            AISession previous = aiSessions.get(c.id);
            if (previous != null) invalidate(previous);
            save();
        }
        if (c.trick != null && (CompanionSafety.critical(wolf)
                || wolf.getHealth() < wolf.getMaxHealth() * .25 && wolf.getTarget() != null)) cancelTrick(c);
        if (c.trick != null) { c.action = c.trick.kind.name(); c.memory.flushPending(); return; }
        if (wolfWork.tick(c, wolf, owner)) { c.memory.flushPending(); return; }
        livingRuntime.tick(c, wolf, owner);
        long now = level.getGameTime();
        if (companions.selected(c.owner) == c && c.living.settings.autochat && aiConfig != null
                && aiConfig.automaticConversationSeconds > 0 && now >= c.nextAutomaticConversation && wolf.distanceTo(owner) <= 12
                && c.trick == null && c.living.activity() != Activity.DEFEND && c.living.activity() != Activity.RETREAT) {
            c.nextAutomaticConversation = now + 20;
            AISession session = aiSessions.get(c.id);
            if (ollama != null && (session == null || session.enabled && session.pending == null)
                    && aiSessions.values().stream().noneMatch(s -> s.pending != null)
                    && (session == null || System.currentTimeMillis() >= session.nextRequest)
                    && ambient(c).begin(now, aiConfig.automaticConversationSeconds, ambientScene(c, wolf, owner)))
                ask(owner, AMBIENT_PROMPT, true);
        }
    }

    private int train(ServerPlayer owner, String name) {
        Companion c = companions.selected(owner.getUUID());
        Wolf wolf = c == null ? null : find(c);
        Trick trick = Trick.parse(name);
        if (!storageHealthy || wolf == null || !owner.isAlive() || owner.isSpectator() || owner.level() != wolf.level()) {
            owner.sendSystemMessage(Component.literal("Для обучения нужен ваш живой компаньон рядом.")); return 0;
        }
        if (trick == null) {
            owner.sendSystemMessage(Component.literal("Обучение: /walk train голос | ко мне | принеси")); return 0;
        }
        if (wolf.distanceTo(owner) > 4 || wolf.getTarget() != null || c.trick != null) {
            owner.sendSystemMessage(Component.literal("Подойдите к компаньону. Тренироваться можно, когда он свободен и бой закончен.")); return 0;
        }
        if (!c.training.canTrain(trick)) {
            owner.sendSystemMessage(Component.literal(c.training.knows(trick) ? c.living.name + " уже освоил «" + trick.title + "»."
                : "Для «" + trick.title + "» нужен уровень " + trick.requiredLevel + ".")); return 0;
        }
        long now = wolf.level().getGameTime();
        if (now < c.nextLessonAt) {
            owner.sendSystemMessage(Component.literal("Сделайте паузу на пару секунд между уроками.")); return 0;
        }
        var treat = owner.getMainHandItem();
        if (!treat.is(ItemTags.WOLF_FOOD)) {
            owner.sendSystemMessage(Component.literal("Возьмите в основную руку мясное лакомство, например жареную говядину.")); return 0;
        }
        c.training.train(trick);
        if (!owner.getAbilities().instabuild) treat.shrink(1);
        c.nextLessonAt = now + 40;
        wolf.getLookControl().setLookAt(owner, 30, 30);
        ((ServerLevel) wolf.level()).sendParticles(ParticleTypes.HEART, wolf.getX(), wolf.getY() + 0.8, wolf.getZ(), 3, 0.2, 0.2, 0.2, 0.01);
        if (trick == Trick.BARK) bark(wolf);
        if (c.training.knows(trick)) {
            if (memoryEnabled()) c.memory.rememberEvent("learned:" + trick, "Хозяин научил меня команде «" + trick.title + "».", now);
            owner.sendSystemMessage(Component.literal(c.living.name + " освоил «" + trick.title + "»! " + c.training.status()));
        } else owner.sendSystemMessage(Component.literal("Урок «" + trick.title + "»: " + c.training.progress(trick) + "/3. " + c.training.status()));
        save();
        return 1;
    }

    private static void bark(Wolf wolf) {
        wolf.playSound(wolf.get(DataComponents.WOLF_SOUND_VARIANT).value().adultSounds().ambientSound().value(), 0.8F, 1.0F);
    }

    private int performTrick(ServerPlayer owner, Trick trick) {
        Companion c = companions.selected(owner.getUUID());
        Wolf wolf = c == null ? null : find(c);
        if (!storageHealthy || wolf == null || !owner.isAlive() || owner.isSpectator() || owner.level() != wolf.level() || wolf.distanceTo(owner) > 32) {
            owner.sendSystemMessage(Component.literal("Компаньон должен быть загружен и находиться поблизости.")); return 0;
        }
        if (!c.training.knows(trick)) {
            owner.sendSystemMessage(Component.literal(c.living.name + " ещё не знает «" + trick.title + "». Обучение: /mark train " + trick.title)); return 0;
        }
        long now = wolf.level().getGameTime();
        if (now < c.nextTrickAt || (wolf.getTarget() != null && trick != Trick.BARK)) {
            owner.sendSystemMessage(Component.literal(c.living.name + " занят. Повторите команду чуть позже.")); return 0;
        }
        ItemEntity item = null;
        if (trick == Trick.FETCH) {
            item = wolf.level().getEntitiesOfClass(ItemEntity.class, wolf.getBoundingBox().inflate(c.training.fetchRange()),
                e -> e.isAlive() && !e.isPassenger() && !e.getItem().isEmpty() && e.getOwner() == owner)
                .stream().min(Comparator.comparingDouble(wolf::distanceToSqr)).orElse(null);
            if (item == null) {
                owner.sendSystemMessage(Component.literal("Бросьте предмет клавишей Q рядом с компаньоном, затем скажите «принеси».")); return 0;
            }
        }
        cancelTrick(c);
        wolfWork.cancel(c, wolf);
        invalidate(aiSessions.computeIfAbsent(c.id, key -> new AISession()));
        c.nextTrickAt = now + 40;
        if (trick == Trick.BARK) {
            bark(wolf);
            completeTrick(c, wolf, trick);
        } else {
            c.trick = new TrickTask(trick, now + 600, item == null ? null : item.getUUID());
            wolf.setOrderedToSit(false);
            wolf.setInSittingPose(false);
            wolf.getNavigation().stop();
            owner.sendSystemMessage(Component.literal(c.living.name + (trick == Trick.COME ? " бежит к вам." : " идёт за предметом.")));
        }
        return 1;
    }

    private void completeTrick(Companion c, Wolf wolf, Trick trick) {
        c.training.completed(wolf.level().getGameTime());
        if (memoryEnabled()) c.memory.rememberEvent("trick:" + trick, "Я выполнил команду хозяина «" + trick.title + "».", wolf.level().getGameTime());
        c.trick = null;
        wolf.getNavigation().stop();
        wolf.setOrderedToSit(!c.following);
        save();
    }

    private void cancelTrick(Companion c) {
        TrickTask task = c.trick;
        c.trick = null;
        if (task == null) return;
        Wolf wolf = find(c);
        if (wolf == null) return;
        if (task.item != null && wolf.level() instanceof ServerLevel level && level.getEntity(task.item) instanceof ItemEntity item
                && item.getVehicle() == wolf) {
            item.stopRiding(); item.setDefaultPickUpDelay();
        }
        wolf.getNavigation().stop();
        wolf.setOrderedToSit(wolf.getTarget() == null && !c.following);
    }

    private final class DogTrickGoal extends Goal {
        private final Companion companion;
        private final Wolf wolf;
        DogTrickGoal(Companion companion, Wolf wolf) {
            this.companion = companion; this.wolf = wolf;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        @Override public boolean canUse() {
            ServerPlayer owner = server == null ? null : server.getPlayerList().getPlayer(companion.owner);
            return companion.trick != null && wolf.level().getGameTime() < companion.trick.deadline
                && wolf.getTarget() == null && owner != null && owner.isAlive() && owner.level() == wolf.level();
        }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public void stop() { cancelTrick(companion); }
        private void approach(Entity target, double speed) {
            // Pathfinding may stop at a reachable nearby node. Finish the short visible approach
            // through normal movement/collision instead of pretending the item was reached.
            if (wolf.distanceTo(target) < 5 && wolf.hasLineOfSight(target)) {
                wolf.getNavigation().stop();
                wolf.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), speed);
            } else wolf.getNavigation().moveTo(wolf.getNavigation().createPath(target.blockPosition(), 0), speed);
        }
        @Override public void tick() {
            TrickTask task = companion.trick;
            if (task == null) return;
            ServerPlayer owner = server.getPlayerList().getPlayer(companion.owner);
            if (owner == null) return;
            if (task.kind == Trick.COME) {
                if (wolf.distanceTo(owner) < 1.8) {
                    completeTrick(companion, wolf, task.kind);
                    owner.sendSystemMessage(Component.literal(companion.living.name + " подошёл к вам."));
                } else approach(owner, 1.1 + companion.training.level() * 0.02);
                return;
            }
            Entity entity = ((ServerLevel) wolf.level()).getEntity(task.item);
            if (!(entity instanceof ItemEntity item) || !item.isAlive()) { cancelTrick(companion); return; }
            if (!task.carrying) {
                if (wolf.distanceTo(item) < 1.6) {
                    if (item.hasPickUpDelay()) return;
                    if (!item.startRiding(wolf, true, false)) { cancelTrick(companion); return; }
                    item.setTarget(companion.owner);
                    task.carrying = true;
                    wolf.getNavigation().stop();
                } else approach(item, 1.1);
            } else {
                item.setPickUpDelay(10);
                if (wolf.distanceTo(owner) < 2.0) {
                    item.stopRiding();
                    var remaining = item.getItem().copy();
                    owner.getInventory().add(remaining);
                    if (remaining.isEmpty()) item.discard();
                    else {
                        item.setItem(remaining); item.setPos(owner.getX(), owner.getY(), owner.getZ());
                        item.setNoPickUpDelay();
                    }
                    completeTrick(companion, wolf, task.kind);
                    owner.sendSystemMessage(Component.literal(companion.living.name + " принёс ваш предмет."));
                } else approach(owner, 1.1 + companion.training.level() * 0.02);
            }
        }
    }

    private void selectCompanion(ServerPlayer owner, String rawId) {
        if (!storageHealthy || !NetworkRules.companionId(rawId)) {
            CompanionNetwork.notice(owner, "error", "Некорректный ID компаньона или ошибка сохранения.");
            return;
        }
        UUID id = UUID.fromString(rawId);
        if (!companions.select(owner.getUUID(), id)) {
            CompanionNetwork.notice(owner, "error", "Нельзя выбрать чужого или неизвестного компаньона.");
            return;
        }
        save();
    }

    /** A departing or dead owner may have multiple outstanding companion conversations. */
    private void invalidateOwnerSessions(UUID ownerId) {
        for (Companion companion : companions.owned(ownerId)) {
            AISession session = aiSessions.get(companion.id);
            if (session != null) invalidate(session);
        }
    }

    private int aiCommand(ServerPlayer owner, String mode) {
        Companion c = companions.selected(owner.getUUID());
        if (c == null) return wolfNotice(owner, "Сначала создайте или приручите компаньона.", false);
        AISession session = aiSessions.computeIfAbsent(c.id, key -> new AISession());
        if (!mode.equals("status")) {
            invalidate(session);
            session.enabled = mode.equals("on");
        }
        String name = c.living.name;
        owner.sendSystemMessage(Component.literal(name + ": ИИ " + (session.enabled ? "включён" : "выключен")
            + ", модель=" + (aiConfig == null ? "ошибка конфига" : aiConfig.effectiveModel())
            + (ollama == null ? ", клиент недоступен; см. журнал" : "")
            + (session.pending == null ? "" : ", ожидаю ответ")
            + ". Обращение: /mark ask <текст> или walk, <текст>."));
        return 1;
    }

    private static void invalidate(AISession session) {
        session.generation++;
        if (session.cancelTrace != null) {
            session.cancelTrace.run(); session.cancelTrace = null;
        }
        if (session.pending != null) { session.pending.cancel(true); session.pending = null; }
        session.pendingAutomatic = false;
        session.idleUntil = session.wanderUntil = session.lookUntil = session.fleeUntil = 0;
    }

    private int performAction(Companion c, ServerPlayer owner, CompanionAction action) {
        Wolf wolf = find(c);
        if (wolf == null) { owner.sendSystemMessage(Component.literal("Компаньон не загружен. /mark summon")); return 0; }
        wolfWork.cancel(c, wolf);
        cancelTrick(c); invalidate(aiSessions.computeIfAbsent(c.id, key -> new AISession()));
        ActionResult result = livingRuntime.request(c, wolf, owner, action);
        owner.sendSystemMessage(Component.literal(c.living.name + ": " + result.message()));
        CompanionNetwork.notice(owner, result.status() == ActionResult.Status.FAILED ? "error" : "status", result.message());
        save(); return result.status() == ActionResult.Status.FAILED ? 0 : 1;
    }

    private void cancelCompanionAction(Companion c, ServerPlayer owner) {
        AISession session = aiSessions.get(c.id);
        if (session != null) { invalidate(session); session.error = ""; }
        cancelTrick(c);
        Wolf wolf = find(c);
        wolfWork.cancel(c, wolf);
        if (wolf != null) livingRuntime.request(c, wolf, owner, CompanionAction.CANCEL);
        else { c.requestedAction = null; c.living.behavior.cancel(server.getTickCount()); }
    }

    private void cancelRequest(ServerPlayer owner) {
        Companion c = companions.selected(owner.getUUID());
        if (c != null) { cancelCompanionAction(c, owner); save(); }
        CompanionNetwork.notice(owner, "status", "Ожидание и действие отменены.");
    }

    private int summon(Companion c, ServerPlayer owner) {
        if (!owner.isAlive() || owner.isSpectator() || c.dead) return 0;
        for (ServerLevel level : server.getAllLevels()) if (level.dimension().identifier().toString().equals(c.dimension))
            level.getChunk(c.chunkX, c.chunkZ);
        Wolf wolf = find(c);
        if (wolf == null || !wolf.isOwnedBy(owner)) {
            owner.sendSystemMessage(Component.literal("Сохранённый волк не найден. Повторный экземпляр не создаётся.")); return 0;
        }
        wolfWork.cancel(c, wolf);
        ServerLevel destination = (ServerLevel) owner.level();
        Entity occupant = destination.getEntity(c.entity);
        if (occupant != null && occupant != wolf) {
            owner.sendSystemMessage(Component.literal("Переход между мирами ещё завершается. Повторите призыв через секунду.")); return 0;
        }
        for (int dy : new int[]{0, 1, -1}) for (int[] offset : new int[][]{{2,0},{-2,0},{0,2},{0,-2},{2,2},{-2,-2}}) {
            Vec3 pos = owner.position().add(offset[0], dy, offset[1]);
            if (!LivingCompanionRuntime.safePathTarget(destination, pos)
                    || !destination.noCollision(wolf, wolf.getBoundingBox().move(pos.subtract(wolf.position())))) continue;
            cancelTrick(c); invalidate(aiSessions.computeIfAbsent(c.id, key -> new AISession()));
            Entity arrived = wolf.teleport(new TeleportTransition(destination, pos, Vec3.ZERO,
                wolf.getYRot(), wolf.getXRot(), Set.of(), TeleportTransition.DO_NOTHING));
            if (arrived == null) continue;
            if (!(arrived instanceof Wolf current) || !current.isAlive() || !current.isOwnedBy(owner)) {
                owner.sendSystemMessage(Component.literal("Не удалось подтвердить прибытие компаньона. Проверьте /mark status.")); return 0;
            }
            if (current != wolf) configured.remove(c.entity);
            livingRuntime.remove(c.entity);
            c.dismissed = false; c.following = true; c.requestedAction = null; c.ownerUnavailable = false;
            remember(c, current, destination); configure(c, current); current.setOrderedToSit(false);
            c.expectedSit = false; c.sitObserved = true; c.automaticSit = false; save();
            owner.sendSystemMessage(Component.literal(c.living.name + " пришёл к вам.")); return 1;
        }
        owner.sendSystemMessage(Component.literal("Рядом нет безопасного места для призыва.")); return 0;
    }

    private void updateSetting(ServerPlayer owner, String key, String value) {
        Companion c = companions.selected(owner.getUUID());
        if (c == null || !storageHealthy || !NetworkRules.setting(key, value)) return;
        var settings = c.living.settings; var traits = c.living.personality;
        double numeric = key.startsWith("trait_") ? Double.parseDouble(value) / 100 : 0;
        switch (key) {
            case "name" -> c.living.name = value.strip();
            case "hud" -> settings.hud = Boolean.parseBoolean(value);
            case "defense" -> settings.defense = Boolean.parseBoolean(value);
            case "autochat" -> {
                settings.autochat = Boolean.parseBoolean(value);
                AISession session = aiSessions.get(c.id);
                if (!settings.autochat && session != null && session.pendingAutomatic) {
                    invalidate(session); session.nextRequest = 0;
                }
            }
            case "follow_distance" -> settings.followDistance = Double.parseDouble(value);
            case "trait_loyalty" -> traits.loyalty = numeric;
            case "trait_curiosity" -> traits.curiosity = numeric;
            case "trait_caution" -> traits.caution = numeric;
            case "trait_playfulness" -> traits.playfulness = numeric;
            case "trait_sociability" -> traits.sociability = numeric;
            case "trait_independence" -> traits.independence = numeric;
            case "trait_confidence" -> traits.confidence = numeric;
            case "trait_trust" -> { traits.trust = numeric; c.living.emotions.trust = numeric * 100; }
            default -> { return; }
        }
        c.living.normalize();
        Wolf wolf = find(c); if (wolf != null) { wolf.setCustomName(Component.literal(c.living.name)); if (!settings.defense) wolf.setTarget(null); }
        save();
    }

    private String uiSnapshot(ServerPlayer owner) {
        var available = new ArrayList<Map<String, Object>>();
        for (Companion member : companions.owned(owner.getUUID())) {
            Wolf rosterWolf = find(member);
            var summary = new LinkedHashMap<String, Object>();
            summary.put("id", member.id.toString());
            summary.put("entity_uuid", member.entity.toString());
            summary.put("name", member.living.name);
            summary.put("species", member.species);
            summary.put("dead", member.dead);
            summary.put("activity", member.dead ? "DEAD" : rosterWolf == null ? "UNLOADED" : member.action);
            summary.put("health", rosterWolf == null ? 0 : rosterWolf.getHealth());
            summary.put("max_health", rosterWolf == null ? 40 : rosterWolf.getMaxHealth());
            available.add(summary);
        }
        Companion c = companions.selected(owner.getUUID());
        if (c == null) return CHAT_JSON.toJson(Map.of("name", "Марк", "activity", "NOT_CREATED",
            "ai_status", "Создайте компаньона", "messages", List.of(), "memories", List.of(),
            "companions", available));
        c.memory.flushPending(); c.living.normalize();
        Wolf wolf = find(c); AISession session = aiSessions.get(c.id);
        var data = new LinkedHashMap<String, Object>();
        data.put("companions", available);
        data.put("name", c.living.name); data.put("entity_uuid", c.entity.toString()); data.put("entity_id", wolf == null ? -1 : wolf.getId());
        data.put("companion_id", c.id == null ? c.entity.toString() : c.id.toString());
        data.put("health", wolf == null ? 0 : wolf.getHealth()); data.put("max_health", wolf == null ? 40 : wolf.getMaxHealth());
        data.put("activity", c.dead ? "DEAD" : wolf == null ? "UNLOADED" : c.action);
        data.put("mood", c.living.emotions.mood.name()); data.put("trust", c.living.emotions.trust); data.put("level", c.training.level());
        data.put("ai_status", ollama == null ? "Недоступен" : session != null && !session.enabled ? "Выключен" : session != null && session.pending != null && !session.pendingAutomatic ? "Думает…" : "Готов");
        data.put("can_chat", session == null || session.pendingAutomatic || session.pending == null && System.currentTimeMillis() >= session.nextRequest);
        data.put("error", session == null ? "" : session.error);
        var settings = new LinkedHashMap<String, Object>(c.living.settings.context());
        settings.put("follow_distance", c.living.settings.followDistance); data.put("settings", settings);
        var traits = new LinkedHashMap<String, Object>(); c.living.personality.context().forEach((key, value) -> traits.put(key, ((Number) value).doubleValue() * 100));
        data.put("traits", traits); data.put("emotions", c.living.emotions.context());
        data.put("last_action_result", c.living.lastAction.status() == ActionResult.Status.NONE ? "" : c.living.lastAction.message());
        data.put("home", c.living.home == null ? "Не отмечен" : c.living.home.dimension() + " " + (int)c.living.home.x() + ", " + (int)c.living.home.y() + ", " + (int)c.living.home.z());
        var history = new ArrayList<Map<String, String>>();
        for (var message : c.memory.history()) {
            String text = message.content();
            if (message.role().equals("assistant")) try { text = com.google.gson.JsonParser.parseString(text).getAsJsonObject().get("say").getAsString(); } catch (RuntimeException ignored) { }
            history.add(Map.of("role", message.role(), "text", text.length() > 512 ? text.substring(0, 512) : text));
        }
        data.put("messages", history);
        var entries = c.memory.inspect(); int page = Math.min(memoryPages.getOrDefault(c.id, 0), Math.max(0, (entries.size() - 1) / 16));
        memoryPages.put(c.id, page);
        data.put("memory_page", page); data.put("memory_total", entries.size());
        data.put("memories", entries.stream().skip(page * 16L).limit(16).map(e -> Map.of("id", e.id().toString(), "category", e.category(),
            "content", e.content(), "time", e.gameTime(), "source", e.sourceType().name() + "/" + e.reliability().name(), "importance", e.importance())).toList());
        String result = CHAT_JSON.toJson(data);
        while (result.length() > CompanionNetwork.MAX_SNAPSHOT && !history.isEmpty()) { history.removeFirst(); result = CHAT_JSON.toJson(data); }
        return result;
    }

    private int ask(ServerPlayer owner, String message) { return ask(owner, message, false); }

    private int ask(ServerPlayer owner, String message, boolean automatic) {
        if (!storageHealthy) {
            owner.sendSystemMessage(Component.literal("MarkAI: ошибка файла состояния; см. журнал.")); return 0;
        }
        if (message == null || message.isBlank() || message.length() > 512 || message.codePoints().anyMatch(Character::isISOControl)) {
            CompanionNetwork.notice(owner, "error", "Сообщение: от 1 до 512 символов, без управляющих кодов."); return 0;
        }
        String wolfOrder = automatic ? null : WolfOrderIntent.parse(message);
        if (wolfOrder != null) {
            int performed = performCommand(owner, wolfOrder);
            Companion ordered = companions.selected(owner.getUUID());
            if (performed != 0 && ordered != null && memoryEnabled() && (wolfOrder.equals("attack") || wolfOrder.equals("collect"))) {
                ordered.memory.rememberExchange(message, new OllamaClient.Reply("say", ordered.living.lastAction.message()), owner.level().getGameTime());
                save();
            }
            return performed;
        }
        Companion c = companions.selected(owner.getUUID());
        Wolf wolf = c == null ? null : find(c);
        if (wolf == null || !owner.isAlive() || owner.isSpectator() || wolf.level() != owner.level() || wolf.distanceTo(owner) > 32) {
            owner.sendSystemMessage(Component.literal("Компаньон должен быть жив и загружен рядом с вами. Проверьте /mark status."));
            return 0;
        }
        if (automatic && (!c.living.settings.autochat || c.dismissed || c.trick != null)) return 0;
        AISession session = aiSessions.computeIfAbsent(c.id, key -> new AISession());
        if (!automatic) {
            ambient(c).defer(wolf.level().getGameTime(), aiConfig == null ? 60 : aiConfig.automaticConversationSeconds);
            if (session.pending != null && session.pendingAutomatic) {
                invalidate(session); session.nextRequest = 0;
            }
        }
        Trick requestedTrick = Trick.parse(message);
        if (requestedTrick != null && (requestedTrick != Trick.COME || c.training.knows(Trick.COME))) return performTrick(owner, requestedTrick);
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        for (String prefix : List.of("учи ", "учим ", "научись ", "тренируй ")) {
            if (lowerMessage.startsWith(prefix)) return train(owner, message.substring(prefix.length()));
        }
        CompanionAction parsed = automatic ? null : IntentParser.parse(message);
        if (parsed != null) {
            int performed = performAction(c, owner, parsed);
            if (memoryEnabled()) c.memory.rememberExchange(message, new OllamaClient.Reply("say", c.living.lastAction.message()), wolf.level().getGameTime());
            save(); return performed;
        }
        if (!session.enabled || ollama == null) {
            session.error = "ИИ недоступен. /mark ai on; настройки: config/markai.json.";
            if (!automatic) { owner.sendSystemMessage(Component.literal(session.error)); CompanionNetwork.notice(owner, "error", session.error); } return 0;
        }
        long nowMillis = System.currentTimeMillis();
        if (session.pending != null || aiSessions.values().stream().anyMatch(s -> s.pending != null)
                || nowMillis < session.nextRequest) {
            String waiting = session.pending != null ? c.living.name + " ещё думает." : "Подождите несколько секунд между сообщениями.";
            if (!automatic) { owner.sendSystemMessage(Component.literal(waiting)); CompanionNetwork.notice(owner, "status", waiting); } return 0;
        }
        session.nextRequest = nowMillis + aiConfig.requestCooldownSeconds * 1000L;
        c.nextAutomaticConversation = wolf.level().getGameTime() + Math.max(30, aiConfig.automaticConversationSeconds) * 20L;
        if (!automatic) session.error = "";
        MinecraftServer requestServer = server;
        UUID entityUuid = c.entity;
        var requestLevel = wolf.level();
        long generation = ++session.generation;
        DialogueContext dialogue = automatic ? ambientDialogueContext(c, wolf, owner) : dialogueContext(c, wolf, owner, message);
        String requestMessage = automatic ? AMBIENT_PROMPT : message;
        String context = dialogue.observation();
        boolean retainMemory = memoryEnabled();
        ChatJournal journal = chatJournal;
        String requestId = retainMemory ? journal.begin(c.owner, c.entity, owner.getGameProfile().name(), requestMessage, context) : null;
        session.cancelTrace = () -> journal.finish(requestId, "cancelled", null, null, null);
        var pending = ollama.ask(context, requestMessage, dialogue.history());
        session.pending = pending;
        session.pendingAutomatic = automatic;
        if (!automatic) {
            owner.sendSystemMessage(Component.literal(c.living.name + " думает…"));
            CompanionNetwork.notice(owner, "thinking", c.living.name + " думает…");
        }
        pending.whenComplete((reply, error) -> {
            if (pending.isCancelled()) {
                journal.finish(requestId, "cancelled", reply, null, null); return;
            }
            try {
                requestServer.execute(() -> {
                    if (server != requestServer || aiSessions.get(c.id) != session || session.generation != generation) {
                        journal.finish(requestId, "discarded", reply, null, "Session changed"); return;
                    }
                    session.pending = null;
                    session.pendingAutomatic = false;
                    if (automatic) session.nextRequest = 0;
                    session.lastLatencyMillis = System.currentTimeMillis() - nowMillis;
                    session.cancelTrace = null;
                    if (!session.enabled || companions.get(c.id) != c || !entityUuid.equals(c.entity)) {
                        journal.finish(requestId, "discarded", reply, null, "Companion changed"); return;
                    }
                    ServerPlayer currentOwner = requestServer.getPlayerList().getPlayer(c.owner);
                    Wolf current = find(c);
                    if (currentOwner != owner || !currentOwner.isAlive() || current == null
                            || current.level() != requestLevel || current.level() != currentOwner.level()) {
                        journal.finish(requestId, "discarded", reply, null, "Owner or world changed"); return;
                    }
                    if (automatic && (!c.living.settings.autochat || c.dismissed || current.distanceTo(currentOwner) > 12
                            || c.trick != null || c.living.activity() == Activity.DEFEND || c.living.activity() == Activity.RETREAT)) {
                        journal.finish(requestId, "discarded", reply, null, "Automatic conversation no longer appropriate"); return;
                    }
                    if (error != null) {
                        LOG.warn("Local AI request failed: {}", error.toString());
                        if (automatic) { journal.finish(requestId, "failed", null, null, error.toString()); return; }
                        Throwable cause = error;
                        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                        String fallback = cause instanceof dev.markai.ai.RepetitionGuardLlmProvider.RepeatedReplyException
                            ? "Не получилось сформулировать новый ответ. Попробуй ещё раз."
                            : "Сейчас не могу ответить. Проверь подключение ИИ в config/markai.json.";
                        session.error = fallback;
                        currentOwner.sendSystemMessage(Component.literal("<" + c.living.name + "> " + fallback));
                        CompanionNetwork.notice(currentOwner, "error", fallback);
                        journal.finish(requestId, "failed", null, new OllamaClient.Reply("say", fallback), error.toString());
                        return;
                    }
                    // Conversation cannot change movement just because the model selected an action.
                    var delivered = new OllamaClient.Reply(automatic ? "say" : IntentParser.allowsStructuredAction(message) ? reply.action() : DialogueIntent.requestedAction(message), reply.say());
                    if (aiConfig != null && delivered.say().length() > aiConfig.maxDialogueLength)
                        delivered = new OllamaClient.Reply(delivered.action(), delivered.say().substring(0, aiConfig.maxDialogueLength));
                    if (automatic && !ambient(c).accept(delivered.say())) {
                        journal.finish(requestId, "suppressed_repeat", reply, null, null); return;
                    }
                    delivered = applyReply(c, current, currentOwner, session, delivered);
                    journal.finish(requestId, "applied", reply, delivered, null);
                    if (!automatic && retainMemory && memoryEnabled()) c.memory.rememberExchange(message, delivered, current.level().getGameTime());
                    save();
                    CompanionNetwork.notice(currentOwner, "reply", delivered.say());
                    CompanionNetwork.sendSnapshot(currentOwner);
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                journal.finish(requestId, "discarded", reply, null, "Server stopped");
            }
        });
        return 1;
    }

    private DialogueContext dialogueContext(Companion c, Wolf wolf, ServerPlayer owner, String question) {
        return new DialogueContext(snapshot(c, wolf, owner, question), memoryEnabled() ? c.memory.history() : List.of());
    }

    private static AmbientConversation ambient(Companion c) {
        if (c.autoConversation == null) c.autoConversation = new AmbientConversation();
        return c.autoConversation;
    }

    private WorldEvent freshAmbientEvent(Companion c, long now) {
        if (!memoryEnabled()) return null;
        return c.living.events.recent(now, dev.markai.events.EventHistory.LIMIT).stream()
            .filter(event -> now - event.gameTime() <= 1200 && event.type() != WorldEvent.Type.ACTION_FAILED
                && event.type() != WorldEvent.Type.PLAYER_LEFT_COMPANION)
            .max(Comparator.comparingLong(WorldEvent::gameTime)).orElse(null);
    }

    /** Coarse changes only: ticking clocks, footsteps and follow/idle oscillation are not new topics. */
    private String ambientScene(Companion c, Wolf wolf, ServerPlayer owner) {
        var view = livingRuntime.world(c, wolf, owner); var environment = view.environment();
        WorldEvent event = freshAmbientEvent(c, wolf.level().getGameTime());
        return CHAT_JSON.toJson(List.of(environment.dimension(), environment.biome(), environment.raining(),
            environment.thundering(), environment.dangerous_cave(), environment.time_of_day() / 6000,
            c.living.emotions.mood.name(), view.visible_entities().stream().map(e -> e.type()).distinct().sorted().limit(6).toList(),
            environment.important_blocks().stream().map(block -> block.type()).distinct().sorted().limit(4).toList(),
            event == null ? "none" : event.type().name() + ":" + event.gameTime()));
    }

    /** Ambient speech has its own short context; an old player question is never replayed as a new request. */
    private DialogueContext ambientDialogueContext(Companion c, Wolf wolf, ServerPlayer owner) {
        var context = com.google.gson.JsonParser.parseString(snapshot(c, wolf, owner, "")).getAsJsonObject();
        context.remove("memory"); context.remove("last_action_result");
        var living = context.getAsJsonObject("living");
        living.remove("home"); living.remove("last_action"); living.remove("recent_events");
        living.remove("behavior");
        context.addProperty("conversation_mode", "ambient");
        var recentLines = CHAT_JSON.toJsonTree(ambient(c).recentLines()).getAsJsonArray();
        context.add("recent_lines", recentLines);
        WorldEvent event = freshAmbientEvent(c, wolf.level().getGameTime());
        if (event != null) context.add("fresh_event", CHAT_JSON.toJsonTree(event));
        String serialized = CHAT_JSON.toJson(context);
        while (serialized.length() > 8192 && !recentLines.isEmpty()) {
            recentLines.remove(0); serialized = CHAT_JSON.toJson(context);
        }
        if (serialized.length() > 8192) {
            context.remove("nearby_entities"); serialized = CHAT_JSON.toJson(context);
        }
        if (serialized.length() > 8192) throw new IllegalStateException("Ambient observation exceeded its context budget");
        return new DialogueContext(serialized, List.of());
    }

    private String snapshot(Companion c, Wolf wolf, ServerPlayer owner, String question) {
        var view = livingRuntime.world(c, wolf, owner);
        var facts = new LinkedHashMap<String, Object>();
        facts.put("companion", Map.of("name", c.living.name, "type", "minecraft:wolf", "health", wolf.getHealth(),
            "max_health", wolf.getMaxHealth(), "tamed", wolf.isTame(), "can_attack", c.living.settings.defense,
            "owner_uuid", c.owner.toString(), "position", position(wolf), "in_water", wolf.isInWater(),
            "movement", Map.of("action", c.action == null ? "IDLE" : c.action,
                "navigation_active", !wolf.getNavigation().isDone(), "following_owner", c.following)));
        facts.put("player", view.player()); facts.put("environment", view.environment());
        facts.put("identity", new CompanionIdentity(c.id, c.owner, c.entity));
        facts.put("danger_now", view.environment().critical_hazard() || view.hostiles().stream().anyMatch(e -> e.distance_blocks() < 6));
        facts.put("nearby_hostiles", view.hostiles()); facts.put("nearby_entities", view.visible_entities());
        facts.put("mode", c.following ? "follow" : "stay"); facts.put("dimension", c.dimension);
        facts.put("memory", memoryEnabled() ? c.memory.context(question) : Map.of());
        facts.put("training", c.training.context());
        var living = new LinkedHashMap<String, Object>(c.living.context());
        living.remove("personality"); living.remove("emotions");
        if (!memoryEnabled()) living.remove("recent_events");
        facts.put("living", living);
        facts.put("personality", c.living.personality.context()); facts.put("emotions", c.living.emotions.context());
        facts.put("last_action_result", c.living.lastAction);
        var bounded = CHAT_JSON.toJsonTree(facts).getAsJsonObject();
        String serialized = CHAT_JSON.toJson(bounded);
        for (String key : List.of("nearby_entities", "nearby_hostiles")) {
            var entries = bounded.getAsJsonArray(key);
            while (serialized.length() > 7800 && entries.size() > 2) { entries.remove(entries.size() - 1); serialized = CHAT_JSON.toJson(bounded); }
        }
        var memory = bounded.getAsJsonObject("memory");
        for (String key : List.of("observed_events", "player_reports")) {
            var entries = memory.getAsJsonArray(key);
            while (serialized.length() > 7800 && entries != null && entries.size() > 1) { entries.remove(entries.size() - 1); serialized = CHAT_JSON.toJson(bounded); }
        }
        if (serialized.length() > 8192) throw new IllegalStateException("Companion observation exceeded its bounded context budget");
        return serialized;
    }
    private static Map<String, Integer> position(Entity entity) {
        var pos = entity.blockPosition();
        return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
    }

    private OllamaClient.Reply applyReply(Companion c, Wolf wolf, ServerPlayer owner, AISession session, OllamaClient.Reply reply) {
        long now = wolf.level().getGameTime();
        session.idleUntil = session.wanderUntil = session.lookUntil = session.fleeUntil = 0;
        CompanionAction action = switch (reply.action()) {
            case "follow" -> CompanionAction.FOLLOW; case "stay" -> CompanionAction.STAY;
            case "idle" -> CompanionAction.IDLE; case "look_at_player" -> CompanionAction.LOOK_AT_PLAYER;
            case "wander" -> CompanionAction.WANDER; case "flee" -> CompanionAction.RETREAT; default -> null;
            case "come" -> CompanionAction.COME; case "rest" -> CompanionAction.REST;
            case "return_home" -> CompanionAction.RETURN_HOME; case "investigate" -> CompanionAction.INVESTIGATE;
            case "play" -> CompanionAction.PLAY;
        };
        if (action != null) {
            wolfWork.cancel(c, wolf);
            cancelTrick(c);
            ActionResult result = livingRuntime.request(c, wolf, owner, action);
            if (result.status() == ActionResult.Status.FAILED) reply = new OllamaClient.Reply("say", result.message());
        }
        if (!reply.say().isBlank()) {
            owner.sendSystemMessage(Component.literal("<" + c.living.name + "> " + reply.say()));
            c.nextMessage = now + 200;
        }
        return reply;
    }

    private void stopUnlessFleeing(Companion c, Wolf wolf) {
        if (!"FLEE".equals(c.action)) wolf.getNavigation().stop();
    }

    private void remember(Companion c, Entity entity, ServerLevel level) {
        c.dimension = level.dimension().identifier().toString();
        c.chunkX = entity.chunkPosition().x(); c.chunkZ = entity.chunkPosition().z();
    }

    private Path statePath() { return server.getWorldPath(LevelResource.ROOT).resolve("markai.json"); }
    private boolean memoryEnabled() { return aiConfig == null || aiConfig.memoryEnabled; }

    private void save() {
        if (server == null || !storageHealthy) return;
        try {
            for (Companion c : companions.values()) c.memory.flushPending();
            companionStore.write(statePath(), companions.values(), companions.selections());
        } catch (IOException | RuntimeException e) {
            storageHealthy = false;
            LOG.error("Cannot save MarkAI state; further commands disabled", e);
        }
    }
}
