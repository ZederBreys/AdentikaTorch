package adentika.torch.mc;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.ICustomGoalProcess;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * The only class that touches Baritone, through its public API (D-4): set a goal, cancel, read path events, check
 * whether Baritone is busy with something else, and temporarily forbid breaking/placing (D-5) with restore.
 * Create only when {@code BaritoneUtils.IS_AVAILABLE} is true; without Baritone this class is never loaded.
 */
public final class Navigator {
    public enum Status { IDLE, MOVING, ARRIVED, FAILED }

    private static boolean listenerRegistered;
    private static volatile Navigator active;

    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private Goal goal;
    private volatile Status status = Status.IDLE;

    private Boolean savedAllowBreak, savedAllowPlace;

    public Navigator() {
        if (!listenerRegistered) {
            baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override
                public void onPathEvent(PathEvent event) {
                    Navigator n = active;
                    if (n != null) n.onPathEvent(event);
                }
            });
            listenerRegistered = true;
        }
    }

    /** Baritone is pathing or another process (mine, follow, build...) holds control: do not take over silently (D-7). */
    public boolean busyWithOtherTask() {
        if (baritone.getPathingBehavior().isPathing()) return true;
        Optional<IBaritoneProcess> inControl = baritone.getPathingControlManager().mostRecentInControl();
        return inControl.isPresent() && inControl.get().isActive();
    }

    /** Walk so that the player ends within {@code range} blocks of {@code pos}. */
    public void goNear(BlockPos pos, int range) {
        go(new GoalNear(pos, range));
    }

    /** Walk to the X/Z column (any height): used to come close enough for chunks to load. */
    public void goXZ(int x, int z) {
        go(new GoalXZ(x, z));
    }

    private void go(Goal g) {
        active = this;
        goal = g;
        status = Status.MOVING;
        ICustomGoalProcess process = baritone.getCustomGoalProcess();
        process.setGoalAndPath(g);
    }

    public Status status() {
        if (status == Status.MOVING && goal != null && !baritone.getPathingBehavior().isPathing()
            && goal.isInGoal(baritone.getPlayerContext().playerFeet())) {
            status = Status.ARRIVED;
        }
        return status;
    }

    private void onPathEvent(PathEvent event) {
        if (status != Status.MOVING) return;
        switch (event) {
            case AT_GOAL -> status = Status.ARRIVED;
            case CALC_FAILED, NEXT_CALC_FAILED -> status = Status.FAILED;
            case CANCELED -> status = Status.FAILED;
            default -> {
            }
        }
    }

    /** Stops our goal (and whatever Baritone is executing for it). */
    public void stop() {
        if (status == Status.MOVING) baritone.getPathingBehavior().cancelEverything();
        status = Status.IDLE;
        goal = null;
        if (active == this) active = null;
    }

    /** Forbid breaking and placing blocks while we use Baritone; {@link #restoreSettings()} puts the old values back. */
    public void restrictSettings() {
        Settings s = BaritoneAPI.getSettings();
        if (savedAllowBreak == null) {
            savedAllowBreak = s.allowBreak.value;
            savedAllowPlace = s.allowPlace.value;
        }
        s.allowBreak.value = false;
        s.allowPlace.value = false;
    }

    public void restoreSettings() {
        if (savedAllowBreak == null) return;
        Settings s = BaritoneAPI.getSettings();
        s.allowBreak.value = savedAllowBreak;
        s.allowPlace.value = savedAllowPlace;
        savedAllowBreak = savedAllowPlace = null;
    }
}
