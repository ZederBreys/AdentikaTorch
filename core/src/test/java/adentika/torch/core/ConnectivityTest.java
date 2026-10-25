package adentika.torch.core;

import adentika.torch.core.SpawnModel.LightMode;
import adentika.torch.core.SpawnModel.Profile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectivityTest {
    /** Surface at y = 5 (feet y = 6), closed cave pocket with floor y = 1 (feet y = 2) underneath. */
    private static Scene surfaceWithClosedCave() {
        Scene s = Scene.flat(16, 9, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 1; y <= 5; y++) s.solid(x, y, z);
                s.floorCell(x, 6, z);
            }
        }
        for (int x = 4; x < 12; x++) {
            for (int z = 4; z < 12; z++) {
                s.air(x, 2, z).air(x, 3, z).floorCell(x, 2, z);
            }
        }
        return s;
    }

    @Test
    void closedCaveIsNotReachedFromSurface() {
        Scene s = surfaceWithClosedCave();
        boolean[] reached = Connectivity.flood(s.g, s.i(8, 6, 8));
        assertTrue(reached[s.i(0, 6, 0)]);
        assertFalse(reached[s.i(8, 2, 8)]);
    }

    @Test
    void surfaceModeIgnoresClosedCave() {
        Scene s = surfaceWithClosedCave();
        Scope all = Scope.of(s.g);
        Scope connected = Scope.of(s.g).requireConnected(Connectivity.flood(s.g, s.i(8, 6, 8)));

        int allDanger = SpawnModel.analyze(s.g, all, Profile.MONSTERS, LightMode.REAL).dangerCells().length;
        int surfaceDanger = SpawnModel.analyze(s.g, connected, Profile.MONSTERS, LightMode.REAL).dangerCells().length;
        assertEquals(256 + 64, allDanger);
        assertEquals(256, surfaceDanger);
    }

    @Test
    void openingConnectsCave() {
        Scene s = surfaceWithClosedCave();
        for (int y = 3; y <= 5; y++) s.air(8, y, 8);
        boolean[] reached = Connectivity.flood(s.g, s.i(0, 6, 0));
        assertTrue(reached[s.i(5, 2, 5)]);
    }

    @Test
    void windowAndDepthLimitScope() {
        Scene s = surfaceWithClosedCave();
        for (int y = 3; y <= 5; y++) s.air(8, y, 8);
        int[] surfaceY = new int[16 * 16];
        java.util.Arrays.fill(surfaceY, 5);

        Scope shallow = Scope.of(s.g).maxDepthBelowSurface(surfaceY, 3);
        int danger = SpawnModel.analyze(s.g, shallow, Profile.MONSTERS, LightMode.REAL).dangerCells().length;
        assertEquals(256, danger, "cave floor is 4 below the surface");

        Scope window = Scope.window(8, 6, 8, 3, 1, 1);
        assertEquals(49, SpawnModel.analyze(s.g, window, Profile.MONSTERS, LightMode.REAL).dangerCells().length);
    }
}
