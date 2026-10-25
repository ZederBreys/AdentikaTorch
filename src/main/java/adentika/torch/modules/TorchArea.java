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
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseButtonEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Selected area (PRODUCT_SPEC §2, mode 2a), milestone M3: select two corners, preview dark spots and the torch plan,
 * estimate the number of torches. Places nothing and does not move the player (placement: M4, walking: M5).
 */
public class TorchArea extends Module {
    private static final ExecutorService ANALYZER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "torch-automation-area");
        t.setDaemon(true);
        return t;
    });

    /** Selection includes this many blocks above the higher corner: corners are floor blocks, mobs stand above them. */
    private static final int HEADROOM = 2;
    private static final int MARGIN = 2;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Keybind> cornerBind = sgGeneral.add(new KeybindSetting.Builder()
        .name("corner-bind")
        .description("Look at a block and press to set a corner (first, then second; a third press starts over).")
        .defaultValue(Keybind.fromButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE))
        .build()
    );

    private final Setting<Integer> maxSize = sgGeneral.add(new IntSetting.Builder()
        .name("max-size")
        .description("Maximum horizontal side of the selection, in blocks.")
        .defaultValue(64)
        .range(8, 128)
        .sliderRange(8, 128)
        .build()
    );

    private final Setting<Integer> maxHeight = sgGeneral.add(new IntSetting.Builder()
        .name("max-height")
        .description("Maximum height of the selection, in blocks.")
        .defaultValue(32)
        .range(3, 96)
        .sliderRange(3, 64)
        .build()
    );

    private final Setting<SpawnModel.Profile> profile = sgGeneral.add(new EnumSetting.Builder<SpawnModel.Profile>()
        .name("profile")
        .description("MONSTERS: block light >= 1. SWAMP_SLIMES: >= 8. PATROLS: >= 9.")
        .defaultValue(SpawnModel.Profile.MONSTERS)
        .onChanged(v -> dirty = true)
        .build()
    );

    private final Setting<Boolean> preferWall = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-wall-torches")
        .description("Prefer wall torches when they cover as much as floor torches (useful in caves and tunnels).")
        .defaultValue(false)
        .onChanged(v -> dirty = true)
        .build()
    );

    private final Setting<Boolean> includeNoMonsterBiomes = sgGeneral.add(new BoolSetting.Builder()
        .name("include-no-monster-biomes")
        .description("Also mark spots in mushroom fields and deep dark (mobs can walk in from nearby biomes).")
        .defaultValue(false)
        .onChanged(v -> dirty = true)
        .build()
    );

    private final Setting<Integer> budgetMs = sgGeneral.add(new IntSetting.Builder()
        .name("budget-ms")
        .description("Maximum scan time per client tick, in milliseconds.")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> refreshSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("refresh-seconds")
        .description("Rescan the selection periodically (light can change without block updates).")
        .defaultValue(10)
        .range(2, 120)
        .sliderRange(2, 60)
        .build()
    );

    private final Setting<SettingColor> selectionColor = sgRender.add(new ColorSetting.Builder()
        .name("selection-color")
        .defaultValue(new SettingColor(80, 180, 255, 255))
        .build()
    );

    private final Setting<Integer> maxSpots = sgRender.add(new IntSetting.Builder()
        .name("max-spots")
        .description("Maximum number of dark spots drawn per frame.")
        .defaultValue(5000)
        .range(100, 30000)
        .sliderRange(100, 10000)
        .build()
    );

    private final Setting<SettingColor> spotColor = sgRender.add(new ColorSetting.Builder()
        .name("spot-color")
        .defaultValue(new SettingColor(255, 40, 40, 60))
        .build()
    );

    private final Setting<SettingColor> torchColor = sgRender.add(new ColorSetting.Builder()
        .name("torch-color")
        .defaultValue(new SettingColor(255, 210, 40, 160))
        .build()
    );

    // Selection (kept while the module is off; cleared on world change)
    private BlockPos corner1, corner2;
    private ClientWorld selectionWorld;

    // Analysis state
    private WorldScan scan;
    private int[] scanBounds;
    private Future<Analysis.Result> pending;
    private Analysis.Result result;
    private int rulesVersion = -1;
    private boolean dirty;
    private int dirtyDelay;
    private int refreshTicks;
    private boolean summaryDue;

    public TorchArea() {
        super(TorchAutomationAddon.CATEGORY, "torch-area",
            "Select an area with two corners and preview where torches are needed and how many. Places nothing yet.");
    }

    // Selection API (also used by .torcharea)

    public void setCorner(int which, BlockPos pos) {
        checkWorld();
        if (which == 1) {
            corner1 = pos.toImmutable();
            corner2 = null;
        } else {
            corner2 = pos.toImmutable();
        }
        selectionWorld = mc.world;
        Msg.info("area", Msg.t("area.corner", Msg.value(which), Msg.value(pos.getX() + " " + pos.getY() + " " + pos.getZ())));
        onSelectionChanged();
    }

    public void clearSelection() {
        corner1 = corner2 = null;
        selectionWorld = null;
        resetAnalysis();
    }

    public boolean hasSelection() {
        return corner1 != null && corner2 != null;
    }

    public Analysis.Result result() {
        return result;
    }

    /** Selection bounds {minX, minY, minZ, maxX, maxY, maxZ} including headroom, or null. */
    public int[] bounds() {
        if (!hasSelection()) return null;
        return new int[]{
            Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()),
            Math.max(corner1.getX(), corner2.getX()), Math.max(corner1.getY(), corner2.getY()) + HEADROOM, Math.max(corner1.getZ(), corner2.getZ())
        };
    }

    /** Null if the selection is acceptable, otherwise the reason (localised). */
    public Text validate() {
        int[] b = bounds();
        if (b == null) return Msg.t("area.need_corners");
        int sx = b[3] - b[0] + 1, sy = b[4] - b[1] + 1, sz = b[5] - b[2] + 1;
        if (sx > maxSize.get() || sz > maxSize.get()) return Msg.t("area.too_big", Msg.value(sx + "×" + sz), Msg.value(maxSize.get()));
        if (sy > maxHeight.get()) return Msg.t("area.too_high", Msg.value(sy), Msg.value(maxHeight.get()));
        if (mc.world == null || mc.world.getRegistryKey() != World.OVERWORLD) return Msg.t("common.overworld_only");
        return null;
    }

    private void onSelectionChanged() {
        resetAnalysis();
        if (!hasSelection()) return;
        Text problem = validate();
        if (problem != null) Msg.error("area", problem);
        else if (!isActive()) Msg.info("area", Msg.t("area.enable"));
    }

    private void checkWorld() {
        if (selectionWorld != null && selectionWorld != mc.world) {
            corner1 = corner2 = null;
            selectionWorld = null;
            resetAnalysis();
        }
    }

    // Lifecycle

    @Override
    public void onActivate() {
        resetAnalysis();
        if (!hasSelection()) Msg.info("area", Msg.t("area.hint", Msg.value(cornerBind.get())));
    }

    @Override
    public void onDeactivate() {
        resetAnalysis();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        clearSelection();
    }

    private void resetAnalysis() {
        scan = null;
        scanBounds = null;
        if (pending != null) pending.cancel(false);
        pending = null;
        result = null;
        dirty = false;
        summaryDue = true;
        lastNeeded = lastExisting = -1;
        lastDone = false;
    }

    // Input

    @EventHandler
    private void onMouse(MouseButtonEvent event) {
        if (event.action == KeyAction.Press && cornerBind.get().matches(false, event.button, 0) && pickCorner()) event.cancel();
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action == KeyAction.Press && cornerBind.get().matches(true, event.key, event.modifiers) && pickCorner()) event.cancel();
    }

    private boolean pickCorner() {
        if (mc.currentScreen != null || mc.world == null) return false;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;
        setCorner(corner1 == null || hasSelection() ? 1 : 2, hit.getBlockPos());
        return true;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        GridSnapshot g = result != null ? result.grid() : null;
        if (g != null && g.index(event.pos.getX(), event.pos.getY(), event.pos.getZ()) >= 0 && !dirty) {
            dirty = true;
            dirtyDelay = 20; // let a burst of changes settle
        }
    }

    // Tick: incremental scan -> background analysis

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        checkWorld();
        if (!hasSelection() || validate() != null) return;

        if (pending != null && pending.isDone()) {
            try {
                result = pending.get();
                if (summaryDue) report(result);
                else reportProgress(result);
                summaryDue = false;
            } catch (Exception e) {
                Msg.error("area", Msg.t("common.failed"));
                TorchAutomationAddon.LOG.error("Torch area analysis failed", e);
            }
            pending = null;
        }

        if (scan != null) {
            if (rulesVersion != BlockRules.version()) {
                scan = null;
            } else if (scan.step(System.nanoTime() + budgetMs.get() * 1_000_000L)) {
                Analysis.AreaRequest request = new Analysis.AreaRequest(scan.grid(), scanBounds[0], scanBounds[1], scanBounds[2],
                    scanBounds[3], scanBounds[4], scanBounds[5], profile.get(), BlockRules.get().distrustsVariableLight(),
                    preferWall.get(), previousTorches());
                pending = ANALYZER.submit(() -> Analysis.runArea(request));
                scan = null;
            }
            return;
        }
        if (pending != null) return;

        refreshTicks--;
        if (dirty && --dirtyDelay > 0) return;
        if (result == null || dirty || refreshTicks <= 0 || rulesVersion != BlockRules.version()) startScan();
    }

    private void startScan() {
        int[] b = bounds();
        int minY = Math.max(mc.world.getBottomY(), b[1] - MARGIN);
        int maxY = Math.min(mc.world.getTopY() - 1, b[4] + MARGIN);
        rulesVersion = BlockRules.version();
        scan = new WorldScan(mc.world, BlockRules.get(), b[0] - MARGIN, minY, b[2] - MARGIN,
            b[3] - b[0] + 1 + 2 * MARGIN, maxY - minY + 1, b[5] - b[2] + 1 + 2 * MARGIN, true, includeNoMonsterBiomes.get());
        scanBounds = b;
        dirty = false;
        refreshTicks = refreshSeconds.get() * 20;
    }

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

    /** Torches the placer could use (hotbar and offhand, D-14). */
    public static int torchesInHotbar() {
        var player = meteordevelopment.meteorclient.MeteorClient.mc.player;
        if (player == null) return 0;
        int n = 0;
        for (int i = 0; i < 9; i++) n += count(player.getInventory().getStack(i));
        return n + count(player.getOffHandStack());
    }

    private static int count(ItemStack s) {
        return s.isOf(Items.TORCH) ? s.getCount() : 0;
    }

    /** Full, player-oriented summary: after a new selection or on .torcharea info. */
    public void report(Analysis.Result r) {
        int[] b = bounds();
        int needed = r.torches().size(), hotbar = torchesInHotbar();
        TorchAutomationAddon.LOG.info("Torch area {}x{}x{}: dark {}, plan {}, uncoverable {}, unknown {}, existing {}, {} ms",
            b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1, r.dangerCells().length, needed, r.uncovered(),
            r.unknownInScope(), r.existingTorches(), r.millis());

        Msg.info("area", Msg.t("area.summary", Msg.value((b[3] - b[0] + 1) + "×" + (b[5] - b[2] + 1) + "×" + (b[4] - b[1] + 1))));
        if (isDone(r)) {
            Msg.ok("area", Msg.t("area.no_dark", Msg.good(r.existingTorches())));
        } else {
            Msg.info("area", Msg.t("area.counts", Msg.approx(needed), Msg.good(r.existingTorches()),
                hotbar < needed ? Msg.warn(hotbar) : Msg.value(hotbar)));
            if (r.dangerCells().length > 0) Msg.info("area", Msg.t("area.dark", Msg.bad(r.dangerCells().length)));
            if (r.uncovered() > 0) Msg.warning("area", Msg.t("area.uncoverable", Msg.warn(r.uncovered())));
            if (hotbar < needed) Msg.warning("area", Msg.t("area.short", Msg.warn(hotbar), Msg.approx(needed)));
        }
        if (r.unknownInScope() > 0) Msg.warning("area", Msg.t("area.partial"));
        remember(r);
    }

    // Last reported state, so updates are posted only when something meaningful changed.
    private int lastNeeded = -1, lastExisting = -1;
    private boolean lastDone;

    private void reportProgress(Analysis.Result r) {
        boolean done = isDone(r);
        if (done && !lastDone) {
            Msg.ok("area", Msg.t("area.done", Msg.good(r.existingTorches())));
        } else if (!done && (r.torches().size() != lastNeeded || r.existingTorches() != lastExisting)) {
            Msg.status(Msg.ID_AREA_PROGRESS, "area", Msg.t("area.progress", Msg.good(r.existingTorches()),
                Msg.approx(r.torches().size()), Msg.bad(r.dangerCells().length)));
        }
        remember(r);
    }

    private void remember(Analysis.Result r) {
        lastNeeded = r.torches().size();
        lastExisting = r.existingTorches();
        lastDone = isDone(r);
    }

    /** No dark spot left in a fully loaded selection. Claims nothing about unloaded parts. */
    private static boolean isDone(Analysis.Result r) {
        return r.dangerCells().length == 0 && r.unknownInScope() == 0;
    }

    @Override
    public String getInfoString() {
        if (!hasSelection()) return Msg.s(corner1 != null ? "area.status.corner2" : "area.status.select");
        if (validate() != null) return Msg.s("area.status.invalid");
        if (scan != null) return Msg.s("common.status.scan", (int) (scan.progress() * 100));
        if (result == null) return Msg.s("common.status.analysing");
        if (isDone(result)) return Msg.s("area.status.done");
        return Msg.s("area.status.result", result.torches().size(), result.existingTorches(), torchesInHotbar());
    }

    // Render

    @EventHandler
    private void onRender(Render3DEvent event) {
        SettingColor sel = selectionColor.get();
        if (corner1 != null && corner2 == null) event.renderer.box(corner1, sel, sel, ShapeMode.Lines, 0);
        int[] b = bounds();
        if (b != null) event.renderer.box(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, sel, sel, ShapeMode.Lines, 0);

        if (result == null || mc.player == null) return;
        PlanRenderer.spots(event, result, spotColor.get(), mc.player.getX(), mc.player.getY(), mc.player.getZ(), 1e4, maxSpots.get());
        PlanRenderer.torches(event, result, torchColor.get());
    }
}
