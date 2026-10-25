package adentika.torch.core;

import adentika.torch.core.SpawnModel.LightMode;
import adentika.torch.core.SpawnModel.Profile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnModelTest {
    @Test
    void darkFloorIsDangerousEverywhere() {
        Scene s = Scene.flat(20, 3, 20);
        var r = SpawnModel.analyze(s.g, Scope.of(s.g), Profile.MONSTERS, LightMode.REAL);
        assertEquals(400, r.dangerCells().length);
    }

    @Test
    void existingLightRemovesDanger() {
        Scene s = Scene.flat(41, 3, 1);
        s.g.setEmission(s.i(0, 2, 0), 15);
        s.computeRealLight();
        var r = SpawnModel.analyze(s.g, Scope.of(s.g), Profile.MONSTERS, LightMode.REAL);
        // Emitter at y=2: feet cells x <= 13 get light >= 1 (15 - 1 - x).
        for (int cell : r.dangerCells()) assertTrue(s.g.x(cell) >= 14, "lit cell reported: x=" + s.g.x(cell));
        assertEquals(41 - 14, r.dangerCells().length);
    }

    @Test
    void nonSpawnableFloorIsNeverDangerous() {
        Scene s = Scene.flat(10, 3, 10);
        // e.g. glass or bottom slabs: the adapter does not set SPAWNABLE.
        for (int x = 0; x < 5; x++) for (int z = 0; z < 10; z++) s.g.clear(s.i(x, 1, z), GridSnapshot.SPAWNABLE);
        var r = SpawnModel.analyze(s.g, Scope.of(s.g), Profile.MONSTERS, LightMode.REAL);
        assertEquals(50, r.dangerCells().length);
        for (int cell : r.dangerCells()) assertTrue(s.g.x(cell) >= 5);
    }

    @Test
    void unknownCellsAreReportedNotSafe() {
        Scene s = Scene.flat(10, 3, 10);
        s.unknown(3, 1, 3).unknown(4, 1, 4);
        var r = SpawnModel.analyze(s.g, Scope.of(s.g), Profile.MONSTERS, LightMode.REAL);
        assertEquals(2, r.unknownInScope());
        assertEquals(98, r.dangerCells().length);
    }

    @Test
    void variableEmitterIgnoredInStableMode() {
        // Large enough that the lamp's light never reaches the snapshot border (border light is kept as incoming light).
        Scene s = Scene.flat(41, 20, 41);
        int lamp = s.i(20, 2, 20);
        s.g.setEmission(lamp, 15);
        s.g.set(lamp, GridSnapshot.VARIABLE_EMITTER);
        s.computeRealLight();

        var real = SpawnModel.analyze(s.g, Scope.of(s.g), Profile.MONSTERS, LightMode.REAL);
        var stable = SpawnModel.analyze(s.g, Scope.of(s.g), Profile.MONSTERS, LightMode.STABLE_ONLY);
        assertTrue(real.dangerCells().length < 41 * 41, "lamp lights part of the floor");
        assertEquals(41 * 41, stable.dangerCells().length, "lamp light is not trusted");

        s.g.clear(lamp, GridSnapshot.VARIABLE_EMITTER);
        var stableTrusted = SpawnModel.analyze(s.g, Scope.of(s.g), Profile.MONSTERS, LightMode.STABLE_ONLY);
        assertEquals(real.dangerCells().length, stableTrusted.dangerCells().length, "stable emitter reproduces real light");
    }

    @Test
    void patrolProfileNeedsBrighterLight() {
        Scene s = Scene.flat(5, 3, 5);
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) s.light(x, 1, z, 8);
        assertEquals(0, SpawnModel.analyze(s.g, Scope.of(s.g), Profile.MONSTERS, LightMode.REAL).dangerCells().length);
        assertEquals(25, SpawnModel.analyze(s.g, Scope.of(s.g), Profile.PATROLS, LightMode.REAL).dangerCells().length);
    }
}
