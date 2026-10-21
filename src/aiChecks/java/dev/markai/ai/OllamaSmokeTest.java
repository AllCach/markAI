package dev.markai.ai;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Explicit real-model check; intentionally separate from offline build/check. */
public final class OllamaSmokeTest {
    public static void main(String[] args) throws Exception {
        String context = """
            {"companion":{"name":"walk","type":"minecraft:wolf","health":40,"max_health":40},
             "player":{"name":"TestOwner","distance_blocks":8},"nearby_hostiles":[],
             "mode":"stay","dimension":"minecraft:overworld"}
            """;
        var results = new ArrayList<Map<String, Object>>();
        var history = new ArrayList<OllamaClient.ChatMessage>();
        var compact = new GsonBuilder().disableHtmlEscaping().create();
        try (var client = new OllamaClient("http://127.0.0.1:11434", "qwen3:8b", Duration.ofSeconds(90))) {
            for (var sample : List.of(
                    Map.entry("Привет! Представься одним предложением.", "say"),
                    Map.entry("walk, следуй за мной.", "follow"),
                    Map.entry("Оставайся здесь. Не следуй за мной.", "stay"))) {
                long start = System.nanoTime();
                var reply = client.ask(context, sample.getKey(), history).get(95, TimeUnit.SECONDS);
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                results.add(Map.of("prompt", sample.getKey(), "expected_action", sample.getValue(),
                    "reply", reply, "milliseconds", millis));
                Files.createDirectories(Path.of("build"));
                Files.writeString(Path.of("build", "ollama-smoke.json"),
                    new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(results));
                System.out.println("qwen3:8b " + millis + " ms: " + compact.toJson(reply));
                if (!reply.action().equals(sample.getValue()) || reply.say().isBlank())
                    throw new AssertionError("Unexpected response to " + sample.getKey() + ": " + reply);
                history.add(new OllamaClient.ChatMessage("user", sample.getKey()));
                history.add(new OllamaClient.ChatMessage("assistant", compact.toJson(reply)));
            }
        }
        System.out.println("Real Ollama: 3 structured, multi-turn responses passed");
    }
}
