/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;

import java.util.*;
import java.util.stream.Stream;

public class HypixelHelper {
    public static List<AbstractMap.SimpleEntry<BlockPos, Integer>> rubySpots = Arrays.asList(
            new AbstractMap.SimpleEntry<>(new BlockPos(725, 52, 747), 20),
            new AbstractMap.SimpleEntry<>(new BlockPos(228, 52, 407), 26),
            new AbstractMap.SimpleEntry<>(new BlockPos(262, 52, 307), 25),
            new AbstractMap.SimpleEntry<>(new BlockPos(704, 52, 313), 27),
            new AbstractMap.SimpleEntry<>(new BlockPos(410, 52, 557), 26),
            new AbstractMap.SimpleEntry<>(new BlockPos(350, 52, 548), 27)
    );

    public enum World {
        UNKNOWN,
        MAIN_LOBBY,
        PROTOTYPE_LOBBY,
        SKYBLOCK_HUB,
        SKYBLOCK_DWARVEN_MINES,
        SKYBLOCK_CRYSTAL_HOLLOWS,
        SKYBLOCK_UNKNOWN
    }

    public static World getWorldFromScoreBoard(Scoreboard scoreboard) {
        Objective sidebar = scoreboard.getDisplayObjective(1);
        if (sidebar != null) {
            String gamemode = Stream.of(sidebar.getDisplayName().getSiblings())
                    .filter(list -> !list.isEmpty())
                    .map(list -> list.stream()
                            .map(Component::getString)
                            .reduce("", String::concat)
                    )
                    .reduce("", String::concat);
            switch (gamemode) {
                case "HYPIXEL" -> {
                    return World.MAIN_LOBBY;
                }
                case "PROTOTYPE" -> {
                    return World.PROTOTYPE_LOBBY;
                }
                case "SKYBLOCK CO-OP" -> {
                    Collection<Score> scores = scoreboard.getPlayerScores(sidebar);
                    for (Score score : scores) {
                        PlayerTeam team = scoreboard.getPlayersTeam(score.getOwner());
                        if (team != null) {
                            String line = Stream.of(team.getPlayerPrefix(), team.getFormattedDisplayName(), team.getPlayerSuffix())
                                    .filter(Objects::nonNull)
                                    .map(Component::getSiblings)
                                    .filter(list -> !list.isEmpty())
                                    .map(list -> list.stream()
                                            .map(Component::getString)
                                            .reduce("", String::concat)
                                    )
                                    .reduce("", String::concat);
                            if (line.matches("^ . Village")) {
                                return World.SKYBLOCK_HUB;
                            } else if (line.matches("^ . (The Forge|Forge Basin|Rampart's Quarry|Far Reserve)$")) {
                                return World.SKYBLOCK_DWARVEN_MINES;
                            } else if (line.matches("^ . (" +
                                    "Jungle|" +
                                    "Jungle Temple|" +
                                    "Mithril Deposits|" +
                                    "Mines of Divan|" +
                                    "Goblin Holdout|" +
                                    "Goblin Queen's Den|" +
                                    "Precursor Remnants|" +
                                    "Lost Precursor City|" +
                                    "Crystal Nucleus|" +
                                    "Magma Fields|" +
                                    "Khazad-dûm|" +
                                    "Fairy Grotto|" +
                                    "Dragon's Lair)$")) {
                                return World.SKYBLOCK_CRYSTAL_HOLLOWS;
                            }
                        }
                    }
                    return World.SKYBLOCK_UNKNOWN;
                }
            }
        }
        return World.UNKNOWN;
    }
}
