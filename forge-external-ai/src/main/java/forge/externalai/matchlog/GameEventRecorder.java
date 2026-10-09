package forge.externalai.matchlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.Subscribe;
import forge.externalai.boardstate.BoardStateSerializer;
import forge.game.Game;
import forge.game.event.*;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Subscribes to a {@link Game}'s event bus and records every meaningful
 * player action (spell/ability cast, land play, attackers, blockers, mulligan,
 * mode choice, card zone change) together with the full board state.
 *
 * <p>Each action is forwarded to the {@link ExternalMatchLogger}, which POSTs it
 * to the Python server's {@code /game/move} endpoint. The board state is
 * serialized from the acting player's perspective so the acting player's hand
 * is visible (matching the fidelity of the human-vs-external-AI {@code /decision}
 * logs).
 *
 * <p>Serialization happens on the game thread (to get a consistent snapshot);
 * the HTTP POST is dispatched asynchronously by the logger so it never blocks
 * gameplay.
 */
public class GameEventRecorder extends IGameEventVisitor.Base<Void> {

    private static final Logger log = LoggerFactory.getLogger(GameEventRecorder.class);

    private final Game game;
    private final String matchId;
    private final String gameId;
    private final ExternalMatchLogger logger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Player name -> Player, for mapping PlayerView (from events) to Player. */
    private final Map<String, Player> playersByName = new HashMap<>();

    public GameEventRecorder(Game game, String matchId, String gameId, ExternalMatchLogger logger) {
        this.game = game;
        this.matchId = matchId;
        this.gameId = gameId;
        this.logger = logger;
        for (Player p : game.getPlayers()) {
            playersByName.put(p.getName(), p);
        }
        game.subscribeToEvents(this);
    }

    // ------------------------------------------------------------------
    // EventBus entry point
    // ------------------------------------------------------------------

    @Subscribe
    public void receiveGameEvent(final GameEvent evt) {
        try {
            evt.visit(this);
        } catch (Exception e) {
            log.debug("Error recording game event {}: {}", evt.getClass().getSimpleName(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Action events
    // ------------------------------------------------------------------

    @Override
    public Void visit(GameEventSpellAbilityCast event) {
        Player actor = resolvePlayer(event.si() != null ? event.si().getActivatingPlayer() : null);
        String action = event.toString();
        recordMove(actor, "spell", action, null);
        return null;
    }

    @Override
    public Void visit(GameEventLandPlayed event) {
        Player actor = resolvePlayer(event.player());
        String action = event.toString();
        recordMove(actor, "land", action, null);
        return null;
    }

    @Override
    public Void visit(GameEventAttackersDeclared event) {
        Player actor = resolvePlayer(event.player());
        String action = event.toString();
        recordMove(actor, "attackers", action, null);
        return null;
    }

    @Override
    public Void visit(GameEventBlockersDeclared event) {
        Player actor = resolvePlayer(event.defendingPlayer());
        String action = event.toString();
        recordMove(actor, "blockers", action, null);
        return null;
    }

    @Override
    public Void visit(GameEventMulligan event) {
        Player actor = resolvePlayer(event.player());
        String action = event.toString();
        recordMove(actor, "mulligan", action, null);
        return null;
    }

    @Override
    public Void visit(GameEventCardModeChosen event) {
        Player actor = resolvePlayer(event.player());
        String action = event.player() + " chose mode " + event.mode() + " for " + event.cardName();
        recordMove(actor, "mode", action, null);
        return null;
    }

    @Override
    public Void visit(GameEventCardChangeZone event) {
        // Zone changes (discards, sacrifices, searches, etc.). The event does not
        // name the causing player, so we use the active player as the focal player.
        Player actor = activePlayer();
        String action = event.toString();
        recordMove(actor, "zone_change", action, null);
        return null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void recordMove(Player actor, String actionType, String action, Map<String, Object> details) {
        try {
            Player focal = actor != null ? actor : activePlayer();
            if (focal == null) {
                return;
            }
            Map<String, Object> boardState = serializeBoardState(focal);
            if (boardState == null) {
                return;
            }
            int turn = safeTurn();
            String phase = safePhase();
            String playerName = actor != null ? actor.getName() : (focal != null ? focal.getName() : null);
            logger.logMove(matchId, gameId, turn, phase, playerName, action, actionType, details, boardState);
        } catch (Exception e) {
            log.debug("Error recording move ({}): {}", actionType, e.getMessage());
        }
    }

    /**
     * Serialize the full board state from the focal player's perspective and
     * parse it into a Map (the Python endpoint expects a JSON object).
     */
    private Map<String, Object> serializeBoardState(Player focal) {
        try {
            String json = BoardStateSerializer.serialize(game, focal);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("Failed to serialize board state: {}", e.getMessage());
            return null;
        }
    }

    private Player resolvePlayer(PlayerView view) {
        if (view == null) {
            return null;
        }
        return playersByName.get(view.getName());
    }

    private Player activePlayer() {
        try {
            return game.getPhaseHandler().getPlayerTurn();
        } catch (Exception e) {
            return null;
        }
    }

    private int safeTurn() {
        try {
            return game.getPhaseHandler().getTurn();
        } catch (Exception e) {
            return 0;
        }
    }

    private String safePhase() {
        try {
            var phase = game.getPhaseHandler().getPhase();
            return phase != null ? phase.name() : "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}
