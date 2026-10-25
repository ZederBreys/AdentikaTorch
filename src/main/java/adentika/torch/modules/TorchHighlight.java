package adentika.torch.modules;

import adentika.torch.TorchAutomationAddon;
import adentika.torch.core.GridSnapshot;
import adentika.torch.core.Planner;
import adentika.torch.core.SpawnModel;
import adentika.torch.mc.Analysis;
import adentika.torch.mc.BlockRules;
import adentika.torch.mc.Msg;
import adentika.torch.mc.PlanRenderer;
import adentika.torch.mc.WorldScan;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Mode 3 "Highlight" (PRODUCT_SPEC §2): shows dark chunks, spots where dark-spawning monsters can appear and
 * recommended torch positions. Places nothing. Scans incrementally within a per-tick time budget; analysis and
 * planning run on a background thread from an immutable snapshot.
 */
public class TorchHighlight extends Module {
    public enum Mode { Auto, Surface, Cave }

    private static final ExecutorService ANALYZER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "torch-automation-analysis");
        t.setDaemon(true);
        return t;
    });

    private final SettingGroup sgScope = settings.getDefaultGroup();
    private final SettingGroup sgScan = settings.createGroup("Scan");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // Scope

    private final Setting<Mode> mode = sgScope.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Auto: Cave when you are underground, otherwise Surface. Surface: small vertical window near the surface. Cave: larger window, only the cave you are in, wall torches preferred.")
        .defaultValue(Mode.Auto)
        .build()
    );

    private final Setting<Integer> autoCaveDepth = sgScope.add(new IntSetting.Builder()
        .name("auto-cave-depth")
        .description("Auto mode: you are underground when your feet are at least this many blocks below the surface of your column.")
        .defaultValue(6)
        .range(2, 32)
        .sliderRange(2, 16)
        .visible(() -> mode.get() == Mode.Auto)
        .build()
    );

    private final Setting<Integer> radius = sgScope.add(new IntSetting.Builder()
        .name("radius")
        .description("Horizontal radius around you.")
        .defaultValue(32)
        .range(8, 64)
        .sliderRange(8, 64)
        .build()
    );

    private final Setting<Integer> blocksDown = sgScope.add(new IntSetting.Builder()
        .name("blocks-down")
        .description("How many blocks below your feet are analysed (surface mode).")
        .defaultValue(6)
        .range(0, 32)
        .sliderRange(0, 32)
        .visible(() -> mode.get() != Mode.Cave)
        .build()
    );

    private final Setting<Integer> blocksUp = sgScope.add(new IntSetting.Builder()
        .name("blocks-up")
        .description("How many blocks above your feet are analysed (surface mode).")
        .defaultValue(8)
        .range(0, 32)
        .sliderRange(0, 32)
        .visible(() -> mode.get() != Mode.Cave)
        .build()
    );

    private final Setting<Integer> caveBlocksDown = sgScope.add(new IntSetting.Builder()
        .name("cave-blocks-down")
        .description("How many blocks below your feet are analysed (cave mode).")
        .defaultValue(24)
        .range(0, 64)
        .sliderRange(0, 64)
        .visible(() -> mode.get() != Mode.Surface)
        .build()
    );

    private final Setting<Integer> caveBlocksUp = sgScope.add(new IntSetting.Builder()
        .name("cave-blocks-up")
        .description("How many blocks above your feet are analysed (cave mode).")
        .defaultValue(16)
        .range(0, 64)
        .sliderRange(0, 64)
        .visible(() -> mode.get() != Mode.Surface)
        .build()
    );

    private final Setting<Boolean> connectedOnly = sgScope.add(new BoolSetting.Builder()
        .name("connected-only")
        .description("Only spots connected to you through air (a closed mine below is ignored).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> limitDepth = sgScope.add(new BoolSetting.Builder()
        .name("limit-depth")
        .description("Surface mode: ignore spots deeper than max-depth below the surface of their column.")
        .defaultValue(true)
        .visible(() -> mode.get() != Mode.Cave)
        .build()
    );

    private final Setting<Integer> maxDepth = sgScope.add(new IntSetting.Builder()
        .name("max-depth")
        .description("Maximum depth below the surface, in blocks.")
        .defaultValue(3)
        .range(0, 32)
        .sliderRange(0, 16)
        .visible(() -> mode.get() != Mode.Cave && limitDepth.get())
        .build()
    );

    private final Setting<Boolean> includeNoMonsterBiomes = sgScope.add(new BoolSetting.Builder()
        .name("include-no-monster-biomes")
        .description("Also mark spots in mushroom fields and deep dark. Monsters do not spawn there naturally, but can walk in from nearby biomes.")
        .defaultValue(false)
        .onChanged(v -> dirty = true)
        .build()
    );

    private final Setting<SpawnModel.Profile> profile = sgScope.add(new EnumSetting.Builder<SpawnModel.Profile>()
        .name("profile")
        .description("MONSTERS: block light >= 1 (zombies, skeletons, creepers...). SWAMP_SLIMES: >= 8. PATROLS: >= 9.")
        .defaultValue(SpawnModel.Profile.MONSTERS)
        .build()
    );

    // Scan

    private final Setting<Integer> budgetMs = sgScan.add(new IntSetting.Builder()
        .name("budget-ms")
        .description("Maximum scan time per client tick, in milliseconds.")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> rescanDistance = sgScan.add(new IntSetting.Builder()
        .name("rescan-distance")
        .description("Rescan after you move this many blocks from the last scan centre.")
        .defaultValue(8)
        .range(1, 64)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Integer> refreshSeconds = sgScan.add(new IntSetting.Builder()
        .name("refresh-seconds")
        .description("Rescan periodically even without changes (light may change asynchronously).")
        .defaultValue(10)
        .range(2, 120)
        .sliderRange(2, 60)
        .build()
    );

    private final Setting<Boolean> chatSummary = sgScan.add(new BoolSetting.Builder()
        .name("chat-summary")
        .description("Print a short scan summary to chat after enabling (helps diagnose an empty highlight).")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> showSpots = sgRender.add(new BoolSetting.Builder()
        .name("show-spots")
        .description("Mark single dark spots where monsters can spawn.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> spotRadius = sgRender.add(new IntSetting.Builder()
        .name("spot-radius")
        .description("Only draw spots within this distance.")
        .defaultValue(24)
        .range(4, 64)
        .sliderRange(4, 64)
        .visible(showSpots::get)
        .build()
    );

    private final Setting<Integer> maxSpots = sgRender.add(new IntSetting.Builder()
        .name("max-spots")
        .description("Maximum number of spots drawn per frame.")
        .defaultValue(3000)
        .range(100, 20000)
        .sliderRange(100, 10000)
        .visible(showSpots::get)
        .build()
    );

    private final Setting<Boolean> showChunks = sgRender.add(new BoolSetting.Builder()
        .name("show-chunks")
        .description("Tint chunks that contain dark spots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTorches = sgRender.add(new BoolSetting.Builder()
        .name("show-torches")
        .description("Show recommended torch positions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> spotColor = sgRender.add(new ColorSetting.Builder()
        .name("spot-color")
        .defaultValue(new SettingColor(255, 40, 40, 60))
        .build()
    );

    private final Setting<SettingColor> chunkColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-color")
        .defaultValue(new SettingColor(255, 120, 0, 40))
        .build()
    );

    private final Setting<SettingColor> torchColor = sgRender.add(new ColorSetting.Builder()
        .name("torch-color")
        .defaultValue(new SettingColor(255, 210, 40, 160))
        .build()
    );

    // State

    private WorldScan scan;
    private Analysis.Request scanRequest;
    private Future<Analysis.Result> pending;
    private Analysis.Result result;
    private BlockPos scanCenter;
    private int rulesVersion = -1;
    private boolean dirty;
    private int refreshTicks;
    private boolean warnedDimension;
    private Boolean lastCave;

    public TorchHighlight() {
        super(TorchAutomationAddon.CATEGORY, "torch-highlight",
            "Shows dark spots where hostile mobs can spawn and recommended torch positions. Places nothing.");
    }

    @Override
    public void onActivate() {
        reset();
        warnedDimension = false;
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    private void reset() {
        lastCave = null;
        scan = null;
        scanRequest = null;
        if (pending != null) pending.cancel(false);
        pending = null;
        result = null;
        scanCenter = null;
        dirty = false;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        GridSnapshot g = result != null ? result.grid() : null;
        if (g != null && g.index(event.pos.getX(), event.pos.getY(), event.pos.getZ()) >= 0) dirty = true;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        if (mc.world.getRegistryKey() != World.OVERWORLD) {
            if (!warnedDimension) Msg.warning("highlight", Msg.t("highlight.overworld_only"));
            warnedDimension = true;
            reset();
            return;
        }
        warnedDimension = false;

        if (pending != null && pending.isDone()) {
            try {
                boolean first = result == null;
                result = pending.get();
                if (first) reportSummary(result);
            } catch (Exception e) {
                Msg.error("highlight", Msg.t("common.failed"));
                TorchAutomationAddon.LOG.error("Torch analysis failed", e);
            }
            pending = null;
        }

        if (scan != null) {
            if (rulesVersion != BlockRules.version()) {
                scan = null;
            } else if (scan.step(System.nanoTime() + budgetMs.get() * 1_000_000L)) {
                Analysis.Request done = withGrid(scanRequest, scan);
                pending = ANALYZER.submit(() -> Analysis.run(done));
                scan = null;
            }
            return;
        }

        if (pending != null) return;
        refreshTicks--;
        BlockPos p = mc.player.getBlockPos();
        boolean moved = scanCenter == null || scanCenter.getManhattanDistance(p) >= rescanDistance.get();
        if (result == null || dirty || moved || refreshTicks <= 0 || rulesVersion != BlockRules.version()) startScan(p);
    }

    private void startScan(BlockPos p) {
        boolean cave = isCave(p);
        if (mode.get() == Mode.Auto && lastCave != null && lastCave != cave) Msg.info("highlight", Msg.t(cave ? "highlight.cave" : "highlight.surface"));
        if (lastCave == null || lastCave != cave) result = null;
        lastCave = cave;
        int down = cave ? caveBlocksDown.get() : blocksDown.get();
        int up = cave ? caveBlocksUp.get() : blocksUp.get();
        int r = radius.get();
        int margin = 2;

        int minY = Math.max(mc.world.getBottomY(), p.getY() - down - 1);
        int maxY = Math.min(mc.world.getTopY() - 1, p.getY() + up + 1);
        int size = 2 * (r + margin) + 1;

        rulesVersion = BlockRules.version();
        boolean overworld = mc.world.getRegistryKey() == World.OVERWORLD;
        scan = new WorldScan(mc.world, BlockRules.get(), p.getX() - r - margin, minY, p.getZ() - r - margin,
            size, maxY - minY + 1, size, overworld, includeNoMonsterBiomes.get());
        scanRequest = new Analysis.Request(null, null, p.getX(), p.getY(), p.getZ(), r, down, up,
            connectedOnly.get(), limitDepth.get() ? maxDepth.get() : -1, cave, profile.get(),
            BlockRules.get().distrustsVariableLight(), previousTorches());
        scanCenter = p;
        dirty = false;
        refreshTicks = refreshSeconds.get() * 20;
    }

    private void reportSummary(Analysis.Result r) {
        GridSnapshot g = r.grid();
        String line = String.format("area %dx%dx%d, spawnable %d, dark %d, torches %d, uncoverable %d, unknown %d, analysis %d ms",
            g.sizeX(), g.sizeY(), g.sizeZ(), r.spawnableCells(), r.dangerCells().length, r.torches().size(),
            r.uncovered(), r.unknownCells(), r.millis());
        TorchAutomationAddon.LOG.info("Torch highlight: {}", line);
        if (!chatSummary.get()) return;
        // One line, replaced on the next summary instead of piling up.
        if (r.dangerCells().length == 0) {
            Msg.status(Msg.ID_HIGHLIGHT, "highlight", Msg.t("highlight.no_dark"));
        } else {
            Msg.status(Msg.ID_HIGHLIGHT, "highlight", Msg.t("highlight.summary", Msg.bad(r.dangerCells().length), Msg.approx(r.torches().size())));
        }
        if (r.unknownInScope() > 0) Msg.warning("highlight", Msg.t("highlight.partial"));
    }

    private static Analysis.Request withGrid(Analysis.Request r, WorldScan s) {
        return new Analysis.Request(s.grid(), s.surfaceY(), r.playerX(), r.playerY(), r.playerZ(), r.radius(),
            r.blocksDown(), r.blocksUp(), r.connectedOnly(), r.maxDepthBelowSurface(), r.caveMode(), r.profile(),
            r.stableLightOnly(), r.previousTorches());
    }

    private boolean isCave(BlockPos p) {
        return switch (mode.get()) {
            case Surface -> false;
            case Cave -> true;
            case Auto -> p.getY() <= WorldScan.surfaceY(mc.world, p.getX(), p.getZ()) + 1 - autoCaveDepth.get();
        };
    }

    /** World positions of the current recommendations, so the next plan keeps the ones around the player. */
    private int[] previousTorches() {
        if (result == null) return null;
        GridSnapshot g = result.grid();
        int[] xyz = new int[result.torches().size() * 3];
        int k = 0;
        for (Planner.Torch t : result.torches()) {
            xyz[k++] = g.x(t.cell());
            xyz[k++] = g.y(t.cell());
            xyz[k++] = g.z(t.cell());
        }
        return xyz;
    }

    @Override
    public String getInfoString() {
        if (scan != null) return Msg.s("common.status.scan", (int) (scan.progress() * 100));
        if (result == null) return pending != null ? Msg.s("common.status.analysing") : null;
        String mode = lastCave == null ? "" : Msg.s(lastCave ? "highlight.status.cave" : "highlight.status.surface") + " | ";
        if (result.dangerCells().length == 0) return mode + Msg.s("highlight.status.clear");
        return mode + Msg.s("highlight.status.result", result.dangerCells().length, result.torches().size());
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (result == null || mc.player == null) return;
        if (showChunks.get()) PlanRenderer.chunks(event, result, chunkColor.get());
        if (showSpots.get()) {
            PlanRenderer.spots(event, result, spotColor.get(), mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                spotRadius.get(), maxSpots.get());
        }
        if (showTorches.get()) PlanRenderer.torches(event, result, torchColor.get());
    }
}
