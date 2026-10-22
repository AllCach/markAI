package dev.markai.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback HTTP checks; no Ollama installation, model or external connection is needed. */
public final class OllamaClientTest {
    private static int checks;
    private static final String VALID = "{\"action\":\"follow\",\"say\":\"Игрок, иду!\"}";

    public static void main(String[] args) throws Exception {
        parsingChecks();
        endpointChecks();
        networkChecks();
        System.out.println("OllamaClient: " + checks + " checks passed");
    }

    private static void parsingChecks() throws Exception {
        check("shared dialogue has its own persona and one current request", () -> {
            String question="Марк, что думаешь?";
            var messages=OllamaClient.dialogueMessages("{\"conversation_mode\":\"group\",\"group_turn\":\"player_reply\"}",question,
                List.of(new OllamaClient.ChatMessage("user","Walk: Я рядом.")));
            String system=messages.get(0).getAsJsonObject().get("content").getAsString();
            require(system.contains("ОБЩИЙ чат")&&system.contains("action строго say"),"Group speech has no movement authority");
            require(!system.contains(question)&&messages.size()==3,"The current user text occurs only once after the labelled peer history");
        });
        check("manual current turn is a single user message after completed history", () -> {
            String current = "UNIQUE_CURRENT_TURN: а с чем? у нас первый день хардкора";
            String previous = "{\"action\":\"say\",\"say\":\"Ты снова решил поздравить меня?\"}";
            var messages = OllamaClient.dialogueMessages("{}", current, List.of(
                new OllamaClient.ChatMessage("user", "бро"), new OllamaClient.ChatMessage("assistant", previous)));
            require(messages.size() == 4, "Completed exchange and one new turn preserved");
            require(!messages.get(0).getAsJsonObject().get("content").getAsString().contains(current),
                "Current user words cannot also appear as a preceding system statement");
            require(messages.get(2).getAsJsonObject().get("content").getAsString().equals(previous),
                "Previous model mistake remains an assistant statement, not a user fact");
            require(messages.get(3).getAsJsonObject().get("content").getAsString().equals(current),
                "Latest clarification is the final user turn");
        });
        check("repair instruction keeps rejected text separate from the latest player turn", () -> {
            var context = new JsonObject();
            var repair = new JsonObject();
            repair.addProperty("rejected_reply", "Ха! Ты снова решил поздравить меня? Что-то случилось?");
            context.add("dialogue_repair", repair);
            var messages = OllamaClient.dialogueMessages(context.toString(), "А с чем?", List.of());
            String system = messages.get(0).getAsJsonObject().get("content").getAsString();
            require(system.contains("была отклонена") && system.contains("Это не новый ход беседы"),
                "Rejected generation is explicitly excluded from the conversation");
            require(messages.size() == 2 && messages.get(1).getAsJsonObject().get("content").getAsString().equals("А с чем?"),
                "Repair does not invent a followup question or assistant exchange");
        });
        check("species identity is injected from persisted state, not a shared wolf-only prompt", () -> {
            String chickenContext = speciesContext("Mark", "minecraft:chicken");
            String wolfContext = speciesContext("Walk", "minecraft:wolf");
            String chickenSystem = OllamaClient.dialogueMessages(chickenContext, "Ты вообще кто?", List.of())
                .get(0).getAsJsonObject().get("content").getAsString();
            String wolfSystem = OllamaClient.dialogueMessages(wolfContext, "Ты вообще кто?", List.of())
                .get(0).getAsJsonObject().get("content").getAsString();
            require(chickenSystem.contains("Ты курица-компаньон, а НЕ волк")
                    && !chickenSystem.contains("Ты — Марк, разумный говорящий волк")
                    && !chickenSystem.contains("Твоё физическое тело — приручённый волк"),
                "Chicken receives no contradictory wolf-only base persona");
            require(wolfSystem.contains("Ты волк-компаньон") && wolfSystem.contains("можешь атаковать допустимую цель"),
                "Wolf retains its species-specific body and gameplay capabilities");
        });
        check("zombie and sword facts respect the selected species", () -> {
            String chicken = OllamaClient.dialogueMessages(
                speciesContext("Mark", "minecraft:chicken"),
                "Может ли зомби убить тебя мечом?", List.of()).get(0).getAsJsonObject().get("content").getAsString();
            String wolf = OllamaClient.dialogueMessages(
                speciesContext("Walk", "minecraft:wolf"),
                "Может ли зомби убить тебя мечом?", List.of()).get(0).getAsJsonObject().get("content").getAsString();
            require(chicken.contains("не утверждай, что можешь кусать врагов")
                    && !chicken.contains("Как приручённый волк, ты можешь кусать"),
                "Chicken receives no wolf combat factual rule");
            require(wolf.contains("Как приручённый волк, ты можешь кусать враждебных существ"),
                "Wolf receives its real combat factual rule");
        });
        check("ambient speech uses its own prompt and authoritative night phase", () -> {
            var messages = OllamaClient.dialogueMessages(
                "{\"conversation_mode\":\"ambient\",\"environment\":{\"time_of_day\":17000,\"raining\":false},\"recent_lines\":[]}",
                "Коротко прокомментируй текущую обстановку.", List.of());
            String system = messages.get(0).getAsJsonObject().get("content").getAsString();
            require(system.contains("сейчас ночь") && system.contains("Дождя сейчас нет"), "Ambient facts describe the actual night and weather");
            require(system.contains("Повод для самостоятельной реплики") && !system.contains("Текущее сообщение игрока"),
                "Unsolicited speech cannot be prompted as an old player question");
            require(messages.size() == 2 && system.contains("recent_lines"), "Ambient speech has its own bounded standalone context");
        });
        check("ambient Nether commentary does not claim ordinary daytime or rain", () -> {
            var messages = OllamaClient.dialogueMessages(
                "{\"conversation_mode\":\"ambient\",\"environment\":{\"dimension\":\"minecraft:the_nether\",\"time_of_day\":6000,\"raining\":false}}",
                "Прокомментируй текущую обстановку.", List.of());
            String system = messages.get(0).getAsJsonObject().get("content").getAsString();
            require(system.contains("нет обычного неба") && !system.contains("сейчас день"), "A Nether clock cannot be turned into a visible sun observation");
        });
        check("valid Russian reply", () -> {
            var reply = OllamaClient.parseReply(VALID);
            require(reply.action().equals("follow") && reply.say().equals("Игрок, иду!"), "Reply preserved");
        });
        check("all allowed actions", () -> {
            for (String action : List.of("follow", "stay", "flee", "wander", "look_at_player", "say", "idle"))
                require(OllamaClient.parseReply("{\"action\":\"" + action + "\",\"say\":\"Хорошо\"}").action().equals(action), "Allowed action");
        });
        rejects("empty conversational reply", "{\"action\":\"stay\",\"say\":\"\"}");
        rejects("unknown action", "{\"action\":\"execute\",\"say\":\"\"}");
        rejects("Minecraft command action", "{\"action\":\"/kill @a\",\"say\":\"\"}");
        rejects("extra command field", "{\"action\":\"idle\",\"say\":\"\",\"command\":\"op\"}");
        rejects("duplicate action", "{\"action\":\"follow\",\"action\":\"idle\",\"say\":\"\"}");
        rejects("missing say", "{\"action\":\"idle\"}");
        rejects("missing action", "{\"say\":\"hello\"}");
        rejects("null say", "{\"action\":\"idle\",\"say\":null}");
        rejects("numeric say", "{\"action\":\"idle\",\"say\":123}");
        rejects("boolean action", "{\"action\":true,\"say\":\"\"}");
        rejects("array instead of object", "[]");
        rejects("single quoted JSON", "{'action':'idle','say':''}");
        rejects("JSON comment", "{\"action\":\"idle\",/*x*/\"say\":\"\"}");
        rejects("trailing JSON object", VALID + "{}");
        rejects("Markdown wrapper", "```json\n" + VALID + "\n```");
        rejects("newline in say", "{\"action\":\"say\",\"say\":\"a\\nb\"}");
        rejects("NUL in say", "{\"action\":\"say\",\"say\":\"a\\u0000b\"}");
        rejects("Minecraft formatting", "{\"action\":\"say\",\"say\":\"\\u00a7cRed\"}");
        rejects("bidi control", "{\"action\":\"say\",\"say\":\"a\\u202eb\"}");
        rejects("lone surrogate", "{\"action\":\"say\",\"say\":\"\\ud800\"}");
        rejects("overlong say", "{\"action\":\"say\",\"say\":\"" + "x".repeat(241) + "\"}");
        rejects("overlong JSON", " ".repeat(4097));
        rejects("null JSON", null);
    }

    private static String speciesContext(String name, String type) {
        JsonObject context = new JsonObject();
        JsonObject companion = new JsonObject();
        companion.addProperty("name", name);
        companion.addProperty("type", type);
        context.add("companion", companion);
        return context.toString();
    }

    private static void endpointChecks() throws Exception {
        for (String url : List.of("https://127.0.0.1:11434", "http://example.com:11434",
                "http://127.0.0.2:11434", "http://127.1:11434", "http://localhost.example.com:11434",
                "http://user@127.0.0.1:11434", "http://127.0.0.1:11434?x=1",
                "http://127.0.0.1:11434/#x", "http://127.0.0.1:11434/proxy", "http://127.0.0.1:0")) {
            check("reject endpoint " + url, () -> expectFailure(() -> {
                try (var ignored = new OllamaClient(url, "qwen3:8b", Duration.ofSeconds(2))) {}
            }));
        }
        check("accept literal IPv6 loopback", () -> {
            try (var ignored = new OllamaClient("http://[::1]:11434/", "qwen3:8b", Duration.ofSeconds(2))) {}
        });
        check("reject invalid timeout", () -> expectFailure(() -> {
            try (var ignored = new OllamaClient("http://127.0.0.1:11434", "qwen3:8b", Duration.ZERO)) {}
        }));
    }

    private static void networkChecks() throws Exception {
        try (var fake = new FakeOllama(); var client = fake.client(Duration.ofSeconds(3))) {
            check("real asynchronous local HTTP and request contract", () -> {
                var history = List.of(new OllamaClient.ChatMessage("user", "Привет"),
                    new OllamaClient.ChatMessage("assistant", "{\"action\":\"say\",\"say\":\"Привет!\"}"));
                var reply = client.ask("{\"player\":{\"name\":\"DifferentPlayer\"}}", "Марк, сюда!", history)
                    .get(4, TimeUnit.SECONDS);
                require(reply.action().equals("follow"), "HTTP response parsed");
                JsonObject request = JsonParser.parseString(fake.request.get()).getAsJsonObject();
                require(request.get("model").getAsString().equals("qwen3:8b"), "Model passed");
                require(!request.get("stream").getAsBoolean() && !request.get("think").getAsBoolean(), "Nonstreaming nonthinking request");
                require(request.get("keep_alive").getAsString().equals("2m"), "Keep-alive bounded");
                require(request.getAsJsonObject("options").get("num_ctx").getAsInt() == 8192, "Context bound");
                require(request.getAsJsonObject("options").get("num_predict").getAsInt() == 160, "Generation bound");
                JsonArray messages = request.getAsJsonArray("messages");
                require(messages.size() == 4, "History and latest observation included");
                require(messages.get(0).getAsJsonObject().get("content").getAsString().contains("DifferentPlayer"), "Persona and current context share the system slot");
                require(messages.get(3).getAsJsonObject().get("content").getAsString().equals("Марк, сюда!"), "Player message included");
                JsonObject schema = request.getAsJsonObject("format");
                require(!schema.get("additionalProperties").getAsBoolean(), "Strict schema");
                var allowedActions = schema.getAsJsonObject("properties").getAsJsonObject("action").getAsJsonArray("enum");
                require(allowedActions.size() == 1 && allowedActions.get(0).getAsString().equals("follow"), "Action limited to explicit intent");
                require(schema.getAsJsonObject("properties").getAsJsonObject("say").get("maxLength").getAsInt() == 240, "Say bound");
                require(fake.path.get().equals("/api/chat") && fake.method.get().equals("POST"), "API endpoint");
            });
            check("localhost is normalized to literal loopback", () -> {
                try (var local = new OllamaClient("http://localhost:" + fake.port(), "qwen3:8b", Duration.ofSeconds(3))) {
                    local.ask("{}", "Привет", List.of()).get(4, TimeUnit.SECONDS);
                    require(fake.host.get().startsWith("127.0.0.1:"), "No localhost DNS lookup");
                }
            });
            check("thinking mode uses a bounded generation budget", () -> {
                try (var reasoning = new OllamaClient("http://127.0.0.1:" + fake.port(), "qwen3:8b", Duration.ofSeconds(3), true)) {
                    reasoning.ask("{}", "Сюда!", List.of()).get(4, TimeUnit.SECONDS);
                    var request = JsonParser.parseString(fake.request.get()).getAsJsonObject();
                    require(request.get("think").getAsBoolean(), "Thinking enabled for quality mode");
                    require(request.getAsJsonObject("options").get("num_predict").getAsInt() == 1024, "Thinking token budget bounded");
                }
            });
            check("persistent memories reach the model with an empty recent history", () -> {
                String context = """
                    {"companion":{"name":"walk","type":"minecraft:wolf"},
                     "memory":{"player_reports":[{"kind":"plan","quote":"Хочу дом из березы с синей крышей.","game_time":1200}],
                     "observed_events":[{"key":"owner_defense","text":"walk защитил владельца от зомби.","game_time":2400}]}}
                    """;
                client.ask(context, "Какой дом я задумал?", List.of()).get(4, TimeUnit.SECONDS);
                var messages = JsonParser.parseString(fake.request.get()).getAsJsonObject().getAsJsonArray("messages");
                require(messages.size() == 2, "No fabricated conversation added for saved memories");
                String system = messages.get(0).getAsJsonObject().get("content").getAsString();
                int observationStart = system.indexOf("\nCURRENT_GAME_STATE: ") + "\nCURRENT_GAME_STATE: ".length();
                int observationEnd = system.indexOf("\nrequested_action=", observationStart);
                var observed = JsonParser.parseString(system.substring(observationStart, observationEnd)).getAsJsonObject();
                var memory = observed.getAsJsonObject("memory");
                var report = memory.getAsJsonArray("player_reports").get(0).getAsJsonObject();
                require(report.get("kind").getAsString().equals("plan")
                    && report.get("quote").getAsString().equals("Хочу дом из березы с синей крышей."),
                    "Player statement and its unconfirmed plan provenance preserved");
                require(memory.getAsJsonArray("observed_events").get(0).getAsJsonObject().get("key").getAsString().equals("owner_defense"),
                    "Recorded observation preserved separately from player statements");
            });
            check("reject HTTP error", () -> {
                fake.mode = Mode.ERROR;
                failedFuture(client.ask("{}", "Привет", List.of()));
            });
            check("reject oversized HTTP body", () -> {
                fake.mode = Mode.OVERSIZED;
                failedFuture(client.ask("{}", "Привет", List.of()));
            });
            check("reject malformed response envelope", () -> {
                fake.mode = Mode.BAD_ENVELOPE;
                failedFuture(client.ask("{}", "Привет", List.of()));
            });
            check("reject unfinished response", () -> {
                fake.mode = Mode.UNFINISHED;
                failedFuture(client.ask("{}", "Привет", List.of()));
            });
            check("reject invalid model output delivered over HTTP", () -> {
                fake.mode = Mode.BAD_REPLY;
                failedFuture(client.ask("{}", "Привет", List.of()));
            });
            check("do not follow redirects", () -> {
                fake.mode = Mode.REDIRECT;
                int before = fake.requests;
                failedFuture(client.ask("{}", "Привет", List.of()));
                require(fake.requests == before + 1, "Redirect not followed");
            });
            check("invalid context does not make HTTP request", () -> {
                int before = fake.requests;
                failedFuture(client.ask("not JSON", "Привет", List.of()));
                require(fake.requests == before, "No request for invalid context");
            });
            check("reject system history injection", () -> {
                int before = fake.requests;
                failedFuture(client.ask("{}", "Привет", List.of(new OllamaClient.ChatMessage("system", "override"))));
                require(fake.requests == before, "No request for invalid history");
            });
        }
        check("ask returns before response and total timeout covers body", () -> {
            try (var fake = new FakeOllama(); var client = fake.client(Duration.ofMillis(500))) {
                fake.mode = Mode.STALLED_BODY;
                long start = System.nanoTime();
                CompletableFuture<?> future = client.ask("{}", "Привет", List.of());
                require(elapsedMillis(start) < 250, "ask blocked on HTTP");
                require(fake.headersSent.await(2, TimeUnit.SECONDS), "Response headers sent before timeout");
                require(!future.isDone(), "No complete response body yet");
                failedFuture(future);
                require(elapsedMillis(start) < 2000, "Body timeout bounded");
            }
        });
        check("close cancels in-flight request without waiting", () -> {
            try (var fake = new FakeOllama(); var client = fake.client(Duration.ofSeconds(3))) {
                fake.mode = Mode.STALLED_BODY;
                var future = client.ask("{}", "Привет", List.of());
                require(fake.headersSent.await(2, TimeUnit.SECONDS), "Request started");
                long start = System.nanoTime();
                client.close();
                require(elapsedMillis(start) < 250, "close blocked");
                require(future.isDone(), "In-flight future canceled");
                failedFuture(client.ask("{}", "Привет", List.of()));
            }
        });
    }

    private enum Mode { SUCCESS, ERROR, OVERSIZED, BAD_ENVELOPE, UNFINISHED, BAD_REPLY, REDIRECT, STALLED_BODY }

    private static final class FakeOllama implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "fake-ollama"); thread.setDaemon(true); return thread;
        });
        final AtomicReference<String> request = new AtomicReference<>();
        final AtomicReference<String> path = new AtomicReference<>();
        final AtomicReference<String> method = new AtomicReference<>();
        final AtomicReference<String> host = new AtomicReference<>();
        final CountDownLatch headersSent = new CountDownLatch(1);
        final CountDownLatch releaseBody = new CountDownLatch(1);
        volatile Mode mode = Mode.SUCCESS;
        volatile int requests;

        FakeOllama() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::respond);
            server.start();
        }
        int port() { return server.getAddress().getPort(); }
        OllamaClient client(Duration timeout) {
            return new OllamaClient("http://127.0.0.1:" + port(), "qwen3:8b", timeout);
        }
        private void respond(HttpExchange exchange) throws IOException {
            try (exchange) {
                Mode current = mode;
                requests++;
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                path.set(exchange.getRequestURI().getPath()); method.set(exchange.getRequestMethod());
                host.set(exchange.getRequestHeaders().getFirst("Host"));
                if (current == Mode.STALLED_BODY) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                    headersSent.countDown();
                    try { releaseBody.await(4, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    return;
                }
                String body = envelope(VALID, true);
                int status = 200;
                switch (current) {
                    case ERROR -> { status = 503; body = "{\"error\":\"Unavailable\"}"; }
                    case OVERSIZED -> body = "x".repeat(64 * 1024 + 1);
                    case BAD_ENVELOPE -> body = "{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":123}}";
                    case UNFINISHED -> body = envelope(VALID, false);
                    case BAD_REPLY -> body = envelope("{\"action\":\"exec\",\"say\":\"\"}", true);
                    case REDIRECT -> { status = 302; exchange.getResponseHeaders().set("Location", "/redirect-target"); }
                    default -> { }
                }
                byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, encoded.length);
                exchange.getResponseBody().write(encoded);
            } catch (IOException error) {
                // Cancellation and response-size rejection may close the socket before the fixture finishes writing.
                if (mode != Mode.STALLED_BODY && mode != Mode.OVERSIZED) throw error;
            }
        }
        @Override public void close() {
            releaseBody.countDown(); server.stop(0); executor.shutdownNow();
        }
    }

    private static String envelope(String content, boolean done) {
        JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", content);
        JsonObject response = new JsonObject(); response.addProperty("done", done); response.add("message", message);
        return response.toString();
    }
    private static long elapsedMillis(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static void rejects(String name, String content) throws Exception {
        check(name, () -> expectFailure(() -> OllamaClient.parseReply(content)));
    }
    private static void failedFuture(CompletableFuture<?> future) throws Exception {
        try { future.get(4, TimeUnit.SECONDS); throw new AssertionError("Expected failed future"); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException expected) { }
    }
    private static void expectFailure(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void check(String name, Checked action) throws Exception {
        try { action.run(); checks++; }
        catch (Throwable error) { throw new AssertionError("Failed check: " + name, error); }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
