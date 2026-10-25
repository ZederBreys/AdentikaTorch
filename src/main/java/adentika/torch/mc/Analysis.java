package adentika.torch.mc;

import adentika.torch.core.Connectivity;
import adentika.torch.core.GridSnapshot;
import adentika.torch.core.LightLevels;
import adentika.torch.core.Planner;
import adentika.torch.core.Scope;
import adentika.torch.core.SpawnModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure analysis of a finished snapshot (no Minecraft calls, safe on a worker thread): scope, dark spots, plan,
 * per-chunk summary.
 */
public final class Analysis {
    public record Request(
        GridSnapshot grid,
        int[] surfaceY,
        int playerX, int playerY, int playerZ,
        int radius, int blocksDown, int blocksUp,
        boolean connectedOnly,
        int maxDepthBelowSurface,
        boolean caveMode,
        SpawnModel.Profile profile,
        boolean stableLightOnly,
        /** previous recommendations as world x, y, z triples (kept near the player so they do not jump) */
        int[] previousTorches
    ) {
    }

    public record Result(
        GridSnapshot grid,
        int[] dangerCells,
        int unknownInScope,
        List<Planner.Torch> torches,
        int uncovered,
        /** chunk key {@code ChunkPos.toLong} -> dark spots in scope */
        Map<Long, ChunkInfo> chunkDanger,
        long millis,
        /** spawnable cells in the whole snapshot regardless of light (diagnostics) */
        int spawnableCells,
        int unknownCells,
        /** torches already standing inside the scope */
        int existingTorches,
        /** cells air-connected to the seeds (only for requests with connectSeeds), else null */
        boolean[] connected
    ) {
    }

    /**
     * Selected area (PRODUCT_SPEC §2, mode 2a; M3 preview). The selection itself is the scope; torches are planned only
     * at positions reachable from a standable cell of the snapshot, so the estimate matches what can be placed later.
     *
     * @param previousTorches world x, y, z triples of the last plan for this selection (kept while still useful)
     * @param connectSeeds    null: the whole box is the scope (selection). Otherwise only cells air-connected to these
     *                        cells count (torch-around: no closed mine under your feet); standing spots must be connected too
     * @param surfaceY        per-column surface Y of the snapshot (see {@link WorldScan#surfaceY()}), used with maxDepth
     * @param maxDepth        surface mode: ignore spots deeper than this below the surface; -1 = no limit
     */
    public record AreaRequest(
        GridSnapshot grid,
        int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
        SpawnModel.Profile profile,
        boolean stableLightOnly,
        boolean preferWall,
        int[] previousTorches,
        int[] connectSeeds,
        int[] surfaceY,
        int maxDepth
    ) {
        /** Plain box scope (selection), as used by torch-area and torch-pass. */
        public AreaRequest(GridSnapshot grid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                           SpawnModel.Profile profile, boolean stableLightOnly, boolean preferWall, int[] previousTorches) {
            this(grid, minX, minY, minZ, maxX, maxY, maxZ, profile, stableLightOnly, preferWall, previousTorches, null, null, -1);
        }
    }

    public static Result runArea(AreaRequest r) {
        long start = System.nanoTime();
        GridSnapshot g = r.grid();
        Scope scope = new Scope(r.minX(), r.minY(), r.minZ(), r.maxX(), r.maxY(), r.maxZ());
        boolean[] connected = r.connectSeeds() != null ? Connectivity.flood(g, r.connectSeeds()) : null;
        if (connected != null) scope.requireConnected(connected);
        if (r.surfaceY() != null && r.maxDepth() >= 0) scope.maxDepthBelowSurface(r.surfaceY(), r.maxDepth());

        SpawnModel.LightMode mode = r.stableLightOnly() ? SpawnModel.LightMode.STABLE_ONLY : SpawnModel.LightMode.REAL;
        SpawnModel.Result spawn = SpawnModel.analyze(g, scope, r.profile(), mode);

        boolean[] standFrom = new boolean[g.volume()];
        for (int i = 0; i < standFrom.length; i++) standFrom[i] = g.has(i, GridSnapshot.STANDABLE) && (connected == null || connected[i]);

        Planner.Config cfg = new Planner.Config();
        cfg.safeLight = r.profile().safeLight;
        cfg.preferWall = r.preferWall();
        cfg.requireReachable = true;
        cfg.keepCells = toCells(g, r.previousTorches());
        Planner.Plan plan = Planner.plan(g, spawn.dangerCells(), standFrom, cfg);

        return result(g, scope, spawn, plan, start, connected);
    }

    private static int[] toCells(GridSnapshot g, int[] xyz) {
        if (xyz == null) return new int[0];
        int[] cells = new int[xyz.length / 3];
        int n = 0;
        for (int k = 0; k + 2 < xyz.length; k += 3) {
            int cell = g.index(xyz[k], xyz[k + 1], xyz[k + 2]);
            if (cell >= 0) cells[n++] = cell;
        }
        return java.util.Arrays.copyOf(cells, n);
    }

    /** Dark spots of one chunk and their average feet Y (where the chunk outline is drawn). */
    public record ChunkInfo(int count, double averageY) {
    }

    private Analysis() {
    }

    public static Result run(Request r) {
        long start = System.nanoTime();
        GridSnapshot g = r.grid();

        Scope scope = Scope.window(r.playerX(), r.playerY(), r.playerZ(), r.radius(), r.blocksDown(), r.blocksUp());
        if (r.connectedOnly()) scope.requireConnected(Connectivity.flood(g, startCell(g, r)));
        if (!r.caveMode() && r.maxDepthBelowSurface() >= 0) scope.maxDepthBelowSurface(r.surfaceY(), r.maxDepthBelowSurface());

        SpawnModel.LightMode mode = r.stableLightOnly() ? SpawnModel.LightMode.STABLE_ONLY : SpawnModel.LightMode.REAL;
        SpawnModel.Result spawn = SpawnModel.analyze(g, scope, r.profile(), mode);

        Planner.Config cfg = new Planner.Config();
        cfg.safeLight = r.profile().safeLight;
        cfg.preferWall = r.caveMode();
        // Highlight mode recommends positions regardless of reach; the placer checks reach later (M4).
        cfg.requireReachable = false;
        cfg.keepCells = keepCells(g, r);
        Planner.Plan plan = Planner.plan(g, spawn.dangerCells(), null, cfg);
        return result(g, scope, spawn, plan, start, null);
    }

    private static Result result(GridSnapshot g, Scope scope, SpawnModel.Result spawn, Planner.Plan plan, long start, boolean[] connected) {
        Map<Long, long[]> sums = new HashMap<>();
        for (int cell : spawn.dangerCells()) {
            long key = ((long) (g.x(cell) >> 4) & 0xFFFFFFFFL) | (((long) (g.z(cell) >> 4) & 0xFFFFFFFFL) << 32);
            long[] s = sums.computeIfAbsent(key, k -> new long[2]);
            s[0]++;
            s[1] += g.y(cell);
        }
        Map<Long, ChunkInfo> chunks = new HashMap<>();
        sums.forEach((key, s) -> chunks.put(key, new ChunkInfo((int) s[0], (double) s[1] / s[0])));

        int spawnable = 0, unknown = 0, torches = 0;
        for (int i = 0; i < g.volume(); i++) {
            if (g.has(i, GridSnapshot.SPAWNABLE)) spawnable++;
            if (g.has(i, GridSnapshot.UNKNOWN)) unknown++;
            if (g.has(i, GridSnapshot.TORCH_BLOCK) && scope.contains(g, i)) torches++;
        }

        return new Result(g, spawn.dangerCells(), spawn.unknownInScope(), plan.torches(), plan.uncovered().length,
            chunks, (System.nanoTime() - start) / 1_000_000, spawnable, unknown, torches, connected);
    }

    /**
     * Previous recommendations that lie in the interior of the new area (farther than a torch's reach of light, 13,
     * from the horizontal edge). Those around the player stay put; ones near the edge may be re-planned, because the
     * old plan placed them for an edge that has moved.
     */
    private static int[] keepCells(GridSnapshot g, Request r) {
        int[] prev = r.previousTorches();
        if (prev == null) return new int[0];
        int interior = r.radius() - (LightLevels.TORCH - 1);
        int[] cells = new int[prev.length / 3];
        int n = 0;
        for (int k = 0; k + 2 < prev.length; k += 3) {
            int x = prev[k], y = prev[k + 1], z = prev[k + 2];
            if (Math.max(Math.abs(x - r.playerX()), Math.abs(z - r.playerZ())) > interior) continue;
            int cell = g.index(x, y, z);
            if (cell >= 0) cells[n++] = cell;
        }
        return java.util.Arrays.copyOf(cells, n);
    }

    /** Player's feet cell, or the cell above when standing inside a partial block. */
    private static int startCell(GridSnapshot g, Request r) {
        int feet = g.index(r.playerX(), r.playerY(), r.playerZ());
        if (feet >= 0 && g.has(feet, GridSnapshot.PASSABLE)) return feet;
        return g.index(r.playerX(), r.playerY() + 1, r.playerZ());
    }
}
