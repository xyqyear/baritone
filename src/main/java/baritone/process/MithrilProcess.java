package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IMithrilProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;

import java.util.*;
import java.util.stream.Stream;

public final class MithrilProcess extends BaritoneProcessHelper implements IMithrilProcess {
    /**
     * The current process state.
     *
     * @see State
     */
    private State state;
    private World world;
    private int waitingTicks = 0;
    private int playerInRangeTick = 0;
    private final Map<String, Boolean> playerThatWasInRange = new HashMap<>();

    public MithrilProcess(Baritone baritone) {
        super(baritone);
    }

    protected enum State {
        NONE,
        EXECUTING,
        // waiting means waiting for tp
        WAITING_IN_HYPIXEL_LOBBY,
        // because there are still a few tick where the command has been send but the player is not tp'd
        TELEPORTED_IN_HYPIXEL_LOBBY,
        WAITING_IN_HUB,
        TELEPORTED_IN_HUB,
        WAITING_IN_DWARVEN_MINES,
        TELEPORTED_IN_DWARVEN_MINES
    }

    protected enum World {
        UNKNOWN,
        MAIN_LOBBY,
        PROTOTYPE_LOBBY,
        SKYBLOCK_HUB,
        SKYBLOCK_DWARVEN_MINES,
        SKYBLOCK_UNKNOWN
    }

    private final Vec3 FORGE_POS = new Vec3(0.5, 149, -68.5);
    private final Vec3 CHECKPOINT1 = new Vec3(-65.5, 139, 11.5);
    private final Vec3 CHECKPOINT2 = new Vec3(-94.5, 147, 3.5);
    private final Vec3 CHECKPOINT3 = new Vec3(-125.5, 149, 17.5);
    private final Vec3 CHECKPOINT4 = new Vec3(-151.5, 149, 43.5);
    private final Vec3 MINING_SPOT = new Vec3(-90, 156, 82);

    @Override
    public double priority() {
        return 10;
    }

    @Override
    public boolean isActive() {
        return this.state != State.NONE;
    }

    @Override
    public void start() {
        waitingTicks = 0;
        playerInRangeTick = 0;
        this.world = World.UNKNOWN;
        this.state = State.EXECUTING;
    }

    @Override
    public void onLostControl() {
        this.state = State.NONE;
    }

    @Override
    public String displayName0() {
        return "Mithril Mining";
    }

    private void getWorldFromScoreBoard() {
        Scoreboard scoreboard = baritone.getPlayerContext().player().getScoreboard();
        Objective sidebar = baritone.getPlayerContext().player().getScoreboard().getDisplayObjective(1);
        if (sidebar != null) {
            String gamemode = Stream.of(sidebar.getDisplayName().getSiblings())
                    .filter(list -> !list.isEmpty())
                    .map(list -> list.stream()
                            .map(Component::getString)
                            .reduce("", String::concat)
                    )
                    .reduce("", String::concat);
            switch (gamemode) {
                case "HYPIXEL" -> world = World.MAIN_LOBBY;
                case "PROTOTYPE" -> world = World.PROTOTYPE_LOBBY;
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
                                world = World.SKYBLOCK_HUB;
                                return;
                            } else if (line.matches("^ . (The Forge|Forge Basin|Rampart's Quarry|Far Reserve)$")) {
                                world = World.SKYBLOCK_DWARVEN_MINES;
                                return;
                            }
                        }
                    }
                    world = World.SKYBLOCK_UNKNOWN;
                }
                default -> world = World.UNKNOWN;
            }
        }
    }

    private long getPlayerCountNearBlock(Vec3 vec) {
        return ctx.entitiesStream()
                .filter(entity -> entity.getType() == EntityType.PLAYER)
                .filter(entity -> {
                    if (entity.getUUID() != ctx.player().getUUID()
                            && !entity.getName().getString().matches("^Goblin $")
                            && entity.position().distanceTo(vec) < 8) {
                        String entityName = entity.getName().getString();
                        if (!playerThatWasInRange.containsKey(entityName)) {
                            logDirect("Player Found: \"" + entityName + "\"");
                            playerThatWasInRange.put(entityName, true);
                        }
                        return true;
                    } else {
                        return false;
                    }
                })
                .count();
    }

    private void checkPlayer() {
        if (getPlayerCountNearBlock(MINING_SPOT) > 0) {
            state = State.WAITING_IN_DWARVEN_MINES;
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        getWorldFromScoreBoard();
        if (waitingTicks > 0) {
            waitingTicks--;
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        switch (world) {
            case MAIN_LOBBY, PROTOTYPE_LOBBY -> {
                baritone.getMineProcess().onLostControl();
                baritone.getCustomGoalProcess().onLostControl();
                if (state == State.TELEPORTED_IN_HUB || state == State.TELEPORTED_IN_DWARVEN_MINES) {
                    state = State.EXECUTING;
                }

                if (state == State.WAITING_IN_HYPIXEL_LOBBY) {
                    state = State.TELEPORTED_IN_HYPIXEL_LOBBY;
                    ctx.player().chat("/skyblock");
                } else if (state != State.TELEPORTED_IN_HYPIXEL_LOBBY) {
                    logDirect("Teleporting in 4 seconds because we're in hypixel lobby");
                    waitingTicks = 80;
                    state = State.WAITING_IN_HYPIXEL_LOBBY;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case SKYBLOCK_HUB, SKYBLOCK_UNKNOWN -> {
                baritone.getMineProcess().onLostControl();
                baritone.getCustomGoalProcess().onLostControl();
                if (state == State.TELEPORTED_IN_HYPIXEL_LOBBY || state == State.TELEPORTED_IN_DWARVEN_MINES) {
                    state = State.EXECUTING;
                }

                if (state == State.WAITING_IN_HUB) {
                    state = State.TELEPORTED_IN_HUB;
                    ctx.player().chat("/warp forge");
                    playerThatWasInRange.clear();
                } else if (state != State.TELEPORTED_IN_HUB) {
                    logDirect("Teleporting in 4 seconds because we're in other part of skyblock");
                    waitingTicks = 80;
                    state = State.WAITING_IN_HUB;
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case SKYBLOCK_DWARVEN_MINES -> {
                if (state == State.TELEPORTED_IN_HUB || state == State.TELEPORTED_IN_HYPIXEL_LOBBY) {
                    state = State.EXECUTING;
                }

                if (state == State.WAITING_IN_DWARVEN_MINES) {
                    state = State.TELEPORTED_IN_DWARVEN_MINES;
                    ctx.player().chat("/warp hub");
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                } else if (state == State.TELEPORTED_IN_DWARVEN_MINES) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (!baritone.getMineProcess().isActive() && !baritone.getCustomGoalProcess().isActive()) {
                    if (ctx.player().position().distanceTo(FORGE_POS) < 1) {
                        checkPlayer();
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(CHECKPOINT1)));
                    } else if (ctx.player().position().distanceTo(CHECKPOINT1) < 2) {
                        checkPlayer();
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(CHECKPOINT2)));
                    } else if (ctx.player().position().distanceTo(CHECKPOINT2) < 2) {
                        checkPlayer();
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(CHECKPOINT3)));
                    } else if (ctx.player().position().distanceTo(CHECKPOINT3) < 2) {
                        checkPlayer();
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(CHECKPOINT4)));
                    } else if (ctx.player().position().distanceTo(CHECKPOINT4) < 2) {
                        checkPlayer();
                        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(MINING_SPOT)));
                    } else if (ctx.player().position().distanceTo(MINING_SPOT) < 4) {
                        checkPlayer();
                        if (ctx.player().position().distanceTo(MINING_SPOT) < 1) {
                            baritone.getMineProcess().mine(Blocks.LIGHT_BLUE_WOOL, Blocks.POLISHED_DIORITE);
                        } else {
                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(MINING_SPOT)));
                        }
                    } else {
                        logDirect("Teleporting in 4 seconds because we're in other part of dwarven mines");
                        ctx.player().chat("/warp forge");
                        waitingTicks = 80;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }

                if (getPlayerCountNearBlock(MINING_SPOT) > 0) {
                    playerInRangeTick++;
                } else if (playerInRangeTick > 0) {
                    playerInRangeTick--;
                    if (playerInRangeTick == 0) {
                        logDirect("Player left mining spot");
                    }
                }
                if (playerInRangeTick > 40) {
                    baritone.getMineProcess().onLostControl();
                    baritone.getCustomGoalProcess().onLostControl();
                    state = State.WAITING_IN_DWARVEN_MINES;
                    logDirect("Teleporting in 5 seconds because of player in range");
                    waitingTicks = 100;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
            case UNKNOWN -> {
                baritone.getMineProcess().onLostControl();
                baritone.getCustomGoalProcess().onLostControl();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        return new PathingCommand(null, PathingCommandType.DEFER);
    }
}
