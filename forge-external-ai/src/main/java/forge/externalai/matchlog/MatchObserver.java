package forge.externalai.matchlog;

import java.util.List;
import java.util.Map;

/**
 * Observes the lifecycle of a Forge match (all games of a Bo1/Bo3/...) so that
 * the entire match can be logged as a single replay file on the external AI
 * server.
 *
 * <p>Implementations receive match-level and game-level lifecycle callbacks.
 * The default implementation ({@link ExternalMatchLogger}) forwards these to the
 * Python server over HTTP.
 *
 * <p>All callbacks are invoked on the game thread (or the EDT for match-end),
 * so implementations must not block for long.
 */
public interface MatchObserver {

    /**
     * A new match is starting.
     *
     * @param matchId     unique id for this match
     * @param players     player names in seat order
     * @param format      game type / format name (e.g. "standard")
     * @param gamesToWin  number of games needed to win the match
     * @param playerTypes player name -> role ("human", "bot", "ai", or "unknown").
     *                    This makes the replay self-describing so the trainer can
     *                    tell which moves were made by a human, a Forge bot, or the
     *                    external AI, regardless of the match composition.
     */
    void onMatchStart(String matchId, List<String> players, String format, int gamesToWin, Map<String, String> playerTypes);

    /**
     * A new game within the match is starting.
     *
     * @param matchId    the match id
     * @param gameId     unique id for this game
     * @param gameNumber 1-based game number within the match
     * @param decklists  player name -> main deck card names (may be empty)
     */
    void onGameStart(String matchId, String gameId, int gameNumber, Map<String, List<String>> decklists);

    /**
     * A player finished sideboarding for an upcoming game (games 2+).
     *
     * @param matchId    the match id
     * @param gameId     the game id this sideboard applies to
     * @param player     the player who sideboarded
     * @param mainToSide cards moved from main deck to sideboard
     * @param sideToMain cards moved from sideboard to main deck
     */
    void onSideboard(String matchId, String gameId, String player, List<String> mainToSide, List<String> sideToMain);

    /**
     * A game within the match has ended.
     *
     * @param matchId    the match id
     * @param gameId     the game id
     * @param winner     winning player name, or null/empty for a draw
     * @param totalTurns number of turns the game lasted
     */
    void onGameEnd(String matchId, String gameId, String winner, int totalTurns);

    /**
     * The match is over.
     *
     * @param matchId      the match id
     * @param matchWinner  winning player name, or null/empty for a draw
     * @param finalScore   player name -> games won (may be null)
     * @param result       "completed" or "abandoned"
     */
    void onMatchEnd(String matchId, String matchWinner, Map<String, Integer> finalScore, String result);
}
