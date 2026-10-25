package adentika.torch.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Bookkeeping for torch clicks, independent of Minecraft: a click is only a request; it counts as placed when the
 * world still shows the torch after the server had time to answer (or revert the client's prediction).
 * Several clicks may wait for confirmation at once (fast mode). Positions are packed longs (any packing works).
 */
public final class PlacementTracker {
    public enum Event { PLACED, REJECTED }

    private record Pending(long pos, long deadline) {
    }

    private final int failuresPerPosition;
    private final int maxConsecutiveFailures;
    private final List<Pending> pending = new ArrayList<>();
    private final Map<Long, Integer> failures = new HashMap<>();
    private final Set<Long> skipped = new HashSet<>();
    private int consecutiveFailures;
    private int placed;

    public PlacementTracker(int failuresPerPosition, int maxConsecutiveFailures) {
        this.failuresPerPosition = failuresPerPosition;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }

    /** A click was sent at {@code now}; it will be judged at {@code now + confirmTicks}. */
    public void clicked(long pos, long now, int confirmTicks) {
        pending.add(new Pending(pos, now + confirmTicks));
    }

    public boolean isPending(long pos) {
        for (Pending p : pending) if (p.pos == pos) return true;
        return false;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** Positions rejected too often; not tried again in this session. */
    public boolean isSkipped(long pos) {
        return skipped.contains(pos);
    }

    /**
     * Judges every click whose deadline has passed. {@code torchAt} reads the real world state.
     * Returns the events in click order.
     */
    public List<Event> poll(long now, LongPredicate torchAt) {
        List<Event> events = new ArrayList<>();
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (now < p.deadline) continue;
            it.remove();
            if (torchAt.test(p.pos)) {
                placed++;
                consecutiveFailures = 0;
                events.add(Event.PLACED);
            } else {
                consecutiveFailures++;
                if (failures.merge(p.pos, 1, Integer::sum) >= failuresPerPosition) skipped.add(p.pos);
                events.add(Event.REJECTED);
            }
        }
        return events;
    }

    /** Too many clicks in a row did not result in a torch: stop instead of continuing silently. */
    public boolean shouldStop() {
        return consecutiveFailures >= maxConsecutiveFailures;
    }

    public int placed() {
        return placed;
    }

    public void clearPending() {
        pending.clear();
    }
}
