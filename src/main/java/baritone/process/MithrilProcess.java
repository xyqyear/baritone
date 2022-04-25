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
    private int waitingTicksAfterTeleported = 0;
    private int worldChangedTicks = 0;
    private String playerInRange;

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
        TELEPORTED_IN_DWARVEN_MINES,
        PATHING,
        MINING,
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
    private final List<Vec3> CHECKPOINTS = Arrays.asList(
            new Vec3(-24.5, 143, -16.5),
            new Vec3(-65.5, 139, 11.5),
            new Vec3(-83.5, 144, 7.5),
            new Vec3(-94.5, 147, 3.5),
            new Vec3(-125.5, 149, 17.5),
            new Vec3(-151.5, 149, 43.5),
            new Vec3(-124.5, 150, 67.5),
            new Vec3(-88.5, 156, 82.5)
    );
    private final Vec3 MINING_SPOT = new Vec3(-89.5, 156, 82.5);
    private Vec3 currentPos = null;

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
        worldChangedTicks = 0;
        currentPos = null;
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

    private World getWorldFromScoreBoard() {
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
                            }
                        }
                    }
                    return World.SKYBLOCK_UNKNOWN;
                }
            }
        }
        return World.UNKNOWN;
    }

    private long getPlayerCountNearBlock(Vec3 vec) {
        return ctx.entitiesStream()
                .filter(entity -> entity.getType() == EntityType.PLAYER)
                .filter(entity -> {
                    if (entity.getUUID() != ctx.player().getUUID()
                            && !entity.getName().getString().matches("^Goblin $")
                            && entity.position().distanceTo(vec) < 8) {
                        String entityName = entity.getName().getString();
                        if (playerInRange == null || !playerInRange.equals(entityName)) {
                            logDirect("Player Found: \"" + entityName + "\"");
                            playerInRange = entityName;
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
        World worldFromScoreBoard = getWorldFromScoreBoard();
        // sometimes world would only change for a tick or two
        if (worldFromScoreBoard != world) {
            worldChangedTicks++;
            if (worldChangedTicks > 5) {
                world = worldFromScoreBoard;
                worldChangedTicks = 0;
            }
        } else {
            worldChangedTicks = 0;
        }
        // this is for waiting before teleporting
        if (waitingTicks > 0) {
            waitingTicks--;
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        // if the player hasn't teleported yet, wait for a set amount of time
        // if the player still hasn't teleported yet, try again
        if (state == State.TELEPORTED_IN_HUB || state == State.TELEPORTED_IN_DWARVEN_MINES || state == State.TELEPORTED_IN_HYPIXEL_LOBBY) {
            if (waitingTicksAfterTeleported > 0) {
                waitingTicksAfterTeleported--;
                if (waitingTicksAfterTeleported == 0) {
                    logDirect("Teleport timeout, retrying...");
                    waitingTicks = 80;
                    state = switch (state) {
                        case TELEPORTED_IN_HUB -> State.WAITING_IN_HUB;
                        case TELEPORTED_IN_DWARVEN_MINES -> State.WAITING_IN_DWARVEN_MINES;
                        case TELEPORTED_IN_HYPIXEL_LOBBY -> State.WAITING_IN_HYPIXEL_LOBBY;
                        default -> state;
                    };
                }
            }
        }
        switch (world) {
            case MAIN_LOBBY, PROTOTYPE_LOBBY -> {
                baritone.getMineProcess().onLostControl();
                baritone.getCustomGoalProcess().onLostControl();
                switch (state) {
                    // we actually don't need this right now, but i'll add this for good measure
                    // adding this costs an extra tick, but it keeps state neat
                    case TELEPORTED_IN_HUB, TELEPORTED_IN_DWARVEN_MINES -> state = State.EXECUTING;
                    case WAITING_IN_HYPIXEL_LOBBY -> {
                        state = State.TELEPORTED_IN_HYPIXEL_LOBBY;
                        waitingTicksAfterTeleported = 80;
                        ctx.player().chat("/skyblock");
                    }
                    case TELEPORTED_IN_HYPIXEL_LOBBY -> {}
                    default -> {
                        logDirect("Teleporting in 4 seconds because we're in hypixel lobby");
                        waitingTicks = 80;
                        state = State.WAITING_IN_HYPIXEL_LOBBY;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case SKYBLOCK_HUB, SKYBLOCK_UNKNOWN -> {
                baritone.getMineProcess().onLostControl();
                baritone.getCustomGoalProcess().onLostControl();
                switch (state) {
                    case TELEPORTED_IN_HYPIXEL_LOBBY, TELEPORTED_IN_DWARVEN_MINES -> state = State.EXECUTING;
                    case WAITING_IN_HUB -> {
                        state = State.TELEPORTED_IN_HUB;
                        waitingTicksAfterTeleported = 80;
                        ctx.player().chat("/warp forge");
                    }
                    case TELEPORTED_IN_HUB -> {}
                    default -> {
                        logDirect("Teleporting in 4 seconds because we're in other part of skyblock");
                        waitingTicks = 80;
                        state = State.WAITING_IN_HUB;
                    }
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case SKYBLOCK_DWARVEN_MINES -> {
                switch (state) {
                    // we need this here
                    case TELEPORTED_IN_HUB, TELEPORTED_IN_HYPIXEL_LOBBY -> {
                        currentPos = FORGE_POS;
                        state = State.PATHING;
                    }
                    // if we are waiting for teleportation
                    case WAITING_IN_DWARVEN_MINES -> {
                        state = State.TELEPORTED_IN_DWARVEN_MINES;
                        waitingTicksAfterTeleported = 80;
                        ctx.player().chat("/warp hub");
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    // we need this because the default behavior is DEFER,
                    // but we don't want other processes to control
                    case TELEPORTED_IN_DWARVEN_MINES -> {
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    // this only happens if we start in dwarven mines
                    case EXECUTING -> {
                        logDirect("Teleporting in 4 seconds because we're in other part of dwarven mines");
                        ctx.player().chat("/warp forge");
                        waitingTicks = 80;
                        currentPos = FORGE_POS;
                        state = State.PATHING;
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    case PATHING -> {
                        // we only need to change state if neither process is active
                        if (!baritone.getMineProcess().isActive() && !baritone.getCustomGoalProcess().isActive()) {
                            checkPlayer();
                            if (currentPos.equals(FORGE_POS)) {
                                currentPos = CHECKPOINTS.get(0);
                                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(CHECKPOINTS.get(0))));
                            } else if (currentPos.equals(MINING_SPOT)) {
                                state = State.MINING;
                                baritone.getMineProcess().mine(Blocks.LIGHT_BLUE_WOOL, Blocks.POLISHED_DIORITE);
                            } else {
                                for (int i = 0; i < CHECKPOINTS.size(); i++) {
                                    if (currentPos.equals(CHECKPOINTS.get(i))) {
                                        if (i == CHECKPOINTS.size() - 1) {
                                            currentPos = MINING_SPOT;
                                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(MINING_SPOT)));

                                        } else {
                                            currentPos = CHECKPOINTS.get(i + 1);
                                            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(CHECKPOINTS.get(i + 1))));
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    case MINING -> {
                        if (getPlayerCountNearBlock(MINING_SPOT) > 0) {
                            playerInRangeTick++;
                        } else if (playerInRangeTick > 0) {
                            playerInRangeTick--;
                            if (playerInRangeTick == 0) {
                                playerInRange = null;
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
