package dev.markai.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/** Per-install settings: never place API credentials inside a shared world save. */
public final class AIConfig {
    public String provider = "ollama";
    public String ollamaUrl = "http://127.0.0.1:11434";
    public String ollamaModel = "qwen3:8b";
    public String baseUrl = "";
    public String model = "";
    public String authentication = "none";
    public String apiKeyEnvironment = "MARKAI_API_KEY";
    public boolean remoteAIEnabled = false;
    public boolean structuredOutputs = true;
    public int timeoutSeconds = 120;
    public int requestCooldownSeconds = 5;
    public boolean thinking = true;
    public double temperature = 0.35;
    public int maxOutputTokens = 1024;
    public int retries = 1;
    public int maxDialogueLength = 240;
    public int automaticConversationSeconds = 60;
    public boolean memoryEnabled = true;
    public boolean hudVisible = true;
    public double followDistance = 4.0;
    public boolean defensiveBehavior = true;
    public boolean debugMode = false;
    public boolean voiceEnabled = false;
    public transient boolean recoveredFromInvalid;

    public static AIConfig load(Path path) throws IOException {
        var gson = new GsonBuilder().setPrettyPrinting().setStrictness(Strictness.STRICT).create();
        if (!Files.exists(path)) {
            if (path.toAbsolutePath().getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
            AIConfig config = new AIConfig();
            Files.writeString(path, gson.toJson(config), StandardCharsets.UTF_8);
            return config;
        }
        try {
            if (Files.size(path) > 64 * 1024) throw new IllegalArgumentException("Config size limit exceeded");
            AIConfig config = gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), AIConfig.class);
            if (config == null) throw new IllegalArgumentException("Missing config object");
            config.validate();
            return config;
        } catch (RuntimeException | java.nio.charset.MalformedInputException malformed) {
            // Preserve the exact file for review without ever including its contents in a log/error.
            Path backup = path.resolveSibling(path.getFileName() + ".invalid-" + UUID.randomUUID() + ".bak");
            Files.copy(path, backup);
            AIConfig defaults = new AIConfig();
            Path temporary = path.resolveSibling(path.getFileName() + ".recovery-" + UUID.randomUUID() + ".tmp");
            Files.writeString(temporary, gson.toJson(defaults), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            defaults.recoveredFromInvalid = true;
            return defaults;
        }
    }

    public void validate() {
        if (provider == null) throw new IllegalArgumentException("Missing AI provider");
        provider = provider.strip().toLowerCase(Locale.ROOT);
        if (!(provider.equals("ollama") || provider.equals("openai-compatible")))
            throw new IllegalArgumentException("Unknown AI provider");
        if (timeoutSeconds < 5 || timeoutSeconds > 300 || requestCooldownSeconds < 2 || requestCooldownSeconds > 60
                || !Double.isFinite(temperature) || temperature < 0 || temperature > 2
                || maxOutputTokens < 64 || maxOutputTokens > 4096 || retries < 0 || retries > 3
                || maxDialogueLength < 32 || maxDialogueLength > 240
                || automaticConversationSeconds < 0 || automaticConversationSeconds > 3600
                || !Double.isFinite(followDistance) || followDistance < 2 || followDistance > 16)
            throw new IllegalArgumentException("Invalid MarkAI configuration limits");
        if (authentication == null || !(authentication.equals("none") || authentication.equals("bearer")))
            throw new IllegalArgumentException("Authentication must be none or bearer");
        if (apiKeyEnvironment == null || !apiKeyEnvironment.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("Invalid API key environment variable name");
        if (effectiveModel() == null || !effectiveModel().matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,127}"))
            throw new IllegalArgumentException("Invalid model identifier");
        if (provider.equals("openai-compatible")) OpenAiCompatibleProvider.endpoint(effectiveBaseUrl(), remoteAIEnabled);
        else {
            // The native Ollama transport remains local-only, including when remote AI is enabled.
            java.net.URI uri;
            try { uri = java.net.URI.create(effectiveBaseUrl()); }
            catch (RuntimeException error) { throw new IllegalArgumentException("Invalid Ollama endpoint"); }
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !(host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost") || host.equals("[::1]") || host.equals("::1"))
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
                throw new IllegalArgumentException("Ollama requires a local HTTP root endpoint");
            if (!authentication.equals("none")) throw new IllegalArgumentException("Native local Ollama does not use bearer authentication");
        }
    }

    public String effectiveBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? ollamaUrl : baseUrl;
    }

    public String effectiveModel() {
        return model == null || model.isBlank() ? ollamaModel : model;
    }

    public LlmProvider createProvider() {
        validate();
        LlmProvider transport;
        if (provider.equals("ollama")) transport = new OllamaClient(effectiveBaseUrl(), effectiveModel(),
            Duration.ofSeconds(timeoutSeconds), thinking, temperature, maxOutputTokens);
        else {
            String key = "";
            if (authentication.equals("bearer")) {
                key = System.getenv(apiKeyEnvironment);
                if (key == null || key.isBlank()) throw new IllegalArgumentException("AI API key environment variable is not set");
            }
            transport = new OpenAiCompatibleProvider(effectiveBaseUrl(), effectiveModel(), key,
                Duration.ofSeconds(timeoutSeconds), temperature, maxOutputTokens, remoteAIEnabled, structuredOutputs);
        }
        LlmProvider retried = retries == 0 ? transport : new RetryingLlmProvider(transport, retries);
        return new RepetitionGuardLlmProvider(retried, Duration.ofSeconds(timeoutSeconds));
    }
}
