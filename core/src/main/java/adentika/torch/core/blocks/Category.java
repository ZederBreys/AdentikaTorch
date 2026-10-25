package adentika.torch.core.blocks;

/** Mod/user categories (layers L1-L3). Independent; a block may be in several. See docs/BLOCK_CLASSIFICATION.md §2. */
public enum Category {
    /** C: avoid standing on / entering / being near. */
    HAZARD,
    /** G1: clicking it opens or toggles it; place torches on it only while sneaking. */
    NO_CLICK,
    /** G2: never place torches on or attach to it (user preference). */
    DO_NOT_TOUCH,
    /** G3: support may disappear later (falling blocks, leaves, scaffolding). */
    UNSTABLE_SUPPORT,
    /** G4: harmless now, but stepping on/near triggers effects (sculk, pressure plates, tripwire, turtle eggs, farmland). */
    CONSEQUENCES,
    /** F2: light that may go out (furnaces, lamps, candles, campfires...). */
    VARIABLE_LIGHT
}
