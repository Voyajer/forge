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
package forge.externalai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import forge.LobbyPlayer;
import forge.game.card.CardState;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.externalai.AiDecisionRequest.Constraints;
import forge.externalai.AiDecisionRequest.DecisionContext;
import forge.externalai.AiDecisionRequest.Option;
import forge.externalai.boardstate.BoardStateSerializer;
import forge.externalai.config.ExternalAiConfig;
import forge.externalai.http.ExternalAiHttpClient;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameType;
import forge.game.PlanarDice;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.cost.*;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.function.Predicate;

/**
 * External AI player controller that delegates all decisions to an external AI server.
 *
 * This controller intercepts human player decision points and forwards them to an
 * external AI via HTTP REST API. The AI receives the complete board state as JSON
 * and returns its decision as JSON. The GUI renders normally, showing AI actions
 * as if a human were clicking.
 */
public class PlayerControllerExternalAi extends PlayerController {

    private static final Logger logger = LoggerFactory.getLogger(PlayerControllerExternalAi.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ExternalAiConfig config;
    private final ExternalAiHttpClient httpClient;
    private final String gameId;

    public PlayerControllerExternalAi(Game game, Player player, LobbyPlayer lobbyPlayer) {
        this(game, player, lobbyPlayer, new ExternalAiConfig());
    }

    public PlayerControllerExternalAi(Game game, Player player, LobbyPlayer lobbyPlayer, ExternalAiConfig cfg) {
        super(game, player, lobbyPlayer);
        this.config = cfg;
        this.httpClient = new ExternalAiHttpClient();
        this.gameId = UUID.randomUUID().toString();
        logger.info("ExternalAi controller created for player: {}", player.getName());
    }

    @Override
    public boolean isAI() {
        return true;
    }

    // =====================================================================
    // Spell playing and trigger decisions
    // =====================================================================

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }

        AiDecisionRequest request = buildRequest("ABILITY_CHOICE",
                "Choose a spell ability to play from " + abilities.size() + " options");
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        for (int i = 0; i < abilities.size(); i++) {
            SpellAbility sa = abilities.get(i);
            Option option = new Option();
            option.setId(String.valueOf(i));
            option.setType("ability");
            option.setLabel(sa.getHostCard().getName() + " - " + sa.getDescription());
            ctx.getOptions().add(option);
        }

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            int idx = Integer.parseInt(response.getSelectedOptionId());
            return abilities.get(Math.min(idx, abilities.size() - 1));
        }
        return abilities.get(0);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        logger.debug("Playing spell ability without stack: {}", effectSA.getDescription());
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (activePlayerSAs == null || activePlayerSAs.size() <= 1) {
            return activePlayerSAs;
        }

        AiDecisionRequest request = buildRequest("ORDER_ABILITIES",
                "Order " + activePlayerSAs.size() + " simultaneous abilities");
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        for (int i = 0; i < activePlayerSAs.size(); i++) {
            Option option = new Option();
            option.setId(String.valueOf(i));
            option.setType("ability");
            option.setLabel(activePlayerSAs.get(i).getHostCard().getName());
            ctx.getOptions().add(option);
        }

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getValue() != null && response.getValue().containsKey("order")) {
            // TODO: Parse order from response
        }
        return activePlayerSAs;
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        List<SpellAbility> ordered = orderSimultaneousSa(activePlayerSAs);
        for (SpellAbility sa : ordered) {
            playSpellAbilityNoStack(sa, false);
        }
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (isMandatory) {
            return true;
        }

        AiDecisionRequest request = buildRequest("TRIGGER_CHOICE",
                "Play trigger from " + host.getName() + "?");
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        ctx.getOptions().add(new Option("yes", "boolean", "Play trigger", null));
        ctx.getOptions().add(new Option("no", "boolean", "Don't play trigger", null));

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            return "yes".equals(response.getSelectedOptionId());
        }
        return false;
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        return true;
    }

    // =====================================================================
    // Deck building decisions
    // =====================================================================

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        // Return null to use default sideboard plan
        return null;
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        return losses;
    }

    // =====================================================================
    // Combat decisions
    // =====================================================================

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers,
                                                  CardCollectionView remaining, int damageDealt,
                                                  GameEntity defender, boolean overrideOrder) {
        Map<Card, Integer> damageMap = new HashMap<>();

        if (blockers.isEmpty()) {
            // No blockers - damage goes to defender (player), not recorded in Card map
            return damageMap;
        }

        AiDecisionRequest request = buildRequest("COMBAT_DAMAGE_ASSIGNMENT",
                "Assign " + damageDealt + " damage from " + attacker.getName());
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        int idx = 0;
        for (Card blocker : blockers) {
            Option option = new Option();
            option.setId("blocker_" + idx++);
            option.setType("card");
            option.setLabel(blocker.getName());
            ctx.getOptions().add(option);
        }

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getValue() != null) {
            // TODO: Parse damage distribution from response
        }

        // Fallback: front-loaded damage
        int remainingDamage = damageDealt;
        for (Card blocker : blockers) {
            if (remainingDamage <= 0) break;
            int toughness = blocker.getCurrentToughness();
            int damage = Math.min(remainingDamage, toughness);
            damageMap.put(blocker, damage);
            remainingDamage -= damage;
        }
        return damageMap;
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected,
                                                  int shieldAmount) {
        Map<GameEntity, Integer> result = new HashMap<>();
        int remaining = shieldAmount;
        for (GameEntity entity : affected.keySet()) {
            int amount = Math.min(remaining, affected.get(entity));
            result.put(entity, amount);
            remaining -= amount;
        }
        return result;
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount,
                                                boolean different) {
        Map<Byte, Integer> result = new HashMap<>();
        if (colorSet != null) {
            for (var color : colorSet) {
                result.put(color.getColorMask(), manaAmount);
                break;
            }
        }
        return result;
    }

    // =====================================================================
    // Card selection decisions
    // =====================================================================

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
                                                           CardCollectionView validTargets, String message) {
        List<Card> chosen = new ArrayList<>();
        int count = Math.min(min, validTargets.size());
        for (Card card : validTargets) {
            if (chosen.size() >= count) break;
            chosen.add(card);
        }
        return new CardCollection(chosen);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
                                                         CardCollectionView validTargets, String message) {
        return choosePermanentsToSacrifice(sa, min, max, validTargets, message);
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        AiDecisionRequest request = buildRequest("ANNOUNCE_NUMBER",
                "Announce a number between " + min + " and " + max + " for " + announce);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getConstraints().setMinChoices(min);
        ctx.getConstraints().setMaxChoices(max);

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getValue() != null && response.getValue().containsKey("number")) {
            try {
                int value = Integer.parseInt(response.getValue().get("number").toString());
                return Math.max(min, Math.min(max, value));
            } catch (NumberFormatException e) {
                logger.warn("Invalid number from AI: {}", response.getValue().get("number"));
            }
        }
        return min;
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter,
                                              boolean optional) {
        return null;
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        AiDecisionRequest request = buildRequest("TARGET_CHOICE",
                "Choose targets for " + currentAbility.getHostCard().getName());
        AiDecisionResponse response = httpClient.requestDecision(request);
        // TODO: Parse target choices from response
        return true;
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa,
                                                                     List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        if (allTargets.isEmpty()) {
            return null;
        }

        AiDecisionRequest request = buildRequest("SPECIFY_TARGET",
                "Choose a target for Spellskite-like effect");
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        for (int i = 0; i < allTargets.size(); i++) {
            Option option = new Option();
            option.setId(String.valueOf(i));
            option.setType("target");
            option.setLabel(allTargets.get(i).getRight().toString());
            ctx.getOptions().add(option);
        }

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            int selectedIdx = Integer.parseInt(response.getSelectedOptionId());
            return allTargets.get(Math.min(selectedIdx, allTargets.size() - 1));
        }
        return allTargets.get(0);
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max,
                                          int requested) {
        return false;
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa,
                                               String title, int max) {
        return optionList.isEmpty() ? null : optionList.get(0);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa,
                                                    String title, int min, int max, boolean isOptional,
                                                    Map<String, Object> params) {
        if (sourceList.isEmpty()) {
            return new CardCollection();
        }

        AiDecisionRequest request = buildRequest("CARD_SELECTION", title);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        for (int i = 0; i < sourceList.size(); i++) {
            Card card = sourceList.get(i);
            Option option = new Option();
            option.setId(String.valueOf(i));
            option.setType("card");
            option.setLabel(card.getName());
            ctx.getOptions().add(option);
        }
        ctx.getConstraints().setMinChoices(min);
        ctx.getConstraints().setMaxChoices(max);
        ctx.getConstraints().setOptional(isOptional);

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionIds() != null) {
            List<Card> chosen = new ArrayList<>();
            for (String id : response.getSelectedOptionIds()) {
                try {
                    int selectedIdx = Integer.parseInt(id);
                    if (selectedIdx >= 0 && selectedIdx < sourceList.size()) {
                        chosen.add(sourceList.get(selectedIdx));
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Invalid card index from AI: {}", id);
                }
            }
            return new CardCollection(chosen);
        }

        // Fallback
        List<Card> fallback = new ArrayList<>();
        int count = Math.min(min, sourceList.size());
        for (Card card : sourceList) {
            if (fallback.size() >= count) break;
            fallback.add(card);
        }
        return new CardCollection(fallback);
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap,
                                                        SpellAbility sa, String title, boolean isOptional) {
        CardCollection result = new CardCollection();
        for (Map.Entry<String, CardCollection> entry : validMap.entrySet()) {
            result.addAll(entry.getValue());
        }
        return result;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList,
                                                                  DelayedReveal delayedReveal,
                                                                  SpellAbility sa, String title,
                                                                  boolean isOptional, Player relatedPlayer,
                                                                  Map<String, Object> params) {
        if (optionList.isEmpty()) {
            return null;
        }

        AiDecisionRequest request = buildRequest("ENTITY_SELECTION", title);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        int idx = 0;
        for (T option : optionList) {
            Option opt = new Option();
            opt.setId(String.valueOf(idx++));
            opt.setType("entity");
            opt.setLabel(option.getName());
            ctx.getOptions().add(opt);
        }

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            int selectedIdx = Integer.parseInt(response.getSelectedOptionId());
            int i = 0;
            for (T option : optionList) {
                if (i++ == selectedIdx) return option;
            }
        }
        return optionList.get(0);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList,
                                                                    int min, int max,
                                                                    DelayedReveal delayedReveal,
                                                                    SpellAbility sa, String title,
                                                                    Player relatedPlayer,
                                                                    Map<String, Object> params) {
        List<T> result = new ArrayList<>();
        int count = Math.min(min, optionList.size());
        for (T option : optionList) {
            if (result.size() >= count) break;
            result.add(option);
        }
        return result;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa,
                                                             String title, int num,
                                                             Map<String, Object> params) {
        List<SpellAbility> result = new ArrayList<>();
        int count = Math.min(num, spells.size());
        for (int i = 0; i < count; i++) {
            result.add(spells.get(i));
        }
        return result;
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa,
                                                    String title, Map<String, Object> params) {
        return spells.isEmpty() ? null : spells.get(0);
    }

    // =====================================================================
    // Confirmation decisions
    // =====================================================================

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
                                  List<String> options, Card cardToShow, Map<String, Object> params) {
        AiDecisionRequest request = buildRequest("CONFIRM_ACTION", message);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        ctx.getOptions().add(new Option("yes", "boolean", "Confirm", null));
        ctx.getOptions().add(new Option("no", "boolean", "Cancel", null));

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            return "yes".equals(response.getSelectedOptionId());
        }
        return false;
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string,
                                     int bid, Player winner) {
        return false;
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA,
                                             GameEntity affected, String question) {
        return true;
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message,
                                             String logic) {
        return true;
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        return true;
    }

    // =====================================================================
    // Combat declaration
    // =====================================================================

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return attackers;
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return attackers;
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        AiDecisionRequest request = buildRequest("DECLARE_ATTACKERS",
                "Declare attackers for combat");
        AiDecisionResponse response = httpClient.requestDecision(request);
        // TODO: Parse attacker declarations from response
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        AiDecisionRequest request = buildRequest("DECLARE_BLOCKERS",
                "Declare blockers for combat");
        AiDecisionResponse response = httpClient.requestDecision(request);
        // TODO: Parse blocker declarations from response
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return blockers;
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        CardCollection result = new CardCollection(oldBlockers);
        result.add(blocker);
        return result;
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return attackers;
    }

    // =====================================================================
    // Reveal and notify
    // =====================================================================

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix,
                       boolean addMsgSuffix) {
        logger.debug("Revealing {} cards from {} zone (owner: {})", cards.size(), zone, owner.getName());
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix,
                       boolean addMsgSuffix) {
        logger.debug("Revealing {} cards from {} zone (owner: {})", cards.size(), zone, owner.getName());
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject relatedTarget, String value) {
        logger.debug("Notified of value: {} (target: {})", value,
                relatedTarget != null ? relatedTarget.toString() : "null");
    }

    // =====================================================================
    // Card ordering
    // =====================================================================

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        CardCollection faceUp = new CardCollection();
        CardCollection faceDown = new CardCollection();
        int mid = topN.size() / 2;
        int i = 0;
        for (Card card : topN) {
            if (i++ < mid) {
                faceUp.add(card);
            } else {
                faceDown.add(card);
            }
        }
        return new ImmutablePair<>(faceUp, faceDown);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return arrangeForScry(topN);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        return false;
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
                                                   SpellAbility source) {
        return cards;
    }

    // =====================================================================
    // Discard decisions
    // =====================================================================

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa,
                                                        CardCollection validCards, int min, int max,
                                                        CardCollectionView visibleToChooser) {
        List<Card> chosen = new ArrayList<>();
        int count = Math.min(min, validCards.size());
        for (Card card : validCards) {
            if (chosen.size() >= count) break;
            chosen.add(card);
        }
        return new CardCollection(chosen);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand,
                                                              String[] unlessTypes, SpellAbility sa) {
        List<Card> chosen = new ArrayList<>();
        for (Card card : hand) {
            if (chosen.size() >= min) break;
            boolean matches = false;
            for (String type : unlessTypes) {
                if (card.getCurrentState().getType().hasSubtype(type)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                chosen.add(card);
            }
        }
        return new CardCollection(chosen);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollectionView hand = getPlayer().getCardsIn(ZoneType.Hand);
        List<Card> chosen = new ArrayList<>();
        for (Card card : hand) {
            if (chosen.size() >= numDiscard) break;
            chosen.add(card);
        }
        return new CardCollection(chosen);
    }

    // =====================================================================
    // Keyword cost decisions
    // =====================================================================

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        List<Card> chosen = new ArrayList<>();
        for (Card card : grave) {
            if (chosen.size() >= genericAmount) break;
            chosen.add(card);
        }
        return new CardCollection(chosen);
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost,
                                                                       CardCollectionView untappedCards,
                                                                       boolean artifacts, boolean creatures,
                                                                       Integer maxReduction) {
        Map<Card, ManaCostShard> result = new HashMap<>();
        return result;
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return new ArrayList<>();
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        List<Card> chosen = new ArrayList<>();
        int count = Math.min(min, valid.size());
        for (Card card : valid) {
            if (chosen.size() >= count) break;
            chosen.add(card);
        }
        return new CardCollection(chosen);
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return new ArrayList<>();
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return getPlayer();
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return zones.isEmpty() ? null : zones.get(0);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return manaChoices.isEmpty() ? null : manaChoices.get(0);
    }

    // =====================================================================
    // Type and sector choices
    // =====================================================================

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes,
                                  boolean isOptional) {
        if (validTypes.isEmpty()) {
            return null;
        }

        AiDecisionRequest request = buildRequest("TYPE_CHOICE",
                "Choose a " + kindOfType);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        int idx = 0;
        for (String type : validTypes) {
            Option option = new Option();
            option.setId(String.valueOf(idx++));
            option.setType("type");
            option.setLabel(type);
            ctx.getOptions().add(option);
        }

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            int selectedIdx = Integer.parseInt(response.getSelectedOptionId());
            List<String> typeList = new ArrayList<>(validTypes);
            if (selectedIdx >= 0 && selectedIdx < typeList.size()) {
                return typeList.get(selectedIdx);
            }
        }
        return new ArrayList<>(validTypes).get(0);
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return sectors.isEmpty() ? null : sectors.get(0);
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        return new ArrayList<>();
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        return sprockets.isEmpty() ? 1 : sprockets.get(0);
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        return rolls.isEmpty() ? null : rolls.get(0);
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return rolls.isEmpty() ? null : rolls.get(0);
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return new ArrayList<>();
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return rolls.isEmpty() ? null : rolls.get(0);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return rolls.isEmpty() ? null : rolls.get(0);
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power,
                                       int toughness) {
        return swapChoices.isEmpty() ? null : swapChoices.get(0);
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options,
                       ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return options.isEmpty() ? null : options.get(0);
    }

    // =====================================================================
    // Mulligan
    // =====================================================================

    @Override
    public boolean mulliganKeepHand(Player player, int cardsToReturn) {
        AiDecisionRequest request = buildRequest("MULLIGAN",
                "Keep hand or mulligan? (cards to return: " + cardsToReturn + ")");
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        ctx.getOptions().add(new Option("keep", "boolean", "Keep hand", null));
        ctx.getOptions().add(new Option("mulligan", "boolean", "Mulligan", null));

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            return "keep".equals(response.getSelectedOptionId());
        }
        return true;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        return new CardCollection();
    }

    // =====================================================================
    // Spell playing
    // =====================================================================

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        AiDecisionRequest request = buildRequest("SPELL_CHOICE",
                "Choose a spell to play");
        AiDecisionResponse response = httpClient.requestDecision(request);
        // TODO: Parse spell choice from response
        return new ArrayList<>();
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        return true;
    }

    // =====================================================================
    // Mode and cost choices
    // =====================================================================

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible,
                                                  int min, int num, boolean allowRepeat) {
        List<AbilitySub> chosen = new ArrayList<>();
        int count = Math.min(num, possible.size());
        for (int i = 0; i < count; i++) {
            chosen.add(possible.get(i));
        }
        return chosen;
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return min;
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword,
                                           String prompt, int max) {
        return 1;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        AiDecisionRequest request = buildRequest("NUMBER_CHOICE",
                title + " (range: " + min + "-" + max + ")");
        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getValue() != null && response.getValue().containsKey("number")) {
            try {
                int value = Integer.parseInt(response.getValue().get("number").toString());
                return Math.max(min, Math.min(max, value));
            } catch (NumberFormatException e) {
                logger.warn("Invalid number from AI: {}", response.getValue().get("number"));
            }
        }
        return min;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        return values.isEmpty() ? 0 : values.get(0);
    }

    // =====================================================================
    // Binary and flip choices
    // =====================================================================

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice,
                                 Boolean defaultChoice) {
        AiDecisionRequest request = buildRequest("BINARY_CHOICE", question);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        ctx.getOptions().add(new Option("true", "boolean", "Yes/True", null));
        ctx.getOptions().add(new Option("false", "boolean", "No/False", null));

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            return "true".equals(response.getSelectedOptionId());
        }
        return defaultChoice != null ? defaultChoice : false;
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        return call;
    }

    // =====================================================================
    // Color choices
    // =====================================================================

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        AiDecisionRequest request = buildRequest("COLOR_CHOICE", message);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        int idx = 0;
        for (var color : colors) {
            Option option = new Option();
            option.setId(String.valueOf(idx++));
            option.setType("color");
            option.setLabel(color.getName());
            ctx.getOptions().add(option);
        }

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            int selectedIdx = Integer.parseInt(response.getSelectedOptionId());
            int i = 0;
            for (var color : colors) {
                if (i++ == selectedIdx) return color.getColorMask();
            }
        }
        int i = 0;
        for (var color : colors) {
            if (i++ == 0) return color.getColorMask();
        }
        return 0;
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        return chooseColor(message, null, colors);
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        return ColorSet.W;
    }

    // =====================================================================
    // Card face and state choices
    // =====================================================================

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp,
                                           String name) {
        return null;
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        return faces.isEmpty() ? null : faces.get(0);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message,
                                            Map<String, Object> params) {
        return states.isEmpty() ? null : states.get(0);
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2,
                                    String faceUp) {
        return true;
    }

    // =====================================================================
    // Counter and keyword choices
    // =====================================================================

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt,
                                          Map<String, Object> params) {
        return options.isEmpty() ? null : options.get(0);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt,
                                        Card tgtCard) {
        return options.isEmpty() ? null : options.get(0);
    }

    // =====================================================================
    // Payment and cost decisions
    // =====================================================================

    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        return true;
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        return possibleReplacers.isEmpty() ? null : possibleReplacers.get(0);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) {
        return possibleReplacers.isEmpty() ? null : possibleReplacers.get(0);
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        return choices.isEmpty() ? null : choices.get(0);
    }

    // =====================================================================
    // Pre-game reveals
    // =====================================================================

    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) {
        logger.debug("Revealing ante: {}", message);
    }

    @Override
    public void revealAISkipCards(String message,
                                   Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {
        logger.debug("Revealing AI skip cards: {}", message);
    }

    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
        logger.debug("Revealing unsupported cards");
    }

    // =====================================================================
    // Optional costs and cost ordering
    // =====================================================================

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen,
                                                        List<OptionalCostValue> optionalCostValues) {
        return new ArrayList<>();
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return costs;
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid,
                                           FCollectionView<Player> allPayers) {
        return false;
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        return true;
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        return true;
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt,
                                ManaConversionMatrix matrix, boolean effect) {
        return true;
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt,
                                    ManaConversionMatrix matrix, boolean effect) {
        return true;
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa,
                                                  CostPartWithList cpl, int amount, boolean isOptional,
                                                  String prompt) {
        List<Card> chosen = new ArrayList<>();
        int count = isOptional ? 0 : Math.min(amount, optionList.size());
        for (Card card : optionList) {
            if (chosen.size() >= count) break;
            chosen.add(card);
        }
        return new CardCollection(chosen);
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability,
                                                       boolean effect, String prompt) {
        return new CostDecisionMakerBase(player, effect, ability, null) {
            @Override
            public boolean paysRightAfterDecision() {
                return false;
            }

            @Override
            public PaymentDecision visit(CostBehold cost) { return null; }
            @Override
            public PaymentDecision visit(CostBeholdExile cost) { return null; }
            @Override
            public PaymentDecision visit(CostGainControl cost) { return null; }
            @Override
            public PaymentDecision visit(CostChooseColor cost) { return null; }
            @Override
            public PaymentDecision visit(CostChooseCreatureType cost) { return null; }
            @Override
            public PaymentDecision visit(CostCollectEvidence cost) { return null; }
            @Override
            public PaymentDecision visit(CostDiscard cost) { return null; }
            @Override
            public PaymentDecision visit(CostDamage cost) { return null; }
            @Override
            public PaymentDecision visit(CostDraw cost) { return null; }
            @Override
            public PaymentDecision visit(CostExile cost) { return null; }
            @Override
            public PaymentDecision visit(CostExileFromStack cost) { return null; }
            @Override
            public PaymentDecision visit(CostExiledMoveToGrave cost) { return null; }
            @Override
            public PaymentDecision visit(CostExert cost) { return null; }
            @Override
            public PaymentDecision visit(CostEnlist cost) { return null; }
            @Override
            public PaymentDecision visit(CostFlipCoin cost) { return null; }
            @Override
            public PaymentDecision visit(CostForage cost) { return null; }
            @Override
            public PaymentDecision visit(CostRollDice cost) { return null; }
            @Override
            public PaymentDecision visit(CostMill cost) { return null; }
            @Override
            public PaymentDecision visit(CostAddMana cost) { return null; }
            @Override
            public PaymentDecision visit(CostPayLife cost) { return null; }
            @Override
            public PaymentDecision visit(CostPayEnergy cost) { return null; }
            @Override
            public PaymentDecision visit(CostGainLife cost) { return null; }
            @Override
            public PaymentDecision visit(CostPartMana cost) { return null; }
            @Override
            public PaymentDecision visit(CostPromiseGift cost) { return null; }
            @Override
            public PaymentDecision visit(CostPutCardToLib cost) { return null; }
            @Override
            public PaymentDecision visit(CostTap cost) { return null; }
            @Override
            public PaymentDecision visit(CostSacrifice cost) { return null; }
            @Override
            public PaymentDecision visit(CostReturn cost) { return null; }
            @Override
            public PaymentDecision visit(CostReveal cost) { return null; }
            @Override
            public PaymentDecision visit(CostRevealChosen cost) { return null; }
            @Override
            public PaymentDecision visit(CostRemoveAnyCounter cost) { return null; }
            @Override
            public PaymentDecision visit(CostRemoveCounter cost) { return null; }
            @Override
            public PaymentDecision visit(CostPutCounter cost) { return null; }
            @Override
            public PaymentDecision visit(CostPutCounterYou cost) { return null; }
            @Override
            public PaymentDecision visit(CostUntapType cost) { return null; }
            @Override
            public PaymentDecision visit(CostUntap cost) { return null; }
            @Override
            public PaymentDecision visit(CostUnattach cost) { return null; }
            @Override
            public PaymentDecision visit(CostTapType cost) { return null; }
            @Override
            public PaymentDecision visit(CostPayShards cost) { return null; }
            @Override
            public PaymentDecision visit(CostBlight cost) { return null; }
        };
    }

    // =====================================================================
    // Card name choices
    // =====================================================================

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        return valid;
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        return faces.isEmpty() ? null : faces.get(0).getName();
    }

    // =====================================================================
    // Zone change decisions
    // =====================================================================

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                                                CardCollection fetchList, DelayedReveal delayedReveal,
                                                String selectPrompt, boolean isOptional, Player decider) {
        return fetchList.isEmpty() ? null : fetchList.get(0);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin,
                                                SpellAbility sa, CardCollection fetchList, int min, int max,
                                                DelayedReveal delayedReveal, String selectPrompt,
                                                Player decider) {
        List<Card> result = new ArrayList<>();
        int count = Math.min(min, fetchList.size());
        for (Card card : fetchList) {
            if (result.size() >= count) break;
            result.add(card);
        }
        return result;
    }

    // =====================================================================
    // Control methods
    // =====================================================================

    @Override
    public void autoPassCancel() {
        logger.debug("autoPassCancel called");
    }

    @Override
    public void awaitNextInput() {
        logger.debug("awaitNextInput called");
    }

    @Override
    public void cancelAwaitNextInput() {
        logger.debug("cancelAwaitNextInput called");
    }

    // =====================================================================
    // Helper methods
    // =====================================================================

    /**
     * Build an AiDecisionRequest with the current board state.
     */
    private AiDecisionRequest buildRequest(String decisionType, String description) {
        AiDecisionRequest request = new AiDecisionRequest();
        request.setGameId(gameId);

        Game game = getGame();
        // BoardStateSerializer.serialize() returns a JSON string
        String boardStateJson = BoardStateSerializer.serialize(game, getPlayer());
        Map<String, Object> boardStateMap;
        try {
            boardStateMap = objectMapper.readValue(boardStateJson, Map.class);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize board state", e);
            boardStateMap = Collections.emptyMap();
        }
        request.setBoardState(boardStateMap);

        DecisionContext context = new DecisionContext();
        context.setType(decisionType);
        context.setDescription(description);
        context.setOptions(new ArrayList<>());
        context.setConstraints(new Constraints());
        request.setDecisionContext(context);

        return request;
    }
}
