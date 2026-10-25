package adentika.torch.core;

/**
 * Light constants of Minecraft Java 1.21.1 used by the planner.
 * Values verified against decompiled 1.21.1 sources (see docs/TECHNICAL_RESEARCH.md §8.8, docs/BLOCK_CLASSIFICATION.md §1).
 */
public final class LightLevels {
    public static final int MAX = 15;
    public static final int TORCH = 14;
    public static final int SOUL_TORCH = 10;

    /** Overworld {@code monster_spawn_block_light_limit}: block light above this blocks dark-spawning monsters. */
    public static final int OVERWORLD_MONSTER_BLOCK_LIGHT_LIMIT = 0;

    private LightLevels() {
    }

    /** A spot is safe from dark-spawning Overworld monsters when its block light exceeds the dimension limit. */
    public static boolean isSafeForOverworldMonsters(int blockLight) {
        return blockLight > OVERWORLD_MONSTER_BLOCK_LIGHT_LIMIT;
    }
}
