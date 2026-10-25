package adentika.torch.core;

/** Test helper: builds snapshots with a solid floor and open air above, then lets tests carve and decorate. */
final class Scene {
    final GridSnapshot g;

    private Scene(GridSnapshot g) {
        this.g = g;
    }

    /** Solid floor at y = 0, air with spawnable/standable feet cells at y = 1, plain air above. */
    static Scene flat(int sizeX, int sizeY, int sizeZ) {
        return flatAt(0, 0, sizeX, sizeY, sizeZ);
    }

    /** Same as {@link #flat} but the snapshot starts at world (originX, 0, originZ). */
    static Scene flatAt(int originX, int originZ, int sizeX, int sizeY, int sizeZ) {
        Scene s = new Scene(new GridSnapshot(originX, 0, originZ, sizeX, sizeY, sizeZ));
        for (int x = originX; x < originX + sizeX; x++) {
            for (int z = originZ; z < originZ + sizeZ; z++) {
                s.solid(x, 0, z);
                for (int y = 1; y < sizeY; y++) s.air(x, y, z);
                if (sizeY > 1) s.floorCell(x, 1, z);
            }
        }
        return s;
    }

    int i(int x, int y, int z) {
        int idx = g.index(x, y, z);
        if (idx < 0) throw new IllegalArgumentException("outside: " + x + "," + y + "," + z);
        return idx;
    }

    Scene solid(int x, int y, int z) {
        int c = i(x, y, z);
        g.clear(c, GridSnapshot.PASSABLE | GridSnapshot.SPAWNABLE | GridSnapshot.STANDABLE | GridSnapshot.FLOOR_TORCH_OK);
        g.setOpacity(c, 15);
        g.setWallTorchDirs(c, 0);
        return this;
    }

    Scene air(int x, int y, int z) {
        int c = i(x, y, z);
        g.set(c, GridSnapshot.PASSABLE);
        g.setOpacity(c, 0);
        return this;
    }

    /** Feet cell on a solid floor: spawnable, standable, floor torch possible. */
    Scene floorCell(int x, int y, int z) {
        int c = i(x, y, z);
        g.set(c, GridSnapshot.PASSABLE | GridSnapshot.SPAWNABLE | GridSnapshot.STANDABLE | GridSnapshot.FLOOR_TORCH_OK);
        return this;
    }

    /** Solid wall column from y = 1 to the top. */
    Scene wall(int x, int z) {
        for (int y = 1; y < g.sizeY(); y++) solid(x, y, z);
        return this;
    }

    Scene light(int x, int y, int z, int level) {
        g.setBlockLight(i(x, y, z), level);
        return this;
    }

    Scene unknown(int x, int y, int z) {
        g.set(i(x, y, z), GridSnapshot.UNKNOWN);
        return this;
    }

    /** Applies real light as the game would compute it from the current emissions. */
    Scene computeRealLight() {
        int[] seeds = new int[g.volume()];
        for (int c = 0; c < seeds.length; c++) seeds[c] = g.emission(c);
        int[] light = new LightSim(g).propagate(seeds);
        for (int c = 0; c < light.length; c++) g.setBlockLight(c, light[c]);
        return this;
    }

    boolean[] standableEverywhere() {
        boolean[] s = new boolean[g.volume()];
        for (int c = 0; c < s.length; c++) s[c] = g.has(c, GridSnapshot.STANDABLE);
        return s;
    }
}
