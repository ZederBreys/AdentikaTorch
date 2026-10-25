package adentika.torch.core;

/**
 * Which cells of a snapshot belong to the analysis (PRODUCT_SPEC §3): a world-space box (selection or vertical window
 * around the player / start point), optionally only cells air-connected to the player, optionally only cells not deeper
 * than N blocks below the column surface.
 */
public final class Scope {
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private boolean[] connected;
    private int[] surfaceY;
    private int maxDepthBelowSurface = -1;

    /** Inclusive world-space bounds. */
    public Scope(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
    }

    /** Bounds of the whole snapshot. */
    public static Scope of(GridSnapshot g) {
        return new Scope(g.originX(), g.originY(), g.originZ(),
            g.originX() + g.sizeX() - 1, g.originY() + g.sizeY() - 1, g.originZ() + g.sizeZ() - 1);
    }

    /** Horizontal radius (square) and vertical window around a point; window is fixed at the given Y (start point). */
    public static Scope window(int x, int y, int z, int radius, int blocksDown, int blocksUp) {
        return new Scope(x - radius, y - blocksDown, z - radius, x + radius, y + blocksUp, z + radius);
    }

    /** Restrict to cells marked in {@code reached} (see {@link Connectivity}). */
    public Scope requireConnected(boolean[] reached) {
        this.connected = reached;
        return this;
    }

    /**
     * Restrict to cells whose Y is at most {@code depth} below the surface of their column.
     * {@code surfaceY} is indexed by {@code lz * sizeX + lx} and holds the world Y of the topmost solid block.
     */
    public Scope maxDepthBelowSurface(int[] surfaceY, int depth) {
        this.surfaceY = surfaceY;
        this.maxDepthBelowSurface = depth;
        return this;
    }

    public boolean contains(GridSnapshot g, int index) {
        int x = g.x(index), y = g.y(index), z = g.z(index);
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) return false;
        if (connected != null && !connected[index]) return false;
        if (surfaceY != null) {
            int column = (z - g.originZ()) * g.sizeX() + (x - g.originX());
            // Feet cell directly on the surface block is depth 0.
            int depth = surfaceY[column] + 1 - y;
            if (depth > maxDepthBelowSurface) return false;
        }
        return true;
    }
}
