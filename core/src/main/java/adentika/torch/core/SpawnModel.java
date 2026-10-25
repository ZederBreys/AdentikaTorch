package adentika.torch.core;

import java.util.Arrays;

/**
 * Finds dangerous cells: a dark-spawning mob could appear there and the block light is below the profile threshold.
 * Spawn checks themselves (floor, space, hitbox) are done by the adapter with vanilla methods and arrive as
 * {@link GridSnapshot#SPAWNABLE}; this class only combines them with light and scope.
 */
public final class SpawnModel {
    /** Minimum block light that makes a spot safe (TECHNICAL_RESEARCH §8.4, §8.8). */
    public enum Profile {
        MONSTERS(1),
        SWAMP_SLIMES(8),
        PATROLS(9);

        public final int safeLight;

        Profile(int safeLight) {
            this.safeLight = safeLight;
        }
    }

    public enum LightMode {
        /** Use the real block light read from the game. */
        REAL,
        /**
         * Do not trust {@link GridSnapshot#VARIABLE_EMITTER} sources (furnaces, lamps, candles...; D-21): light is
         * re-propagated from stable emitters inside the snapshot plus real light on the snapshot border.
         */
        STABLE_ONLY
    }

    public record Result(int[] dangerCells, int unknownInScope, int[] light) {
    }

    private SpawnModel() {
    }

    public static Result analyze(GridSnapshot g, Scope scope, Profile profile, LightMode mode) {
        int[] light = mode == LightMode.REAL ? realLight(g) : stableLight(g);

        int[] danger = new int[64];
        int count = 0;
        int unknown = 0;
        for (int i = 0; i < g.volume(); i++) {
            if (!scope.contains(g, i)) continue;
            if (g.has(i, GridSnapshot.UNKNOWN)) {
                unknown++;
                continue;
            }
            if (g.has(i, GridSnapshot.SPAWNABLE) && light[i] < profile.safeLight) {
                if (count == danger.length) danger = Arrays.copyOf(danger, count * 2);
                danger[count++] = i;
            }
        }
        return new Result(Arrays.copyOf(danger, count), unknown, light);
    }

    static int[] realLight(GridSnapshot g) {
        int[] light = new int[g.volume()];
        for (int i = 0; i < light.length; i++) light[i] = g.blockLight(i);
        return light;
    }

    static int[] stableLight(GridSnapshot g) {
        int[] seeds = new int[g.volume()];
        for (int i = 0; i < seeds.length; i++) {
            if (g.has(i, GridSnapshot.UNKNOWN)) continue;
            int seed = g.has(i, GridSnapshot.VARIABLE_EMITTER) ? 0 : g.emission(i);
            // Light entering from outside the snapshot is only visible on its border.
            if (g.isOnBorder(i)) seed = Math.max(seed, g.blockLight(i));
            seeds[i] = seed;
        }
        return new LightSim(g).propagate(seeds);
    }
}
