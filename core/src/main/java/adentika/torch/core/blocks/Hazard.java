package adentika.torch.core.blocks;

/**
 * Movement/placement consequences of a block (BLOCK_CLASSIFICATION §2C, D-28).
 *
 * @param enterForbidden never move into this block's space
 * @param standForbidden never stand on top of this block
 * @param pathBuffer     keep this many blocks away while walking
 * @param placeBuffer    keep this many blocks away from the position used to place a torch
 */
public record Hazard(boolean enterForbidden, boolean standForbidden, int pathBuffer, int placeBuffer) {
    public static final Hazard NONE = new Hazard(false, false, 0, 0);

    public boolean isNone() {
        return equals(NONE);
    }

    /** Combine two rule sources: the stricter value of each field wins. */
    public Hazard strictest(Hazard other) {
        return new Hazard(
            enterForbidden || other.enterForbidden,
            standForbidden || other.standForbidden,
            Math.max(pathBuffer, other.pathBuffer),
            Math.max(placeBuffer, other.placeBuffer)
        );
    }
}
