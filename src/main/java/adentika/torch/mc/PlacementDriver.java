package adentika.torch.mc;

import adentika.torch.core.GridSnapshot;
import adentika.torch.core.PlacementTracker;
import adentika.torch.core.Planner;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Places planned torches within reach while the player moves on their own (mode 1; torch-area "place-in-reach").
 * Call {@link #tickPre} on {@code TickEvent.Pre} and {@link #tickPost} on {@code TickEvent.Post}.
 * <ul>
 *   <li>{@link Mode#Legit}: turn, wait a tick, click, wait for the server's answer and a fresh scan, then the next.</li>
 *   <li>{@link Mode#Aggressive}: turn at the start of the tick, click at its end (after the look direction was sent),
 *       next click after {@code fastDelay} ticks; confirmations are judged in parallel. Every torch of a plan covers
 *       spots no other planned torch covers, so placing several from one plan never wastes torches.</li>
 * </ul>
 * Reach and line of sight are checked for every click in both modes ({@link TorchPlacer}).
 * Stops (never silently continues) when torches run out, after repeated rejections, on death.
 */
public final class PlacementDriver {
    public enum Mode { Aggressive, Legit }

    public enum Stop { NONE, NO_TORCHES, REPEATED_FAILURES, DEAD }

    /** A position the server rejected this many times is skipped for the rest of the session. */
    private static final int FAILURES_PER_TARGET = 2;
    /** Aggressive mode turns the camera back after this many ticks without a target. */
    private static final int RESTORE_AFTER_IDLE_TICKS = 3;

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final TorchPlacer placer = new TorchPlacer();
    private PlacementTracker tracker = new PlacementTracker(FAILURES_PER_TARGET, 3);

    private Mode mode = Mode.Aggressive;
    private int legitDelay = 4, fastDelay = 2, maxFailures = 3;
    private boolean restoreView = true;

    private long tick;
    private long nextClickTick;
    private int waitTicks;
    private boolean clickThisTick;
    private long requiredScan;
    private boolean targetInReach;
    private int idleTicks;
    private Float savedYaw, savedPitch;

    public void configure(Mode mode, int legitDelay, int fastDelay, int maxFailures, boolean restoreView) {
        this.mode = mode;
        this.legitDelay = legitDelay;
        this.fastDelay = fastDelay;
        this.restoreView = restoreView;
        if (maxFailures != this.maxFailures) {
            this.maxFailures = maxFailures;
            tracker = new PlacementTracker(FAILURES_PER_TARGET, maxFailures);
        }
    }

    public void reset() {
        cancel();
        tracker = new PlacementTracker(FAILURES_PER_TARGET, maxFailures);
        tick = nextClickTick = requiredScan = 0;
        targetInReach = false;
    }

    /** Immediate stop: no further clicks, sneak released, camera turned back. */
    public void cancel() {
        placer.cancel();
        clickThisTick = false;
        restoreView();
    }

    public int placed() {
        return tracker.placed();
    }

    public boolean placing() {
        return placer.prepared() || tracker.pendingCount() > 0;
    }

    public boolean targetInReach() {
        return targetInReach;
    }

    public TorchPlacer.Target currentTarget() {
        return placer.target();
    }

    public Stop tickPre(PlanSource source) {
        if (mc.player == null || mc.world == null) return Stop.NONE;
        tick++;
        if (!mc.player.isAlive()) {
            cancel();
            return Stop.DEAD;
        }

        tracker.poll(tick, this::torchAt);
        if (tracker.shouldStop()) {
            cancel();
            return Stop.REPEATED_FAILURES;
        }

        if (placer.prepared()) {
            if (mode == Mode.Legit) {
                placer.lookAtTarget();
                if (--waitTicks <= 0) return click(source);
            }
            return Stop.NONE;
        }
        if (tick < nextClickTick) return Stop.NONE;
        if (mode == Mode.Legit && (tracker.pendingCount() > 0 || source.resultScanId() < requiredScan)) return Stop.NONE;

        Analysis.Result result = source.result();
        if (result == null) return Stop.NONE;
        TorchPlacer.Target target = nearestPlaceable(result);
        targetInReach = target != null;
        if (target == null) {
            if (++idleTicks >= RESTORE_AFTER_IDLE_TICKS) restoreView();
            return Stop.NONE;
        }
        idleTicks = 0;
        if (!TorchPlacer.hasTorch()) {
            restoreView();
            return Stop.NO_TORCHES;
        }

        if (savedYaw == null) {
            savedYaw = mc.player.getYaw();
            savedPitch = mc.player.getPitch();
        }
        boolean sneak = BlockRules.get().needsSneak(TraitsProvider.of(mc.world.getBlockState(target.supportPos())));
        if (!placer.prepare(target, sneak)) return Stop.NONE;
        if (mode == Mode.Legit) waitTicks = placer.sneakPressed() ? 2 : 1;
        else clickThisTick = true;
        return Stop.NONE;
    }

    /** Aggressive mode clicks here: the movement packet with the new look direction has been sent this tick. */
    public Stop tickPost(PlanSource source) {
        if (!clickThisTick) return Stop.NONE;
        clickThisTick = false;
        return click(source);
    }

    private Stop click(PlanSource source) {
        BlockPos cell = placer.target().cell();
        switch (placer.click()) {
            case CLICKED -> {
                tracker.clicked(cell.asLong(), tick, confirmTicks());
                requiredScan = source.requestFreshScan();
                nextClickTick = tick + (mode == Mode.Legit ? legitDelay : fastDelay);
                if (mode == Mode.Legit) restoreView();
            }
            case NO_TORCHES -> {
                restoreView();
                return Stop.NO_TORCHES;
            }
            case NOT_NOW -> nextClickTick = tick + 1;
        }
        return Stop.NONE;
    }

    /** Time for the server to answer: the client shows its prediction at once, the server may revert it. */
    private static int confirmTicks() {
        return Math.max(6, PlayerUtils.getPing() / 50 + 4);
    }

    private boolean torchAt(long packed) {
        BlockState s = mc.world.getBlockState(BlockPos.fromLong(packed));
        return s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH);
    }

    private void restoreView() {
        if (savedYaw == null || mc.player == null) return;
        if (restoreView) {
            mc.player.setYaw(savedYaw);
            mc.player.setPitch(savedPitch);
        }
        savedYaw = savedPitch = null;
    }

    private TorchPlacer.Target nearestPlaceable(Analysis.Result result) {
        GridSnapshot g = result.grid();
        Vec3d eye = mc.player.getEyePos();
        double reach = TorchPlacer.reach() + 1;
        TorchPlacer.Target best = null;
        double bestDist = Double.MAX_VALUE;
        for (Planner.Torch t : result.torches()) {
            BlockPos cell = new BlockPos(g.x(t.cell()), g.y(t.cell()), g.z(t.cell()));
            long key = cell.asLong();
            if (tracker.isSkipped(key) || tracker.isPending(key)) continue;
            double d = eye.squaredDistanceTo(Vec3d.ofCenter(cell));
            // Cheap distance filter first; the exact reach and line-of-sight check is TorchPlacer.aim().
            if (d >= bestDist || d > reach * reach) continue;
            TorchPlacer.Target target = new TorchPlacer.Target(cell, Direction.byId(t.attachDir()));
            if (TorchPlacer.aim(target) == null) continue;
            best = target;
            bestDist = d;
        }
        return best;
    }
}
