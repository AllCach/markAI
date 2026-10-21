package dev.markai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.markai.ai.OllamaClient;
import dev.markai.ai.LlmProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AIGameTest {
    @GameTest(structure = "markai-test:arena", maxTicks = 2000)
    public void validatedReplyChangesOwnerMode(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++)
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        var owner = helper.makeMockServerPlayerInLevel();
        Vec3 position = helper.absoluteVec(new Vec3(2, 2, 2));
        owner.setPos(position.x, position.y, position.z);
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark spawn");
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark stay");
        Path state = server.getWorldPath(LevelResource.ROOT).resolve("markai.json");
        String entityUuid = savedCompanion(state, owner.getUUID()).get("entity").getAsString();

        var firstRequest = new AtomicReference<String>();
        var secondRequest = new AtomicReference<String>();
        var thirdRequest = new AtomicReference<String>();
        var expectedObservation = new AtomicReference<JsonObject>();
        var testBoat = new AtomicReference<Boat>();
        var controlledProvider = new AtomicReference<ControlledProvider>();
        var ambientHistorySize = new AtomicInteger();
        var lastDeliveryTick = new java.util.concurrent.atomic.AtomicLong();
        var requests = new AtomicInteger();
        var releaseSecondReply = new CountDownLatch(1);
        var secondReplyAttempted = new CountDownLatch(1);
        String longSay = "<".repeat(240);
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var fakeExecutor = Executors.newVirtualThreadPerTaskExecutor();
        fake.setExecutor(fakeExecutor);
        fake.createContext("/api/chat", exchange -> {
            boolean second = false;
            try (exchange) {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int turn = requests.incrementAndGet();
                second = turn == 2;
                if (second) {
                    secondRequest.set(request);
                    try { releaseSecondReply.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
                } else if (turn == 1) firstRequest.set(request);
                else thirdRequest.set(request);
                JsonObject message = new JsonObject();
                message.addProperty("role", "assistant");
                message.addProperty("content", new Gson().toJson(new OllamaClient.Reply("follow", turn == 1 ? longSay : turn == 2 ? "Иду!" : "Рад слышать!")));
                JsonObject response = new JsonObject(); response.add("message", message); response.addProperty("done", true);
                byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // Manual stay cancels the second HTTP request and may close its connection before this write.
            } finally {
                if (second) secondReplyAttempted.countDown();
            }
        });
        fake.start();

        MarkAIMod mod = (MarkAIMod) FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
            .filter(value -> value instanceof MarkAIMod).findFirst().orElseThrow();
        var clientField = MarkAIMod.class.getDeclaredField("ollama");
        clientField.setAccessible(true);
        var original = (LlmProvider) clientField.get(mod);
        var client = new OllamaClient("http://127.0.0.1:" + fake.getAddress().getPort(), "qwen3:8b", Duration.ofSeconds(5));
        var cleaned = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            try {
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark ai off");
                clientField.set(mod, original);
            } catch (IllegalAccessException error) { throw new RuntimeException(error); }
            finally {
                releaseSecondReply.countDown();
                if (controlledProvider.get() != null) controlledProvider.get().close();
                client.close(); fake.stop(0); fakeExecutor.shutdownNow();
                if (testBoat.get() != null) { owner.stopRiding(); testBoat.get().discard(); }
            }
        };
        // Restore the shared client even if an asynchronous assertion fails or the test times out.
        var infoField = GameTestHelper.class.getDeclaredField("testInfo");
        infoField.setAccessible(true);
        ((GameTestInfo) infoField.get(helper)).addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo, GameTestRunner runner) { cleanup.run(); }
        });
        clientField.set(mod, client);
        server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark ask Привет, как дела?");
        Path journal = FabricLoader.getInstance().getGameDir().resolve("logs/markai-chat.jsonl");

        helper.startSequence()
            .thenWaitUntil(() -> {
                helper.assertTrue(firstRequest.get() != null, "AI request must be sent asynchronously");
                JsonObject saved = savedCompanion(state, owner.getUUID());
                helper.assertTrue(!saved.get("following").getAsBoolean(), "Ordinary-chat model follow proposal must preserve STAY");
                helper.assertTrue(currentCompanion(mod, owner.getUUID()).memory.history().size() == 2,
                    "First reply must be delivered and stored before the next asynchronous turn");
                helper.assertTrue(saved.get("entity").getAsString().equals(entityUuid), "Reply must retain companion UUID");
                helper.assertTrue(firstRequest.get().contains(owner.getGameProfile().name()), "Prompt must contain actual owner name");
            })
            .thenExecute(() -> {
                resetCooldown(mod, owner.getUUID());
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark ask О чем поговорим?");
            })
            .thenWaitUntil(() -> helper.assertTrue(secondRequest.get() != null, "Second turn must send HTTP with the previous reply in history"))
            .thenExecute(() -> {
                var messages = JsonParser.parseString(secondRequest.get()).getAsJsonObject().getAsJsonArray("messages");
                helper.assertTrue(messages.size() == 4, "Second turn must contain combined personality/observation, history and current user");
                helper.assertTrue(messages.get(1).getAsJsonObject().get("content").getAsString().equals("Привет, как дела?"), "Previous player message retained");
                JsonObject previousAssistant = messages.get(2).getAsJsonObject();
                helper.assertTrue(previousAssistant.get("role").getAsString().equals("assistant"), "Previous reply role retained");
                var previous = OllamaClient.parseReply(previousAssistant.get("content").getAsString());
                helper.assertTrue(previous.action().equals("say") && previous.say().equals(longSay), "Constrained say action and 240 HTML-sensitive characters must survive history");
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark stay");
                helper.assertTrue(!savedCompanion(state, owner.getUUID()).get("following").getAsBoolean(), "Manual stay persisted while response is pending");
                releaseSecondReply.countDown();
            })
            .thenWaitUntil(() -> helper.assertTrue(secondReplyAttempted.getCount() == 0, "Delayed follow response must be released"))
            .thenIdle(20)
            .thenExecute(() -> {
                JsonObject saved = savedCompanion(state, owner.getUUID());
                helper.assertTrue(!saved.get("following").getAsBoolean(), "Late follow response must not override manual stay");
                helper.assertTrue(saved.get("entity").getAsString().equals(entityUuid), "Manual command and late reply retain companion UUID");
                helper.assertTrue(requests.get() == 2, "Exactly two AI requests expected");
                Boat boat = helper.spawn(EntityTypes.OAK_BOAT, new BlockPos(2, 2, 2));
                testBoat.set(boat);
                helper.assertTrue(owner.startRiding(boat), "Owner must actually be riding a boat for the observation check");
                Wolf wolf = (Wolf) helper.getLevel().getEntity(UUID.fromString(entityUuid));
                helper.assertTrue(wolf != null && wolf.isTame() && wolf.isOwnedBy(owner), "Companion must remain loaded and tamed to its owner");
                JsonObject expected = new JsonObject();
                expected.add("player_position", coordinates(owner.blockPosition()));
                expected.add("companion_position", coordinates(wolf.blockPosition()));
                expected.addProperty("navigation_active", !wolf.getNavigation().isDone());
                expected.addProperty("action", currentCompanion(mod, owner.getUUID()).action);
                expectedObservation.set(expected);
                resetCooldown(mod, owner.getUUID());
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark ask я добрый");
            })
            .thenWaitUntil(() -> helper.assertTrue(thirdRequest.get() != null, "Ordinary conversation must reach Ollama"))
            .thenExecute(() -> {
                JsonObject request = JsonParser.parseString(thirdRequest.get()).getAsJsonObject();
                var actions = request.getAsJsonObject("format").getAsJsonObject("properties").getAsJsonObject("action").getAsJsonArray("enum");
                helper.assertTrue(actions.size() == 1 && actions.get(0).getAsString().equals("say"), "Ordinary conversation must constrain the model schema to say");
                JsonObject context = latestObservation(request);
                JsonObject player = context.getAsJsonObject("player");
                JsonObject companion = context.getAsJsonObject("companion");
                JsonObject movement = companion.getAsJsonObject("movement");
                JsonObject expected = expectedObservation.get();
                helper.assertTrue(companion.get("name").getAsString().equals("Марк") && companion.get("type").getAsString().equals("minecraft:wolf"), "Snapshot must identify the actual wolf Марк");
                helper.assertTrue(companion.get("max_health").getAsInt() == 40 && companion.get("tamed").getAsBoolean() && companion.get("can_attack").getAsBoolean(), "Snapshot must describe native tamed wolf health and combat ability");
                helper.assertTrue(player.get("position").equals(expected.get("player_position")), "Snapshot player coordinates must match the actual position at request time");
                helper.assertTrue(companion.get("position").equals(expected.get("companion_position")), "Snapshot companion coordinates must match the actual position at request time");
                helper.assertTrue(player.get("in_boat").getAsBoolean() && player.get("vehicle").getAsString().equals("minecraft:oak_boat"), "Snapshot must report the actual boat");
                helper.assertTrue(!movement.get("following_owner").getAsBoolean() && context.get("mode").getAsString().equals("stay"), "Snapshot must preserve the actual STAY mode");
                helper.assertTrue(movement.get("navigation_active").equals(expected.get("navigation_active")) && movement.get("action").equals(expected.get("action")), "Snapshot movement must match actual navigation and controller action");
            })
            .thenWaitUntil(() -> {
                JsonObject pair = journalPair(journal, entityUuid, "я добрый");
                helper.assertTrue(pair != null, "Journal must contain the ordinary-chat request and response pair");
                JsonObject response = pair.getAsJsonObject("response");
                helper.assertTrue(response.get("status").getAsString().equals("applied"), "Ordinary-chat response must be applied");
                helper.assertTrue(response.getAsJsonObject("proposed").get("action").getAsString().equals("follow"), "Journal must retain the model's proposed follow action");
                helper.assertTrue(response.getAsJsonObject("delivered").get("action").getAsString().equals("say"), "Journal must record the constrained say action actually delivered");
                helper.assertTrue(pair.getAsJsonObject("request").get("context").equals(latestObservation(JsonParser.parseString(thirdRequest.get()).getAsJsonObject())), "Journal must retain the same world observation sent to the model");
            })
            .thenExecute(() -> {
                JsonObject saved = savedCompanion(state, owner.getUUID());
                helper.assertTrue(!saved.get("following").getAsBoolean(), "Model follow proposal during ordinary chat must not change STAY");
                helper.assertTrue(saved.get("entity").getAsString().equals(entityUuid), "Ordinary chat must retain companion UUID");
                helper.assertTrue(requests.get() == 3, "Exactly three AI requests expected");
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark ask Иди за мной");
                helper.assertTrue(savedCompanion(state, owner.getUUID()).get("following").getAsBoolean(),
                    "Explicit natural-language follow must execute immediately without waiting for an LLM");
                helper.assertTrue(requests.get() == 3, "Supported deterministic movement must not send another HTTP request");
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark stay");
                var companion = currentCompanion(mod, owner.getUUID());
                companion.memory.rememberExchange("Пошли домой AUTOGT_OLD_MANUAL_ORDER",
                    new OllamaClient.Reply("say", "Запомнил прежнюю просьбу."), helper.getLevel().getGameTime());
                ambientHistorySize.set(companion.memory.history().size());
                var controlled = new ControlledProvider(); controlledProvider.set(controlled);
                replaceProvider(mod, controlled);
                updateSetting(mod, owner, "autochat", "true");
                resetCooldown(mod, owner.getUUID());
                helper.assertTrue(askAutomatic(mod, owner) == 1, "An automatic observation must reach the controlled provider");
                helper.assertTrue(controlled.calls.size() == 1, "One automatic request expected");
                var first = controlled.calls.get(0);
                helper.assertTrue(first.history().isEmpty(), "Automatic chatter must not replay the manual conversation history");
                JsonObject context = JsonParser.parseString(first.context()).getAsJsonObject();
                helper.assertTrue(context.get("conversation_mode").getAsString().equals("ambient"), "Automatic requests must identify ambient conversation mode");
                helper.assertTrue(!first.context().contains("AUTOGT_OLD_MANUAL_ORDER"), "Old manual orders must not contaminate an automatic observation");
                helper.assertTrue(uiSnapshot(mod, owner).get("can_chat").getAsBoolean(), "A pending automatic remark must leave manual chat available");
                // Deliberately keep the automatic request's cooldown: a real manual message must preempt it.
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark ask Мне нравится дождь");
                helper.assertTrue(first.reply().isCancelled(), "A manual message must cancel the pending automatic request");
                helper.assertTrue(controlled.calls.size() == 2 && controlled.calls.get(1).question().equals("Мне нравится дождь"),
                    "The manual message must reach the provider immediately instead of being blocked by automatic chatter");
                helper.assertTrue(!first.reply().complete(new OllamaClient.Reply("follow", "Старый автоматический ответ")),
                    "A cancelled automatic completion must not be deliverable");
                controlled.calls.get(1).reply().complete(new OllamaClient.Reply("say", "Под дождём прохладно."));
            })
            .thenWaitUntil(() -> helper.assertTrue(currentCompanion(mod, owner.getUUID()).memory.history().stream()
                .anyMatch(message -> message.role().equals("user") && message.content().equals("Мне нравится дождь")),
                "The preempting manual message must be delivered and retained normally"))
            .thenExecute(() -> {
                var companion = currentCompanion(mod, owner.getUUID());
                helper.assertTrue(companion.memory.history().size() == ambientHistorySize.get() + 2,
                    "Cancelled automatic chatter must not create an extra memory exchange");
                ambientHistorySize.set(companion.memory.history().size());
                server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "mark stay");
                resetCooldown(mod, owner.getUUID());
                helper.assertTrue(askAutomatic(mod, owner) == 1, "A fresh automatic observation must start after a manual reply");
                controlledProvider.get().calls.get(2).reply().complete(new OllamaClient.Reply("follow", "Слышишь, как шелестят листья?"));
            })
            .thenWaitUntil(() -> helper.assertTrue(!pending(mod, owner.getUUID()), "The automatic reply must finish"))
            .thenExecute(() -> {
                var companion = currentCompanion(mod, owner.getUUID());
                helper.assertTrue(!companion.following, "An automatic follow proposal must be forced to say and preserve STAY");
                helper.assertTrue(companion.autoConversation.recentLines().equals(List.of("Слышишь, как шелестят листья?")),
                    "Only the delivered automatic remark must enter the bounded recent-lines history");
                helper.assertTrue(companion.memory.history().size() == ambientHistorySize.get(),
                    "Automatic speech must not be stored as a player conversation");
                lastDeliveryTick.set(companion.nextMessage);
            })
            .thenIdle(2)
            .thenExecute(() -> {
                resetCooldown(mod, owner.getUUID());
                helper.assertTrue(askAutomatic(mod, owner) == 1, "A later automatic request must be testable after the request cooldown");
                var repeat = controlledProvider.get().calls.get(3);
                helper.assertTrue(repeat.context().contains("Слышишь, как шелестят листья?"),
                    "An automatic prompt must include recent automatic lines so the model can avoid repetition");
                repeat.reply().complete(new OllamaClient.Reply("follow", "Слышишь, как шелестят листья?"));
            })
            .thenWaitUntil(() -> helper.assertTrue(!pending(mod, owner.getUUID()), "The repeated automatic reply must finish"))
            .thenExecute(() -> {
                var companion = currentCompanion(mod, owner.getUUID());
                helper.assertTrue(companion.nextMessage == lastDeliveryTick.get(), "A repeated automatic line must not be sent to chat again");
                helper.assertTrue(companion.autoConversation.recentLines().equals(List.of("Слышишь, как шелестят листья?")),
                    "A repeated automatic line must not extend recent-lines history");
                helper.assertTrue(companion.memory.history().size() == ambientHistorySize.get(),
                    "Suppressed repeated chatter must not add a memory exchange");
                resetCooldown(mod, owner.getUUID());
                helper.assertTrue(askAutomatic(mod, owner) == 1, "An automatic request must be pending before disabling autochat");
                var late = controlledProvider.get().calls.get(4);
                updateSetting(mod, owner, "autochat", "false");
                helper.assertTrue(late.reply().isCancelled() && !pending(mod, owner.getUUID()),
                    "Disabling autochat must cancel its in-flight reply");
                helper.assertTrue(!late.reply().complete(new OllamaClient.Reply("follow", "Эту реплику уже выключили")),
                    "An automatic reply completed after disabling the mode must remain cancelled");
            })
            .thenIdle(2)
            .thenExecute(() -> {
                var companion = currentCompanion(mod, owner.getUUID());
                helper.assertTrue(companion.nextMessage == lastDeliveryTick.get() && !companion.following,
                    "A disabled automatic completion must neither speak nor change movement");
                helper.assertTrue(companion.memory.history().size() == ambientHistorySize.get()
                        && companion.autoConversation.recentLines().equals(List.of("Слышишь, как шелестят листья?")),
                    "A disabled automatic completion must leave manual memory and recent lines unchanged");
                helper.assertTrue(controlledProvider.get().calls.size() == 5, "Only the five explicitly requested controlled turns are expected");
                replaceProvider(mod, client);
                cleanup.run();
            })
            .thenSucceed();
    }

    private record ControlledCall(String context, String question, List<OllamaClient.ChatMessage> history,
                                  CompletableFuture<OllamaClient.Reply> reply) {}

    private static final class ControlledProvider implements LlmProvider {
        final List<ControlledCall> calls = new CopyOnWriteArrayList<>();
        @Override public CompletableFuture<OllamaClient.Reply> ask(String context, String question, List<OllamaClient.ChatMessage> history) {
            var reply = new CompletableFuture<OllamaClient.Reply>();
            calls.add(new ControlledCall(context, question, List.copyOf(history), reply));
            return reply;
        }
        @Override public void close() { calls.forEach(call -> call.reply().cancel(true)); }
    }

    private static void replaceProvider(MarkAIMod mod, LlmProvider provider) {
        try {
            var field = MarkAIMod.class.getDeclaredField("ollama"); field.setAccessible(true); field.set(mod, provider);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }

    private static int askAutomatic(MarkAIMod mod, ServerPlayer owner) {
        try {
            var method = MarkAIMod.class.getDeclaredMethod("ask", ServerPlayer.class, String.class, boolean.class);
            method.setAccessible(true);
            return (int) method.invoke(mod, owner, "Поделись коротким впечатлением о том, что видишь сейчас.", true);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }

    private static void updateSetting(MarkAIMod mod, ServerPlayer owner, String key, String value) {
        try {
            var method = MarkAIMod.class.getDeclaredMethod("updateSetting", ServerPlayer.class, String.class, String.class);
            method.setAccessible(true); method.invoke(mod, owner, key, value);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }

    private static JsonObject uiSnapshot(MarkAIMod mod, ServerPlayer owner) {
        try {
            var method = MarkAIMod.class.getDeclaredMethod("uiSnapshot", ServerPlayer.class); method.setAccessible(true);
            return JsonParser.parseString((String) method.invoke(mod, owner)).getAsJsonObject();
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }

    private static boolean pending(MarkAIMod mod, UUID owner) {
        try {
            var sessions = MarkAIMod.class.getDeclaredField("aiSessions"); sessions.setAccessible(true);
            Object session = ((Map<?, ?>) sessions.get(mod)).get(TestCompanions.selected(mod, owner).id);
            var field = session.getClass().getDeclaredField("pending"); field.setAccessible(true);
            return field.get(session) != null;
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }

    private static JsonObject savedCompanion(Path state, UUID owner) {
        try {
            for (var entry : JsonParser.parseString(Files.readString(state)).getAsJsonArray()) {
                JsonObject companion = entry.getAsJsonObject();
                if (companion.get("owner").getAsString().equals(owner.toString())) return companion;
            }
            throw new IllegalStateException("Missing owner state " + owner);
        } catch (IOException error) { throw new RuntimeException(error); }
    }

    private static void resetCooldown(MarkAIMod mod, UUID owner) {
        try {
            var sessionsField = MarkAIMod.class.getDeclaredField("aiSessions"); sessionsField.setAccessible(true);
            Object session = ((Map<?, ?>) sessionsField.get(mod)).get(TestCompanions.selected(mod, owner).id);
            var nextRequestField = session.getClass().getDeclaredField("nextRequest"); nextRequestField.setAccessible(true);
            nextRequestField.setLong(session, 0);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }

    private static MarkAIMod.Companion currentCompanion(MarkAIMod mod, UUID owner) {
        try {
            return TestCompanions.selected(mod, owner);
        } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
    }

    private static JsonObject coordinates(BlockPos position) {
        JsonObject result = new JsonObject();
        result.addProperty("x", position.getX()); result.addProperty("y", position.getY()); result.addProperty("z", position.getZ());
        return result;
    }

    private static JsonObject latestObservation(JsonObject request) {
        for (var entry : request.getAsJsonArray("messages")) {
            JsonObject message = entry.getAsJsonObject();
            String content = message.get("content").getAsString();
            int start = content.indexOf("CURRENT_GAME_STATE: ");
            if (message.get("role").getAsString().equals("system") && start >= 0) {
                return JsonParser.parseString(content.substring(start + "CURRENT_GAME_STATE: ".length(),
                    content.indexOf("\nrequested_action=", start))).getAsJsonObject();
            }
        }
        throw new IllegalStateException("Missing fresh observation message");
    }

    private static JsonObject journalPair(Path journal, String entityUuid, String question) {
        try {
            if (!Files.exists(journal)) return null;
            var records = new java.util.ArrayList<JsonObject>();
            for (String line : Files.readAllLines(journal)) {
                try { records.add(JsonParser.parseString(line).getAsJsonObject()); }
                catch (RuntimeException ignored) { /* The asynchronous writer may still be appending this line. */ }
            }
            for (JsonObject request : records) {
                if (!request.get("type").getAsString().equals("request") || !request.get("entity_uuid").getAsString().equals(entityUuid)
                        || !request.get("question").getAsString().equals(question)) continue;
                for (JsonObject response : records) {
                    if (response.get("type").getAsString().equals("response") && response.get("request_id").equals(request.get("request_id"))) {
                        JsonObject pair = new JsonObject(); pair.add("request", request); pair.add("response", response); return pair;
                    }
                }
            }
            return null;
        } catch (IOException error) { throw new RuntimeException(error); }
    }
}
