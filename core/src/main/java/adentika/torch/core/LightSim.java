package adentika.torch.core;

import java.util.Arrays;

/**
 * Block-light propagation with the rules of the 1.21.1 light engine ({@code ChunkBlockLightProvider}):
 * moving into a neighbour costs {@code max(1, opacity(neighbour))}; a step is impossible when the light-occluding face of
 * the source (towards the neighbour) or of the neighbour (towards the source) is full.
 *
 * <p>Known approximation: the game blocks a step when the <i>union</i> of both faces covers the square; we only detect
 * a single full face. Two complementary partial faces (rare) let light through here but not in the game. Unknown cells
 * neither receive nor transmit light (conservative: the planner then places more torches, never fewer).
 */
public final class LightSim {
    private final GridSnapshot grid;

    // Scratch state for bounded single-source fields, reused between calls.
    private final byte[] field;
    private final int[] stamp;
    private final int[] done;
    private int generation;
    private final IntBuckets scratchBuckets = new IntBuckets(LightLevels.MAX + 1);

    public LightSim(GridSnapshot grid) {
        this.grid = grid;
        this.field = new byte[grid.volume()];
        this.stamp = new int[grid.volume()];
        this.done = new int[grid.volume()];
    }

    /**
     * Full propagation from seed levels (index -> level 0..15). Returns the resulting light for every cell.
     * Seeds inside unknown cells are ignored.
     */
    public int[] propagate(int[] seeds) {
        int n = grid.volume();
        int[] light = new int[n];
        // Bucket queue by level: process brighter cells first so each cell is final when expanded.
        IntBuckets buckets = new IntBuckets(LightLevels.MAX + 1);
        for (int i = 0; i < n; i++) {
            int level = seeds[i];
            if (level > 0 && !grid.has(i, GridSnapshot.UNKNOWN)) {
                light[i] = level;
                buckets.add(level, i);
            }
        }

        for (int level = LightLevels.MAX; level > 1; level--) {
            IntBuckets.Bucket bucket = buckets.get(level);
            for (int k = 0; k < bucket.size; k++) {
                int cell = bucket.items[k];
                if (light[cell] != level) continue;
                for (int dir = 0; dir < Dir.COUNT; dir++) {
                    int next = step(cell, dir, level);
                    if (next < 0) continue;
                    int target = grid.neighbor(cell, dir);
                    if (next > light[target]) {
                        light[target] = next;
                        buckets.add(next, target);
                    }
                }
            }
        }
        return light;
    }

    /**
     * Light field of one source of {@code level} at {@code source}. Calls {@code visitor} for every cell whose light from
     * this source is at least {@code minLevel}. Bounded by the source level, so cheap (at most ~3k cells for a torch).
     */
    public void forEachLit(int source, int level, int minLevel, CellVisitor visitor) {
        if (grid.has(source, GridSnapshot.UNKNOWN) || level < minLevel || minLevel < 1) return;
        generation++;
        for (IntBuckets.Bucket b : scratchBuckets.buckets) b.size = 0;

        field[source] = (byte) level;
        stamp[source] = generation;
        scratchBuckets.add(level, source);

        // Brightest first: when a cell is taken at its stored level, that level is final.
        for (int cur = level; cur >= minLevel; cur--) {
            IntBuckets.Bucket bucket = scratchBuckets.get(cur);
            for (int k = 0; k < bucket.size; k++) {
                int cell = bucket.items[k];
                if (field[cell] != cur || done[cell] == generation) continue;
                done[cell] = generation;
                visitor.visit(cell, cur);
                for (int dir = 0; dir < Dir.COUNT; dir++) {
                    int next = step(cell, dir, cur);
                    if (next < minLevel) continue;
                    int target = grid.neighbor(cell, dir);
                    if (stamp[target] != generation || next > field[target]) {
                        stamp[target] = generation;
                        field[target] = (byte) next;
                        scratchBuckets.add(next, target);
                    }
                }
            }
        }
    }

    /** Light level that reaches the neighbour of {@code cell} in {@code dir}, or -1 if it cannot go there. */
    private int step(int cell, int dir, int level) {
        int target = grid.neighbor(cell, dir);
        if (target < 0 || grid.has(target, GridSnapshot.UNKNOWN)) return -1;
        if ((grid.occludedFaces(cell) & Dir.bit(dir)) != 0) return -1;
        if ((grid.occludedFaces(target) & Dir.bit(Dir.opposite(dir))) != 0) return -1;
        int next = level - Math.max(1, grid.opacity(target));
        return next > 0 ? next : -1;
    }

    @FunctionalInterface
    public interface CellVisitor {
        void visit(int cell, int level);
    }

    /** Minimal growable int lists indexed by light level. */
    private static final class IntBuckets {
        private final Bucket[] buckets;

        IntBuckets(int count) {
            buckets = new Bucket[count];
            for (int i = 0; i < count; i++) buckets[i] = new Bucket();
        }

        void add(int level, int item) {
            buckets[level].add(item);
        }

        Bucket get(int level) {
            return buckets[level];
        }

        static final class Bucket {
            int[] items = new int[16];
            int size;

            void add(int item) {
                if (size == items.length) items = Arrays.copyOf(items, size * 2);
                items[size++] = item;
            }
        }
    }
}
