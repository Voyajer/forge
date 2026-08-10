package forge.externalai.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.externalai.AiDecisionRequest;
import forge.externalai.AiDecisionResponse;
import forge.externalai.config.ExternalAiConfig;
import forge.externalai.config.ExternalAiConfig.FallbackBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for communicating with the external AI server.
 *
 * Uses Java's built-in HttpClient (Java 11+) for synchronous request/response.
 * Supports configurable timeout, retry with exponential backoff, and fallback behavior.
 */
public class ExternalAiHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalAiHttpClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;
    private final int retryCount;
    private final FallbackBehavior fallbackBehavior;

    /**
     * Create a client using configuration from ExternalAiConfig.
     */
    public ExternalAiHttpClient() {
        this(ExternalAiConfig.getServerUrl(), ExternalAiConfig.getTimeoutMs(),
             ExternalAiConfig.getRetryCount(), ExternalAiConfig.getFallbackBehavior());
    }

    /**
     * Create a client with explicit parameters.
     */
    public ExternalAiHttpClient(String baseUrl, int timeoutMs, int retryCount, FallbackBehavior fallbackBehavior) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.retryCount = retryCount;
        this.fallbackBehavior = fallbackBehavior;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Send a decision request to the AI server and receive a response.
     *
     * @param request the decision request containing board state and context
     * @return the AI's decision response
     * @throws ExternalAiException if the request fails after retries and strict mode
     */
    public AiDecisionResponse requestDecision(AiDecisionRequest request) {
        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize decision request", e);
            throw new ExternalAiException("Failed to serialize request", e);
        }

        String endpoint = baseUrl + "/decision";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        AiDecisionResponse lastResponse = null;
        Exception lastException = null;

        for (int attempt = 0; attempt <= retryCount; attempt++) {
            if (attempt > 0) {
                long backoffMs = (long) Math.pow(2, attempt - 1) * 500;
                log.warn("Retry {} for AI decision request (backoff {}ms)", attempt, backoffMs);
                try {
                    TimeUnit.MILLISECONDS.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    AiDecisionResponse decisionResponse = objectMapper.readValue(
                            response.body(), AiDecisionResponse.class);
                    log.debug("Received AI decision: {}", decisionResponse.getDecisionType());
                    return decisionResponse;
                } else {
                    log.warn("AI server returned status {} for decision request: {}",
                            response.statusCode(), response.body());
                    lastResponse = parseResponseOrNull(response.body());
                }
            } catch (JsonProcessingException e) {
                lastException = e;
                log.error("Failed to deserialize AI response", e);
                break; // No point retrying deserialization errors
            } catch (IOException e) {
                lastException = e;
                log.warn("IO error on AI decision request (attempt {}): {}", attempt + 1, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastException = e;
                log.warn("Interrupted while waiting for AI decision");
                break;
            }
        }

        // All retries exhausted
        if (lastResponse != null) {
            return lastResponse;
        }

        log.error("All {} retries exhausted for AI decision request", retryCount);
        handleFailure(lastException);
        return null;
    }

    /**
     * Notify the AI server that a game has started.
     */
    public void notifyGameStart(String gameId, String decklistJson) {
        String endpoint = baseUrl + "/game/start";
        String body = String.format(
                "{\"gameId\":\"%s\",\"decklist\":%s}",
                escapeJson(gameId), decklistJson);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Notified AI server of game start: {}", gameId);
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to notify AI of game start: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Notify the AI server that a game has ended.
     */
    public void notifyGameEnd(String gameId, String result) {
        String endpoint = baseUrl + "/game/end";
        String body = String.format(
                "{\"gameId\":\"%s\",\"result\":\"%s\"}",
                escapeJson(gameId), escapeJson(result));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Notified AI server of game end: {} -> {}", gameId, result);
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to notify AI of game end: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Check if the AI server is available.
     */
    public boolean isServerAvailable() {
        String endpoint = baseUrl + "/health";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException | InterruptedException e) {
            log.debug("AI server health check failed: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Handle failure after all retries exhausted based on fallback behavior.
     */
    private void handleFailure(Exception lastException) {
        switch (fallbackBehavior) {
            case RANDOM:
                log.info("Fallback: returning random decision");
                // Caller will handle random selection
                break;
            case PASS:
                log.info("Fallback: returning pass decision");
                // Caller will handle pass
                break;
            case CANCEL:
                log.info("Fallback: cancelling decision");
                // Caller will handle cancel
                break;
        }

        if (ExternalAiConfig.isStrictMode()) {
            throw new ExternalAiException("AI server unavailable after retries", lastException);
        }
    }

    private AiDecisionResponse parseResponseOrNull(String body) {
        try {
            return objectMapper.readValue(body, AiDecisionResponse.class);
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse AI response from non-2xx status: {}", e.getMessage());
            return null;
        }
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Exception thrown when the external AI request fails.
     */
    public static class ExternalAiException extends RuntimeException {
        public ExternalAiException(String message, Throwable cause) {
            super(message, cause);
        }

        public ExternalAiException(String message) {
            super(message);
        }
    }
}
