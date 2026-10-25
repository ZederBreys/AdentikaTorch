package adentika.torch.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementTrackerTest {
    private final Set<Long> world = new HashSet<>();

    @Test
    void clickIsNotSuccessBeforeTheDeadline() {
        PlacementTracker t = new PlacementTracker(2, 3);
        t.clicked(1, 0, 6);
        world.add(1L); // client prediction shows the torch immediately
        assertTrue(t.poll(5, world::contains).isEmpty(), "too early to judge");
        assertEquals(List.of(PlacementTracker.Event.PLACED), t.poll(6, world::contains));
        assertEquals(1, t.placed());
    }

    @Test
    void severalTorchesInARowAreConfirmedIndependently() {
        PlacementTracker t = new PlacementTracker(2, 3);
        for (long pos = 1; pos <= 5; pos++) t.clicked(pos, pos * 2, 6); // a click every 2 ticks
        assertEquals(5, t.pendingCount());
        world.addAll(List.of(1L, 2L, 3L, 5L)); // the server reverted torch 4

        List<PlacementTracker.Event> events = t.poll(20, world::contains);
        assertEquals(List.of(PlacementTracker.Event.PLACED, PlacementTracker.Event.PLACED, PlacementTracker.Event.PLACED,
            PlacementTracker.Event.REJECTED, PlacementTracker.Event.PLACED), events);
        assertEquals(4, t.placed());
        assertFalse(t.shouldStop());
    }

    @Test
    void highLatencyWaitsLonger() {
        PlacementTracker t = new PlacementTracker(2, 3);
        t.clicked(7, 100, 12); // ping 400 ms -> 400/50 + 4 = 12 ticks
        world.add(7L);
        assertTrue(t.poll(111, world::contains).isEmpty());
        assertEquals(1, t.poll(112, world::contains).size());
    }

    @Test
    void positionRejectedTwiceIsSkipped() {
        PlacementTracker t = new PlacementTracker(2, 10);
        t.clicked(3, 0, 1);
        t.poll(1, world::contains);
        assertFalse(t.isSkipped(3));
        t.clicked(3, 2, 1);
        t.poll(3, world::contains);
        assertTrue(t.isSkipped(3));
    }

    @Test
    void repeatedRejectionsStopAndSuccessResetsTheCount() {
        PlacementTracker t = new PlacementTracker(2, 3);
        t.clicked(1, 0, 1);
        t.clicked(2, 0, 1);
        t.poll(1, world::contains);
        assertFalse(t.shouldStop());

        world.add(4L);
        t.clicked(4, 2, 1);
        t.poll(3, world::contains);
        assertFalse(t.shouldStop(), "a success resets the streak");

        t.clicked(5, 4, 1);
        t.clicked(6, 4, 1);
        t.clicked(8, 4, 1);
        t.poll(5, world::contains);
        assertTrue(t.shouldStop());
    }

    @Test
    void pendingPositionIsKnown() {
        PlacementTracker t = new PlacementTracker(2, 3);
        t.clicked(9, 0, 6);
        assertTrue(t.isPending(9));
        t.clearPending();
        assertFalse(t.isPending(9));
    }
}
