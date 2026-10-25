package adentika.torch.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A large selection split into chunk-aligned tiles (16×16 columns, full selection height), processed one at a time
 * "like a tiled render" (PRODUCT_SPEC §2, D-41). Tracks each tile's state; serialisable to a short string.
 */
public final class TileGrid {
    public static final int TILE = 16;

    public enum State {
        /** Not analysed yet (or not loaded when tried). */
        TODO,
        /** Analysed: no dark spot left that could be lit from a reachable position. */
        DONE,
        /** Given up (could not get there / repeated failures); reported, never silently ignored. */
        SKIPPED
    }

    private final int minX, minZ, maxX, maxZ;
    private final int tilesX, tilesZ;
    private final int originTileX, originTileZ;
    private final State[] states;

    /** Inclusive world bounds of the selection (Y is handled by the caller). */
    public TileGrid(int minX, int minZ, int maxX, int maxZ) {
        this.minX = Math.min(minX, maxX);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxZ = Math.max(minZ, maxZ);
        originTileX = Math.floorDiv(this.minX, TILE);
        originTileZ = Math.floorDiv(this.minZ, TILE);
        tilesX = Math.floorDiv(this.maxX, TILE) - originTileX + 1;
        tilesZ = Math.floorDiv(this.maxZ, TILE) - originTileZ + 1;
        states = new State[tilesX * tilesZ];
        Arrays.fill(states, State.TODO);
    }

    public int count() {
        return states.length;
    }

    public State state(int tile) {
        return states[tile];
    }

    public void set(int tile, State state) {
        states[tile] = state;
    }

    public int countIn(State state) {
        int n = 0;
        for (State s : states) if (s == state) n++;
        return n;
    }

    public boolean finished() {
        return countIn(State.TODO) == 0;
    }

    /** Inclusive world X/Z bounds of the tile, clipped to the selection: {minX, minZ, maxX, maxZ}. */
    public int[] bounds(int tile) {
        int tx = originTileX + tile % tilesX, tz = originTileZ + tile / tilesX;
        return new int[]{
            Math.max(minX, tx * TILE), Math.max(minZ, tz * TILE),
            Math.min(maxX, tx * TILE + TILE - 1), Math.min(maxZ, tz * TILE + TILE - 1)
        };
    }

    /** Tile containing the world column, or -1 outside the selection. */
    public int tileAt(int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return -1;
        return (Math.floorDiv(z, TILE) - originTileZ) * tilesX + (Math.floorDiv(x, TILE) - originTileX);
    }

    /** TODO tiles ordered by distance of their centre to (x, z); ties by index (deterministic). */
    public List<Integer> todoByDistance(double x, double z) {
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < states.length; i++) if (states[i] == State.TODO) todo.add(i);
        todo.sort((a, b) -> {
            int c = Double.compare(dist2(a, x, z), dist2(b, x, z));
            return c != 0 ? c : Integer.compare(a, b);
        });
        return todo;
    }

    /**
     * TODO tiles in walking order from the player at (x, z): the next tile is the nearest one that borders an
     * already finished tile (DONE/SKIPPED, 8-neighbourhood), so the pass grows as one patch instead of jumping across
     * the area; while nothing is finished every TODO tile is a candidate. A tile gets {@link #CLOSED_SIDE_BONUS} blocks
     * of distance off per side that is finished or the selection edge, so holes and border strips are closed first
     * instead of being left behind as stragglers. Ties by index (deterministic).
     */
    public List<Integer> todoInWalkOrder(double x, double z) {
        List<Integer> todo = new ArrayList<>();
        boolean anyFinished = false;
        for (int i = 0; i < states.length; i++) {
            if (states[i] == State.TODO) todo.add(i);
            else anyFinished = true;
        }
        double[] score = new double[states.length];
        boolean[] frontier = new boolean[states.length];
        for (int t : todo) {
            frontier[t] = !anyFinished || touchesFinished(t);
            score[t] = Math.sqrt(dist2(t, x, z)) - CLOSED_SIDE_BONUS * closedSides(t);
        }
        todo.sort((a, b) -> {
            if (frontier[a] != frontier[b]) return frontier[a] ? -1 : 1;
            int c = Double.compare(score[a], score[b]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        return todo;
    }

    /** Less than a tile step, so a closed side never outweighs walking one tile further. */
    public static final double CLOSED_SIDE_BONUS = 4;

    private boolean touchesFinished(int tile) {
        int tx = tile % tilesX, tz = tile / tilesX;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = tx + dx, nz = tz + dz;
                if ((dx != 0 || dz != 0) && nx >= 0 && nz >= 0 && nx < tilesX && nz < tilesZ
                    && states[nz * tilesX + nx] != State.TODO) return true;
            }
        }
        return false;
    }

    private int closedSides(int tile) {
        int tx = tile % tilesX, tz = tile / tilesX, n = 0;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : sides) {
            int nx = tx + d[0], nz = tz + d[1];
            if (nx < 0 || nz < 0 || nx >= tilesX || nz >= tilesZ || states[nz * tilesX + nx] != State.TODO) n++;
        }
        return n;
    }

    private double dist2(int tile, double x, double z) {
        int[] b = bounds(tile);
        double cx = (b[0] + b[2] + 1) / 2.0 - x, cz = (b[1] + b[3] + 1) / 2.0 - z;
        return cx * cx + cz * cz;
    }

    /** One character per tile: '.' TODO, 'D' DONE, 'S' SKIPPED. */
    public String encode() {
        StringBuilder sb = new StringBuilder(states.length);
        for (State s : states) sb.append(s == State.TODO ? '.' : s == State.DONE ? 'D' : 'S');
        return sb.toString();
    }

    /** Restores states saved with {@link #encode()}; ignored if the layout does not match. */
    public boolean decode(String saved) {
        if (saved == null || saved.length() != states.length) return false;
        for (int i = 0; i < states.length; i++) {
            char c = saved.charAt(i);
            states[i] = c == 'D' ? State.DONE : c == 'S' ? State.SKIPPED : State.TODO;
        }
        return true;
    }
}
