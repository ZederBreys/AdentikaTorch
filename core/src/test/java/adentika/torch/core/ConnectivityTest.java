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
    void severalStartsAndInvalidStartsAreHandled() {
        Scene s = surfaceWithClosedCave();
        boolean[] reached = Connectivity.flood(s.g, new int[]{-1, s.i(8, 3, 8), s.i(0, 6, 0), s.i(5, 5, 5)});
        assertTrue(reached[s.i(15, 6, 15)], "surface reached from the surface start");
        assertTrue(reached[s.i(4, 2, 4)], "cave reached from the cave start");
        assertFalse(reached[s.i(5, 5, 5)], "a solid start is ignored");
    }

    @Test
    void connectivityCarriesOverToAnOverlappingTile() {
        // Two overlapping snapshots of the same world: the second one only knows the first one's results.
        Scene first = Scene.flatAt(0, 0, 24, 6, 8);
        Scene second = Scene.flatAt(16, 0, 24, 6, 8);
        for (Scene s : new Scene[]{first, second}) {
            for (int x = s.g.originX(); x < s.g.originX() + 24; x++) {
                for (int z = 0; z < 8; z++) {
                    for (int y = 1; y <= 2; y++) s.solid(x, y, z);
                    s.floorCell(x, 3, z);
                }
            }
        }
        // Closed pocket under the surface, only inside the second tile.
        for (int x = 30; x < 34; x++) second.air(x, 1, 3).floorCell(x, 1, 3);

        boolean[] a = Connectivity.flood(first.g, first.i(2, 3, 2));
        java.util.List<Integer> seeds = new java.util.ArrayList<>();
        for (int c = 0; c < a.length; c++) {
            if (!a[c]) continue;
            int cell = second.g.index(first.g.x(c), first.g.y(c), first.g.z(c));
            if (cell >= 0) seeds.add(cell);
        }
        boolean[] b = Connectivity.flood(second.g, seeds.stream().mapToInt(Integer::intValue).toArray());

        assertTrue(b[second.i(39, 3, 7)], "far end of the second tile is connected through the overlap");
        assertFalse(b[second.i(31, 1, 3)], "closed pocket in the second tile stays unconnected");
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
