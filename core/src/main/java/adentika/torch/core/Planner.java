package adentika.torch.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Torch placement planner: greedy set cover (PRODUCT_SPEC §4). Each candidate torch position covers the dangerous cells
 * its own light would raise to the profile's safe level; already-lit cells are never dangerous, so torches near existing
 * light get no gain and are never chosen. Heuristic: close to minimal, not guaranteed optimal. Deterministic.
 *
 * <p>Line of sight is not checked here (needs world raycasts); the placer verifies it at runtime and re-plans on failure.
 */
public final class Planner {
    public static final class Config {
        /** Light level of the torch to place (14 for a torch, 10 for a soul torch). */
        public int torchLevel = LightLevels.TORCH;
        /** Minimum light that makes a danger cell safe (profile threshold). */
        public int safeLight = SpawnModel.Profile.MONSTERS.safeLight;
        /** Prefer wall torches when coverage is equal (cave mode). */
        public boolean preferWall = false;
        /** Only use positions reachable from a standable, connected cell within {@link #reach}. */
        public boolean requireReachable = true;
        /** Player block interaction range in 1.21.1 (attribute default 4.5), measured from the eyes. */
        public double reach = 4.5;
        /** Do not place a torch that covers fewer danger cells than this. */
        public int minGain = 1;
    }

    /** {@code attachDir}: {@link Dir#DOWN} for a floor torch, otherwise the horizontal direction of the supporting block. */
    public record Torch(int cell, int attachDir, int gain) {
        public boolean isWall() {
            return attachDir != Dir.DOWN;
        }
    }

    public record Plan(List<Torch> torches, int[] uncovered) {
    }

    private static final double EYE_HEIGHT = 1.62;

    private Planner() {
    }

    /**
     * @param standFrom cells the player can stand in and reach (e.g. STANDABLE ∧ connected); ignored when
     *                  {@code requireReachable} is false
     */
    public static Plan plan(GridSnapshot g, int[] dangerCells, boolean[] standFrom, Config cfg) {
        int[] dangerIndexOf = new int[g.volume()];
        Arrays.fill(dangerIndexOf, -1);
        for (int k = 0; k < dangerCells.length; k++) dangerIndexOf[dangerCells[k]] = k;

        LightSim sim = new LightSim(g);
        List<Option> options = new ArrayList<>();
        for (int cell = 0; cell < g.volume(); cell++) {
            int attach = chooseAttach(g, cell, cfg.preferWall);
            if (attach < 0) continue;
            if (cfg.requireReachable && !reachable(g, cell, standFrom, cfg.reach)) continue;

            int[] covered = coverage(sim, cell, cfg, dangerIndexOf);
            if (covered.length >= cfg.minGain) options.add(new Option(cell, attach, covered));
        }

        // Two deterministic heuristics, each followed by redundancy pruning; the plan with fewer torches wins.
        List<Option> greedy = prune(maxGain(options, dangerCells.length, cfg), dangerCells.length);
        List<Option> rarest = prune(rarestFirst(options, dangerCells.length, cfg), dangerCells.length);
        List<Option> chosen = rarest.size() < greedy.size() ? rarest : greedy;

        BitSet done = new BitSet(dangerCells.length);
        List<Torch> torches = new ArrayList<>();
        for (Option o : chosen) {
            torches.add(new Torch(o.cell, o.attach, gain(o, done)));
            for (int k : o.covered) done.set(k);
        }

        int[] uncovered = new int[dangerCells.length - done.cardinality()];
        int u = 0;
        for (int k = 0; k < dangerCells.length; k++) if (!done.get(k)) uncovered[u++] = dangerCells[k];
        return new Plan(torches, uncovered);
    }

    /** Classic lazy greedy: always take the option covering most uncovered cells. */
    private static List<Option> maxGain(List<Option> options, int dangerCount, Config cfg) {
        BitSet done = new BitSet(dangerCount);
        PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> compare(a, b, cfg.preferWall));
        for (Option o : options) queue.add(new Entry(o, o.covered.length));

        List<Option> chosen = new ArrayList<>();
        while (!queue.isEmpty()) {
            Entry top = queue.poll();
            int gain = gain(top.option, done);
            if (gain < cfg.minGain) continue;
            if (gain != top.gain) {
                // Stale estimate, re-insert with the true gain.
                queue.add(new Entry(top.option, gain));
                continue;
            }
            chosen.add(top.option);
            for (int k : top.option.covered) done.set(k);
        }
        return chosen;
    }

    /**
     * Rarest-first: take the uncovered cell with the fewest options able to light it, then its best option.
     * Handles borders and corners better than pure max-gain, which tends to leave thin slivers.
     */
    private static List<Option> rarestFirst(List<Option> options, int dangerCount, Config cfg) {
        int[] freq = new int[dangerCount];
        for (Option o : options) for (int k : o.covered) freq[k]++;
        int[][] coverers = new int[dangerCount][];
        for (int k = 0; k < dangerCount; k++) coverers[k] = new int[freq[k]];
        int[] fill = new int[dangerCount];
        for (int i = 0; i < options.size(); i++) for (int k : options.get(i).covered) coverers[k][fill[k]++] = i;

        Integer[] order = new Integer[dangerCount];
        for (int k = 0; k < dangerCount; k++) order[k] = k;
        java.util.Arrays.sort(order, (a, b) -> freq[a] != freq[b] ? Integer.compare(freq[a], freq[b]) : Integer.compare(a, b));

        BitSet done = new BitSet(dangerCount);
        List<Option> chosen = new ArrayList<>();
        for (int k : order) {
            if (done.get(k) || freq[k] == 0) continue;
            Option best = null;
            int bestGain = 0;
            for (int i : coverers[k]) {
                Option o = options.get(i);
                int gain = gain(o, done);
                if (best == null || gain > bestGain || (gain == bestGain && compare(new Entry(o, gain), new Entry(best, bestGain), cfg.preferWall) < 0)) {
                    best = o;
                    bestGain = gain;
                }
            }
            if (best == null || bestGain < cfg.minGain) continue;
            chosen.add(best);
            for (int c : best.covered) done.set(c);
        }
        return chosen;
    }

    /** Drops torches whose cells are all lit by the other chosen torches (smallest coverage checked first). */
    private static List<Option> prune(List<Option> chosen, int dangerCount) {
        int[] count = new int[dangerCount];
        for (Option o : chosen) for (int k : o.covered) count[k]++;

        List<Option> bySize = new ArrayList<>(chosen);
        bySize.sort((a, b) -> a.covered.length != b.covered.length ? Integer.compare(a.covered.length, b.covered.length) : Integer.compare(a.cell, b.cell));
        java.util.Set<Option> removed = new java.util.HashSet<>();
        for (Option o : bySize) {
            boolean redundant = true;
            for (int k : o.covered) {
                if (count[k] < 2) {
                    redundant = false;
                    break;
                }
            }
            if (redundant) {
                removed.add(o);
                for (int k : o.covered) count[k]--;
            }
        }
        List<Option> kept = new ArrayList<>();
        for (Option o : chosen) if (!removed.contains(o)) kept.add(o);
        return kept;
    }

    private static int chooseAttach(GridSnapshot g, int cell, boolean preferWall) {
        if (g.has(cell, GridSnapshot.UNKNOWN)) return -1;
        boolean floor = g.has(cell, GridSnapshot.FLOOR_TORCH_OK);
        int walls = g.wallTorchDirs(cell);
        int firstWall = -1;
        for (int dir = Dir.NORTH; dir <= Dir.EAST; dir++) {
            if ((walls & Dir.bit(dir)) != 0) {
                firstWall = dir;
                break;
            }
        }
        if (preferWall && firstWall >= 0) return firstWall;
        if (floor) return Dir.DOWN;
        return firstWall;
    }

    private static int[] coverage(LightSim sim, int cell, Config cfg, int[] dangerIndexOf) {
        IntList covered = new IntList();
        sim.forEachLit(cell, cfg.torchLevel, cfg.safeLight, (c, level) -> {
            int k = dangerIndexOf[c];
            if (k >= 0) covered.add(k);
        });
        return covered.toArray();
    }

    private static final class IntList {
        private int[] items = new int[32];
        private int size;

        void add(int v) {
            if (size == items.length) items = Arrays.copyOf(items, size * 2);
            items[size++] = v;
        }

        int[] toArray() {
            return Arrays.copyOf(items, size);
        }
    }

    static boolean reachable(GridSnapshot g, int target, boolean[] standFrom, double reach) {
        if (standFrom == null) return false;
        int r = (int) Math.ceil(reach) + 1;
        double tx = g.x(target) + 0.5, ty = g.y(target) + 0.5, tz = g.z(target) + 0.5;
        double reachSq = reach * reach;
        for (int dy = -r - 1; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int s = g.index(g.x(target) + dx, g.y(target) + dy, g.z(target) + dz);
                    if (s < 0 || !standFrom[s]) continue;
                    double ex = g.x(s) + 0.5 - tx, ey = g.y(s) + EYE_HEIGHT - ty, ez = g.z(s) + 0.5 - tz;
                    if (ex * ex + ey * ey + ez * ez <= reachSq) return true;
                }
            }
        }
        return false;
    }

    private static int gain(Option o, BitSet done) {
        int n = 0;
        for (int k : o.covered) if (!done.get(k)) n++;
        return n;
    }

    private static int compare(Entry a, Entry b, boolean preferWall) {
        if (a.gain != b.gain) return Integer.compare(b.gain, a.gain);
        boolean aw = a.option.attach != Dir.DOWN, bw = b.option.attach != Dir.DOWN;
        if (aw != bw) return (aw == preferWall) ? -1 : 1;
        return Integer.compare(a.option.cell, b.option.cell);
    }

    private record Option(int cell, int attach, int[] covered) {
    }

    private record Entry(Option option, int gain) {
    }
}
