package forge.externalai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Configuration for the external AI server connection.
 *
 * Priority: environment variable > config file (external-ai.json) > default.
 */
public class ExternalAiConfig {

    public enum FallbackBehavior {
        RANDOM,
        PASS,
        CANCEL
    }

    private static final String DEFAULT_SERVER_URL = "http://localhost:8080";
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_RETRY_COUNT = 3;
    private static final boolean DEFAULT_STRICT_MODE = false;
    private static final FallbackBehavior DEFAULT_FALLBACK = FallbackBehavior.PASS;

    private static final String CONFIG_FILE = "/external-ai.json";
    private static volatile JsonNode cachedConfig;
    private static volatile boolean configLoadFailed;

    /**
     * Load configuration from the bundled external-ai.json resource.
     * Result is cached after the first successful load.
     */
    private static JsonNode loadConfig() {
        if (configLoadFailed) {
            return null;
        }
        if (cachedConfig != null) {
            return cachedConfig;
        }
        synchronized (ExternalAiConfig.class) {
            if (configLoadFailed) {
                return null;
            }
            if (cachedConfig != null) {
                return cachedConfig;
            }
            try (InputStream is = ExternalAiConfig.class.getResourceAsStream(CONFIG_FILE)) {
                if (is != null) {
                    ObjectMapper mapper = new ObjectMapper();
                    cachedConfig = mapper.readTree(is);
                } else {
                    configLoadFailed = true;
                }
            } catch (IOException e) {
                configLoadFailed = true;
            }
            return cachedConfig;
        }
    }

    /**
     * Get the AI server URL.
     * Priority: EXTERNAL_AI_URL env var > config file > default.
     */
    public static String getServerUrl() {
        String envUrl = System.getenv("EXTERNAL_AI_URL");
        if (envUrl != null && !envUrl.isEmpty()) {
            return envUrl;
        }
        JsonNode config = loadConfig();
        if (config != null && config.has("server") && config.get("server").has("url")) {
            String configUrl = config.get("server").get("url").asText();
            if (!configUrl.isEmpty()) {
                return configUrl;
            }
        }
        return DEFAULT_SERVER_URL;
    }

    /**
     * Get the timeout in milliseconds for each HTTP request.
     * Priority: EXTERNAL_AI_TIMEOUT_MS env var > config file > default.
     */
    public static int getTimeoutMs() {
        String env = System.getenv("EXTERNAL_AI_TIMEOUT_MS");
        if (env != null) {
            try {
                return Integer.parseInt(env);
            } catch (NumberFormatException e) {
                // fall through to config file
            }
        }
        JsonNode config = loadConfig();
        if (config != null && config.has("server") && config.get("server").has("timeout_ms")) {
            return config.get("server").get("timeout_ms").asInt(DEFAULT_TIMEOUT_MS);
        }
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * Get the number of retry attempts for transient failures.
     * Priority: EXTERNAL_AI_RETRY_COUNT env var > config file > default.
     */
    public static int getRetryCount() {
        String env = System.getenv("EXTERNAL_AI_RETRY_COUNT");
        if (env != null) {
            try {
                return Integer.parseInt(env);
            } catch (NumberFormatException e) {
                // fall through to config file
            }
        }
        JsonNode config = loadConfig();
        if (config != null && config.has("server") && config.get("server").has("retry_count")) {
            return config.get("server").get("retry_count").asInt(DEFAULT_RETRY_COUNT);
        }
        return DEFAULT_RETRY_COUNT;
    }

    /**
     * Get the fallback behavior when the AI server is unavailable.
     * Priority: EXTERNAL_AI_FALLBACK env var > config file > default.
     */
    public static FallbackBehavior getFallbackBehavior() {
        String env = System.getenv("EXTERNAL_AI_FALLBACK");
        if (env != null) {
            try {
                return FallbackBehavior.valueOf(env.toUpperCase());
            } catch (IllegalArgumentException e) {
                // fall through to config file
            }
        }
        JsonNode config = loadConfig();
        if (config != null && config.has("behavior") && config.get("behavior").has("fallback")) {
            String fallbackStr = config.get("behavior").get("fallback").asText("");
            if (!fallbackStr.isEmpty()) {
                try {
                    return FallbackBehavior.valueOf(fallbackStr.toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    // fall through to default
                }
            }
        }
        return DEFAULT_FALLBACK;
    }

    /**
     * When true, the game pauses if the AI cannot be reached instead of using fallback.
     * Priority: EXTERNAL_AI_STRICT env var > config file > default.
     */
    public static boolean isStrictMode() {
        String env = System.getenv("EXTERNAL_AI_STRICT");
        if (env != null) {
            return Boolean.parseBoolean(env);
        }
        JsonNode config = loadConfig();
        if (config != null && config.has("behavior") && config.get("behavior").has("strict_mode")) {
            return config.get("behavior").get("strict_mode").asBoolean(DEFAULT_STRICT_MODE);
        }
        return DEFAULT_STRICT_MODE;
    }
}
