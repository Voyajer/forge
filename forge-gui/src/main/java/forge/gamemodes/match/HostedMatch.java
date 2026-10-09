package forge.gamemodes.match;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.eventbus.Subscribe;
import forge.LobbyPlayer;
import forge.StaticData;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import forge.game.*;
import forge.game.event.GameEvent;
import forge.game.event.GameEventSubgameEnd;
import forge.game.event.GameEventSubgameStart;
import forge.game.event.GameEventTurnPhase;
import forge.game.event.IGameEventVisitor;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.net.NetworkGameEventListener;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.quest.QuestController;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.gui.control.FControlGameEventHandler;
import forge.gui.control.FControlGamePlayback;
import forge.gui.control.PlaybackSpeed;
import forge.gui.control.WatchLocalGame;
import forge.gui.events.*;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.haptic.HapticEngine;
import forge.sound.MusicPlaylist;
import forge.sound.SoundSystem;
import forge.trackable.TrackableCollection;
import forge.util.TextUtil;
import forge.util.collect.FCollectionView;
import forge.deck.Deck;
import forge.externalai.LobbyPlayerExternalAi;
import forge.externalai.matchlog.ExternalMatchLogger;
import forge.externalai.matchlog.GameEventRecorder;
import forge.item.PaperCard;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.Map.Entry;

public class HostedMatch {
    private Match match;
    private Game game;
    private String title;
    private MusicPlaylist matchPlaylist = null;
    public HashMap<LobbySlot, IGameController> gameControllers = null;
    private Runnable startGameHook = null;
    private Runnable endGameHook = null;
    private Runnable onMatchOver = null;
    private final List<PlayerControllerHuman> humanControllers = Lists.newArrayList();
    private Map<RegisteredPlayer, IGuiGame> guis;
    private int humanCount;
    private FControlGamePlayback playbackControl = null;
    private final MatchUiEventVisitor visitor = new MatchUiEventVisitor();
    private final Map<PlayerControllerHuman, NextGameDecision> nextGameDecisions = Maps.newHashMap();
    private boolean isMatchOver = false;
    public int subGameCount = 0;

    // Match logging (external AI server). Best-effort: if the server is down,
    // logging is silently skipped and gameplay is unaffected.
    private String matchId = null;
    private ExternalMatchLogger matchLogger = null;
    private GameEventRecorder gameRecorder = null;

    public HostedMatch() {}

    public void setStartGameHook(Runnable hook) {
        startGameHook = hook;
    }
    public void setEndGameHook(Runnable hook) { endGameHook = hook; }
    public void setOnMatchOver(Runnable callback) { onMatchOver = callback; }

    private static GameRules getDefaultRules(final GameType gameType) {
        final GameRules gameRules = new GameRules(gameType);
        gameRules.setPlayForAnte(FModel.getPreferences().getPrefBoolean(FPref.UI_ANTE));
        gameRules.setMatchAnteRarity(FModel.getPreferences().getPrefBoolean(FPref.UI_ANTE_MATCH_RARITY));
        gameRules.setAnteIncludeBasicLands(FModel.getPreferences().getPrefBoolean(FPref.UI_ANTE_INCLUDE_BASIC_LANDS));
        gameRules.setManaBurn(FModel.getPreferences().getPrefBoolean(FPref.LEGACY_MANABURN));
        gameRules.setOrderCombatants(FModel.getPreferences().getPrefBoolean(FPref.LEGACY_ORDER_COMBATANTS));
        gameRules.setUseGrayText(FModel.getPreferences().getPrefBoolean(FPref.UI_GRAY_INACTIVE_TEXT));
        gameRules.setGamesPerMatch(FModel.getPreferences().getPrefInt(FPref.UI_MATCHES_PER_GAME));
        gameRules.setAllowCheatShuffle(FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_AI_CHEATS));
        switch (AiProfileUtil.getAISideboardingMode()) {
            case Off:
                gameRules.setAISideboardingEnabled(false);
                gameRules.setSideboardForAI(false);
                break;
            case AI:
                gameRules.setAISideboardingEnabled(true);
                gameRules.setSideboardForAI(false);
                break;
            case HumanForAI:
                gameRules.setAISideboardingEnabled(true);
                gameRules.setSideboardForAI(true);
                break;
        }
        return gameRules;
    }

    public void startMatch(final GameType gameType, final Set<GameType> appliedVariants, final List<RegisteredPlayer> players, final RegisteredPlayer human, final IGuiGame gui) {
        startMatch(getDefaultRules(gameType), appliedVariants, players, human, gui);
    }
    public void startMatch(final GameType gameType, final Set<GameType> appliedVariants, final List<RegisteredPlayer> players, final Map<RegisteredPlayer, IGuiGame> guis) {
        startMatch(getDefaultRules(gameType), appliedVariants, players, guis, null);
    }
    public void startMatch(final GameRules gameRules, final Set<GameType> appliedVariants, final List<RegisteredPlayer> players, final RegisteredPlayer human, final IGuiGame gui) {
        startMatch(gameRules, appliedVariants, players, human == null || gui == null ? null : ImmutableMap.of(human, gui), null);
    }
    public void startMatch(final GameRules gameRules, final Set<GameType> appliedVariants, final List<RegisteredPlayer> players, final Map<RegisteredPlayer, IGuiGame> guis, final MusicPlaylist playlist) {
        if (gameRules == null || gameRules.getGameType() == null || players == null || players.isEmpty()) {
            throw new IllegalArgumentException();
        }

        this.guis = guis == null ? ImmutableMap.of() : guis;
        final boolean useRandomFoil = FModel.getPreferences().getPrefBoolean(FPref.UI_RANDOM_FOIL);
        for (final RegisteredPlayer rp : players) {
            rp.setRandomFoil(useRandomFoil);
        }

        if (appliedVariants != null && !appliedVariants.isEmpty()) {
            gameRules.setAppliedVariants(appliedVariants);
        }

        final List<RegisteredPlayer> sortedPlayers = Lists.newArrayList(players);
        sortedPlayers.sort((p1, p2) -> {

            final int v1 = p1.getPlayer() instanceof LobbyPlayerHuman ? 0 : 1;
            final int v2 = p2.getPlayer() instanceof LobbyPlayerHuman ? 0 : 1;
            return Integer.compare(v1, v2);
        });

        if (sortedPlayers.size() == 2) {
            title = TextUtil.concatNoSpace(sortedPlayers.get(0).getPlayer().getName(), " vs ", sortedPlayers.get(1).getPlayer().getName());
        } else {
            title = TextUtil.concatNoSpace("Multiplayer Game (", String.valueOf(sortedPlayers.size()), " players)");
        }
        this.match = new Match(gameRules, sortedPlayers, title);
        this.match.subscribeToEvents(SoundSystem.instance);
        this.match.subscribeToEvents(HapticEngine.instance);
        this.match.subscribeToEvents(visitor);
        this.matchPlaylist = playlist;

        // Set up match logging (external AI server). The whole match (all games
        // of a Bo1/Bo3) is logged as a single replay file with per-game and
        // match-level winners/rewards.
        if (matchLogger != null) {
            matchLogger.shutdown();
            matchLogger = null;
        }
        this.matchId = UUID.randomUUID().toString();
        this.matchLogger = new ExternalMatchLogger();
        final List<String> playerNames = new ArrayList<>();
        final Map<String, String> playerTypes = new LinkedHashMap<>();
        for (final RegisteredPlayer rp : sortedPlayers) {
            final String name = rp.getPlayer().getName();
            playerNames.add(name);
            playerTypes.put(name, classifyPlayerType(rp.getPlayer()));
        }
        final String formatName = gameRules.getGameType() != null ? gameRules.getGameType().toString() : "standard";
        final int gamesToWin = gameRules.getGamesToWinMatch();
        matchLogger.onMatchStart(matchId, playerNames, formatName, gamesToWin, playerTypes);
        // Record sideboard actions (games 2+) as they happen in Match.prepareAllZones.
        this.match.addSideboardListener((playerName, mainToSide, sideToMain) -> {
            final Game g = this.game;
            if (g != null) {
                matchLogger.onSideboard(matchId, String.valueOf(g.getId()), playerName, mainToSide, sideToMain);
            }
        });

        startGame();
    }

    public void continueMatch() {
        endCurrentGame();
        startGame();
    }

    public void restartMatch() {
        endCurrentGame();
        startMatch(match.getRules(), null, match.getPlayers(), this.guis, this.matchPlaylist);
    }

    public void startGame() {
        nextGameDecisions.clear();
        SoundSystem.instance.setBackgroundMusic(this.matchPlaylist == null ? MusicPlaylist.MATCH : this.matchPlaylist);

        game = match.createGame();
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = FModel.getPreferences().getPrefBoolean(FPref.MATCH_EXPERIMENTAL_RESTORE);
        game.AI_TIMEOUT = FModel.getPreferences().getPrefInt(FPref.MATCH_AI_TIMEOUT);
        // Android API 31 and above can use completeOnTimeout -> CompletableFuture:
        //https://developer.android.com/reference/java/util/concurrent/CompletableFuture#completeOnTimeout(T,%20long,%20java.util.concurrent.TimeUnit)
        game.AI_CAN_USE_TIMEOUT = !GuiBase.isAndroid() || GuiBase.getAndroidAPILevel() > 30;

        StaticData.instance().setSourceImageForClone(FModel.getPreferences().getPrefBoolean(FPref.UI_CLONE_MODE_SOURCE));

        if (game.getRules().getGameType() == GameType.Quest) {
            final QuestController qc = FModel.getQuest();
            // Reset new list when the Match round starts, not when each game starts
            if (game.getMatch().getOutcomes().isEmpty()) {
                qc.getCards().resetNewList();
            }
            qc.setActiveGame(game);
            game.subscribeToEvents(qc); // this one listens to player's mulligans ATM
        }

        // SoundSystem receives GameEvents via handleGameEvent() on each GUI,
        // so it doesn't need a direct event bus subscription here.
        // (It still subscribes to the Match bus for UiEvent sounds like blocker assignment.)

        // This logs game actions to NetworkLogConfig for debugging network games
        if (FServerManager.getInstance().isHosting()) {
            game.subscribeToEvents(new NetworkGameEventListener());
        }

        final FCollectionView<Player> players = game.getPlayers();
        final String[] avatarIndices = FModel.getPreferences().getPref(FPref.UI_AVATARS).split(",");
        final String[] sleeveIndices = FModel.getPreferences().getPref(FPref.UI_SLEEVES).split(",");
        final GameView gameView = getGameView();

        humanCount = 0;
        final Multimap<IGuiGame, PlayerView> playersPerGui = MultimapBuilder.hashKeys().arrayListValues().build();
        for (int iPlayer = 0; iPlayer < players.size(); iPlayer++) {
            final RegisteredPlayer rp = match.getPlayers().get(iPlayer);
            final Player p = players.get(iPlayer);

            p.getLobbyPlayer().setAvatarIndex(rp.getPlayer().getAvatarIndex());
            if (p.getLobbyPlayer().getAvatarIndex() == -1) {
                if (iPlayer < avatarIndices.length) {
                    p.getLobbyPlayer().setAvatarIndex(Integer.parseInt(avatarIndices[iPlayer]));
                } else {
                    p.getLobbyPlayer().setAvatarIndex(0);
                }
            }
            p.updateAvatar();
            p.getLobbyPlayer().setSleeveIndex(rp.getPlayer().getSleeveIndex());
            if (p.getLobbyPlayer().getSleeveIndex() == -1) {
                if (iPlayer < sleeveIndices.length) {
                    p.getLobbyPlayer().setSleeveIndex(Integer.parseInt(sleeveIndices[iPlayer]));
                } else {
                    p.getLobbyPlayer().setSleeveIndex(0);
                }
            }
            p.updateSleeve();

            if (p.getController() instanceof PlayerControllerHuman humanController) {
                final IGuiGame gui = guis.get(p.getRegisteredPlayer());
                humanController.setGui(gui);
                gui.setGameView(null); //clear out game view first so we don't copy into old game view
                gui.setGameView(gameView);
                gui.setOriginalGameController(p.getView(), humanController);

                if (gui instanceof forge.gamemodes.net.server.RemoteClientGuiGame ngg) {
                    forge.gui.control.GameEventForwarder forwarder = new forge.gui.control.GameEventForwarder(gui);
                    ngg.setForwarder(forwarder);
                    game.subscribeToEvents(forwarder);
                } else {
                    game.subscribeToEvents(new FControlGameEventHandler(humanController));
                }
                playersPerGui.put(gui, p.getView());

                if (gameControllers != null) {
                    LobbySlot lobbySlot = getLobbySlot(p.getLobbyPlayer());
                    gameControllers.put(lobbySlot, humanController);
                }

                humanControllers.add(humanController);
                humanCount++;
            }
        }

        // Register each remote client's event forwarder as an observer on ALL human
        // players' InputQueues. This ensures events are flushed on the game thread
        // before it blocks for input (e.g. host plays a land and retains priority).
        for (PlayerControllerHuman hc : humanControllers) {
            if (hc.getGui() instanceof forge.gamemodes.net.server.RemoteClientGuiGame ngg) {
                forge.gui.control.GameEventForwarder fwd = ngg.getForwarder();
                if (fwd != null) {
                    for (PlayerControllerHuman allHc : humanControllers) {
                        allHc.getInputQueue().addObserver(fwd);
                    }
                }
            }
        }

        for (final Entry<IGuiGame, Collection<PlayerView>> e : playersPerGui.asMap().entrySet()) {
            e.getKey().openView(new TrackableCollection<>(e.getValue()));
        }

        if (humanCount == 0) { //watch game but do not participate
            final IGuiGame gui = GuiBase.getInterface().getNewGuiGame();
            gui.setGameView(null); //clear the view so when the game restarts again, it updates correctly
            gui.setGameView(gameView);
            registerSpectator(gui, new WatchLocalGame(game, new LobbyPlayerHuman("Spectator"), gui));
        }

        //prompt user for player one name if needed
        if (StringUtils.isBlank(FModel.getPreferences().getPref(FPref.PLAYER_NAME)) && humanCount == 1) {
            GamePlayerUtil.setPlayerName();
        }

        //ensure opponents set properly
        for (final Player p : players) {
            p.updateOpponentsForView();
        }

        // It's important to run match in a different thread to allow GUI inputs to be invoked from inside game. 
        // Game is set on pause while gui player takes decisions
        game.getAction().invoke(() -> {
            // Capture the current game in a local so this lambda keeps operating on the
            // game it started even if another thread (e.g. the EDT handling a player's
            // "quit"/"continue" decision via addNextGameDecision -> endCurrentGame())
            // concurrently clears the `game` field while match.startGame() is wrapping up.
            final Game currentGame = game;

            if (humanCount == 0) {
                // Create FControlGamePlayback in game thread to allow pausing
                playbackControl = new FControlGamePlayback(humanControllers.get(0));
                playbackControl.setGame(currentGame);
                currentGame.subscribeToEvents(playbackControl);
            }
            // Notify the match logger that a new game is starting and begin
            // recording its moves (every action + full board state).
            if (matchLogger != null) {
                final int gameNumber = match.getOutcomes().size() + 1;
                final String gameId = String.valueOf(currentGame.getId());
                matchLogger.onGameStart(matchId, gameId, gameNumber, captureDecklists());
                gameRecorder = new GameEventRecorder(currentGame, matchId, gameId, matchLogger);
            }
            // Actually start the game!
            match.startGame(currentGame, startGameHook);
            // this function waits?
            // The game is over — record its outcome (winner + turns).
            if (matchLogger != null) {
                final String gameId = String.valueOf(currentGame.getId());
                final GameOutcome outcome = currentGame.getOutcome();
                final String winner = (outcome != null && outcome.getWinningLobbyPlayer() != null)
                        ? outcome.getWinningLobbyPlayer().getName() : null;
                final int totalTurns = outcome != null ? outcome.getLastTurnNumber() : 0;
                matchLogger.onGameEnd(matchId, gameId, winner, totalTurns);
            }
            if (endGameHook != null){
                endGameHook.run();
            }

            // Flush any buffered game events to remote clients so they receive
            // GameEventGameOutcome and GameEventGameFinished before we proceed.
            for (PlayerControllerHuman hc : humanControllers) {
                if (hc.getGui() instanceof forge.gamemodes.net.server.RemoteClientGuiGame ngg) {
                    forge.gui.control.GameEventForwarder fwd = ngg.getForwarder();
                    if (fwd != null) {
                        fwd.flush();
                    }
                }
            }

            // After game is over...
            isMatchOver = match.isMatchOver();
            if (humanCount == 0) {
                // ... if no human players, let AI decide next game
                if (currentGame.getRules().getGameType() == GameType.Constructed) {
                    // Dramatic interlude to signal end of game.
                    FThreads.delayInEDT(3000, () -> {
                        if (isMatchOver) {
                            // Leave match-end overview open for spectator.
                            // Finalize the match log NOW: the QUIT path in
                            // addNextGameDecision (which normally calls
                            // notifyMatchEnd) never runs for Constructed
                            // matches, so without this the match replay file
                            // would only be written when the NEXT match
                            // starts or the server shuts down.
                            notifyMatchEnd();
                        } else {
                            addNextGameDecision(null, NextGameDecision.CONTINUE);
                        }
                    });
                } else if (isMatchOver) {
                    addNextGameDecision(null, NextGameDecision.QUIT);
                } else {
                    addNextGameDecision(null, NextGameDecision.CONTINUE);
                }
            }
        });
    }

    private LobbySlot getLobbySlot(LobbyPlayer lobbyPlayer) {
        for (LobbySlot key: gameControllers.keySet()) {
            IGameController value = gameControllers.get(key);
            if (value instanceof PlayerControllerHuman pch) {
                if (lobbyPlayer == pch.getLobbyPlayer()) {
                    return key;
                }
            }
        }
        return null;
    }

    public void registerSpectator(final IGuiGame gui) {
        final PlayerControllerHuman humanController = new WatchLocalGame(game, null, gui);
        registerSpectator(gui, humanController);
    }
    public void registerSpectator(final IGuiGame gui, final PlayerControllerHuman humanController) {
        gui.setSpectator(humanController);
        gui.openView(null);
        game.subscribeToEvents(new FControlGameEventHandler(humanController));
        humanControllers.add(humanController);
    }

    public Game getGame() {
        return game;
    }
    public Match getMatch() {
        return match;
    }
    public GameView getGameView() {
        return game == null ? null : game.getView();
    }

    public void endCurrentGame() {
        if (game == null) { return; }
        boolean isMatchOver = game.getView().isMatchOver();

        game = null;

        for (final PlayerControllerHuman humanController : humanControllers) {
            if (humanController.getGui() instanceof forge.gamemodes.net.server.RemoteClientGuiGame ngg) {
                forge.gui.control.GameEventForwarder fwd = ngg.getForwarder();
                if (fwd != null) {
                    for (PlayerControllerHuman allHc : humanControllers) {
                        allHc.getInputQueue().deleteObserver(fwd);
                    }
                }
                ngg.shutdownForwarder();
            }
            humanController.getGui().setGameSpeed(PlaybackSpeed.NORMAL);
            humanController.getYieldController().clearAutoYields();

            //conceded
            if (humanCount > 0 || !GuiBase.getInterface().isLibgdxPort() || !isMatchOver) {
                humanController.getGui().afterGameEnd();
            }
            humanController.getGui().updateDayTime(null);
        }
        humanControllers.clear();

        // Force GC here rather than in Match.startGame so it runs ONLY for
        // GUI-hosted matches — headless AI sims (DeckBattler, SimulateMatch,
        // quest-draft bracket) build Match directly and bypass HostedMatch.
        // Runs after game=null + afterGameEnd above, so the finished game's
        // whole object graph is collectible (more than a gc while it's still
        // referenced). iOS only: it keeps the iPad under its jetsam ceiling
        // across the games of a multi-game Commander match; other platforms
        // don't need it and shouldn't pay the GC stall.
        if (GuiBase.isIOS()) {
            System.gc();
        }
    }

    public void pause() {
        final ForgePreferences prefs = FModel.getPreferences();
        if (prefs == null) { return; } //do nothing if prefs haven't been initialized yet

        //pause playback if needed
        if (prefs.getPrefBoolean(FPref.UI_PAUSE_WHILE_MINIMIZED) && playbackControl != null) {
            playbackControl.getInput().pause();
        }
    }

    public void resume() {
        // Doesn't need to do anything right now
    }

    public boolean isMatchOver() {
        return isMatchOver;
    }

    public GameOutcome.AnteResult getAnteResult(RegisteredPlayer player) {
        return match.getAnteResult(player);
    }

    private final class MatchUiEventVisitor extends IGameEventVisitor.Base<Void> implements IUiEventVisitor<Void> {
        @Override
        public Void visit(final UiEventBlockerAssigned event) {
            for (final PlayerControllerHuman humanController : humanControllers) {
                humanController.getGui().updateSingleCard(event.blocker());
            }
            return null;
        }

        @Override
        public Void visit(final UiEventAttackerDeclared event) {
            for (final PlayerControllerHuman humanController : humanControllers) {
                humanController.getGui().updateSingleCard(event.attacker());
            }
            return null;
        }

        @Override
        public Void visit(final UiEventNextGameDecision event) {
            addNextGameDecision(event.controller(), event.decision());
            return null;
        }

        @Override
        public Void visit(final GameEventSubgameStart event) {
            subGameCount++;

            final GameView gameView = event.subgame().getView();

            Runnable switchGameView = () -> {
                for (final Player p : event.subgame().getPlayers()) {
                    if (p.getController() instanceof PlayerControllerHuman humanController) {
                        final IGuiGame gui = guis.get(p.getRegisteredPlayer());
                        humanController.setGui(gui);
                        gui.setGameView(null);
                        gui.setGameView(gameView);
                        gui.setOriginalGameController(p.getView(), humanController);
                        gui.openView(new TrackableCollection<>(p.getView()));
                        gui.setGameView(null);
                        gui.setGameView(gameView);
                        event.subgame().subscribeToEvents(new FControlGameEventHandler(humanController));
                        gui.message(event.message());
                    }
                }
            };
            if (GuiBase.getInterface().isLibgdxPort())
                GuiBase.getInterface().invokeInEdtNow(switchGameView);
            else
                GuiBase.getInterface().invokeInEdtAndWait(switchGameView);

            //ensure opponents set properly
            for (final Player p : event.subgame().getPlayers()) {
                p.updateOpponentsForView();
            }

            return null;
        }

        @Override
        public Void visit(final GameEventSubgameEnd event) {
            final GameView gameView = event.maingame().getView();
            Runnable switchGameView = () -> {
                for (final Player p : event.maingame().getPlayers()) {
                    if (p.getController() instanceof PlayerControllerHuman humanController) {
                        final IGuiGame gui = guis.get(p.getRegisteredPlayer());
                        gui.setGameView(null);
                        gui.setGameView(gameView);
                        gui.setOriginalGameController(p.getView(), humanController);
                        gui.openView(new TrackableCollection<>(p.getView()));
                        gui.setGameView(null);
                        gui.setGameView(gameView);
                        gui.handleGameEvent(new GameEventTurnPhase(
                                gameView.getPlayerTurn(), gameView.getPhase(), ""));
                        gui.message(event.message());
                    }
                }
            };
            if (GuiBase.getInterface().isLibgdxPort())
                GuiBase.getInterface().invokeInEdtNow(switchGameView);
            else
                GuiBase.getInterface().invokeInEdtAndWait(switchGameView);

            return null;
        }

        @Subscribe
        public void receiveEvent(final UiEvent evt) {
            try {
                evt.visit(this);
            } catch (Exception e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
        }

        @Subscribe
        public void receiveGameEvent(final GameEvent evt) {
            try {
                evt.visit(this);
            } catch (Exception e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void addNextGameDecision(final PlayerControllerHuman controller, final NextGameDecision decision) {
        if (decision == NextGameDecision.QUIT) {
            FThreads.invokeInEdtNowOrLater(() -> {
                endCurrentGame();
                isMatchOver = true;
                notifyMatchEnd();
                if (onMatchOver != null) {
                    onMatchOver.run();
                }
            });
            return; // if any player chooses quit, quit the match
        }

        nextGameDecisions.put(controller, decision);
        if (nextGameDecisions.size() < humanControllers.size()) {
            return;
        }

        int newMatch = 0, continueMatch = 0;
        for (final NextGameDecision dec : nextGameDecisions.values()) {
            switch (dec) {
            case CONTINUE:
                continueMatch++;
                break;
            case NEW:
                newMatch++;
                break;
            default:
            }
        }

        if (continueMatch >= newMatch) {
            FThreads.invokeInEdtNowOrLater(this::continueMatch);
        } else {
            FThreads.invokeInEdtNowOrLater(this::restartMatch);
        }
    }

    public List<PlayerControllerHuman> getHumanControllers(){
        return humanControllers;
    }

    /**
     * Capture each player's current main deck as a list of card names.
     */
    private Map<String, List<String>> captureDecklists() {
        final Map<String, List<String>> decklists = new LinkedHashMap<>();
        for (final RegisteredPlayer rp : match.getPlayers()) {
            final List<String> names = new ArrayList<>();
            final Deck deck = rp.getDeck();
            if (deck != null) {
                for (final Entry<PaperCard, Integer> e : deck.getMain()) {
                    for (int i = 0; i < e.getValue(); i++) {
                        names.add(e.getKey().getName());
                    }
                }
            }
            decklists.put(rp.getPlayer().getName(), names);
        }
        return decklists;
    }

    /**
     * Classify a player's role for the replay file so the trainer can tell which
     * moves were made by a human, a Forge bot, or the external AI. This is what
     * makes every match composition (human vs human, human vs bot, bot vs bot,
     * bot vs AI, AI vs AI) self-describing and usable for training.
     */
    private static String classifyPlayerType(final LobbyPlayer lp) {
        if (lp instanceof LobbyPlayerHuman) {
            return "human";
        }
        if (lp instanceof LobbyPlayerExternalAi) {
            return "ai";
        }
        if (lp instanceof LobbyPlayerAi) {
            return "bot";
        }
        return "unknown";
    }

    /**
     * Notify the match logger that the match is over and finalize the match file.
     */
    private void notifyMatchEnd() {
        if (matchLogger == null) {
            return;
        }
        final RegisteredPlayer winner = match.getWinner();
        final String matchWinner = (winner != null) ? winner.getPlayer().getName() : null;
        final Map<String, Integer> finalScore = new LinkedHashMap<>();
        for (final RegisteredPlayer rp : match.getPlayers()) {
            finalScore.put(rp.getPlayer().getName(), match.getGamesWonBy(rp.getPlayer()));
        }
        final String result = match.isMatchOver() ? "completed" : "abandoned";
        matchLogger.onMatchEnd(matchId, matchWinner, finalScore, result);
        matchLogger.shutdown();
        matchLogger = null;
    }
}
