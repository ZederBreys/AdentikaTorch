package adentika.torch.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightSimTest {
    private static int[] single(GridSnapshot g, int cell, int level) {
        int[] seeds = new int[g.volume()];
        seeds[cell] = level;
        return new LightSim(g).propagate(seeds);
    }

    @Test
    void torchMakesTaxicabDiamondOnFlatFloor() {
        Scene s = Scene.flat(31, 3, 31);
        int[] light = single(s.g, s.i(15, 1, 15), LightLevels.TORCH);

        for (int d = 0; d <= 13; d++) assertEquals(14 - d, light[s.i(15 + d, 1, 15)], "distance " + d);
        assertEquals(0, light[s.i(29, 1, 15)], "distance 14 is dark");
        assertEquals(4, light[s.i(20, 1, 20)], "diagonal 5+5");
        assertEquals(0, light[s.i(15, 0, 15)], "opaque floor receives nothing");
    }

    @Test
    void fullWallBlocksLight() {
        Scene s = Scene.flat(31, 3, 31);
        for (int z = 0; z < 31; z++) s.wall(16, z);
        int[] light = single(s.g, s.i(15, 1, 15), LightLevels.TORCH);
        assertEquals(0, light[s.i(17, 1, 15)]);
    }

    @Test
    void lightBendsAroundWallEnd() {
        Scene s = Scene.flat(31, 3, 31);
        for (int z = 0; z <= 20; z++) s.wall(16, z);
        int[] light = single(s.g, s.i(15, 1, 15), LightLevels.TORCH);
        // Path: 6 along z to z=21, 2 across, 2 back: 10 steps.
        assertEquals(4, light[s.i(17, 1, 19)]);
    }

    @Test
    void occludedFaceBlocksOnlyThatSide() {
        Scene s = Scene.flat(31, 3, 31);
        int slab = s.i(16, 1, 15);
        s.g.setOccludedFaces(slab, Dir.bit(Dir.WEST));
        int[] light = single(s.g, s.i(15, 1, 15), LightLevels.TORCH);
        // Not 13 from the west; 11 via the north neighbour (12) instead.
        assertEquals(11, light[slab]);
    }

    @Test
    void opacityAboveOneCostsMore() {
        Scene s = Scene.flat(31, 3, 31);
        s.g.setOpacity(s.i(16, 1, 15), 3);
        int[] light = single(s.g, s.i(15, 1, 15), LightLevels.TORCH);
        assertEquals(11, light[s.i(16, 1, 15)]);
    }

    @Test
    void unknownCellsNeitherReceiveNorTransmit() {
        Scene s = Scene.flat(31, 3, 31);
        for (int z = 0; z < 31; z++) for (int y = 1; y < 3; y++) s.unknown(16, y, z);
        int[] light = single(s.g, s.i(15, 1, 15), LightLevels.TORCH);
        assertEquals(0, light[s.i(16, 1, 15)]);
        assertEquals(0, light[s.i(17, 1, 15)]);
    }

    @Test
    void singleSourceFieldMatchesFullPropagation() {
        Random random = new Random(42);
        for (int round = 0; round < 20; round++) {
            Scene s = Scene.flat(24, 6, 24);
            for (int k = 0; k < 120; k++) {
                int c = s.i(random.nextInt(24), 1 + random.nextInt(5), random.nextInt(24));
                switch (random.nextInt(3)) {
                    case 0 -> s.g.setOpacity(c, 15);
                    case 1 -> s.g.setOpacity(c, 1 + random.nextInt(4));
                    default -> s.g.setOccludedFaces(c, random.nextInt(64));
                }
            }
            int source = s.i(12, 2, 12);
            s.g.setOpacity(source, 0);
            s.g.setOccludedFaces(source, 0);

            int[] full = single(s.g, source, LightLevels.TORCH);
            int[] field = new int[s.g.volume()];
            new LightSim(s.g).forEachLit(source, LightLevels.TORCH, 1, (cell, level) -> field[cell] = level);
            for (int c = 0; c < full.length; c++) assertEquals(full[c], field[c], "round " + round + " cell " + c);
        }
    }
}
