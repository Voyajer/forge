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
import forge.card.ColorSet;
import forge.card.MagicColor.Color;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.card.*;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

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
        boardState.setPhase(game.getPhaseHandler().getPhase().name());

        Player activePlayer = game.getPhaseHandler().getPlayerTurn();
        boardState.setActivePlayer(activePlayer != null ? activePlayer.getName() : "");
        boardState.setFocalPlayer(focalPlayer.getName());

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
                BoardState.CardInfo cardInfo = serializeCard(source, focalPlayer, ZoneType.Stack);
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

        boolean isFocal = player == focalPlayer;
        ps.setFocal(isFocal);
        ps.setName(player.getName());
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
            BoardState.CardInfo cmdInfo = serializeCard(cmd, focalPlayer, ZoneType.Command);
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
            BoardState.CardInfo cardInfo = serializeCard(card, focalPlayer, ZoneType.Battlefield);
            battlefield.add(cardInfo);
        }
        ps.setBattlefield(battlefield);

        // Hand - only show actual cards for focal player, otherwise hidden
        boolean handHidden = !isFocal;
        ps.setHandHidden(handHidden);
        List<BoardState.CardInfo> hand = new ArrayList<>();
        if (isFocal) {
            for (Card card : player.getZone(ZoneType.Hand).getCards(false)) {
                BoardState.CardInfo cardInfo = serializeCard(card, focalPlayer, ZoneType.Hand);
                hand.add(cardInfo);
            }
        }
        ps.setHand(hand);

        // Graveyard - visible to focal player
        List<BoardState.CardInfo> graveyard = new ArrayList<>();
        for (Card card : player.getZone(ZoneType.Graveyard).getCards(false)) {
            BoardState.CardInfo cardInfo = serializeCard(card, focalPlayer, ZoneType.Graveyard);
            graveyard.add(cardInfo);
        }
        ps.setGraveyard(graveyard);

        // Exile - show only cards visible to focal player
        List<BoardState.CardInfo> exile = new ArrayList<>();
        for (Card card : player.getZone(ZoneType.Exile).getCards(false)) {
            if (card.mayPlayerLook(focalPlayer)) {
                BoardState.CardInfo cardInfo = serializeCard(card, focalPlayer, ZoneType.Exile);
                exile.add(cardInfo);
            }
        }
        ps.setExile(exile);

        // Command zone
        List<BoardState.CardInfo> commandZone = new ArrayList<>();
        for (Card card : player.getZone(ZoneType.Command).getCards(false)) {
            BoardState.CardInfo cardInfo = serializeCard(card, focalPlayer, ZoneType.Command);
            commandZone.add(cardInfo);
        }
        ps.setCommandZone(commandZone);

        // Library size (not contents)
        ps.setLibrarySize(player.getZone(ZoneType.Library).size());

        // Sideboard size
        ps.setSideboardSize(player.getZone(ZoneType.Sideboard).size());

        return ps;
    }

    /**
     * Serialize a single card's information.
     */
    private static BoardState.CardInfo serializeCard(Card card, Player focalPlayer, ZoneType zone) {
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

        // Power and toughness
        if (card.isCreature()) {
            info.setPower(csv.getPower());
            info.setToughness(csv.getToughness());
        }

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
