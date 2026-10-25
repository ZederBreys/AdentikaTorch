package adentika.torch.modules;

import adentika.torch.core.GridSnapshot;
import adentika.torch.core.TileGrid;
import adentika.torch.mc.Analysis;
import adentika.torch.mc.BlockRules;
import adentika.torch.mc.Msg;
import adentika.torch.mc.PassStore;
import adentika.torch.mc.WorldScan;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mode 2b "Torches around me" (PRODUCT_SPEC §2, M6): like torch-pass, but without a selection — the region is a square
 * of {@code radius} around the start point with a vertical window fixed at the start height. Only spots air-connected
 * to the player count (a closed mine under your feet is left alone); connectivity is carried from tile to tile.
 * Cave mode: larger window, wall torches preferred, Baritone does not dig unless allowed.
 * Commands: {@code .torcharound start [radius] | preview [radius] | pause | resume | cancel | info}.
 */
public class TorchAround extends AbstractPass {
    private static final int RESUME_DISTANCE = 8;
    private static final int PREVIEW_TICKS = 200;

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Half-size of the square around the start point, in blocks.")
        .defaultValue(42)
        .range(8, 200)
        .sliderRange(8, 200)
        .build()
    );

    private final Setting<Integer> maxTorches = sgGeneral.add(new IntSetting.Builder()
        .name("max-torches")
        .description("Stop after placing this many torches (0 = no limit).")
        .defaultValue(0)
        .range(0, 1280)
        .sliderRange(0, 1280)
        .build()
    );

    private final Setting<TorchHighlight.Mode> mode = sgGeneral.add(new EnumSetting.Builder<TorchHighlight.Mode>()
        .name("mode")
        .description("Auto: cave mode when you start underground, otherwise surface mode.")
        .defaultValue(TorchHighlight.Mode.Auto)
        .build()
    );

    private final Setting<Integer> autoCaveDepth = sgGeneral.add(new IntSetting.Builder()
        .name("auto-cave-depth")
        .description("Auto mode: underground when your feet are at least this many blocks below the surface.")
        .defaultValue(6).range(2, 32).sliderRange(2, 16)
        .visible(() -> mode.get() == TorchHighlight.Mode.Auto)
        .build()
    );

    private final Setting<Integer> blocksDown = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-down").description("Surface mode: blocks below the start height.")
        .defaultValue(6).range(0, 64).sliderRange(0, 32)
        .visible(() -> mode.get() != TorchHighlight.Mode.Cave).build());

    private final Setting<Integer> blocksUp = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-up").description("Surface mode: blocks above the start height.")
        .defaultValue(8).range(0, 64).sliderRange(0, 32)
        .visible(() -> mode.get() != TorchHighlight.Mode.Cave).build());

    private final Setting<Integer> caveBlocksDown = sgGeneral.add(new IntSetting.Builder()
        .name("cave-blocks-down").description("Cave mode: blocks below the start height.")
        .defaultValue(24).range(0, 96).sliderRange(0, 64)
        .visible(() -> mode.get() != TorchHighlight.Mode.Surface).build());

    private final Setting<Integer> caveBlocksUp = sgGeneral.add(new IntSetting.Builder()
        .name("cave-blocks-up").description("Cave mode: blocks above the start height.")
        .defaultValue(16).range(0, 96).sliderRange(0, 64)
        .visible(() -> mode.get() != TorchHighlight.Mode.Surface).build());

    private final Setting<Boolean> limitDepth = sgGeneral.add(new BoolSetting.Builder()
        .name("limit-depth").description("Surface mode: ignore spots deeper than max-depth below the surface.")
        .defaultValue(true)
        .visible(() -> mode.get() != TorchHighlight.Mode.Cave).build());

    private final Setting<Integer> maxDepth = sgGeneral.add(new IntSetting.Builder()
        .name("max-depth").description("Maximum depth below the surface, in blocks.")
        .defaultValue(3).range(0, 32).sliderRange(0, 16)
        .visible(() -> mode.get() != TorchHighlight.Mode.Cave && limitDepth.get()).build());

    private final Setting<Boolean> restrictOnSurface = sgGeneral.add(new BoolSetting.Builder()
        .name("restrict-baritone")
        .description("Surface mode: forbid Baritone to break or place blocks (off by default, like torch-pass).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> allowDigInCaves = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-digging-in-caves")
        .description("Cave mode: let Baritone break and place blocks (off by default: it could dig into lava, water or other caves).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Keybind> previewBind = sgGeneral.add(new KeybindSetting.Builder()
        .name("preview-bind")
        .description("Hold to see the border of the area before starting (works while the module is off).")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<SettingColor> radiusColor = sgRender.add(new ColorSetting.Builder()
        .name("radius-color").description("Border of the area.")
        .defaultValue(new SettingColor(80, 180, 255, 230)).build());

    private boolean cave;
    private int startX, startY, startZ;
    /** Cells (region-local index) known to be air-connected to the player. */
    private BitSet connected = new BitSet();
    /** Done tiles that had no connected cell at all (re-opened if connectivity reaches them later). */
    private final Set<Integer> emptyDone = new HashSet<>();

    private boolean previewHeld;
    private int previewTicks;
    private int previewRadius = -1;
    private final Object previewListener = new Object() {
        @EventHandler
        private void onKey(KeyEvent event) {
            if (mc.currentScreen != null || !previewBind.get().matches(true, event.key, event.modifiers)) return;
            if (event.action == KeyAction.Press) previewHeld = true;
            else if (event.action == KeyAction.Release) previewHeld = false;
        }

        @EventHandler
        private void onRender(Render3DEvent event) {
            if (mc.player == null || isActive()) return;
            if (previewTicks > 0) previewTicks--;
            if (!previewHeld && previewTicks <= 0) return;
            int r = previewRadius > 0 ? previewRadius : radius.get();
            BlockPos p = mc.player.getBlockPos();
            boolean c = isCaveAt(p);
            renderBorder(event, regionFor(p, r, c), c);
        }
    };

    public TorchAround() {
        super("torch-around", "Torches around me: walks with Baritone and lights the area around the start point (no selection needed), tile by tile.");
        MeteorClient.EVENT_BUS.subscribe(previewListener);
    }

    // Commands

    /** Start with a radius from the command (kept in the setting). */
    public void startWithRadius(int r) {
        radius.set(Math.max(8, Math.min(200, r)));
        if (!isActive()) toggle();
    }

    /** Show the future border for 10 seconds. */
    public void preview(int r) {
        previewRadius = r > 0 ? Math.max(8, Math.min(200, r)) : -1;
        previewTicks = PREVIEW_TICKS;
        BlockPos p = mc.player.getBlockPos();
        int[] b = regionFor(p, previewRadius > 0 ? previewRadius : radius.get(), isCaveAt(p));
        Msg.info(source(), Msg.t("around.preview", Msg.value((b[3] - b[0] + 1) + "×" + (b[5] - b[2] + 1)),
            Msg.value(b[1] + "…" + b[4]), Msg.t(isCaveAt(p) ? "around.mode.cave" : "around.mode.surface")));
    }

    public void info() {
        if (tiles == null || region == null) {
            Msg.info(source(), Msg.t("around.no_task"));
            return;
        }
        Msg.info(source(), Msg.t("pass.progress", Msg.good(tiles.countIn(TileGrid.State.DONE)), Msg.value(tiles.count()),
            Msg.good(driver.placed())));
    }

    // AbstractPass hooks

    @Override
    protected String source() {
        return "around";
    }

    @Override
    protected String storeKind() {
        return "around";
    }

    @Override
    protected int[] regionAtStart() {
        BlockPos p = mc.player.getBlockPos();
        cave = isCaveAt(p);
        int r = radius.get();

        // Continue the saved task if you start again near the same place with the same radius.
        PassStore.Entry saved = PassStore.entry(storeKind());
        if (saved != null && saved.bounds != null && saved.bounds.length == 6) {
            int[] b = saved.bounds;
            int cx = (b[0] + b[3]) / 2, cz = (b[2] + b[5]) / 2;
            if (b[3] - b[0] == 2 * r && Math.abs(cx - p.getX()) <= RESUME_DISTANCE && Math.abs(cz - p.getZ()) <= RESUME_DISTANCE) {
                startX = cx;
                startZ = cz;
                startY = p.getY();
                return b;
            }
        }
        startX = p.getX();
        startY = p.getY();
        startZ = p.getZ();
        return regionFor(p, r, cave);
    }

    private int[] regionFor(BlockPos p, int r, boolean caveMode) {
        int down = caveMode ? caveBlocksDown.get() : blocksDown.get();
        int up = caveMode ? caveBlocksUp.get() : blocksUp.get();
        int minY = Math.max(mc.world.getBottomY(), p.getY() - down);
        int maxY = Math.min(mc.world.getTopY() - 1, p.getY() + up);
        return new int[]{p.getX() - r, minY, p.getZ() - r, p.getX() + r, maxY, p.getZ() + r};
    }

    private boolean isCaveAt(BlockPos p) {
        return switch (mode.get()) {
            case Surface -> false;
            case Cave -> true;
            case Auto -> p.getY() <= WorldScan.surfaceY(mc.world, p.getX(), p.getZ()) + 1 - autoCaveDepth.get();
        };
    }

    @Override
    protected void regionChanged() {
        connected = new BitSet();
        emptyDone.clear();
    }

    @Override
    protected boolean restrictBaritone() {
        return cave ? !allowDigInCaves.get() : restrictOnSurface.get();
    }

    @Override
    protected int torchLimit() {
        return maxTorches.get();
    }

    @Override
    protected Analysis.AreaRequest request(GridSnapshot g, int[] surfaceY, int[] s) {
        List<Integer> seeds = new ArrayList<>();
        for (int i = 0; i < g.volume(); i++) {
            int bit = regionIndex(g.x(i), g.y(i), g.z(i));
            if (bit >= 0 && connected.get(bit)) seeds.add(i);
        }
        // The player is connected to themselves (feet and the cell above, for partial blocks).
        BlockPos p = mc.player.getBlockPos();
        seeds.add(g.index(p.getX(), p.getY(), p.getZ()));
        seeds.add(g.index(p.getX(), p.getY() + 1, p.getZ()));

        int depth = cave || !limitDepth.get() ? -1 : maxDepth.get();
        return new Analysis.AreaRequest(g, s[0], s[1], s[2], s[3], s[4], s[5],
            Modules.get().get(TorchArea.class).profile(), BlockRules.get().distrustsVariableLight(), cave, null,
            seeds.stream().mapToInt(Integer::intValue).toArray(), surfaceY, depth);
    }

    @Override
    protected void analysed(Analysis.Result r) {
        if (r.connected() == null) return;
        GridSnapshot g = r.grid();
        int[] tb = tiles.bounds(tile);
        boolean any = false;
        Set<Integer> reopened = new HashSet<>();
        for (int i = 0; i < g.volume(); i++) {
            if (!r.connected()[i]) continue;
            int x = g.x(i), y = g.y(i), z = g.z(i);
            int bit = regionIndex(x, y, z);
            if (bit < 0) continue;
            if (x >= tb[0] && x <= tb[2] && z >= tb[1] && z <= tb[3]) any = true;
            if (!connected.get(bit)) {
                connected.set(bit);
                int t = tiles.tileAt(x, z);
                if (t >= 0 && t != tile && emptyDone.contains(t)) reopened.add(t);
            }
        }
        if (!any) emptyDone.add(tile);
        // Connectivity reached a tile that looked empty before (a winding cave): process it again.
        for (int t : reopened) {
            emptyDone.remove(t);
            if (tiles.state(t) == TileGrid.State.DONE) tiles.set(t, TileGrid.State.TODO);
        }
    }

    private int regionIndex(int x, int y, int z) {
        if (region == null || x < region[0] || x > region[3] || y < region[1] || y > region[4] || z < region[2] || z > region[5]) return -1;
        int sx = region[3] - region[0] + 1, sz = region[5] - region[2] + 1;
        return ((y - region[1]) * sz + (z - region[2])) * sx + (x - region[0]);
    }

    @Override
    protected void announceStart(boolean resumed) {
        int[] b = region;
        int limit = maxTorches.get();
        if (resumed) {
            Msg.info(source(), Msg.t("pass.resumed", Msg.good(tiles.countIn(TileGrid.State.DONE)), Msg.value(tiles.count())));
        }
        Msg.info(source(), Msg.t("around.started", Msg.value((b[3] - b[0] + 1) + "×" + (b[5] - b[2] + 1)),
            Msg.value(b[1] + "…" + b[4]), Msg.t(cave ? "around.mode.cave" : "around.mode.surface"),
            limit > 0 ? Msg.value(limit) : Msg.t("around.no_limit"), Msg.value(tiles.count())));
        if (cave && !allowDigInCaves.get()) Msg.info(source(), Msg.t("around.cave_no_dig"));
    }

    @Override
    protected String statusPrefix() {
        return Msg.s(cave ? "highlight.status.cave" : "highlight.status.surface") + " | ";
    }

    @Override
    protected void renderExtra(Render3DEvent event) {
        if (region != null) renderBorder(event, region, cave);
    }

    // Border of the area: along the terrain on the surface, a box in caves, beacons at the corners.

    private int[] borderHeights;
    private int[] borderFor;
    private long borderTime;

    private void renderBorder(Render3DEvent event, int[] b, boolean caveMode) {
        SettingColor col = radiusColor.get();
        // Corner beacons, visible from afar.
        int[][] corners = {{b[0], b[2]}, {b[3] + 1, b[2]}, {b[0], b[5] + 1}, {b[3] + 1, b[5] + 1}};
        for (int[] c : corners) event.renderer.line(c[0], b[1], c[1], c[0], b[4] + 1 + 8, c[1], col);

        if (caveMode) {
            event.renderer.boxLines(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, col, 0);
            return;
        }
        int[] h = heights(b);
        int sx = b[3] - b[0] + 1, sz = b[5] - b[2] + 1;
        // Perimeter walked clockwise: north edge, east edge, south edge, west edge (one segment per block).
        int k = 0;
        for (int i = 0; i < sx; i++) segment(event, b[0] + i, b[2], b[0] + i + 1, b[2], h[k++], col);
        for (int i = 0; i < sz; i++) segment(event, b[3] + 1, b[2] + i, b[3] + 1, b[2] + i + 1, h[k++], col);
        for (int i = 0; i < sx; i++) segment(event, b[3] - i + 1, b[5] + 1, b[3] - i, b[5] + 1, h[k++], col);
        for (int i = 0; i < sz; i++) segment(event, b[0], b[5] - i + 1, b[0], b[5] - i, h[k++], col);
    }

    private void segment(Render3DEvent event, double x1, double z1, double x2, double z2, int y, SettingColor col) {
        event.renderer.line(x1, y + 1.05, z1, x2, y + 1.05, z2, col);
    }

    /** Ground height of every perimeter column (refreshed every 2 seconds). */
    private int[] heights(int[] b) {
        long now = mc.world.getTime();
        if (borderHeights != null && java.util.Arrays.equals(b, borderFor) && now - borderTime < 40) return borderHeights;
        int sx = b[3] - b[0] + 1, sz = b[5] - b[2] + 1;
        int[] h = new int[2 * sx + 2 * sz];
        int k = 0;
        for (int i = 0; i < sx; i++) h[k++] = ground(b[0] + i, b[2], b);
        for (int i = 0; i < sz; i++) h[k++] = ground(b[3], b[2] + i, b);
        for (int i = 0; i < sx; i++) h[k++] = ground(b[3] - i, b[5], b);
        for (int i = 0; i < sz; i++) h[k++] = ground(b[0], b[5] - i, b);
        borderHeights = h;
        borderFor = b.clone();
        borderTime = now;
        return h;
    }

    private int ground(int x, int z, int[] b) {
        if (!mc.world.isChunkLoaded(x >> 4, z >> 4)) return mc.player.getBlockY() - 1;
        return Math.max(b[1] - 1, Math.min(b[4], WorldScan.surfaceY(mc.world, x, z)));
    }
}
