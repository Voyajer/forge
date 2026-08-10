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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;
/**
 * Represents the complete board state serialized for the external AI.
 * Contains all information visible to the focal player.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BoardState {

    @JsonProperty("game_id")
    private String gameId;

    @JsonProperty("active_player")
    private String activePlayer;

    @JsonProperty("turn")
    private int turn;

    @JsonProperty("phase")
    private String phase;

    @JsonProperty("focal_player")
    private String focalPlayer;

    @JsonProperty("players")
    private List<PlayerState> players = new ArrayList<>();

    @JsonProperty("stack")
    private List<CardInfo> stack = new ArrayList<>();

    public BoardState() {
    }

    public String getGameId() {
        return gameId;
    }

    public void setGameId(String gameId) {
        this.gameId = gameId;
    }

    public String getActivePlayer() {
        return activePlayer;
    }

    public void setActivePlayer(String activePlayer) {
        this.activePlayer = activePlayer;
    }

    public int getTurn() {
        return turn;
    }

    public void setTurn(int turn) {
        this.turn = turn;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getFocalPlayer() {
        return focalPlayer;
    }

    public void setFocalPlayer(String focalPlayer) {
        this.focalPlayer = focalPlayer;
    }

    public List<PlayerState> getPlayers() {
        return players;
    }

    public void setPlayers(List<PlayerState> players) {
        this.players = players;
    }

    public List<CardInfo> getStack() {
        return stack;
    }

    public void setStack(List<CardInfo> stack) {
        this.stack = stack;
    }

    /**
     * Player-specific state visible to the focal player.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PlayerState {

        @JsonProperty("name")
        private String name;

        @JsonProperty("life")
        private int life;

        @JsonProperty("starting_life")
        private int startingLife;

        @JsonProperty("poison_counters")
        private int poisonCounters;

        @JsonProperty("mana_pool")
        private Map<String, Integer> manaPool = new HashMap<>();

        @JsonProperty("commanders")
        private List<CardInfo> commanders = new ArrayList<>();

        @JsonProperty("commander_damage")
        private Map<String, Integer> commanderDamage = new HashMap<>();

        @JsonProperty("commander_tax")
        private Map<String, Integer> commanderTax = new HashMap<>();

        @JsonProperty("battlefield")
        private List<CardInfo> battlefield = new ArrayList<>();

        @JsonProperty("hand")
        private List<CardInfo> hand = new ArrayList<>();

        @JsonProperty("graveyard")
        private List<CardInfo> graveyard = new ArrayList<>();

        @JsonProperty("exile")
        private List<CardInfo> exile = new ArrayList<>();

        @JsonProperty("command_zone")
        private List<CardInfo> commandZone = new ArrayList<>();

        @JsonProperty("library_size")
        private int librarySize;

        @JsonProperty("sideboard_size")
        private int sideboardSize;

        @JsonProperty("hand_hidden")
        private boolean handHidden;

        @JsonProperty("is_focal")
        private boolean isFocal;

        public PlayerState() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getLife() {
            return life;
        }

        public void setLife(int life) {
            this.life = life;
        }

        public int getStartingLife() {
            return startingLife;
        }

        public void setStartingLife(int startingLife) {
            this.startingLife = startingLife;
        }

        public int getPoisonCounters() {
            return poisonCounters;
        }

        public void setPoisonCounters(int poisonCounters) {
            this.poisonCounters = poisonCounters;
        }

        public Map<String, Integer> getManaPool() {
            return manaPool;
        }

        public void setManaPool(Map<String, Integer> manaPool) {
            this.manaPool = manaPool;
        }

        public List<CardInfo> getCommanders() {
            return commanders;
        }

        public void setCommanders(List<CardInfo> commanders) {
            this.commanders = commanders;
        }

        public Map<String, Integer> getCommanderDamage() {
            return commanderDamage;
        }

        public void setCommanderDamage(Map<String, Integer> commanderDamage) {
            this.commanderDamage = commanderDamage;
        }

        public Map<String, Integer> getCommanderTax() {
            return commanderTax;
        }

        public void setCommanderTax(Map<String, Integer> commanderTax) {
            this.commanderTax = commanderTax;
        }

        public List<CardInfo> getBattlefield() {
            return battlefield;
        }

        public void setBattlefield(List<CardInfo> battlefield) {
            this.battlefield = battlefield;
        }

        public List<CardInfo> getHand() {
            return hand;
        }

        public void setHand(List<CardInfo> hand) {
            this.hand = hand;
        }

        public List<CardInfo> getGraveyard() {
            return graveyard;
        }

        public void setGraveyard(List<CardInfo> graveyard) {
            this.graveyard = graveyard;
        }

        public List<CardInfo> getExile() {
            return exile;
        }

        public void setExile(List<CardInfo> exile) {
            this.exile = exile;
        }

        public List<CardInfo> getCommandZone() {
            return commandZone;
        }

        public void setCommandZone(List<CardInfo> commandZone) {
            this.commandZone = commandZone;
        }

        public int getLibrarySize() {
            return librarySize;
        }

        public void setLibrarySize(int librarySize) {
            this.librarySize = librarySize;
        }

        public int getSideboardSize() {
            return sideboardSize;
        }

        public void setSideboardSize(int sideboardSize) {
            this.sideboardSize = sideboardSize;
        }

        public boolean isHandHidden() {
            return handHidden;
        }

        public void setHandHidden(boolean handHidden) {
            this.handHidden = handHidden;
        }

        public boolean isFocal() {
            return isFocal;
        }

        public void setFocal(boolean focal) {
            isFocal = focal;
        }
    }

    /**
     * Card information visible on the board.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CardInfo {

        @JsonProperty("id")
        private int id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("oracle_name")
        private String oracleName;

        @JsonProperty("mana_cost")
        private String manaCost;

        @JsonProperty("converted_mana_cost")
        private int convertedManaCost;

        @JsonProperty("colors")
        private List<String> colors = new ArrayList<>();

        @JsonProperty("color_identity")
        private List<String> colorIdentity = new ArrayList<>();

        @JsonProperty("types")
        private List<String> types = new ArrayList<>();

        @JsonProperty("supertypes")
        private List<String> supertypes = new ArrayList<>();

        @JsonProperty("subtypes")
        private List<String> subtypes = new ArrayList<>();

        @JsonProperty("text")
        private String text;

        @JsonProperty("power")
        private Integer power;

        @JsonProperty("toughness")
        private Integer toughness;

        @JsonProperty("loyalty")
        private String loyalty;

        @JsonProperty("damage")
        private int damage;

        @JsonProperty("counters")
        private Map<String, Integer> counters = new HashMap<>();

        @JsonProperty("tapped")
        private boolean tapped;

        @JsonProperty("attacking")
        private boolean attacking;

        @JsonProperty("blocking")
        private boolean blocking;

        @JsonProperty("flipped")
        private boolean flipped;

        @JsonProperty("face_down")
        private boolean faceDown;

        @JsonProperty("owner")
        private String owner;

        @JsonProperty("controller")
        private String controller;

        @JsonProperty("zone")
        private String zone;

        @JsonProperty("abilities")
        private List<String> abilities = new ArrayList<>();

        @JsonProperty("keywords")
        private List<String> keywords = new ArrayList<>();

        @JsonProperty("revealed_to")
        private List<String> revealedTo = new ArrayList<>();

        public CardInfo() {
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getOracleName() {
            return oracleName;
        }

        public void setOracleName(String oracleName) {
            this.oracleName = oracleName;
        }

        public String getManaCost() {
            return manaCost;
        }

        public void setManaCost(String manaCost) {
            this.manaCost = manaCost;
        }

        public int getConvertedManaCost() {
            return convertedManaCost;
        }

        public void setConvertedManaCost(int convertedManaCost) {
            this.convertedManaCost = convertedManaCost;
        }

        public List<String> getColors() {
            return colors;
        }

        public void setColors(List<String> colors) {
            this.colors = colors;
        }

        public List<String> getColorIdentity() {
            return colorIdentity;
        }

        public void setColorIdentity(List<String> colorIdentity) {
            this.colorIdentity = colorIdentity;
        }

        public List<String> getTypes() {
            return types;
        }

        public void setTypes(List<String> types) {
            this.types = types;
        }

        public List<String> getSupertypes() {
            return supertypes;
        }

        public void setSupertypes(List<String> supertypes) {
            this.supertypes = supertypes;
        }

        public List<String> getSubtypes() {
            return subtypes;
        }

        public void setSubtypes(List<String> subtypes) {
            this.subtypes = subtypes;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public Integer getPower() {
            return power;
        }

        public void setPower(Integer power) {
            this.power = power;
        }

        public Integer getToughness() {
            return toughness;
        }

        public void setToughness(Integer toughness) {
            this.toughness = toughness;
        }

        public String getLoyalty() {
            return loyalty;
        }

        public void setLoyalty(String loyalty) {
            this.loyalty = loyalty;
        }

        public int getDamage() {
            return damage;
        }

        public void setDamage(int damage) {
            this.damage = damage;
        }

        public Map<String, Integer> getCounters() {
            return counters;
        }

        public void setCounters(Map<String, Integer> counters) {
            this.counters = counters;
        }

        public boolean isTapped() {
            return tapped;
        }

        public void setTapped(boolean tapped) {
            this.tapped = tapped;
        }

        public boolean isAttacking() {
            return attacking;
        }

        public void setAttacking(boolean attacking) {
            this.attacking = attacking;
        }

        public boolean isBlocking() {
            return blocking;
        }

        public void setBlocking(boolean blocking) {
            this.blocking = blocking;
        }

        public boolean isFlipped() {
            return flipped;
        }

        public void setFlipped(boolean flipped) {
            this.flipped = flipped;
        }

        public boolean isFaceDown() {
            return faceDown;
        }

        public void setFaceDown(boolean faceDown) {
            this.faceDown = faceDown;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getController() {
            return controller;
        }

        public void setController(String controller) {
            this.controller = controller;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public List<String> getAbilities() {
            return abilities;
        }

        public void setAbilities(List<String> abilities) {
            this.abilities = abilities;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }

        public List<String> getRevealedTo() {
            return revealedTo;
        }

        public void setRevealedTo(List<String> revealedTo) {
            this.revealedTo = revealedTo;
        }
    }
}
