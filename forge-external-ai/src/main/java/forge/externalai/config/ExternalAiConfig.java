package forge.externalai.config;

/**
 * Configuration for the external AI server connection.
 *
 * Priority for server URL: environment variable > Forge preference > default.
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

    /**
     * Get the AI server URL.
     * Priority: EXTERNAL_AI_URL env var > Forge preference > default.
     */
    public static String getServerUrl() {
        String envUrl = System.getenv("EXTERNAL_AI_URL");
        if (envUrl != null && !envUrl.isEmpty()) {
            return envUrl;
        }
        // TODO: Read from ForgePreferences when available
        // String prefUrl = ForgePreferences.getExternalAiUrl();
        // if (prefUrl != null && !prefUrl.isEmpty()) { return prefUrl; }
        return DEFAULT_SERVER_URL;
    }

    /**
     * Get the timeout in milliseconds for each HTTP request.
     */
    public static int getTimeoutMs() {
        String env = System.getenv("EXTERNAL_AI_TIMEOUT_MS");
        if (env != null) {
            try {
                return Integer.parseInt(env);
            } catch (NumberFormatException e) {
                // fall through to default
            }
        }
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * Get the number of retry attempts for transient failures.
     */
    public static int getRetryCount() {
        String env = System.getenv("EXTERNAL_AI_RETRY_COUNT");
        if (env != null) {
            try {
                return Integer.parseInt(env);
            } catch (NumberFormatException e) {
                // fall through to default
            }
        }
        return DEFAULT_RETRY_COUNT;
    }

    /**
     * Get the fallback behavior when the AI server is unavailable.
     */
    public static FallbackBehavior getFallbackBehavior() {
        String env = System.getenv("EXTERNAL_AI_FALLBACK");
        if (env != null) {
            try {
                return FallbackBehavior.valueOf(env.toUpperCase());
            } catch (IllegalArgumentException e) {
                // fall through to default
            }
        }
        return DEFAULT_FALLBACK;
    }

    /**
     * When true, the game pauses if the AI cannot be reached instead of using fallback.
     */
    public static boolean isStrictMode() {
        String env = System.getenv("EXTERNAL_AI_STRICT");
        if (env != null) {
            return Boolean.parseBoolean(env);
        }
        return DEFAULT_STRICT_MODE;
    }
}
