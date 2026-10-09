/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.externalai.boardstate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Multiset;
import forge.card.CardRules;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor.Color;
import forge.card.mana.ManaCost;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.card.*;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.*;
/**
 * Serializes the complete game board state to JSON for the external AI.
 * <p>
 * Uses CardView and PlayerView where possible for network-safe data,
 * falling back to direct Card/Player access for the AI's own information.
 * </p>
 */
public class BoardStateSerializer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Serialize the complete board state visible to the focal player.
     *
     * @param game       the current game instance
     * @param focalPlayer the player whose perspective to serialize from
     * @return JSON string representing the board state
     */
    public static String serialize(Game game, Player focalPlayer) {
        BoardState boardState = new BoardState();

        // Game-level info
        boardState.setGameId(String.valueOf(game.getId()));
        boardState.setTurn(game.getPhaseHandler().getTurn());
        String phaseName = game.getPhaseHandler().getPhase() != null
                ? game.getPhaseHandler().getPhase().name()
                : "MULLIGAN";
        boardState.setPhase(phaseName);

        Player activePlayer = game.getPhaseHandler().getPlayerTurn();
        boardState.setActivePlayer(activePlayer != null ? activePlayer.getName() : "");
        boardState.setFocalPlayer(focalPlayer.getName());

        // Total spells cast this turn by all players (the Storm count). This is
        // the same counter Forge uses to evaluate Storm / Aetherflux Reservoir /
        // Ral, Monsoon Mage / Thousand-Year Storm, exposed so the external AI can
        // weigh "how many copies would this produce?" when deciding to cast.
        boardState.setSpellsCastThisTurn(game.getStack().getSpellsCastThisTurn().size());

        // Serialize all players
        PlayerCollection players = game.getPlayers();
        List<BoardState.PlayerState> playerStates = new ArrayList<>();
        for (Player player : players) {
            BoardState.PlayerState ps = serializePlayer(game, player, focalPlayer);
            playerStates.add(ps);
        }
        boardState.setPlayers(playerStates);

        // Serialize stack
        List<BoardState.CardInfo> stackCards = new ArrayList<>();
        for (SpellAbilityStackInstance sai : game.getStack()) {
            SpellAbility sa = sai.getSpellAbility();
            Card source = sa.getHostCard();
            if (source != null) {
                BoardState.CardInfo cardInfo = serializeCard(source, focalPlayer, ZoneType.Stack, game.getCombat());
                stackCards.add(cardInfo);
            }
        }
        boardState.setStack(stackCards);

        return toJson(boardState);
    }

    /**
     * Serialize a single player's state.
     */
    private static BoardState.PlayerState serializePlayer(Game game, Player player, Player focalPlayer) {
        BoardState.PlayerState ps = new BoardState.PlayerState();
        PlayerView pv = player.getView();
        Combat combat = game.getCombat();

        boolean isFocal = player == focalPlayer;
        ps.setFocal(isFocal);
        ps.setName(player.getName());
        // Set opponent_id: lowercase name for opponents, null for focal player
        if (isFocal) {
            ps.setOpponentId(null);
        } else {
            ps.setOpponentId(player.getName().toLowerCase());
        }
        ps.setLife(player.getLife());
        ps.setStartingLife(player.getStartingLife());
        ps.setPoisonCounters(player.getPoisonCounters());

        // Mana pool
        ManaPool manaPool = player.getManaPool();
        Map<String, Integer> manaPoolMap = new HashMap<>();
        if (manaPool != null) {
            for (Color color : Color.values()) {
                byte colorByte = color.getColorMask();
                int amount = manaPool.getAmountOfColor(colorByte);
                if (amount > 0) {
                    manaPoolMap.put(color.getName(), amount);
                }
            }
        }
        ps.setManaPool(manaPoolMap);

        // Commanders
        List<Card> commanders = player.getCommanders();
        List<BoardState.CardInfo> commanderInfos = new ArrayList<>();
        for (Card cmd : commanders) {
            BoardState.CardInfo cmdInfo = serializeCard(cmd, focalPlayer, ZoneType.Command, combat);
            commanderInfos.add(cmdInfo);
        }
        ps.setCommanders(commanderInfos);

        // Commander damage (visible for all players)
        Map<String, Integer> commanderDamage = new HashMap<>();
        for (var entry : player.getCommanderDamage()) {
            Card cmd = entry.getKey();
            Integer damage = entry.getValue();
            if (damage != null && damage > 0) {
                commanderDamage.put(cmd.getName(), damage);
            }
        }
        ps.setCommanderDamage(commanderDamage);

        // Commander tax (cast count)
        Map<String, Integer> commanderTax = new HashMap<>();
        for (Card cmd : commanders) {
            int tax = player.getCommanderCast(cmd);
            if (tax > 0) {
                commanderTax.put(cmd.getName(), tax);
            }
        }
        ps.setCommanderTax(commanderTax);

        // Battlefield
        List<BoardState.CardInfo> battlefield = new ArrayList<>();
        for (Card card : player.getZone(ZoneType.Battlefield).getCards(false)) {
            BoardState.CardInfo cardInfo = serializeCard(card, focalPlayer, ZoneType.Battlefield, combat);
            battlefield.add(cardInfo);
        }
        ps.setBattlefield(battlefield);

        // Hand - serialize the FULL hand for every player (both hands). The
        // Python server masks the opponent's hand per-perspective at
        // tokenization time, so the external AI only ever sees its own hand
        // (plus any cards explicitly revealed to it). Sending the full board
        // lets the server also compute a "human perspective" evaluation (the
        // model re-focused on the human, seeing the human's hand but not the
        // AI's). hand_hidden still records which hand was hidden from the
        // original focal player, for backward compatibility.
        boolean handHidden = !isFocal;
        ps.setHandHidden(handHidden);
        List<BoardState.CardInfo> hand = new ArrayList<>();
        for (Card card : player.getZone(ZoneType.Hand).getCards(false)) {
            BoardState.CardInfo cardInfo = serializeCard(card, focalPlayer, ZoneType.Hand, combat);
            hand.add(cardInfo);
        }
        ps.setHand(hand);

        // Graveyard - visible to focal player
        List<BoardState.CardInfo> graveyard = new ArrayList<>();
        for (Card card : player.getZone(ZoneType.Graveyard).getCards(false)) {
            BoardState.CardInfo cardInfo = serializeCard(card, focalPlayer, ZoneType.Graveyard, combat);
            graveyard.add(cardInfo);
        }
        ps.setGraveyard(graveyard);

        // Exile - show only cards visible to focal player
        List<BoardState.CardInfo> exile = new ArrayList<>();
        for (Card card : player.getZone(ZoneType.Exile).getCards(false)) {
            if (card.mayPlayerLook(focalPlayer)) {
                BoardState.CardInfo cardInfo = serializeCard(card, focalPlayer, ZoneType.Exile, combat);
                exile.add(cardInfo);
            }
        }
        ps.setExile(exile);

        // Command zone
        List<BoardState.CardInfo> commandZone = new ArrayList<>();
        for (Card card : player.getZone(ZoneType.Command).getCards(false)) {
            BoardState.CardInfo cardInfo = serializeCard(card, focalPlayer, ZoneType.Command, combat);
            commandZone.add(cardInfo);
        }
        ps.setCommandZone(commandZone);

        // Library size (not contents)
        ps.setLibrarySize(player.getZone(ZoneType.Library).size());

        // Sideboard size
        ps.setSideboardSize(player.getZone(ZoneType.Sideboard).size());

        // Mulligan count (how many times this player has already mulliganed)
        ps.setMulliganCount(player.getStats().getMulliganCount());

        // Spells THIS player cast this turn (per-player Storm count).
        ps.setSpellsCastThisTurn(player.getSpellsCastThisTurn());

        // Full decklist (main + sideboard) for EVERY player. Each player knows
        // their own deck, so this is not hidden information. The Python server
        // only emits decklist tokens for the perspective (focal) player, so the
        // external AI still only ever sees its own deck. Sending both decks lets
        // the server compute a "human perspective" evaluation (the model
        // re-focused on the human, attending to the human's deck).
        ps.setDecklist(serializeDecklist(player));

        return ps;
    }

    /**
     * Serialize a player's full deck (main + sideboard) as a list of card info.
     *
     * <p>The deck is reached via the player's {@link RegisteredPlayer}. Each
     * distinct card is serialized once (with its full rules: mana cost, colors,
     * types, text, P/T, loyalty, keywords) so the AI can learn about cards it
     * has not yet seen on the board.
     */
    private static List<BoardState.CardInfo> serializeDecklist(Player player) {
        List<BoardState.CardInfo> result = new ArrayList<>();
        try {
            RegisteredPlayer registered = player.getRegisteredPlayer();
            if (registered == null) {
                return result;
            }
            Deck deck = registered.getDeck();
            if (deck == null) {
                return result;
            }
            // One entry per distinct card name, with a count of how many copies
            // are in the deck (main + sideboard), so the AI can see multiplicity
            // (e.g. 4x Mountain, 2x Lightning Bolt).
            Map<String, Integer> counts = new LinkedHashMap<>();
            Map<String, PaperCard> firstSeen = new LinkedHashMap<>();
            for (DeckSection section : new DeckSection[]{DeckSection.Main, DeckSection.Sideboard}) {
                CardPool pool = deck.get(section);
                if (pool == null || pool.isEmpty()) {
                    continue;
                }
                for (PaperCard pc : pool.toFlatList()) {
                    String name = pc.getName();
                    if (name == null) {
                        continue;
                    }
                    counts.merge(name, 1, Integer::sum);
                    firstSeen.putIfAbsent(name, pc);
                }
            }
            for (Map.Entry<String, PaperCard> entry : firstSeen.entrySet()) {
                BoardState.CardInfo info = serializePaperCard(entry.getValue());
                info.setCount(counts.get(entry.getKey()));
                result.add(info);
            }
        } catch (Exception e) {
            // Never let decklist serialization break the board state.
        }
        return result;
    }

    /**
     * Build a {@link BoardState.CardInfo} from a {@link PaperCard} using its
     * static rules (no in-game state such as damage or tapped).
     */
    private static BoardState.CardInfo serializePaperCard(PaperCard pc) {
        BoardState.CardInfo info = new BoardState.CardInfo();
        info.setName(pc.getName());

        CardRules rules = pc.getRules();
        if (rules == null) {
            return info;
        }

        // Mana cost
        ManaCost manaCost = rules.getManaCost();
        if (manaCost != null) {
            info.setManaCost(manaCost.toString());
            info.setConvertedManaCost(manaCost.getCMC());
        }

        // Colors
        ColorSet colors = rules.getColor();
        if (colors != null) {
            info.setColors(colorSetToList(colors));
        }
        ColorSet identity = rules.getColorIdentity();
        if (identity != null) {
            info.setColorIdentity(colorSetToList(identity));
        }

        // Types
        CardType type = rules.getType();
        if (type != null) {
            List<String> types = new ArrayList<>();
            for (CardType.CoreType t : type.getCoreTypes()) {
                types.add(t.toString());
            }
            info.setTypes(types);

            List<String> supertypes = new ArrayList<>();
            for (CardType.Supertype s : type.getSupertypes()) {
                supertypes.add(s.toString());
            }
            info.setSupertypes(supertypes);

            info.setSubtypes(new ArrayList<>(type.getSubtypes()));
        }

        // Text
        info.setText(rules.getOracleText());

        // Power / toughness (when present)
        if (rules.getIntPower() > 0 || rules.getIntToughness() > 0) {
            info.setPower(rules.getIntPower());
            info.setToughness(rules.getIntToughness());
        }

        // Loyalty for planeswalkers
        String loyalty = rules.getInitialLoyalty();
        if (loyalty != null && !loyalty.isEmpty()) {
            info.setLoyalty(loyalty);
        }

        // Keywords
        ICardFace face = rules.getMainPart();
        if (face != null && face.getKeywords() != null) {
            List<String> keywords = new ArrayList<>();
            for (String kw : face.getKeywords()) {
                keywords.add(kw);
            }
            info.setKeywords(keywords);
        }

        return info;
    }

    /**
     * Serialize a single card's information.
     */
    private static BoardState.CardInfo serializeCard(Card card, Player focalPlayer, ZoneType zone, Combat combat) {
        BoardState.CardInfo info = new BoardState.CardInfo();
        CardView cv = card.getView();
        var csv = cv.getCurrentState();

        info.setId(card.getId());
        info.setName(cv.getName());
        info.setOracleName(cv.getOracleName());

        // Mana cost
        ManaCost manaCost = csv.getManaCost();
        if (manaCost != null) {
            info.setManaCost(manaCost.toString());
            info.setConvertedManaCost(manaCost.getCMC());
        }

        // Colors
        ColorSet colors = csv.getColors();
        if (colors != null) {
            info.setColors(colorSetToList(colors));
        }

        // Types
        var typeView = csv.getType();
        if (typeView != null) {
            List<String> types = new ArrayList<>();
            for (var t : typeView.getCoreTypes()) {
                types.add(t.toString());
            }
            info.setTypes(types);

            List<String> supertypes = new ArrayList<>();
            for (var s : typeView.getSupertypes()) {
                supertypes.add(s.toString());
            }
            info.setSupertypes(supertypes);

            info.setSubtypes(new ArrayList<>(typeView.getSubtypes()));
        }

        // Text
        info.setText(cv.getText());

        // Power and toughness (always present; 0/0 for cards without P/T so the
        // model sees a consistent field for every card)
        info.setPower(csv.getPower());
        info.setToughness(csv.getToughness());

        // Loyalty for planeswalkers
        if (card.isPlaneswalker()) {
            info.setLoyalty(String.valueOf(card.getCurrentLoyalty()));
        }

        // Damage
        info.setDamage(cv.getDamage());

        // Counters
        Map<String, Integer> counters = new HashMap<>();
        if (card.getCounters() != null && !card.getCounters().isEmpty()) {
            for (Multiset.Entry<CounterType> entry : card.getCounters().entrySet()) {
                counters.put(entry.getElement().getName(), entry.getCount());
            }
        }
        info.setCounters(counters);

        // Combat state
        info.setTapped(cv.isTapped());
        info.setAttacking(cv.isAttacking());
        info.setBlocking(cv.isBlocking());
        info.setBlocked(combat != null && combat.isBlocked(card));

        // Card state
        info.setFlipped(cv.isFlipped());
        info.setFaceDown(cv.isFaceDown());

        // Ownership
        PlayerView owner = cv.getOwner();
        PlayerView controller = cv.getController();
        info.setOwner(owner != null ? owner.getName() : "");
        info.setController(controller != null ? controller.getName() : "");

        // Zone
        info.setZone(zone != null ? zone.name() : "");

        // Keywords
        var keywordCollection = csv.getKeywords();
        if (keywordCollection != null) {
            List<String> keywords = new ArrayList<>();
            for (var kw : keywordCollection) {
                keywords.add(kw.original());
            }
            info.setKeywords(keywords);
        }

        return info;
    }

    /**
     * Convert a ColorSet to a list of color strings.
     */
    private static List<String> colorSetToList(ColorSet colorSet) {
        List<String> result = new ArrayList<>();
        for (Color color : colorSet) {
            result.add(color.getName());
        }
        return result;
    }

    /**
     * Convert BoardState to JSON string.
     */
    public static String toJson(BoardState boardState) {
        try {
            return OBJECT_MAPPER.writeValueAsString(boardState);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize board state", e);
        }
    }

    /**
     * Parse JSON string to BoardState.
     */
    public static BoardState fromJson(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, BoardState.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse board state", e);
        }
    }
}
