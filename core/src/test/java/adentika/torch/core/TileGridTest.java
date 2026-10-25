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
