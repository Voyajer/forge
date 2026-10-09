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

import forge.ai.AiCostDecision;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.CostPart;
import forge.game.cost.CostAddMana;
import forge.game.cost.CostBehold;
import forge.game.cost.CostBeholdExile;
import forge.game.cost.CostBlight;
import forge.game.cost.CostChooseColor;
import forge.game.cost.CostChooseCreatureType;
import forge.game.cost.CostCollectEvidence;
import forge.game.cost.CostDamage;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostDraw;
import forge.game.cost.CostEnlist;
import forge.game.cost.CostExert;
import forge.game.cost.CostExile;
import forge.game.cost.CostExileFromStack;
import forge.game.cost.CostExiledMoveToGrave;
import forge.game.cost.CostFlipCoin;
import forge.game.cost.CostForage;
import forge.game.cost.CostGainControl;
import forge.game.cost.CostRollDice;
import forge.game.cost.CostGainLife;
import forge.game.cost.CostMill;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayEnergy;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostPayShards;
import forge.game.cost.CostPromiseGift;
import forge.game.cost.CostPutCardToLib;
import forge.game.cost.CostPutCounter;
import forge.game.cost.CostPutCounterYou;
import forge.game.cost.CostRemoveAnyCounter;
import forge.game.cost.CostRemoveCounter;
import forge.game.cost.CostReturn;
import forge.game.cost.CostReveal;
import forge.game.cost.CostRevealChosen;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.cost.CostTapType;
import forge.game.cost.CostUntap;
import forge.game.cost.CostUntapType;
import forge.game.cost.CostUnattach;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.PaymentDecision;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Cost decision maker that makes cost payment learnable by the external AI.
 *
 * It delegates to Forge's built-in {@link AiCostDecision} for all the complex
 * cost logic (mana, counters, colors, dice, etc.), but intercepts the common
 * card-selection costs (sacrifice, discard, tap, exile, return, etc.) so the
 * external AI can choose <em>which</em> cards to pay with.
 *
 * Robustness: a cost is only intercepted when there is a genuine choice (more
 * valid cards than the cost requires). When there is no choice, or when the
 * external AI returns nothing usable, the built-in AI decision is used as a
 * fallback, so cost payment always succeeds.
 */
public class ExternalAiCostDecision extends CostDecisionMakerBase {

    private final AiCostDecision delegate;
    private final PlayerControllerExternalAi controller;

    public ExternalAiCostDecision(Player player, SpellAbility ability, boolean effect,
                                  PlayerControllerExternalAi controller) {
        super(player, effect, ability, ability.getHostCard());
        this.delegate = new AiCostDecision(player, ability, effect);
        this.controller = controller;
    }

    @Override
    public boolean paysRightAfterDecision() {
        return delegate.paysRightAfterDecision();
    }

    // =====================================================================
    // Card-selection costs: let the external AI choose which cards to pay
    // =====================================================================

    @Override
    public PaymentDecision visit(CostSacrifice cost) {
        if (cost.payCostFromSource()) {
            return PaymentDecision.card(source);
        }
        if (cost.getType().equals("OriginalHost")) {
            return PaymentDecision.card(ability.getOriginalHost());
        }
        if (cost.getAmount().equals("All")) {
            return delegate.visit(cost);
        }
        int c = cost.getAbilityAmount(ability);
        List<Card> candidates = new ArrayList<>(CardLists.getValidCards(
                player.getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability));
        return chooseCardsOrFallback(cost, () -> delegate.visit(cost), candidates, c);
    }

    @Override
    public PaymentDecision visit(CostDiscard cost) {
        // Only intercept the generic "choose N cards from hand" case; the
        // special-cased types are handled by the built-in AI.
        String type = cost.getType();
        if (type.equals("LastDrawn") || cost.payCostFromSource() || type.equals("Hand")
                || type.contains("WithSameName") || type.equals("Random")
                || type.contains("+WithDifferentNames")) {
            return delegate.visit(cost);
        }
        int c = cost.getAbilityAmount(ability);
        List<Card> candidates = new ArrayList<>(CardLists.getValidCards(
                player.getCardsIn(ZoneType.Hand), type.split(";"), player, source, ability));
        return chooseCardsOrFallback(cost, () -> delegate.visit(cost), candidates, c);
    }

    @Override
    public PaymentDecision visit(CostTapType cost) {
        String type = cost.getType();
        if (type.contains("sharesCreatureTypeWith") || type.contains("+withTotalPowerGE")) {
            return delegate.visit(cost);
        }
        int c = cost.getAbilityAmount(ability);
        List<Card> candidates = new ArrayList<>(CardLists.getValidCards(
                player.getCardsIn(ZoneType.Battlefield), type.split(";"), player, source, ability));
        return chooseCardsOrFallback(cost, () -> delegate.visit(cost), candidates, c);
    }

    @Override
    public PaymentDecision visit(CostExile cost) {
        if (cost.payCostFromSource() || cost.getType().equals("All")
                || cost.getType().contains("FromTopGrave") || cost.getType().contains("+withTotalCMCGE")) {
            return delegate.visit(cost);
        }
        if (cost.from.size() == 1 && cost.getFrom().get(0).equals(ZoneType.Library)) {
            return delegate.visit(cost);
        }
        if (cost.zoneRestriction == 0) {
            return delegate.visit(cost);
        }
        int c = cost.getAbilityAmount(ability);
        List<Card> candidates = new ArrayList<>(CardLists.getValidCards(
                player.getGame().getCardsIn(cost.getFrom().get(0)), cost.getType().split(";"), player, source, ability));
        return chooseCardsOrFallback(cost, () -> delegate.visit(cost), candidates, c);
    }

    @Override
    public PaymentDecision visit(CostReturn cost) {
        if (cost.payCostFromSource()) {
            return PaymentDecision.card(source);
        }
        int c = cost.getAbilityAmount(ability);
        List<Card> candidates = new ArrayList<>(CardLists.getValidCards(
                player.getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability));
        return chooseCardsOrFallback(cost, () -> delegate.visit(cost), candidates, c);
    }

    @Override
    public PaymentDecision visit(CostPutCounter cost) {
        if (cost.payCostFromSource()) {
            return PaymentDecision.card(source);
        }
        List<Card> candidates = new ArrayList<>(CardLists.getValidCards(
                player.getGame().getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability));
        candidates = new ArrayList<>(CardLists.filter(candidates, CardPredicates.canReceiveCounters(cost.getCounter())));
        return chooseCardsOrFallback(cost, () -> delegate.visit(cost), candidates, 1);
    }

    @Override
    public PaymentDecision visit(CostGainControl cost) {
        if (cost.payCostFromSource()) {
            return PaymentDecision.card(source);
        }
        int c = cost.getAbilityAmount(ability);
        List<Card> candidates = new ArrayList<>(CardLists.getValidCards(
                player.getGame().getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability));
        candidates = new ArrayList<>(CardLists.filter(candidates, crd -> crd.canBeControlledBy(player)));
        return chooseCardsOrFallback(cost, () -> delegate.visit(cost), candidates, c);
    }

    @Override
    public PaymentDecision visit(CostExert cost) {
        if (cost.payCostFromSource()) {
            return PaymentDecision.card(source);
        }
        int c = cost.getAbilityAmount(ability);
        List<Card> candidates = new ArrayList<>(CardLists.getValidCards(
                player.getGame().getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability));
        return chooseCardsOrFallback(cost, () -> delegate.visit(cost), candidates, c);
    }

    @Override
    public PaymentDecision visit(CostEnlist cost) {
        List<Card> candidates = new ArrayList<>(CostEnlist.getCardsForEnlisting(player));
        return chooseCardsOrFallback(cost, () -> delegate.visit(cost), candidates, 1);
    }

    @Override
    public PaymentDecision visit(CostForage cost) {
        List<Card> food = new ArrayList<>(CardLists.filter(player.getCardsIn(ZoneType.Battlefield),
                CardPredicates.isType("Food"), CardPredicates.canBeSacrificedBy(ability, isEffect())));
        if (!food.isEmpty()) {
            return chooseCardsOrFallback(cost, () -> delegate.visit(cost), food, 1);
        }
        return delegate.visit(cost);
    }

    // =====================================================================
    // All other costs: delegate to the built-in AI
    // =====================================================================

    @Override public PaymentDecision visit(CostAddMana cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostBehold cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostBeholdExile cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostChooseColor cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostChooseCreatureType cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostCollectEvidence cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostDamage cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostDraw cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostExileFromStack cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostExiledMoveToGrave cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostFlipCoin cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostMill cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostPayLife cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostPayEnergy cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostGainLife cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostPartMana cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostPromiseGift cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostPutCardToLib cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostTap cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostReveal cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostRevealChosen cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostRemoveAnyCounter cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostRemoveCounter cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostPutCounterYou cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostUntapType cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostUntap cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostUnattach cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostPayShards cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostBlight cost) { return delegate.visit(cost); }
    @Override public PaymentDecision visit(CostRollDice cost) { return delegate.visit(cost); }

    // =====================================================================
    // Helper
    // =====================================================================

    /**
     * If there is a genuine choice (more valid cards than the cost requires),
     * ask the external AI to pick. Otherwise return the deterministic answer.
     * If the external AI gives nothing usable, fall back to the built-in AI
     * decision (supplied lazily via {@code fallback}) so the cost is always paid.
     *
     * @param cost      the cost being paid (used only for the request description)
     * @param fallback  lazy supplier of the built-in AI decision, used when there
     *                  is no choice or the external AI returns nothing usable
     * @param candidates the valid cards that could be paid with
     * @param amount     how many cards the cost requires
     */
    private PaymentDecision chooseCardsOrFallback(CostPart cost, Supplier<PaymentDecision> fallback,
                                                  List<Card> candidates, int amount) {
        if (amount <= 0 || candidates.isEmpty()) {
            return fallback.get();
        }
        if (candidates.size() <= amount) {
            // No real choice — must take all available cards.
            return PaymentDecision.card(candidates);
        }
        CardCollection chosen = controller.queryCardsForCostPayment(cost, candidates, amount);
        if (chosen == null || chosen.isEmpty()) {
            return fallback.get();
        }
        return PaymentDecision.card(chosen);
    }
}
