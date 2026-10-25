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
