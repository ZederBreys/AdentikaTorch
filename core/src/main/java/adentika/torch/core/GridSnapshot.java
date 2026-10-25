package adentika.torch.core;

/**
 * Compact, Minecraft-free snapshot of a cuboid of the world. Filled by the version adapter from real game data
 * (vanilla spawn checks, real block light, opacity, light-occluding faces, torch support), consumed by the core.
 *
 * <p>A cell is one block position. Flags describe the <b>position</b> (e.g. "a mob's feet could be here"), not only the
 * block in it. Cells outside the snapshot do not exist for the core.
 */
public final class GridSnapshot {
    /** A dark-spawning monster could stand with its feet in this cell (floor + space + hitbox checks passed). */
    public static final int SPAWNABLE = 1;
    /** Air-like for connectivity: the player/mob could be inside this cell. */
    public static final int PASSABLE = 1 << 1;
    /** The player can stand with feet in this cell (supporting floor below, cell and cell above passable). */
    public static final int STANDABLE = 1 << 2;
    /** Data for this cell is not loaded/known. Never treated as safe or as transmitting light. */
    public static final int UNKNOWN = 1 << 3;
    /** A floor torch can be placed in this cell (replaceable, no fluid, support below allowed by the rules). */
    public static final int FLOOR_TORCH_OK = 1 << 4;
    /** The block in this cell emits light that may change by itself or by players (D-21). */
    public static final int VARIABLE_EMITTER = 1 << 5;

    private final int ox, oy, oz;
    private final int sx, sy, sz;

    private final int[] flags;
    private final byte[] blockLight;
    private final byte[] opacity;
    private final byte[] emission;
    private final byte[] occludedFaces;
    private final byte[] wallTorchDirs;

    public GridSnapshot(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("empty grid");
        this.ox = originX;
        this.oy = originY;
        this.oz = originZ;
        this.sx = sizeX;
        this.sy = sizeY;
        this.sz = sizeZ;

        int n = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        flags = new int[n];
        blockLight = new byte[n];
        opacity = new byte[n];
        emission = new byte[n];
        occludedFaces = new byte[n];
        wallTorchDirs = new byte[n];
    }

    public int originX() { return ox; }
    public int originY() { return oy; }
    public int originZ() { return oz; }
    public int sizeX() { return sx; }
    public int sizeY() { return sy; }
    public int sizeZ() { return sz; }
    public int volume() { return flags.length; }

    // Indexing

    /** Index of a world position, or -1 if it lies outside the snapshot. */
    public int index(int x, int y, int z) {
        int lx = x - ox, ly = y - oy, lz = z - oz;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return -1;
        return (ly * sz + lz) * sx + lx;
    }

    public int x(int index) { return ox + index % sx; }
    public int y(int index) { return oy + index / (sx * sz); }
    public int z(int index) { return oz + (index / sx) % sz; }

    /** Index of the neighbour in {@code dir}, or -1 at the snapshot border. */
    public int neighbor(int index, int dir) {
        int lx = index % sx;
        int lz = (index / sx) % sz;
        int ly = index / (sx * sz);
        int nx = lx + Dir.DX[dir], ny = ly + Dir.DY[dir], nz = lz + Dir.DZ[dir];
        if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) return -1;
        return (ny * sz + nz) * sx + nx;
    }

    public boolean isOnBorder(int index) {
        int lx = index % sx;
        int lz = (index / sx) % sz;
        int ly = index / (sx * sz);
        return lx == 0 || ly == 0 || lz == 0 || lx == sx - 1 || ly == sy - 1 || lz == sz - 1;
    }

    // Accessors

    public boolean has(int index, int flag) { return (flags[index] & flag) != 0; }
    public void set(int index, int flag) { flags[index] |= flag; }
    public void clear(int index, int flag) { flags[index] &= ~flag; }

    /** Real block light from the game (0..15). Meaningless for {@link #UNKNOWN} cells. */
    public int blockLight(int index) { return blockLight[index]; }
    public void setBlockLight(int index, int level) { blockLight[index] = (byte) clampLevel(level); }

    /** Raw light opacity of the block (0..15) as returned by the game; propagation uses {@code max(1, opacity)}. */
    public int opacity(int index) { return opacity[index]; }
    public void setOpacity(int index, int value) { opacity[index] = (byte) clampLevel(value); }

    /** Luminance of the block in this cell (0..15). */
    public int emission(int index) { return emission[index]; }
    public void setEmission(int index, int level) { emission[index] = (byte) clampLevel(level); }

    /** Bit set of {@link Dir}s whose face fully blocks light (only for blocks with sided transparency, e.g. slabs). */
    public int occludedFaces(int index) { return occludedFaces[index]; }
    public void setOccludedFaces(int index, int dirMask) { occludedFaces[index] = (byte) dirMask; }

    /** Bit set of horizontal {@link Dir}s: a wall torch in this cell can attach to the neighbour in that direction. */
    public int wallTorchDirs(int index) { return wallTorchDirs[index]; }
    public void setWallTorchDirs(int index, int dirMask) { wallTorchDirs[index] = (byte) dirMask; }

    private static int clampLevel(int v) {
        return Math.max(0, Math.min(LightLevels.MAX, v));
    }
}
