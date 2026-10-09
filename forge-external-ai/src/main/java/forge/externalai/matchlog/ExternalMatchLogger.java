package forge.externalai.matchlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import forge.externalai.config.ExternalAiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link MatchObserver} that forwards match/game/sideboard/move events
 * to the external AI (Python) server over HTTP.
 *
 * <p>All HTTP calls are dispatched to a single background thread so they never
 * block the game thread. If the server is unreachable the events are dropped
 * (logging is best-effort and must never disrupt gameplay).
 */
public class ExternalMatchLogger implements MatchObserver {

    private static final Logger log = LoggerFactory.getLogger(ExternalMatchLogger.class);

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor;

    public ExternalMatchLogger() {
        this(ExternalAiConfig.getServerUrl(), ExternalAiConfig.getTimeoutMs());
    }

    public ExternalMatchLogger(String baseUrl, int timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder()
                // HTTP/1.1: uvicorn/h11 does not support the h2c upgrade that
                // Java's HttpClient sends by default for cleartext http:// URLs.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(this.timeout)
                .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "external-ai-match-logger");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------
    // MatchObserver
    // ------------------------------------------------------------------

    @Override
    public void onMatchStart(String matchId, List<String> players, String format, int gamesToWin, Map<String, String> playerTypes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("matchId", matchId);
        body.put("players", players);
        body.put("format", format);
        body.put("gamesToWin", gamesToWin);
        if (playerTypes != null && !playerTypes.isEmpty()) {
            body.put("playerTypes", playerTypes);
        }
        post("/match/start", body);
    }

    @Override
    public void onGameStart(String matchId, String gameId, int gameNumber, Map<String, List<String>> decklists) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("matchId", matchId);
        body.put("gameId", gameId);
        body.put("gameNumber", gameNumber);
        body.put("decklists", decklists);
        post("/game/start/match", body);
    }

    @Override
    public void onSideboard(String matchId, String gameId, String player, List<String> mainToSide, List<String> sideToMain) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("matchId", matchId);
        body.put("gameId", gameId);
        body.put("player", player);
        body.put("mainToSide", mainToSide);
        body.put("sideToMain", sideToMain);
        post("/game/sideboard", body);
    }

    @Override
    public void onGameEnd(String matchId, String gameId, String winner, int totalTurns) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("matchId", matchId);
        body.put("gameId", gameId);
        body.put("winner", winner);
        body.put("totalTurns", totalTurns);
        post("/game/end/match", body);
    }

    @Override
    public void onMatchEnd(String matchId, String matchWinner, Map<String, Integer> finalScore, String result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("matchId", matchId);
        body.put("matchWinner", matchWinner);
        body.put("finalScore", finalScore);
        body.put("result", result);
        post("/match/end", body);
    }

    /**
     * Record a single action (with the full board state) for a game.
     *
     * @param matchId     the match id
     * @param gameId      the game id
     * @param turn        current turn number
     * @param phase       current phase name
     * @param player      the player who acted (may be null)
     * @param action      human-readable description of the action
     * @param actionType  machine-readable action type (e.g. "spell", "land")
     * @param details     optional extra details
     * @param boardState  the board state BEFORE the action (Map form)
     */
    public void logMove(String matchId, String gameId, int turn, String phase, String player,
                        String action, String actionType, Map<String, Object> details,
                        Map<String, Object> boardState) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("matchId", matchId);
        body.put("gameId", gameId);
        body.put("turn", turn);
        body.put("phase", phase);
        body.put("player", player);
        body.put("action", action);
        body.put("actionType", actionType);
        body.put("details", details);
        body.put("boardState", boardState);
        post("/game/move", body);
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private void post(String path, Map<String, Object> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("Failed to serialize match log payload for {}: {}", path, e.getMessage());
            return;
        }
        executor.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("Match log endpoint {} returned status {}: {}", path, response.statusCode(), response.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("Failed to post match log to {} (server may be down): {}", path, e.getMessage());
            }
        });
    }

    /**
     * Stop the background executor, waiting briefly for in-flight posts to finish.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
