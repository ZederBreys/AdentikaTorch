package adentika.torch.modules;

import adentika.torch.TorchAutomationAddon;
import adentika.torch.core.GridSnapshot;
import adentika.torch.core.Planner;
import adentika.torch.core.TileGrid;
import adentika.torch.mc.*;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Engine of the walking modes (torch-pass: selection, torch-around: around the start point). A region is split into
 * 16×16 tiles; per tile: come closer if not loaded → scan (tile + margin inside the region) → plan from reachable
 * positions → place what is in reach ({@link PlacementDriver}) and walk to the rest with Baritone ({@link Navigator})
 * → verification scan → tile done → save progress. Pause/resume/cancel, a visible plan and the next torch marker.
 * Stops with a reason on: no torches, repeated rejections or path failures, death, world change, leaving the
 * region, torch limit. Movement keys never pause it (D-51). Never starts by itself on world join (D-45).
 */
public abstract class AbstractPass extends Module implements PlanSource {
    protected enum Phase { PICK, LOAD, SCAN, ANALYZE, WORK, VERIFY }

    private static final ExecutorService ANALYZER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "torch-automation-pass");
        t.setDaemon(true);
        return t;
    });

    /** Extra blocks scanned around a tile (inside the region): light and torch positions next to the border. */
    protected static final int TILE_MARGIN = 8;
    private static final int VERTICAL_MARGIN = 2;
    private static final int MAX_ROUNDS_PER_TILE = 3;
    private static final int MAX_CONSECUTIVE_PATH_FAILURES = 3;
    private static final int LEAVE_MARGIN = 16;

    protected final SettingGroup sgGeneral = settings.getDefaultGroup();
    protected final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<PlacementDriver.Mode> placementMode = sgGeneral.add(new EnumSetting.Builder<PlacementDriver.Mode>()
        .name("placement-mode")
        .description("Aggressive: several torches in a row. Legit: one at a time, waiting for the server.")
        .defaultValue(PlacementDriver.Mode.Aggressive)
        .build()
    );

    private final Setting<Integer> fastDelay = sgGeneral.add(new IntSetting.Builder()
        .name("fast-delay-ticks")
        .description("Aggressive mode: ticks between two clicks.")
        .defaultValue(2)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(() -> placementMode.get() == PlacementDriver.Mode.Aggressive)
        .build()
    );

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Legit mode: pause between two torches, in ticks.")
        .defaultValue(4)
        .range(2, 40)
        .sliderRange(2, 20)
        .visible(() -> placementMode.get() == PlacementDriver.Mode.Legit)
        .build()
    );

    private final Setting<Integer> maxFailures = sgGeneral.add(new IntSetting.Builder()
        .name("max-failures")
        .description("Stop after this many torches in a row did not appear.")
        .defaultValue(3)
        .range(1, 10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Keybind> pauseBind = sgGeneral.add(new KeybindSetting.Builder()
        .name("pause-bind")
        .description("Key to pause/resume instantly.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<SettingColor> doneColor = sgRender.add(new ColorSetting.Builder()
        .name("done-color").defaultValue(new SettingColor(60, 220, 60, 160)).build());
    private final Setting<SettingColor> currentColor = sgRender.add(new ColorSetting.Builder()
        .name("current-color").defaultValue(new SettingColor(255, 210, 40, 220)).build());
    private final Setting<SettingColor> skippedColor = sgRender.add(new ColorSetting.Builder()
        .name("skipped-color").defaultValue(new SettingColor(255, 60, 60, 160)).build());
    private final Setting<SettingColor> torchColor = sgRender.add(new ColorSetting.Builder()
        .name("torch-color").defaultValue(new SettingColor(255, 210, 40, 160)).build());
    private final Setting<SettingColor> nextColor = sgRender.add(new ColorSetting.Builder()
        .name("next-color").description("The torch walked to / placed next.")
        .defaultValue(new SettingColor(60, 255, 120, 200)).build());
    private final Setting<Boolean> showSpots = sgRender.add(new BoolSetting.Builder()
        .name("show-spots").description("Show the dark spots of the current tile, like torch-highlight.")
        .defaultValue(true).build());
    private final Setting<SettingColor> spotColor = sgRender.add(new ColorSetting.Builder()
        .name("spot-color").defaultValue(new SettingColor(255, 40, 40, 60)).visible(showSpots::get).build());

    protected final PlacementDriver driver = new PlacementDriver();
    protected Navigator nav;

    /** Region {minX, minY, minZ, maxX, maxY, maxZ}. */
    protected int[] region;
    protected TileGrid tiles;
    protected Phase phase = Phase.PICK;
    protected int tile = -1;
    private int rounds;
    private boolean paused;
    private WorldScan scan;
    private int[] scanTileBounds;
    private Future<Analysis.Result> pending;
    protected Analysis.Result plan;
    private final Set<BlockPos> unreachable = new HashSet<>();
    private final Map<BlockPos, Integer> approachAttempts = new HashMap<>();
    private BlockPos walkingTo;
    private TorchPlacer.Target nextTarget;
    private int pathFailures;
    private int skippedTargets;
    private boolean userStarted, silent, forceStart;

    protected AbstractPass(String name, String description) {
        super(TorchAutomationAddon.CATEGORY, name, description);
    }

    // Hooks

    /** Message source key ("pass", "around"). */
    protected abstract String source();

    /** Saved-progress kind ("pass", "around"). */
    protected abstract String storeKind();

    /** Region for this start, or null after printing the reason. */
    protected abstract int[] regionAtStart();

    /** Forbid Baritone to break/place for this run. */
    protected abstract boolean restrictBaritone();

    /** Analysis of one tile: the scope rules of the mode. */
    protected abstract Analysis.AreaRequest request(GridSnapshot grid, int[] surfaceY, int[] tileScope);

    /** Called with every analysis result (torch-around updates its connectivity map). */
    protected void analysed(Analysis.Result r) {
    }

    /** Torch limit for this run (0 = none). */
    protected int torchLimit() {
        return 0;
    }

    /** Start message (after checks, before the first tile). */
    protected abstract void announceStart(boolean resumed);

    /** Order of the remaining tiles: the nearest one to the player that borders the finished part. */
    protected List<Integer> tileOrder() {
        return tiles.todoInWalkOrder(mc.player.getX(), mc.player.getZ());
    }

    /** Extra drawing (region border...). */
    protected void renderExtra(Render3DEvent event) {
    }

    /** Prefix of the HUD status (mode). */
    protected String statusPrefix() {
        return "";
    }

    /** Plain box scope with the profile chosen in torch-area (one place for MONSTERS / SWAMP_SLIMES / PATROLS). */
    protected Analysis.AreaRequest boxRequest(GridSnapshot grid, int[] s, boolean preferWall) {
        return new Analysis.AreaRequest(grid, s[0], s[1], s[2], s[3], s[4], s[5], Modules.get().get(TorchArea.class).profile(),
            BlockRules.get().distrustsVariableLight(), preferWall, null);
    }

    // Start / stop

    @Override
    public void toggle() {
        userStarted = !isActive() && Utils.canUpdate();
        super.toggle();
        userStarted = false;
    }

    /** Start that takes over a busy Baritone, after the player confirmed it with a "force" command. */
    public void forceStart() {
        if (isActive()) return;
        forceStart = true;
        toggle();
        forceStart = false;
    }

    private void quietOff() {
        silent = true;
        super.toggle();
        silent = false;
    }

    @Override
    public void onActivate() {
        if (!userStarted || !startChecks()) {
            // Restored from the saved config on world join (D-45), or a check failed: never start by itself.
            quietOff();
            return;
        }
        driver.configure(placementMode.get(), delayTicks.get(), fastDelay.get(), maxFailures.get(), true);
        driver.reset();
        unreachable.clear();
        approachAttempts.clear();
        pathFailures = skippedTargets = 0;
        paused = false;
        phase = Phase.PICK;
        tile = -1;

        if (restrictBaritone()) {
            nav.restrictSettings();
            Msg.info(source(), Msg.t("pass.baritone_restricted"));
        }
        announceStart(tiles.countIn(TileGrid.State.DONE) > 0);
    }

    private boolean startChecks() {
        if (mc.world == null || mc.world.getRegistryKey() != World.OVERWORLD) {
            Msg.error(source(), Msg.t("common.overworld_only"));
            return false;
        }
        if (!BaritoneUtils.IS_AVAILABLE) {
            Msg.error(source(), Msg.t("pass.no_baritone"));
            return false;
        }
        if (nav == null) nav = new Navigator();
        if (nav.busyWithOtherTask() && !forceStart) {
            Msg.warning(source(), Msg.t(source() + ".busy"));
            return false;
        }
        if (!TorchPlacer.hasTorch()) {
            Msg.error(source(), Msg.t("common.no_torches_hotbar"));
            return false;
        }
        int[] b = regionAtStart();
        if (b == null) return false;
        if (!java.util.Arrays.equals(b, region)) {
            region = b;
            tiles = new TileGrid(b[0], b[2], b[3], b[5]);
            tiles.decode(PassStore.load(storeKind(), b));
            regionChanged();
        }
        return true;
    }

    /** A new region was set (progress loaded); subclasses reset their per-region state. */
    protected void regionChanged() {
    }

    @Override
    public void onDeactivate() {
        driver.cancel();
        if (nav != null) {
            nav.stop();
            nav.restoreSettings();
        }
        scan = null;
        if (pending != null) pending.cancel(false);
        pending = null;
        plan = null;
        walkingTo = null;
        nextTarget = null;
        save();
        if (!silent && mc.world != null && tiles != null) {
            Msg.info(source(), Msg.t("pass.stopped", Msg.good(driver.placed()), Msg.value(tiles.countIn(TileGrid.State.DONE)),
                Msg.value(tiles.count())));
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    /** Stop with a reason (saved progress stays; enable again to continue). */
    protected void stop(String reasonKey, Object... args) {
        Msg.warning(source(), Msg.t(reasonKey, args));
        toggle();
    }

    public void pause() {
        if (!isActive() || paused) return;
        paused = true;
        driver.cancel();
        nav.stop();
        walkingTo = null;
        Msg.warning(source(), Msg.t("pass.paused." + source()));
    }

    public void resume() {
        if (!isActive() || !paused) return;
        paused = false;
        Msg.info(source(), Msg.t("pass.resumed_play"));
    }

    /** Remove the task: stop and forget the saved progress (placed torches stay). */
    public void cancelTask() {
        PassStore.clear(storeKind());
        region = null;
        tiles = null;
        regionChanged();
        if (isActive()) {
            silent = true;
            toggle();
            silent = false;
        }
        Msg.info(source(), Msg.t("pass.cancelled"));
    }

    public boolean paused() {
        return paused;
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press || mc.currentScreen != null) return;
        if (pauseBind.get().matches(true, event.key, event.modifiers)) {
            if (paused) resume();
            else pause();
        }
    }

    // PlanSource: the plan of the current tile (every torch of a plan is needed, so no rescans between torches)

    @Override
    public Analysis.Result result() {
        return phase == Phase.WORK && !paused ? plan : null;
    }

    @Override
    public long resultScanId() {
        return 1;
    }

    @Override
    public long requestFreshScan() {
        return 1;
    }

    // Tick

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || paused) return;
        handlePlacement(driver.tickPost(this));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.world.getRegistryKey() != World.OVERWORLD) {
            stop("common.stop.world");
            return;
        }
        if (!mc.player.isAlive()) {
            stop("common.stop.dead");
            return;
        }
        if (paused) return;
        if (outsideRegion()) {
            stop("pass.stop.left");
            return;
        }
        int limit = torchLimit();
        if (limit > 0 && driver.placed() >= limit) {
            stop("pass.stop.limit", Msg.value(limit));
            return;
        }

        driver.configure(placementMode.get(), delayTicks.get(), fastDelay.get(), maxFailures.get(), true);
        if (!handlePlacement(driver.tickPre(this))) return;

        switch (phase) {
            case PICK -> pick();
            case LOAD -> load();
            case SCAN -> scanStep();
            case ANALYZE -> analysedStep();
            case WORK -> work();
            case VERIFY -> verify();
        }
    }

    private boolean handlePlacement(PlacementDriver.Stop stop) {
        switch (stop) {
            case NO_TORCHES -> stop("common.stop.no_torches");
            case REPEATED_FAILURES -> stop("common.stop.failures");
            case DEAD -> stop("common.stop.dead");
            case NONE -> {
                return true;
            }
        }
        return false;
    }

    private void pick() {
        List<Integer> todo = tileOrder();
        if (todo.isEmpty()) {
            finish();
            return;
        }
        tile = todo.get(0);
        rounds = 0;
        phase = Phase.LOAD;
    }

    private void load() {
        int[] b = tiles.bounds(tile);
        if (chunksLoaded(b)) {
            if (walkingTo != null) {
                nav.stop();
                walkingTo = null;
            }
            startScan();
            return;
        }
        BlockPos center = new BlockPos((b[0] + b[2]) / 2, mc.player.getBlockY(), (b[1] + b[3]) / 2);
        if (walkingTo == null || nav.status() == Navigator.Status.IDLE) {
            walkingTo = center;
            nav.goXZ(center.getX(), center.getZ());
        } else if (nav.status() == Navigator.Status.FAILED) {
            onPathFailure();
            tiles.set(tile, TileGrid.State.SKIPPED);
            save();
            walkingTo = null;
            phase = Phase.PICK;
        }
    }

    private boolean chunksLoaded(int[] b) {
        for (int cx = b[0] >> 4; cx <= b[2] >> 4; cx++) {
            for (int cz = b[1] >> 4; cz <= b[3] >> 4; cz++) {
                if (!mc.world.isChunkLoaded(cx, cz)) return false;
            }
        }
        return true;
    }

    private void startScan() {
        int[] b = tiles.bounds(tile);
        int minX = Math.max(region[0], b[0] - TILE_MARGIN), maxX = Math.min(region[3], b[2] + TILE_MARGIN);
        int minZ = Math.max(region[2], b[1] - TILE_MARGIN), maxZ = Math.min(region[5], b[3] + TILE_MARGIN);
        int minY = Math.max(mc.world.getBottomY(), region[1] - VERTICAL_MARGIN);
        int maxY = Math.min(mc.world.getTopY() - 1, region[4] + VERTICAL_MARGIN);
        scan = new WorldScan(mc.world, BlockRules.get(), minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, true, false);
        scanTileBounds = new int[]{b[0], region[1], b[1], b[2], region[4], b[3]};
        phase = Phase.SCAN;
    }

    private void scanStep() {
        if (!scan.step(System.nanoTime() + 3_000_000L)) return;
        Analysis.AreaRequest request = request(scan.grid(), scan.surfaceY(), scanTileBounds);
        pending = ANALYZER.submit(() -> Analysis.runArea(request));
        scan = null;
        phase = Phase.ANALYZE;
    }

    private void analysedStep() {
        if (pending == null || !pending.isDone()) return;
        try {
            plan = pending.get();
        } catch (Exception e) {
            TorchAutomationAddon.LOG.error("Torch pass analysis failed", e);
            Msg.error(source(), Msg.t("common.failed"));
            toggle();
            return;
        }
        pending = null;
        rounds++;
        analysed(plan);
        if (plan.unknownInScope() > 0) {
            // Part of the tile is still not loaded: come closer and try again.
            phase = Phase.LOAD;
            walkingTo = null;
            return;
        }
        if (plan.torches().isEmpty() || rounds > MAX_ROUNDS_PER_TILE) {
            completeTile();
            return;
        }
        phase = Phase.WORK;
    }

    /** Places what is in reach (driver) and walks to the nearest planned torch that is not. */
    private void work() {
        if (driver.targetInReach() || driver.placing()) return;
        TorchPlacer.Target next = nearestRemaining();
        nextTarget = next;
        if (next == null) {
            if (walkingTo != null) {
                nav.stop();
                walkingTo = null;
            }
            phase = Phase.VERIFY;
            return;
        }
        if (walkingTo == null || !walkingTo.equals(next.cell())) {
            walkingTo = next.cell();
            int attempt = approachAttempts.merge(next.cell(), 1, Integer::sum);
            nav.goNear(next.cell(), attempt == 1 ? 2 : 1);
            return;
        }
        switch (nav.status()) {
            case FAILED -> {
                markUnreachable(next.cell());
                onPathFailure();
            }
            case ARRIVED, IDLE -> {
                // Arrived but still no clear click: one closer attempt, then give up on this torch.
                if (approachAttempts.getOrDefault(next.cell(), 0) >= 2) markUnreachable(next.cell());
                walkingTo = null;
            }
            case MOVING -> {
            }
        }
    }

    private void markUnreachable(BlockPos cell) {
        unreachable.add(cell);
        skippedTargets++;
        walkingTo = null;
    }

    private void onPathFailure() {
        if (++pathFailures >= MAX_CONSECUTIVE_PATH_FAILURES) stop("pass.stop.path");
    }

    private void verify() {
        if (driver.placing()) return;
        pathFailures = 0;
        startScan();
    }

    private void completeTile() {
        tiles.set(tile, TileGrid.State.DONE);
        save();
        int limit = torchLimit();
        Msg.status(Msg.ID_AREA_PROGRESS, source(), limit > 0
            ? Msg.t("pass.progress_limit", Msg.good(tiles.countIn(TileGrid.State.DONE)), Msg.value(tiles.count()),
                Msg.good(driver.placed()), Msg.value(Math.max(0, limit - driver.placed())))
            : Msg.t("pass.progress", Msg.good(tiles.countIn(TileGrid.State.DONE)), Msg.value(tiles.count()), Msg.good(driver.placed())));
        plan = null;
        nextTarget = null;
        phase = Phase.PICK;
    }

    private void finish() {
        int skippedTiles = tiles.countIn(TileGrid.State.SKIPPED);
        Msg.ok(source(), Msg.t("pass.done", Msg.good(driver.placed()), Msg.value(tiles.count())));
        if (skippedTiles > 0 || skippedTargets > 0) {
            Msg.warning(source(), Msg.t("pass.done_skipped", Msg.warn(skippedTiles), Msg.warn(skippedTargets)));
        }
        PassStore.clear(storeKind());
        region = null;
        tiles = null;
        regionChanged();
        silent = true;
        toggle();
        silent = false;
    }

    protected void save() {
        if (region != null && tiles != null) PassStore.save(storeKind(), region, tiles.encode());
    }

    private TorchPlacer.Target nearestRemaining() {
        if (plan == null) return null;
        GridSnapshot g = plan.grid();
        TorchPlacer.Target best = null;
        double bestDist = Double.MAX_VALUE;
        for (Planner.Torch t : plan.torches()) {
            BlockPos cell = new BlockPos(g.x(t.cell()), g.y(t.cell()), g.z(t.cell()));
            if (unreachable.contains(cell) || !mc.world.getBlockState(cell).isReplaceable()) continue;
            double d = mc.player.getPos().squaredDistanceTo(cell.toCenterPos());
            if (d < bestDist) {
                best = new TorchPlacer.Target(cell, Direction.byId(t.attachDir()));
                bestDist = d;
            }
        }
        return best;
    }

    private boolean outsideRegion() {
        if (region == null) return false;
        double x = mc.player.getX(), z = mc.player.getZ();
        return x < region[0] - LEAVE_MARGIN || x > region[3] + 1 + LEAVE_MARGIN
            || z < region[2] - LEAVE_MARGIN || z > region[5] + 1 + LEAVE_MARGIN;
    }

    // Status and rendering

    @Override
    public String getInfoString() {
        if (tiles == null) return null;
        int limit = torchLimit();
        String progress = Msg.s("pass.status.tiles", tiles.countIn(TileGrid.State.DONE), tiles.count(), driver.placed())
            + (limit > 0 ? " (" + Msg.s("pass.status.limit", limit) + ")" : "");
        String prefix = statusPrefix();
        if (paused) return prefix + Msg.s("pass.status.paused") + " | " + progress;
        String what = switch (phase) {
            case PICK, LOAD -> Msg.s("pass.status.moving");
            case SCAN, ANALYZE, VERIFY -> Msg.s("common.status.analysing");
            case WORK -> walkingTo != null ? Msg.s("pass.status.moving") : Msg.s("pass.status.placing");
        };
        return prefix + what + " | " + progress;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        renderExtra(event);
        if (tiles == null || region == null) return;
        double y = mc.player.getY() + 0.05;
        for (int t = 0; t < tiles.count(); t++) {
            SettingColor c = t == tile ? currentColor.get()
                : tiles.state(t) == TileGrid.State.DONE ? doneColor.get()
                : tiles.state(t) == TileGrid.State.SKIPPED ? skippedColor.get() : null;
            if (c == null) continue;
            int[] b = tiles.bounds(t);
            event.renderer.sideHorizontal(b[0], y, b[1], b[2] + 1, b[3] + 1, c, c, ShapeMode.Lines);
        }
        if (plan != null && (phase == Phase.WORK || phase == Phase.VERIFY)) {
            if (showSpots.get()) PlanRenderer.spots(event, plan, spotColor.get(), mc.player.getX(), mc.player.getY(), mc.player.getZ(), 1e4, 8000);
            PlanRenderer.torches(event, plan, torchColor.get());
        }
        // Where exactly the next torch goes: a filled marker and a beam visible from afar.
        TorchPlacer.Target t = driver.currentTarget() != null ? driver.currentTarget() : (phase == Phase.WORK ? nextTarget : null);
        if (t != null) {
            BlockPos c = t.cell();
            SettingColor col = nextColor.get();
            event.renderer.box(c.getX() + 0.25, c.getY(), c.getZ() + 0.25, c.getX() + 0.75, c.getY() + 0.9, c.getZ() + 0.75, col, col, ShapeMode.Both, 0);
            event.renderer.line(c.getX() + 0.5, c.getY() + 0.9, c.getZ() + 0.5, c.getX() + 0.5, c.getY() + 6, c.getZ() + 0.5, col);
        }
    }
}
