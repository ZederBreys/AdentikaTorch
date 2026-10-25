package adentika.torch.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TileGridTest {
    @Test
    void tilesAreChunkAlignedAndClippedToSelection() {
        // 228 x 192 like the user's selection, starting mid-chunk and in negative coordinates.
        TileGrid g = new TileGrid(-100, 5, 127, 196);
        assertEquals((7 - (-7) + 1) * (12 - 0 + 1), g.count());
        int first = g.tileAt(-100, 5);
        assertArrayEquals(new int[]{-100, 5, -97, 15}, g.bounds(first));
        int last = g.tileAt(127, 196);
        assertArrayEquals(new int[]{112, 192, 127, 196}, g.bounds(last));
        assertEquals(-1, g.tileAt(128, 100));
    }

    @Test
    void nearestTodoFirstAndProgressCounts() {
        TileGrid g = new TileGrid(0, 0, 63, 63);
        List<Integer> order = g.todoByDistance(40, 40);
        assertEquals(g.tileAt(40, 40), order.get(0));
        assertEquals(16, order.size());

        g.set(order.get(0), TileGrid.State.DONE);
        g.set(order.get(1), TileGrid.State.SKIPPED);
        assertEquals(14, g.todoByDistance(40, 40).size());
        assertEquals(1, g.countIn(TileGrid.State.DONE));
        assertFalse(g.finished());
        for (int t = 0; t < g.count(); t++) if (g.state(t) == TileGrid.State.TODO) g.set(t, TileGrid.State.DONE);
        assertTrue(g.finished());
    }

    /** The user's sketch: 4×4 tiles, rows A, C, B, D from the top; the player has just finished B1. */
    @Test
    void walkOrderGoesToANeighbourNotAcrossTheArea() {
        TileGrid g = new TileGrid(0, 0, 63, 63);
        // Columns 1..4 -> x tile 0..3; rows A, C, B, D -> z tile 0..3.
        int a1 = g.tileAt(0, 0), a3 = g.tileAt(32, 0), c1 = g.tileAt(0, 16), c2 = g.tileAt(16, 16), b1 = g.tileAt(0, 32);
        for (int t : new int[]{a1, a3, c1, c2, b1}) g.set(t, TileGrid.State.DONE);
        double px = 8, pz = 40; // standing in B1
        int next = g.todoInWalkOrder(px, pz).get(0);
        int b2 = g.tileAt(16, 32), d1 = g.tileAt(0, 48), d2 = g.tileAt(16, 48);
        assertTrue(next == b2 || next == d1 || next == d2, "next tile is next to B1, got " + java.util.Arrays.toString(g.bounds(next)));
        assertEquals(d1, next, "D1: corner with three closed sides (two edges + B1), would otherwise be left behind");
    }

    @Test
    void walkOrderOnlyExtendsTheFinishedPatch() {
        TileGrid g = new TileGrid(0, 0, 79, 79);
        assertEquals(g.tileAt(40, 40), g.todoInWalkOrder(40, 40).get(0), "nothing finished: the player's own tile");
        g.set(g.tileAt(40, 40), TileGrid.State.DONE);
        // The player wandered far away (e.g. to place a torch); still continue next to the finished patch.
        int next = g.todoInWalkOrder(75, 75).get(0);
        int[] b = g.bounds(next);
        assertTrue(Math.abs(b[0] - 32) <= 16 && Math.abs(b[1] - 32) <= 16, "neighbour of the finished tile");
        assertEquals(g.tileAt(48, 48), next, "the neighbour nearest to the player");
        assertEquals(24, g.todoInWalkOrder(0, 0).size());
    }

    @Test
    void progressSurvivesSaveAndLoad() {
        TileGrid a = new TileGrid(0, 0, 47, 31);
        a.set(1, TileGrid.State.DONE);
        a.set(4, TileGrid.State.SKIPPED);
        TileGrid b = new TileGrid(0, 0, 47, 31);
        assertTrue(b.decode(a.encode()));
        assertEquals(a.encode(), b.encode());
        assertEquals(TileGrid.State.SKIPPED, b.state(4));

        TileGrid other = new TileGrid(0, 0, 100, 100);
        assertFalse(other.decode(a.encode()), "different layout is not restored");
    }
}
