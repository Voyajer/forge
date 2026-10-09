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
import forge.StaticData;
import forge.ai.AiBlockController;
import forge.ai.AiCostDecision;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.game.card.CardState;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor.Color;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.CardPool;
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
import forge.game.combat.CombatUtil;
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
import java.util.stream.Collectors;

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
        ctx.getConstraints().setMinChoices(1);
        ctx.getConstraints().setMaxChoices(1);
        ctx.getOptions().clear();
        for (int i = 0; i < abilities.size(); i++) {
            SpellAbility sa = abilities.get(i);
            Option option = new Option();
            option.setId(String.valueOf(i));
            option.setType("ability");
            option.setLabel(sa.getHostCard().getName() + " - " + sa.getDescription());
            Map<String, Object> details = new HashMap<>();
            details.put("card", sa.getHostCard().getName());
            option.setDetails(details);
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
        // Actually play the ability without putting it on the stack (e.g. via
        // orderAndPlaySimultaneousSa), reusing Forge's built-in cost handling.
        PlaySpellAbility.playSaWithoutPayingManaCost(this, effectSA, mayChoseNewTargets);
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (activePlayerSAs == null || activePlayerSAs.size() <= 1) {
            return activePlayerSAs;
        }

        List<String> labels = new ArrayList<>();
        for (SpellAbility sa : activePlayerSAs) {
            labels.add(sa.getHostCard().getName());
        }
        List<Integer> order = queryOrder("ORDER_ABILITIES",
                "Order " + activePlayerSAs.size() + " simultaneous abilities", labels);

        List<SpellAbility> ordered = new ArrayList<>();
        for (int idx : order) {
            ordered.add(activePlayerSAs.get(idx));
        }
        return ordered;
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
        ctx.getConstraints().setMinChoices(1);
        ctx.getConstraints().setMaxChoices(1);
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
        String name = (tgtSA != null && tgtSA.getHostCard() != null) ? tgtSA.getHostCard().getName() : "ability";
        return queryBoolean("PLAY_SA_FROM_EFFECT",
                "Play " + name + " from play effect?",
                "Yes, play", "No, don't play", true);
    }

    // =====================================================================
    // Deck building decisions
    // =====================================================================

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        CardPool sideboard = deck.get(DeckSection.Sideboard);
        if (sideboard == null || sideboard.isEmpty()) {
            return null; // nothing to sideboard with — use default plan
        }
        CardPool main = deck.get(DeckSection.Main);
        int mainSize = main.countAll();
        int sbSize = sideboard.countAll();
        int combined = mainSize + sbSize;
        int deckMin = Math.min(mainSize, gameType.getDeckFormat().getMainRange().getMinimum());
        int sbMax = combined - deckMin;

        // Offer every sideboard card; the AI picks which to put into the main deck.
        List<PaperCard> sbCards = sideboard.toFlatList();
        List<String> sbLabels = new ArrayList<>();
        for (PaperCard c : sbCards) {
            sbLabels.add(c.getName());
        }
        List<Integer> chosenSb = queryMulti("SIDEBOARD",
                "Choose which sideboard cards to put into the main deck "
                        + "(main must stay at least " + deckMin + " cards)",
                sbLabels, 0, Math.min(sbMax, sbCards.size()), true);

        // Dedup and clamp the sideboard picks.
        List<PaperCard> toAdd = new ArrayList<>();
        Set<Integer> seenSb = new HashSet<>();
        for (int idx : chosenSb) {
            if (idx >= 0 && idx < sbCards.size() && seenSb.add(idx)) {
                toAdd.add(sbCards.get(idx));
            }
        }

        // A sideboard swap keeps the main deck size constant: for every sideboard
        // card added, one main-deck card must be removed. Ask the AI which main
        // cards to remove (exactly one per sideboard card added).
        List<PaperCard> mainCards = main.toFlatList();
        List<PaperCard> toRemove = new ArrayList<>();
        if (!toAdd.isEmpty() && !mainCards.isEmpty()) {
            List<String> mainLabels = new ArrayList<>();
            for (PaperCard c : mainCards) {
                mainLabels.add(c.getName());
            }
            List<Integer> chosenMain = queryMulti("SIDEBOARD_REMOVE",
                    "Choose " + toAdd.size() + " main-deck card(s) to remove "
                            + "(one for each sideboard card added)",
                    mainLabels, toAdd.size(), toAdd.size(), false);
            Set<Integer> seenMain = new HashSet<>();
            for (int idx : chosenMain) {
                if (idx >= 0 && idx < mainCards.size() && seenMain.add(idx)) {
                    toRemove.add(mainCards.get(idx));
                }
            }
            // Pad with the first main cards if the AI under-selected.
            for (int i = 0; i < mainCards.size() && toRemove.size() < toAdd.size(); i++) {
                if (seenMain.add(i)) {
                    toRemove.add(mainCards.get(i));
                }
            }
        }

        // Build the new main deck: remove the displaced cards, add the sideboard cards.
        List<PaperCard> newMain = new ArrayList<>(mainCards);
        for (PaperCard c : toRemove) {
            newMain.remove(c);
        }
        newMain.addAll(toAdd);
        return newMain;
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        if (losses.isEmpty()) {
            return losses;
        }
        List<String> labels = new ArrayList<>();
        for (PaperCard c : losses) {
            labels.add(c.getName());
        }
        List<Integer> chosen = queryMulti("ADD_TO_DECK",
                "Choose which cards to add back to your deck", labels, 0, losses.size(), true);
        List<PaperCard> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < losses.size()) {
                result.add(losses.get(idx));
            }
        }
        return result;
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

        // Rule 510.1c: the attacking player must assign at least lethal damage
        // to each blocker in declared order before assigning damage to the next
        // blocker (or the defending player). So for each blocker the minimum
        // assignment is min(remaining, toughness); the AI may overkill (assign
        // up to all remaining damage) but never less than lethal.
        int remainingDamage = damageDealt;
        for (Card blocker : blockers) {
            if (remainingDamage <= 0) {
                break;
            }
            int toughness = blocker.getCurrentToughness();
            int minRequired = Math.min(remainingDamage, toughness);
            int assigned = queryNumber("COMBAT_DAMAGE_ASSIGNMENT",
                    "Assign damage to " + blocker.getName() + " from " + attacker.getName()
                            + " (" + minRequired + "-" + remainingDamage + ", at least lethal)",
                    minRequired, remainingDamage);
            if (assigned > 0) {
                damageMap.put(blocker, assigned);
            }
            remainingDamage -= assigned;
        }
        return damageMap;
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected,
                                                  int shieldAmount) {
        Map<GameEntity, Integer> result = new HashMap<>();
        if (affected.isEmpty()) {
            return result;
        }
        // Ask the AI how much of the remaining shield to give each entity, in order.
        int remaining = shieldAmount;
        for (Map.Entry<GameEntity, Integer> entry : affected.entrySet()) {
            if (remaining <= 0) {
                break;
            }
            int maxForThis = Math.min(remaining, entry.getValue());
            int assigned = queryNumber("SHIELD_DIVISION",
                    "Assign shield to " + entry.getKey() + " (0-" + maxForThis + ")",
                    0, maxForThis);
            if (assigned > 0) {
                result.put(entry.getKey(), assigned);
                remaining -= assigned;
            }
        }
        // If the AI under-assigned, front-load the rest so the shield is fully placed.
        if (remaining > 0) {
            for (Map.Entry<GameEntity, Integer> entry : affected.entrySet()) {
                if (remaining <= 0) {
                    break;
                }
                int already = result.getOrDefault(entry.getKey(), 0);
                int room = Math.max(0, entry.getValue() - already);
                int amount = Math.min(remaining, room);
                if (amount > 0) {
                    result.put(entry.getKey(), already + amount);
                    remaining -= amount;
                }
            }
        }
        return result;
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount,
                                                boolean different) {
        Map<Byte, Integer> result = new HashMap<>();
        if (colorSet == null || colorSet.countColors() == 0) {
            return result;
        }
        // Offer each color in the set; the AI picks which one to pay with.
        List<Color> colors = new ArrayList<>(colorSet.toEnumSet());
        List<String> labels = new ArrayList<>();
        for (Color c : colors) {
            labels.add(c.getName());
        }
        int idx = querySingle("MANA_COMBO",
                "Choose a color to pay " + manaAmount + " mana with", labels, 0);
        result.put(colors.get(idx).getColorMask(), manaAmount);
        return result;
    }

    // =====================================================================
    // Card selection decisions
    // =====================================================================

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
                                                           CardCollectionView validTargets, String message) {
        if (validTargets.isEmpty()) {
            return new CardCollection();
        }
        return queryCards("SACRIFICE_CHOICE", message, new ArrayList<>(validTargets), min, max, false);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
                                                         CardCollectionView validTargets, String message) {
        return choosePermanentsToSacrifice(sa, min, max, validTargets, message);
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        return queryNumber("ANNOUNCE_NUMBER",
                "Announce a number between " + min + " and " + max + " for " + announce,
                min, max);
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter,
                                              boolean optional) {
        // Build the candidate list from the ability's target restrictions, then
        // apply the caller's filter.
        if (ability.getActivatingPlayer() == null) {
            ability.setActivatingPlayer(getPlayer());
        }
        List<GameObject> candidates = new ArrayList<>();
        for (GameEntity e : ability.getTargetRestrictions().getAllCandidates(ability)) {
            if (filter == null || filter.test(e)) {
                candidates.add(e);
            }
        }
        if (candidates.isEmpty()) {
            return new TargetChoices();
        }

        List<String> labels = new ArrayList<>();
        for (GameObject c : candidates) {
            labels.add(c.toString());
        }
        int min = ability.getMinTargets();
        int max = ability.getMaxTargets();
        List<Integer> chosen = queryMulti("TARGET_CHOICE",
                "Choose up to " + max + " target(s) for " + ability.getHostCard().getName(),
                labels, min, max, optional);

        TargetChoices targets = new TargetChoices();
        for (int idx : chosen) {
            if (idx >= 0 && idx < candidates.size() && targets.size() < max) {
                targets.add(candidates.get(idx));
            }
        }
        // Pad with the first candidates until the minimum is met.
        for (int i = 0; i < candidates.size() && targets.size() < min; i++) {
            if (!targets.contains(candidates.get(i))) {
                targets.add(candidates.get(i));
            }
        }
        return targets;
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        // Non-targeting abilities have nothing to choose.
        if (!currentAbility.usesTargeting()) {
            return true;
        }

        // Candidate lookup needs the activating player to be set.
        if (currentAbility.getActivatingPlayer() == null) {
            currentAbility.setActivatingPlayer(getPlayer());
        }

        List<GameEntity> candidates =
                currentAbility.getTargetRestrictions().getAllCandidates(currentAbility);
        int minTargets = currentAbility.getMinTargets();
        int maxTargets = currentAbility.getMaxTargets();

        // No valid candidates: only acceptable if the ability needs none.
        if (candidates.isEmpty()) {
            return minTargets <= 0;
        }
        // Not enough candidates to satisfy the minimum — can't be cast.
        if (candidates.size() < minTargets) {
            return false;
        }

        // Ask the AI to pick up to `maxTargets` of the candidates.
        AiDecisionRequest request = buildRequest("TARGET_CHOICE",
                "Choose up to " + maxTargets + " target(s) for "
                        + currentAbility.getHostCard().getName());
        DecisionContext ctx = request.getDecisionContext();
        ctx.getConstraints().setMinChoices(minTargets);
        ctx.getConstraints().setMaxChoices(maxTargets);
        ctx.getOptions().clear();
        for (int i = 0; i < candidates.size(); i++) {
            Option option = new Option();
            option.setId(String.valueOf(i));
            option.setType("target");
            option.setLabel(candidates.get(i).toString());
            Map<String, Object> details = new HashMap<>();
            details.put("card", candidates.get(i).getName());
            option.setDetails(details);
            ctx.getOptions().add(option);
        }

        AiDecisionResponse response = httpClient.requestDecision(request);

        // Parse chosen indices (multi-select via selectedOptionIds,
        // single-select via selectedOptionId).
        List<Integer> chosenIdx = new ArrayList<>();
        if (response != null) {
            if (response.getSelectedOptionIds() != null) {
                for (String id : response.getSelectedOptionIds()) {
                    try {
                        chosenIdx.add(Integer.parseInt(id));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (response.getSelectedOptionId() != null) {
                try {
                    chosenIdx.add(Integer.parseInt(response.getSelectedOptionId()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // If the AI gave nothing usable, default to the first candidate.
        if (chosenIdx.isEmpty()) {
            chosenIdx.add(0);
        }

        TargetChoices targets = currentAbility.getTargets();
        Set<GameObject> added = new HashSet<>();
        for (int idx : chosenIdx) {
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }
            GameObject target = candidates.get(idx);
            if (added.add(target) && targets.size() < maxTargets) {
                targets.add(target);
            }
        }

        // Pad with the first candidates until the minimum is met.
        for (int i = 0; i < candidates.size() && targets.size() < minTargets; i++) {
            GameObject target = candidates.get(i);
            if (added.add(target)) {
                targets.add(target);
            }
        }

        return currentAbility.isTargetNumberValid();
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
        ctx.getConstraints().setMinChoices(1);
        ctx.getConstraints().setMaxChoices(1);
        ctx.getOptions().clear();
        for (int i = 0; i < allTargets.size(); i++) {
            Option option = new Option();
            option.setId(String.valueOf(i));
            option.setType("target");
            option.setLabel(allTargets.get(i).getRight().toString());
            Map<String, Object> details = new HashMap<>();
            details.put("card", allTargets.get(i).getRight().toString());
            option.setDetails(details);
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
        return queryBoolean("ASSIST_PAYMENT",
                "Help pay for " + sa.getHostCard().getName() + "?",
                "Yes, help pay", "No, don't help", false);
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa,
                                               String title, int max) {
        if (optionList.isEmpty()) {
            return null;
        }
        List<Player> players = new ArrayList<>(optionList);
        List<String> labels = new ArrayList<>();
        for (Player p : players) {
            labels.add(p.getName());
        }
        int idx = querySingle("ASSIST_PAYMENT", title, labels, 0);
        return players.get(idx);
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
            Map<String, Object> details = new HashMap<>();
            details.put("card", card.getName());
            option.setDetails(details);
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
        // Flatten all valid cards into one pool and let the AI choose.
        List<Card> all = new ArrayList<>();
        for (Map.Entry<String, CardCollection> entry : validMap.entrySet()) {
            all.addAll(entry.getValue());
        }
        if (all.isEmpty()) {
            return new CardCollection();
        }
        return queryCards("CARD_SELECTION", title, all, 0, all.size(), isOptional);
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
        ctx.getConstraints().setMinChoices(1);
        ctx.getConstraints().setMaxChoices(1);
        ctx.getOptions().clear();
        int idx = 0;
        for (T option : optionList) {
            Option opt = new Option();
            opt.setId(String.valueOf(idx++));
            opt.setType("entity");
            opt.setLabel(option.getName());
            Map<String, Object> details = new HashMap<>();
            details.put("card", option.getName());
            opt.setDetails(details);
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
        List<T> options = new ArrayList<>(optionList);
        if (options.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> labels = new ArrayList<>();
        for (T option : options) {
            labels.add(option.toString());
        }
        List<Integer> chosen = queryMulti("ENTITY_SELECTION", title, labels, min, max, false);
        List<T> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < options.size()) {
                result.add(options.get(idx));
            }
        }
        for (int i = 0; i < options.size() && result.size() < min; i++) {
            if (!result.contains(options.get(i))) {
                result.add(options.get(i));
            }
        }
        return result;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa,
                                                             String title, int num,
                                                             Map<String, Object> params) {
        if (spells.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> labels = new ArrayList<>();
        for (SpellAbility s : spells) {
            labels.add(s.getHostCard().getName() + " - " + s.getDescription());
        }
        List<Integer> chosen = queryMulti("SPELL_ABILITY_CHOICE", title, labels, 0, num, true);
        List<SpellAbility> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < spells.size()) {
                result.add(spells.get(idx));
            }
        }
        return result;
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa,
                                                    String title, Map<String, Object> params) {
        if (spells.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (SpellAbility s : spells) {
            labels.add(s.getHostCard().getName() + " - " + s.getDescription());
        }
        int idx = querySingle("SPELL_ABILITY_CHOICE", title, labels, 0);
        return spells.get(idx);
    }

    // =====================================================================
    // Confirmation decisions
    // =====================================================================

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
                                  List<String> options, Card cardToShow, Map<String, Object> params) {
        AiDecisionRequest request = buildRequest("CONFIRM_ACTION", message);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getConstraints().setMinChoices(1);
        ctx.getConstraints().setMaxChoices(1);
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
        return queryBoolean("CONFIRM_BID", string, "Yes", "No", false);
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA,
                                             GameEntity affected, String question) {
        return queryBoolean("CONFIRM_REPLACEMENT", question, "Yes", "No", true);
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message,
                                             String logic) {
        return queryBoolean("CONFIRM_STATIC", message, "Yes", "No", true);
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        return queryBoolean("CONFIRM_TRIGGER",
                "Trigger " + sa.getWrappedAbility().getHostCard().getName() + "?",
                "Yes, trigger", "No, don't trigger", true);
    }

    // =====================================================================
    // Combat declaration
    // =====================================================================

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        if (attackers.isEmpty()) {
            return attackers;
        }
        List<String> labels = new ArrayList<>();
        for (Card c : attackers) {
            labels.add(c.getName());
        }
        List<Integer> chosen = queryMulti("EXERT_ATTACKERS",
                "Choose which attackers to exert", labels, 0, attackers.size(), true);
        List<Card> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < attackers.size()) {
                result.add(attackers.get(idx));
            }
        }
        return result;
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        if (attackers.isEmpty()) {
            return attackers;
        }
        List<String> labels = new ArrayList<>();
        for (Card c : attackers) {
            labels.add(c.getName());
        }
        List<Integer> chosen = queryMulti("ENLIST_ATTACKERS",
                "Choose which attackers to enlist", labels, 0, attackers.size(), true);
        List<Card> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < attackers.size()) {
                result.add(attackers.get(idx));
            }
        }
        return result;
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        // Collect every legal attacker/defender pair the AI may choose from.
        List<Card> possibleAttackers = new ArrayList<>(CombatUtil.getPossibleAttackers(attacker));
        if (possibleAttackers.isEmpty()) {
            return;
        }

        List<String> labels = new ArrayList<>();
        List<Card> attackerCards = new ArrayList<>();
        List<GameEntity> defenders = new ArrayList<>();
        for (Card c : possibleAttackers) {
            for (GameEntity defender : CombatUtil.getAllPossibleDefenders(attacker)) {
                if (CombatUtil.canAttack(c, defender)) {
                    labels.add(c.getName() + " -> " + defender);
                    attackerCards.add(c);
                    defenders.add(defender);
                }
            }
        }
        if (labels.isEmpty()) {
            return;
        }

        List<Integer> chosen = queryMulti("DECLARE_ATTACKERS",
                "Declare attackers (pick each attacker/defender pair to attack with)",
                labels, 0, labels.size(), true);

        // Dedup per attacker: each attacker may attack at most one defender, so
        // if the AI picked multiple pairs for the same attacker, keep only the first.
        Set<Card> addedAttackers = new HashSet<>();
        for (int idx : chosen) {
            Card attackerCard = attackerCards.get(idx);
            if (addedAttackers.add(attackerCard)) {
                combat.addAttacker(attackerCard, defenders.get(idx));
            }
        }

        // If the AI picked nothing but attacking is mandatory (goad/must-attack),
        // fall back to the legal attackers so the declaration is valid.
        if (chosen.isEmpty() && !CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            for (Map.Entry<Card, GameEntity> e
                    : combat.getAttackConstraints().getLegalAttackers().getLeft().entrySet()) {
                combat.addAttacker(e.getKey(), e.getValue());
            }
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        List<Card> attackers = new ArrayList<>(combat.getAttackers());
        if (attackers.isEmpty()) {
            return;
        }

        // Collect every legal blocker/attacker pair the AI may choose from.
        List<Card> possibleBlockers = new ArrayList<>(defender.getCreaturesInPlay());
        List<String> labels = new ArrayList<>();
        List<Card> blockerCards = new ArrayList<>();
        List<Card> attackerCards = new ArrayList<>();
        for (Card b : possibleBlockers) {
            if (!CombatUtil.canBlock(b, combat)) {
                continue;
            }
            for (Card a : attackers) {
                if (CombatUtil.canBlock(a, b, combat)) {
                    labels.add(b.getName() + " blocks " + a.getName());
                    blockerCards.add(b);
                    attackerCards.add(a);
                }
            }
        }
        if (labels.isEmpty()) {
            return;
        }

        List<Integer> chosen = queryMulti("DECLARE_BLOCKERS",
                "Declare blockers (pick each blocker/attacker pair to block with)",
                labels, 0, labels.size(), true);

        // Dedup per blocker: each blocker may block at most one attacker, so if
        // the AI picked multiple pairs for the same blocker, keep only the first.
        Set<Card> addedBlockers = new HashSet<>();
        for (int idx : chosen) {
            Card blockerCard = blockerCards.get(idx);
            if (addedBlockers.add(blockerCard)) {
                combat.addBlocker(attackerCards.get(idx), blockerCard);
            }
        }

        // If the AI picked nothing but blocking is mandatory (must-block/lure),
        // fall back to Forge's built-in AI blocker logic so the declaration is valid.
        if (chosen.isEmpty() && CombatUtil.validateBlocks(combat, defender) != null) {
            new AiBlockController(defender, defender != getPlayer())
                    .assignBlockersForCombat(combat);
        }
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        if (blockers.size() <= 1) {
            return blockers;
        }
        List<String> labels = new ArrayList<>();
        for (Card b : blockers) {
            labels.add(b.getName());
        }
        List<Integer> order = queryOrder("ORDER_BLOCKERS",
                "Order the blockers of " + attacker.getName() + " for damage assignment", labels);
        CardCollection result = new CardCollection();
        for (int idx : order) {
            result.add(blockers.get(idx));
        }
        return result;
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        // Insert the new blocker at the position the AI chooses.
        List<Card> all = new ArrayList<>(oldBlockers);
        all.add(blocker);
        List<String> labels = new ArrayList<>();
        for (Card b : all) {
            labels.add(b.getName());
        }
        List<Integer> order = queryOrder("ORDER_BLOCKERS",
                "Order the blockers of " + attacker.getName() + " for damage assignment", labels);
        CardCollection result = new CardCollection();
        for (int idx : order) {
            result.add(all.get(idx));
        }
        return result;
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        if (attackers.size() <= 1) {
            return attackers;
        }
        List<String> labels = new ArrayList<>();
        for (Card a : attackers) {
            labels.add(a.getName());
        }
        List<Integer> order = queryOrder("ORDER_ATTACKERS",
                "Order the attackers blocked by " + blocker.getName() + " for damage assignment", labels);
        CardCollection result = new CardCollection();
        for (int idx : order) {
            result.add(attackers.get(idx));
        }
        return result;
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
        if (topN.isEmpty()) {
            return new ImmutablePair<>(new CardCollection(), new CardCollection());
        }
        // The AI picks which of the scryed cards to put back on top (face-up order);
        // the rest go to the bottom (face-down).
        List<Card> cards = new ArrayList<>(topN);
        List<String> labels = new ArrayList<>();
        for (Card c : cards) {
            labels.add(c.getName());
        }
        List<Integer> chosen = queryMulti("SCRY_ARRANGE",
                "Choose which cards to put back on top of the library (in order)",
                labels, 0, cards.size(), true);

        CardCollection faceUp = new CardCollection();
        CardCollection faceDown = new CardCollection();
        for (int idx : chosen) {
            if (idx >= 0 && idx < cards.size()) {
                faceUp.add(cards.get(idx));
            }
        }
        for (int i = 0; i < cards.size(); i++) {
            if (!faceUp.contains(cards.get(i))) {
                faceDown.add(cards.get(i));
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
        return queryBoolean("SCRY_ARRANGE", "Put " + c.getName() + " on top of the library?",
                "Yes, on top", "No, to the bottom", false);
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
                                                   SpellAbility source) {
        if (cards.size() <= 1) {
            return cards;
        }
        List<Card> list = new ArrayList<>(cards);
        List<String> labels = new ArrayList<>();
        for (Card c : list) {
            labels.add(c.getName());
        }
        List<Integer> order = queryOrder("ZONE_ORDER",
                "Order the cards moving to " + destinationZone, labels);
        CardCollection result = new CardCollection();
        for (int idx : order) {
            result.add(list.get(idx));
        }
        return result;
    }

    // =====================================================================
    // Discard decisions
    // =====================================================================

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa,
                                                        CardCollection validCards, int min, int max,
                                                        CardCollectionView visibleToChooser) {
        if (validCards.isEmpty()) {
            return new CardCollection();
        }
        return queryCards("DISCARD_CHOICE",
                "Choose cards to discard for " + sa.getHostCard().getName(),
                new ArrayList<>(validCards), min, max, false);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand,
                                                               String[] unlessTypes, SpellAbility sa) {
        // Filter out cards matching the "unless" types — those are protected.
        List<Card> discardable = new ArrayList<>();
        for (Card card : hand) {
            boolean matches = false;
            for (String type : unlessTypes) {
                if (card.getCurrentState().getType().hasSubtype(type)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                discardable.add(card);
            }
        }
        if (discardable.isEmpty()) {
            return new CardCollection();
        }
        int count = Math.min(min, discardable.size());
        return queryCards("DISCARD_UNLESS_TYPE",
                "Choose " + count + " card(s) to discard (cards of type "
                        + Arrays.toString(unlessTypes) + " are protected)",
                discardable, count, count, false);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollectionView hand = getPlayer().getCardsIn(ZoneType.Hand);
        if (hand.isEmpty() || numDiscard <= 0) {
            return new CardCollection();
        }
        int count = Math.min(numDiscard, hand.size());
        return queryCards("DISCARD_CHOICE",
                "Choose " + count + " card(s) to discard to reduce to maximum hand size",
                new ArrayList<>(hand), count, count, false);
    }

    // =====================================================================
    // Keyword cost decisions
    // =====================================================================

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        if (grave.isEmpty() || genericAmount <= 0) {
            return new CardCollection();
        }
        int count = Math.min(genericAmount, grave.size());
        return queryCards("DELVE_CHOICE",
                "Choose " + count + " card(s) to delve from the graveyard",
                new ArrayList<>(grave), count, count, false);
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost,
                                                                       CardCollectionView untappedCards,
                                                                       boolean artifacts, boolean creatures,
                                                                       Integer maxReduction) {
        Map<Card, ManaCostShard> result = new HashMap<>();
        if (untappedCards.isEmpty() || maxReduction == null || maxReduction <= 0) {
            return result;
        }
        List<Card> cards = new ArrayList<>(untappedCards);
        int max = Math.min(maxReduction, cards.size());
        CardCollection chosen = queryCards("CONVOKE_IMPROVISE",
                "Choose up to " + max + " card(s) to convoke/improvise for "
                        + sa.getHostCard().getName(),
                cards, 0, max, true);
        if (chosen.isEmpty()) {
            return result;
        }
        // Assign each chosen card to a shard of the cost it can pay for.
        List<ManaCostShard> shards = new ArrayList<>();
        for (ManaCostShard shard : manaCost) {
            shards.add(shard);
        }
        for (Card card : chosen) {
            if (result.size() >= max) {
                break;
            }
            for (Iterator<ManaCostShard> it = shards.iterator(); it.hasNext(); ) {
                ManaCostShard shard = it.next();
                if (shard.isGeneric() || (card.getColor().getColor() & shard.getColorMask()) != 0) {
                    it.remove();
                    result.put(card, shard);
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        if (cards.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> labels = new ArrayList<>();
        for (Card c : cards) {
            labels.add(c.getName());
        }
        List<Integer> chosen = queryMulti("SPLICE_CHOICE",
                "Choose which cards to splice onto " + sa.getHostCard().getName(),
                labels, 0, cards.size(), true);
        List<Card> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < cards.size()) {
                result.add(cards.get(idx));
            }
        }
        return result;
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        if (valid.isEmpty()) {
            return new CardCollection();
        }
        return queryCards("REVEAL_CHOICE",
                "Choose cards to reveal from hand", new ArrayList<>(valid), min, max, false);
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        if (usableFromOpeningHand.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> labels = new ArrayList<>();
        for (SpellAbility s : usableFromOpeningHand) {
            labels.add(s.getHostCard().getName() + " - " + s.getDescription());
        }
        List<Integer> chosen = queryMulti("OPENING_HAND_ABILITY",
                "Choose which abilities to activate from your opening hand",
                labels, 0, usableFromOpeningHand.size(), true);
        List<SpellAbility> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < usableFromOpeningHand.size()) {
                result.add(usableFromOpeningHand.get(idx));
            }
        }
        return result;
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        // Ask the AI whether to go first. In most formats the starting player is
        // determined by game rules, but when the controller is asked, let the
        // model decide.
        boolean goFirst = queryBoolean("STARTING_PLAYER",
                "Go first this game?",
                "Yes, go first", "No, go second", true);
        if (goFirst) {
            return getPlayer();
        }
        // Return the opponent if we don't go first.
        for (Player p : getGame().getPlayers()) {
            if (p != getPlayer()) {
                return p;
            }
        }
        return getPlayer();
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        if (zones.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (PlayerZone z : zones) {
            labels.add(z.toString());
        }
        int idx = querySingle("STARTING_HAND", "Choose which hand to start with", labels, 0);
        return zones.get(idx);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        if (manaChoices.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (Mana m : manaChoices) {
            labels.add(m.toString());
        }
        int idx = querySingle("MANA_CHOICE", "Choose mana from the pool", labels, 0);
        return manaChoices.get(idx);
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
        if (sectors.isEmpty()) {
            return null;
        }
        int idx = querySingle("SECTOR_CHOICE", "Choose a sector for " + assignee.getName(), sectors, 0);
        return sectors.get(idx);
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        if (contraptions.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> labels = new ArrayList<>();
        for (Card c : contraptions) {
            labels.add(c.getName());
        }
        List<Integer> chosen = queryMulti("CONTRAPTION_CHOICE",
                "Choose which contraptions to crank", labels, 0, contraptions.size(), true);
        List<Card> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < contraptions.size()) {
                result.add(contraptions.get(idx));
            }
        }
        return result;
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        if (sprockets.isEmpty()) {
            return 1;
        }
        List<String> labels = new ArrayList<>();
        for (int s : sprockets) {
            labels.add(String.valueOf(s));
        }
        int idx = querySingle("SPROCKET_CHOICE", "Choose a sprocket for " + assignee.getName(), labels, 0);
        return sprockets.get(idx);
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        if (rolls.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (PlanarDice r : rolls) {
            labels.add(r.name());
        }
        int idx = querySingle("DICE_CHOICE", "Choose which roll to ignore", labels, 0);
        return rolls.get(idx);
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        if (rolls.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (int r : rolls) {
            labels.add(String.valueOf(r));
        }
        int idx = querySingle("DICE_CHOICE", "Choose which roll to ignore", labels, 0);
        return rolls.get(idx);
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        if (rolls.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> labels = new ArrayList<>();
        for (int r : rolls) {
            labels.add(String.valueOf(r));
        }
        List<Integer> chosen = queryMulti("DICE_CHOICE",
                "Choose which dice to reroll", labels, 0, rolls.size(), true);
        List<Integer> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < rolls.size()) {
                result.add(rolls.get(idx));
            }
        }
        return result;
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        if (rolls.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (int r : rolls) {
            labels.add(String.valueOf(r));
        }
        int idx = querySingle("DICE_CHOICE", "Choose which roll to modify", labels, 0);
        return rolls.get(idx);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        if (rolls.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (RollDiceEffect.DieRollResult r : rolls) {
            labels.add(r.toString());
        }
        int idx = querySingle("DICE_CHOICE", "Choose which roll to swap", labels, 0);
        return rolls.get(idx);
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power,
                                       int toughness) {
        if (swapChoices.isEmpty()) {
            return null;
        }
        int idx = querySingle("DICE_CHOICE", "Choose a value to swap to", swapChoices, 0);
        return swapChoices.get(idx);
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options,
                       ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        if (options.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (Object o : options) {
            labels.add(o.toString());
        }
        int idx = querySingle("VOTE", prompt, labels, 0);
        return options.get(idx);
    }

    // =====================================================================
    // Mulligan
    // =====================================================================

    @Override
    public boolean mulliganKeepHand(Player player, int cardsToReturn) {
        // Hard cap: never mulligan past the point where we'd be keeping only
        // 1 card (i.e. 6 cards tuck-returned). Keep the hand at that point.
        if (cardsToReturn >= 6) {
            logger.info("Mulligan cap reached (cardsToReturn={}), forcing keep", cardsToReturn);
            return true;
        }

        int mulliganCount = player.getStats().getMulliganCount();
        AiDecisionRequest request = buildRequest("MULLIGAN",
                "Keep hand or mulligan? (cards to return: " + cardsToReturn
                        + ", mulligans so far: " + mulliganCount + ")");
        DecisionContext ctx = request.getDecisionContext();
        ctx.setCardsToReturn(cardsToReturn);
        ctx.setMulliganCount(mulliganCount);
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
        if (hand.isEmpty() || cardsToReturn <= 0) {
            return new CardCollection();
        }
        List<Card> cards = new ArrayList<>(hand);
        List<String> labels = new ArrayList<>();
        for (Card c : cards) {
            labels.add(c.getName());
        }
        int count = Math.min(cardsToReturn, cards.size());
        List<Integer> chosen = queryMulti("MULLIGAN_TUCK",
                "Choose " + count + " card(s) to tuck face-down (London mulligan)",
                labels, count, count, false);

        CardCollection result = new CardCollection();
        for (int idx : chosen) {
            if (idx >= 0 && idx < cards.size()) {
                result.add(cards.get(idx));
            }
        }
        // Pad with the first cards if the AI under-selected.
        for (int i = 0; i < cards.size() && result.size() < count; i++) {
            if (!result.contains(cards.get(i))) {
                result.add(cards.get(i));
            }
        }
        return result;
    }

    // =====================================================================
    // Spell playing
    // =====================================================================

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // Return null to signal "pass" (no spell to play).
        // Returning an empty list would cause PhaseHandler to loop 999 times
        // because it only breaks on null, not on empty collections.

        Player p = getPlayer();

        // Collect all playable spell abilities from every zone a card can be
        // played or activated from, including land abilities. The model decides
        // whether to play a land, a spell, an activated ability, or pass.
        //
        // Zones scanned (full parity with the built-in AI):
        //   Hand        - cast spells, play lands
        //   Battlefield - activated abilities of permanents
        //   Flashback   - flashback cards (dedicated zone, not the graveyard)
        //   Command     - command-zone cards (schemes, cast-from-command, etc.)
        //   Exile       - foretell/suspend/cast-from-exile cards
        //   Graveyard   - rare cast-from-graveyard effects
        //
        // getAllPossibleAbilities(p, true) filters each ability through
        // canPlay(), which enforces the ability's zone restriction, so only
        // abilities actually playable from their current zone are offered.
        CardCollection candidates = new CardCollection();
        candidates.addAll(p.getCardsIn(
                ZoneType.Hand,
                ZoneType.Battlefield,
                ZoneType.Flashback,
                ZoneType.Command,
                ZoneType.Exile,
                ZoneType.Graveyard));

        List<SpellAbility> playable = new ArrayList<>();
        int totalAbs = 0, landAbs = 0, spellAbs = 0, actAbs = 0,
                droppedCost = 0, droppedTarget = 0;
        for (Card c : candidates) {
            for (SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
                totalAbs++;
                if (sa.isLandAbility()) {
                    landAbs++;
                }
                if (sa.isSpell()) {
                    spellAbs++;
                }
                if (sa.isActivatedAbility()) {
                    actAbs++;
                }
                // A "card play" = casting a spell or playing a land (both from
                // hand). Activated abilities of permanents already in play are a
                // separate proactive action and are kept alongside card plays.
                // Mana abilities (e.g. tapping a land for mana) are excluded: the
                // built-in AI never plays them as a standalone action — mana is
                // paid as part of casting a spell, so offering them here would
                // only let the AI waste a turn tapping a land for no reason.
                boolean isCard = sa.isLandAbility() || sa.isSpell();
                boolean isActivation = sa.isActivatedAbility() && !sa.isManaAbility();
                if (isCard || isActivation) {
                    // Only offer spells whose costs we can actually pay, so the
                    // AI never picks a spell it can't cast (which would make
                    // PhaseHandler loop until "AI looped too much"). Land plays
                    // have NO_COST so they always pass this check.
                    if (!ComputerUtilCost.canPayCost(sa, p, false)) {
                        droppedCost++;
                        continue;
                    }
                    // Skip spells that require targets we can't actually make,
                    // otherwise chooseTargetsFor() would fail and PhaseHandler
                    // would loop until "AI looped too much".
                    if (sa.usesTargeting() && sa.getMinTargets() > 0) {
                        sa.setActivatingPlayer(p);
                        List<GameEntity> cands =
                                sa.getTargetRestrictions().getAllCandidates(sa);
                        if (cands.size() < sa.getMinTargets()) {
                            droppedTarget++;
                            continue;
                        }
                    }
                    playable.add(sa);
                }
            }
        }

        // DIAGNOSTIC: log why chooseSpellAbilityToPlay() is (or isn't) offering
        // options. Runs on every priority; only log when nothing is playable to
        // avoid flooding the log.
        if (playable.isEmpty()) {
            logger.info("[DIAG] chooseSpellAbilityToPlay player={} hand={} battlefield={} "
                            + "totalAbs={} landAbs={} spellAbs={} actAbs={} droppedCost={} "
                            + "droppedTarget={} playable=0 mana={}",
                    p.getName(),
                    p.getCardsIn(ZoneType.Hand).size(),
                    p.getCardsIn(ZoneType.Battlefield).size(),
                    totalAbs, landAbs, spellAbs, actAbs, droppedCost, droppedTarget,
                    p.getManaPool().totalMana());
        }

        if (playable.isEmpty()) {
            return null; // nothing to play — pass
        }

        // Query the AI server to pick which spell to play (or pass).
        AiDecisionRequest request = buildRequest("SPELL_ABILITY_CHOICE",
                "Choose a spell to play from " + playable.size() + " options (or pass)");
        DecisionContext ctx = request.getDecisionContext();
        ctx.getOptions().clear();
        for (int i = 0; i < playable.size(); i++) {
            SpellAbility sa = playable.get(i);
            Option option = new Option();
            option.setId(String.valueOf(i));
            option.setType("ability");
            option.setLabel(sa.getHostCard().getName() + " - " + sa.getDescription());
            ctx.getOptions().add(option);
        }
        // Add a "pass" option so the model can choose to do nothing.
        Option passOption = new Option();
        passOption.setId("pass");
        passOption.setType("pass");
        passOption.setLabel("Pass (do nothing)");
        ctx.getOptions().add(passOption);

        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            String selected = response.getSelectedOptionId();
            if ("pass".equals(selected)) {
                return null; // model chose to pass
            }
            try {
                int idx = Integer.parseInt(selected);
                idx = Math.max(0, Math.min(idx, playable.size() - 1));
                return Collections.singletonList(playable.get(idx));
            } catch (NumberFormatException e) {
                logger.warn("Invalid spell index from AI: {}", selected);
            }
        }

        // Fallback: play the first playable spell.
        return Collections.singletonList(playable.get(0));
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        // Actually play the spell (same path as the human controller).
        return PlaySpellAbility.playSpellAbility(this, getPlayer(), sa);
    }

    // =====================================================================
    // Mode and cost choices
    // =====================================================================

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible,
                                                  int min, int num, boolean allowRepeat) {
        if (possible.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> labels = new ArrayList<>();
        for (AbilitySub sub : possible) {
            labels.add(sub.getStackDescription());
        }
        List<Integer> chosen = queryMulti("MODE_CHOICE",
                "Choose " + num + " mode(s) for " + sa.getHostCard().getName(),
                labels, min, num, false);
        List<AbilitySub> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < possible.size()) {
                result.add(possible.get(idx));
            }
        }
        for (int i = 0; i < possible.size() && result.size() < min; i++) {
            if (!result.contains(possible.get(i))) {
                result.add(possible.get(i));
            }
        }
        return result;
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return queryNumber("NUMBER_CHOICE",
                "Choose a number for cost reduction on " + sa.getHostCard().getName()
                        + " (range: " + min + "-" + max + ")",
                min, max);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword,
                                           String prompt, int max) {
        return queryNumber("NUMBER_CHOICE",
                prompt + " (range: 0-" + max + ")",
                0, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return queryNumber("NUMBER_CHOICE",
                title + " (range: " + min + "-" + max + ")",
                min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        return queryNumberFromValues("NUMBER_CHOICE", title, values);
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
        return queryBoolean("FLIP_RESULT",
                "Call the flip for " + sa.getHostCard().getName() + "?",
                "Yes, call it", "No, don't call it", call);
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
            Map<String, Object> details = new HashMap<>();
            details.put("color", color.getName());
            option.setDetails(details);
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
        if (options == null || options.countColors() == 0) {
            return ColorSet.C;
        }
        List<Color> colors = new ArrayList<>(options.toEnumSet());
        List<String> labels = new ArrayList<>();
        for (Color c : colors) {
            labels.add(c.getName());
        }
        List<Integer> chosen = queryMulti("COLOR_CHOICE", message, labels, min, max, false);
        ColorSet result = ColorSet.C;
        for (int idx : chosen) {
            if (idx >= 0 && idx < colors.size()) {
                result = ColorSet.combine(result, ColorSet.fromEnums(colors.get(idx)));
            }
        }
        // Pad with the first colors if the AI under-selected.
        for (int i = 0; i < colors.size() && result.countColors() < min; i++) {
            if (!result.hasAnyColor(colors.get(i))) {
                result = ColorSet.combine(result, ColorSet.fromEnums(colors.get(i)));
            }
        }
        return result;
    }

    // =====================================================================
    // Card face and state choices
    // =====================================================================

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp,
                                           String name) {
        // Collect every card face matching the predicate (mirrors the human
        // controller), then let the AI pick one.
        List<ICardFace> faces = StaticData.instance().getCommonCards().streamAllFaces()
                .filter(cpp)
                .collect(Collectors.toList());
        if (faces.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (ICardFace f : faces) {
            labels.add(f.getName());
        }
        int idx = querySingle("CARD_FACE_CHOICE", message, labels, 0);
        return faces.get(idx);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        if (faces.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (ICardFace f : faces) {
            labels.add(f.getName());
        }
        int idx = querySingle("CARD_FACE_CHOICE", message, labels, 0);
        return faces.get(idx);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message,
                                            Map<String, Object> params) {
        if (states.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (CardState s : states) {
            labels.add(s.getName());
        }
        int idx = querySingle("CARD_STATE_CHOICE", message, labels, 0);
        return states.get(idx);
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2,
                                    String faceUp) {
        return queryBoolean("CARDS_PILE",
                "Put the cards in the " + faceUp + " pile?",
                "Yes", "No", true);
    }

    // =====================================================================
    // Counter and keyword choices
    // =====================================================================

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt,
                                          Map<String, Object> params) {
        if (options.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (CounterType c : options) {
            labels.add(c.getName());
        }
        int idx = querySingle("COUNTER_TYPE_CHOICE", prompt, labels, 0);
        return options.get(idx);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt,
                                        Card tgtCard) {
        if (options.isEmpty()) {
            return null;
        }
        int idx = querySingle("KEYWORD_CHOICE", prompt, options, 0);
        return options.get(idx);
    }

    // =====================================================================
    // Payment and cost decisions
    // =====================================================================

    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        return queryBoolean("CONFIRM_PAYMENT", string, "Yes, pay", "No, don't pay", true);
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (ReplacementEffect r : possibleReplacers) {
            labels.add(r.getDescription());
        }
        int idx = querySingle("REPLACEMENT_EFFECT_CHOICE",
                "Choose a replacement effect", labels, 0);
        return possibleReplacers.get(idx);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) {
        if (possibleReplacers.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (StaticAbility s : possibleReplacers) {
            labels.add(s.toString());
        }
        int idx = querySingle("STATIC_ABILITY_CHOICE",
                "Choose a static ability", labels, 0);
        return possibleReplacers.get(idx);
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        if (choices.isEmpty()) {
            return null;
        }
        int idx = querySingle("PROTECTION_TYPE_CHOICE",
                "Choose a protection type", choices, 0);
        return choices.get(idx);
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
        if (optionalCostValues.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> labels = new ArrayList<>();
        for (OptionalCostValue v : optionalCostValues) {
            labels.add(v.toString());
        }
        List<Integer> chosenIdx = queryMulti("OPTIONAL_COST_CHOICE",
                "Choose which optional costs to pay for " + chosen.getHostCard().getName(),
                labels, 0, optionalCostValues.size(), true);
        List<OptionalCostValue> result = new ArrayList<>();
        for (int idx : chosenIdx) {
            if (idx >= 0 && idx < optionalCostValues.size()) {
                result.add(optionalCostValues.get(idx));
            }
        }
        return result;
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        if (costs.size() <= 1) {
            return costs;
        }
        List<String> labels = new ArrayList<>();
        for (CostPart cp : costs) {
            labels.add(cp.toString());
        }
        List<Integer> order = queryOrder("ORDER_COSTS",
                "Choose the order to pay these costs", labels);
        List<CostPart> result = new ArrayList<>();
        for (int idx : order) {
            if (idx >= 0 && idx < costs.size()) {
                result.add(costs.get(idx));
            }
        }
        return result;
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid,
                                           FCollectionView<Player> allPayers) {
        // Learnable boolean decision: the model decides whether paying the cost
        // is worth it, based on the board state and the cost description. If it
        // says yes, the cost is actually paid using Forge's built-in cost payment
        // logic (same path as the built-in AI).
        String costDesc = (cost != null) ? cost.toString() : "unknown cost";
        boolean wantsToPay = queryBoolean("PAY_TO_PREVENT",
                "Pay " + costDesc + " to prevent " + sa.getHostCard().getName() + "?",
                "Yes, pay", "No, let it happen", false);
        if (!wantsToPay) {
            return false;
        }
        if (!ComputerUtilCost.canPayCost(cost, sa, getPlayer(), true)) {
            return false;
        }
        CostPayment pay = new CostPayment(cost, sa);
        return pay.payComputerCosts(new AiCostDecision(getPlayer(), sa, true));
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        String costDesc = (cost != null) ? cost.toString() : "unknown cost";
        return queryBoolean("PAY_COST_DURING_ROLL",
                "Pay " + costDesc + " during roll?",
                "Yes, pay", "No, don't pay", true);
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        String desc = (prompt != null) ? prompt
                : "Pay " + (cost != null ? cost.toString() : "cost") + " for " + (card != null ? card.getName() : "card") + "?";
        return queryBoolean("PAY_COMBAT_COST", desc,
                "Yes, pay", "No, don't pay", true);
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt,
                                ManaConversionMatrix matrix, boolean effect) {
        String desc = (prompt != null) ? prompt
                : "Pay mana cost " + (toPay != null ? toPay.toString() : "") + "?";
        boolean wantsToPay = queryBoolean("PAY_MANA_COST", desc,
                "Yes, pay", "No, don't pay", true);
        if (!wantsToPay) {
            return false;
        }
        // Actually perform the payment (tap lands, add mana to the pool) using
        // Forge's built-in logic — the same path the built-in AI uses. Without
        // this the spell would be cast for free (the old stub only returned the
        // model's yes/no answer without paying anything).
        return ComputerUtilMana.payManaCost(new Cost(toPay, effect), getPlayer(), sa, effect);
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt,
                                    ManaConversionMatrix matrix, boolean effect) {
        String desc = (prompt != null) ? prompt
                : "Apply mana to cost " + (toPay != null ? toPay.toString() : "") + "?";
        boolean wantsToApply = queryBoolean("APPLY_MANA_TO_COST", desc,
                "Yes, apply", "No, don't apply", true);
        if (!wantsToApply) {
            return false;
        }
        // Actually apply the mana to the cost using Forge's built-in logic.
        return ComputerUtilMana.payManaCost(toPay, ability, getPlayer(), effect);
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa,
                                                  CostPartWithList cpl, int amount, boolean isOptional,
                                                  String prompt) {
        if (optionList.isEmpty()) {
            return new CardCollection();
        }
        int min = isOptional ? 0 : Math.min(amount, optionList.size());
        return queryCards("COST_CARD_CHOICE", prompt, new ArrayList<>(optionList),
                min, amount, isOptional);
    }

    /**
     * Ask the external AI to choose which cards to pay a cost with.
     *
     * <p>Called by {@link ExternalAiCostDecision} when a cost requires choosing
     * specific cards (sacrifice, discard, tap, exile, etc.) and there is a
     * genuine choice. The AI is asked to pick exactly {@code amount} cards from
     * {@code candidates}.
     *
     * @return the chosen cards (exactly {@code amount} when possible), or an
     *         empty collection if there is nothing to choose.
     */
    public CardCollection queryCardsForCostPayment(CostPart cost, List<Card> candidates, int amount) {
        if (candidates.isEmpty() || amount <= 0) {
            return new CardCollection();
        }
        String costDesc = (cost != null) ? cost.toString() : "cost";
        CardCollection chosen = queryCards("COST_PAYMENT",
                "Choose " + amount + " card(s) to pay " + costDesc,
                candidates, amount, amount, false);
        if (chosen.size() > amount) {
            chosen = chosen.subList(0, amount);
        }
        return chosen;
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability,
                                                       boolean effect, String prompt) {
        // Use the external-AI cost decision maker so that card-selection costs
        // (sacrifice, discard, tap, exile, etc.) are learnable. It delegates to
        // Forge's built-in AiCostDecision for all the complex cost logic (mana,
        // counters, colors, dice, etc.) and always falls back to it, so cost
        // payment always succeeds.
        return new ExternalAiCostDecision(player, ability, effect, this);
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
        if (faces.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (ICardFace f : faces) {
            labels.add(f.getName());
        }
        int idx = querySingle("CARD_NAME_CHOICE", message, labels, 0);
        return faces.get(idx).getName();
    }

    // =====================================================================
    // Zone change decisions
    // =====================================================================

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                                                CardCollection fetchList, DelayedReveal delayedReveal,
                                                String selectPrompt, boolean isOptional, Player decider) {
        if (fetchList.isEmpty()) {
            return null;
        }
        List<Card> cards = new ArrayList<>(fetchList);
        List<String> labels = new ArrayList<>();
        for (Card c : cards) {
            labels.add(c.getName());
        }
        int idx = querySingle("ZONE_CHANGE_CHOICE", selectPrompt, labels, 0);
        return cards.get(idx);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin,
                                                SpellAbility sa, CardCollection fetchList, int min, int max,
                                                DelayedReveal delayedReveal, String selectPrompt,
                                                Player decider) {
        if (fetchList.isEmpty()) {
            return new ArrayList<>();
        }
        List<Card> cards = new ArrayList<>(fetchList);
        List<String> labels = new ArrayList<>();
        for (Card c : cards) {
            labels.add(c.getName());
        }
        List<Integer> chosen = queryMulti("ZONE_CHANGE_CHOICE", selectPrompt, labels, min, max, false);
        List<Card> result = new ArrayList<>();
        for (int idx : chosen) {
            if (idx >= 0 && idx < cards.size()) {
                result.add(cards.get(idx));
            }
        }
        for (int i = 0; i < cards.size() && result.size() < min; i++) {
            if (!result.contains(cards.get(i))) {
                result.add(cards.get(i));
            }
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
     * Ask the AI to pick a single option from a labeled list.
     *
     * @return the chosen index, or {@code fallbackIdx} if the AI gave nothing usable.
     */
    private int querySingle(String decisionType, String description, List<String> labels, int fallbackIdx) {
        if (labels.isEmpty()) {
            return fallbackIdx;
        }
        AiDecisionRequest request = buildRequest(decisionType, description);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getConstraints().setMinChoices(1);
        ctx.getConstraints().setMaxChoices(1);
        for (int i = 0; i < labels.size(); i++) {
            ctx.getOptions().add(new Option(String.valueOf(i), "option", labels.get(i), null));
        }
        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            try {
                int idx = Integer.parseInt(response.getSelectedOptionId());
                if (idx >= 0 && idx < labels.size()) {
                    return idx;
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid option index from AI: {}", response.getSelectedOptionId());
            }
        }
        return fallbackIdx;
    }

    /**
     * Ask the AI to pick between two boolean options.
     *
     * @return true if the AI chose the first ("yes") option.
     */
    private boolean queryBoolean(String decisionType, String description, String yesLabel,
                                  String noLabel, boolean fallback) {
        AiDecisionRequest request = buildRequest(decisionType, description);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getConstraints().setMinChoices(1);
        ctx.getConstraints().setMaxChoices(1);
        ctx.getOptions().add(new Option("yes", "boolean", yesLabel, null));
        ctx.getOptions().add(new Option("no", "boolean", noLabel, null));
        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            return "yes".equals(response.getSelectedOptionId());
        }
        return fallback;
    }

    /**
     * Ask the AI to pick between {@code min} and {@code max} (inclusive).
     * Each possible value is offered as an option whose id is the value itself,
     * so the response's selectedOptionId is directly the chosen number.
     *
     * @return the chosen number, clamped to [min, max].
     */
    private int queryNumber(String decisionType, String description, int min, int max) {
        if (max < min) {
            return min;
        }
        AiDecisionRequest request = buildRequest(decisionType, description);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getConstraints().setMinChoices(1);
        ctx.getConstraints().setMaxChoices(1);
        for (int v = min; v <= max; v++) {
            Map<String, Object> details = new HashMap<>();
            details.put("number", v);
            ctx.getOptions().add(new Option(String.valueOf(v), "integer", String.valueOf(v), details));
        }
        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            try {
                int value = Integer.parseInt(response.getSelectedOptionId());
                return Math.max(min, Math.min(max, value));
            } catch (NumberFormatException e) {
                logger.warn("Invalid number from AI: {}", response.getSelectedOptionId());
            }
        }
        return min;
    }

    /**
     * Ask the AI to pick between {@code min} and {@code max} from a discrete set of values.
     *
     * @return the chosen value, or the first value if the AI gave nothing usable.
     */
    private int queryNumberFromValues(String decisionType, String description, List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        AiDecisionRequest request = buildRequest(decisionType, description);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getConstraints().setMinChoices(1);
        ctx.getConstraints().setMaxChoices(1);
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> details = new HashMap<>();
            details.put("number", values.get(i));
            ctx.getOptions().add(new Option(String.valueOf(i), "integer",
                    "Value " + values.get(i), details));
        }
        int idx = querySingleIndex(request, values.size(), 0);
        return values.get(idx);
    }

    /**
     * Parse a single option index from an already-built request's response.
     */
    private int querySingleIndex(AiDecisionRequest request, int size, int fallbackIdx) {
        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionId() != null) {
            try {
                int idx = Integer.parseInt(response.getSelectedOptionId());
                if (idx >= 0 && idx < size) {
                    return idx;
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid option index from AI: {}", response.getSelectedOptionId());
            }
        }
        return fallbackIdx;
    }

    /**
     * Ask the AI to pick between {@code min} and {@code max} options (inclusive) from a
     * labeled list.
     *
     * @return the chosen indices (deduplicated, clamped to [0, labels.size())), or an
     *         empty list if the AI gave nothing usable.
     */
    private List<Integer> queryMulti(String decisionType, String description, List<String> labels,
                                       int min, int max, boolean optional) {
        return queryMulti(decisionType, description, labels, min, max, optional, null);
    }

    private List<Integer> queryMulti(String decisionType, String description, List<String> labels,
                                       int min, int max, boolean optional, List<Card> cards) {
        if (labels.isEmpty()) {
            return new ArrayList<>();
        }
        AiDecisionRequest request = buildRequest(decisionType, description);
        DecisionContext ctx = request.getDecisionContext();
        ctx.getConstraints().setMinChoices(Math.min(min, labels.size()));
        ctx.getConstraints().setMaxChoices(Math.min(max, labels.size()));
        ctx.getConstraints().setOptional(optional);
        ctx.getConstraints().setAllowNone(optional);
        for (int i = 0; i < labels.size(); i++) {
            Map<String, Object> details = null;
            if (cards != null && i < cards.size()) {
                details = new HashMap<>();
                details.put("card", cards.get(i).getName());
            }
            ctx.getOptions().add(new Option(String.valueOf(i), "option", labels.get(i), details));
        }
        AiDecisionResponse response = httpClient.requestDecision(request);
        List<Integer> chosen = new ArrayList<>();
        if (response != null) {
            if (response.getSelectedOptionIds() != null) {
                for (String id : response.getSelectedOptionIds()) {
                    try {
                        int idx = Integer.parseInt(id);
                        if (idx >= 0 && idx < labels.size() && !chosen.contains(idx)) {
                            chosen.add(idx);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (response.getSelectedOptionId() != null) {
                try {
                    int idx = Integer.parseInt(response.getSelectedOptionId());
                    if (idx >= 0 && idx < labels.size()) {
                        chosen.add(idx);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return chosen;
    }

    /**
     * Ask the AI to pick between {@code min} and {@code max} cards from a list.
     *
     * @return the chosen cards (deduplicated, clamped), padded up to {@code min}
     *         with the first cards if the AI under-selected.
     */
    private CardCollection queryCards(String decisionType, String description, List<Card> cards,
                                       int min, int max, boolean optional) {
        if (cards.isEmpty()) {
            return new CardCollection();
        }
        List<String> labels = new ArrayList<>();
        for (Card c : cards) {
            labels.add(c.getName());
        }
        List<Integer> chosen = queryMulti(decisionType, description, labels, min, max, optional, cards);
        CardCollection result = new CardCollection();
        for (int idx : chosen) {
            if (idx >= 0 && idx < cards.size()) {
                result.add(cards.get(idx));
            }
        }
        // Pad with the first cards if the AI under-selected.
        for (int i = 0; i < cards.size() && result.size() < min; i++) {
            if (!result.contains(cards.get(i))) {
                result.add(cards.get(i));
            }
        }
        return result;
    }

    /**
     * Ask the AI to order a labeled list. The AI returns the option ids in the
     * desired order (first id = first position).
     *
     * @return the indices in the chosen order, or the natural order if the AI gave
     *         nothing usable.
     */
    private List<Integer> queryOrder(String decisionType, String description, List<String> labels) {
        List<Integer> natural = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            natural.add(i);
        }
        if (labels.size() <= 1) {
            return natural;
        }
        AiDecisionRequest request = buildRequest(decisionType, description);
        DecisionContext ctx = request.getDecisionContext();
        // Ordering: the AI must return every option exactly once (a full permutation).
        ctx.getConstraints().setMinChoices(labels.size());
        ctx.getConstraints().setMaxChoices(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            ctx.getOptions().add(new Option(String.valueOf(i), "option", labels.get(i), null));
        }
        AiDecisionResponse response = httpClient.requestDecision(request);
        if (response != null && response.getSelectedOptionIds() != null
                && !response.getSelectedOptionIds().isEmpty()) {
            List<Integer> order = new ArrayList<>();
            for (String id : response.getSelectedOptionIds()) {
                try {
                    int idx = Integer.parseInt(id);
                    if (idx >= 0 && idx < labels.size() && !order.contains(idx)) {
                        order.add(idx);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            // Append any options the AI forgot, in natural order.
            for (int i = 0; i < labels.size(); i++) {
                if (!order.contains(i)) {
                    order.add(i);
                }
            }
            return order;
        }
        return natural;
    }

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
