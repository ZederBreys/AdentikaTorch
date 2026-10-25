package adentika.torch.core;

import adentika.torch.core.SpawnModel.LightMode;
import adentika.torch.core.SpawnModel.Profile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {
    private static Planner.Config coverageOnly() {
        Planner.Config cfg = new Planner.Config();
        cfg.requireReachable = false;
        return cfg;
    }

    private static Planner.Plan plan(Scene s, Planner.Config cfg) {
        int[] danger = SpawnModel.analyze(s.g, Scope.of(s.g), Profile.MONSTERS, LightMode.REAL).dangerCells();
        return Planner.plan(s.g, danger, s.standableEverywhere(), cfg);
    }

    @Test
    void noTorchWhenEverythingIsLit() {
        Scene s = Scene.flat(30, 3, 30);
        for (int x = 0; x < 30; x++) for (int z = 0; z < 30; z++) s.light(x, 1, z, 3);
        assertEquals(0, plan(s, coverageOnly()).torches().size());
    }

    @Test
    void coversWholeDarkFloorEconomically() {
        Scene s = Scene.flat(41, 3, 41);
        Planner.Plan p = plan(s, coverageOnly());
        assertEquals(0, p.uncovered().length);
        // One torch lights a diamond of 365 feet cells; 1681 cells need at least 5. Greedy must stay close.
        assertTrue(p.torches().size() >= 5 && p.torches().size() <= 10, "torches: " + p.torches().size());
    }

    @Test
    void existingLightIsRespected() {
        Scene dark = Scene.flat(41, 3, 21);
        int darkCount = plan(dark, coverageOnly()).torches().size();

        Scene halfLit = Scene.flat(41, 3, 21);
        for (int x = 0; x <= 20; x++) for (int z = 0; z < 21; z++) halfLit.light(x, 1, z, 5);
        Planner.Plan p = plan(halfLit, coverageOnly());

        assertEquals(0, p.uncovered().length);
        assertTrue(p.torches().size() < darkCount, p.torches().size() + " vs " + darkCount);
        for (Planner.Torch t : p.torches()) {
            assertTrue(t.gain() > 0);
            // A torch at x <= 7 could only light x <= 20, which is already lit.
            assertTrue(halfLit.g.x(t.cell()) >= 8, "torch inside lit area at x=" + halfLit.g.x(t.cell()));
        }
    }

    @Test
    void planIsDeterministic() {
        Scene a = Scene.flat(33, 3, 27);
        Scene b = Scene.flat(33, 3, 27);
        assertEquals(plan(a, coverageOnly()).torches(), plan(b, coverageOnly()).torches());
    }

    @Test
    void reachLimitsCandidatesAndReportsUncovered() {
        Scene s = Scene.flat(61, 3, 3);
        boolean[] stand = new boolean[s.g.volume()];
        stand[s.i(1, 1, 1)] = true;
        int[] danger = SpawnModel.analyze(s.g, Scope.of(s.g), Profile.MONSTERS, LightMode.REAL).dangerCells();

        Planner.Config cfg = new Planner.Config();
        Planner.Plan p = Planner.plan(s.g, danger, stand, cfg);

        for (Planner.Torch t : p.torches()) assertTrue(s.g.x(t.cell()) <= 6, "out of reach: x=" + s.g.x(t.cell()));
        assertTrue(p.uncovered().length > 0);
    }

    @Test
    void preferWallUsesWallTorchesAtEqualCoverage() {
        Scene s = Scene.flat(31, 3, 3);
        for (int x = 0; x < 31; x++) s.wall(x, 0).wall(x, 2);
        for (int x = 0; x < 31; x++) s.g.setWallTorchDirs(s.i(x, 1, 1), Dir.bit(Dir.NORTH));

        Planner.Config cfg = coverageOnly();
        cfg.preferWall = true;
        Planner.Plan p = plan(s, cfg);
        assertTrue(p.torches().size() > 0);
        for (Planner.Torch t : p.torches()) assertTrue(t.isWall());
    }
}
