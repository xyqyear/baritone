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

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class HypixelHelper {
    public enum World {
        UNKNOWN,
        MAIN_LOBBY,
        PROTOTYPE_LOBBY,
        SKYBLOCK_HUB,
        SKYBLOCK_DWARVEN_MINES,
        SKYBLOCK_CRYSTAL_HOLLOWS,
        SKYBLOCK_UNKNOWN
    }

    public enum Pickaxe {
        DIVANS_DRILL,
        GEMSTONE_GAUNTLET,
        UNKNOWN
    }


    public static Pickaxe getPickaxeType(ItemStack itemStack) {
        String itemName = itemStack.getDisplayName().getString();
        if (itemName.endsWith("]")) {
            itemName = itemName.replace("]", "");
        }
        if (itemName.endsWith("Divan's Drill")) {
            return Pickaxe.DIVANS_DRILL;
        } else if (itemName.endsWith("Gemstone Gauntlet")) {
            return Pickaxe.GEMSTONE_GAUNTLET;
        } else {
            return Pickaxe.UNKNOWN;
        }
    }


    public static Optional<Integer> getFuel(LocalPlayer player, ItemStack itemStack) {
        return itemStack.getTooltipLines(player, TooltipFlag.Default.ADVANCED)
                .stream()
                .filter(component -> {
                    return component.getString().startsWith("Fuel:");
                })
                .findFirst()
                .map(component -> {
                    Matcher matcher = Pattern.compile("^Fuel: ([\\d,k]+)/[\\d,k]+$").matcher(component.getString().strip());
                    if (!matcher.find()) {
                        return null;
                    }
                    String fuelString = matcher.group(1)
                            .replace(",", "")
                            .replace("k", "000");
                    if (!fuelString.matches("^\\d+$")) {
                        return null;
                    }
                    return Integer.parseInt(fuelString);
                });
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
