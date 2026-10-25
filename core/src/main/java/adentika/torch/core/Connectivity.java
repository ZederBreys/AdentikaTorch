package adentika.torch.core;

/**
 * Air connectivity (PRODUCT_SPEC §3): flood fill over {@link GridSnapshot#PASSABLE} cells from a start cell.
 * A cave or mine separated from the player by solid blocks is not reached, so it is not analysed in surface mode.
 */
public final class Connectivity {
    private Connectivity() {
    }

    /** Cells reachable from {@code start} through passable, known cells (6-neighbourhood). Start must be passable. */
    public static boolean[] flood(GridSnapshot g, int start) {
        boolean[] reached = new boolean[g.volume()];
        if (start < 0 || !isOpen(g, start)) return reached;

        int[] queue = new int[g.volume()];
        int head = 0, tail = 0;
        reached[start] = true;
        queue[tail++] = start;
        while (head < tail) {
            int cell = queue[head++];
            for (int dir = 0; dir < Dir.COUNT; dir++) {
                int next = g.neighbor(cell, dir);
                if (next >= 0 && !reached[next] && isOpen(g, next)) {
                    reached[next] = true;
                    queue[tail++] = next;
                }
            }
        }
        return reached;
    }

    private static boolean isOpen(GridSnapshot g, int cell) {
        return g.has(cell, GridSnapshot.PASSABLE) && !g.has(cell, GridSnapshot.UNKNOWN);
    }
}
