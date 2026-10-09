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

import forge.LobbyPlayer;
import forge.externalai.config.ExternalAiConfig;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * Lobby player that delegates all decisions to an external AI server.
 * Follows the same pattern as {@code LobbyPlayerAi} but creates
 * {@link PlayerControllerExternalAi} instances instead.
 */
public class LobbyPlayerExternalAi extends LobbyPlayer implements IGameEntitiesFactory {

    private final ExternalAiConfig config;

    public LobbyPlayerExternalAi(String name) {
        this(name, new ExternalAiConfig());
    }

    public LobbyPlayerExternalAi(String name, ExternalAiConfig config) {
        super(name);
        this.config = config;
    }

    public ExternalAiConfig getConfig() {
        return config;
    }

    private PlayerControllerExternalAi createControllerFor(Player player) {
        return new PlayerControllerExternalAi(player.getGame(), player, this, config);
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createControllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(createControllerFor(player));
        return player;
    }

    @Override
    public void hear(LobbyPlayer player, String message) {
        // External AI does not participate in chat
    }
}
