package adentika.torch.core.blocks;

import adentika.torch.core.Dir;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves block roles from layered rules (docs/BLOCK_CLASSIFICATION.md §4):
 * <ul>
 *   <li>L4 absolute limits and L0 game mechanics — applied in every role method, cannot be changed by anyone;</li>
 *   <li>L1 automatic membership derived from {@link BlockTraits};</li>
 *   <li>L2 mod defaults — curated ids / {@code #tags};</li>
 *   <li>L3 user overrides — per category "added" and "removed" ids.</li>
 * </ul>
 * Membership = (L1 ∨ L2 ∨ user-added) ∧ ¬user-removed. Users can always narrow what the mod does; removing a block from
 * a category only relaxes the mod's soft rules, never L0/L4 (e.g. lava stays impassable).
 */
public final class CategoryResolver {
    /** Hazard kinds with mechanics-based buffers (D-28). */
    public enum HazardKind {
        LAVA(new Hazard(true, true, 1, 2)),
        FIRE(new Hazard(true, true, 1, 1)),
        CONTACT(new Hazard(true, true, 1, 1)),
        STAND_ON(new Hazard(false, true, 0, 0)),
        INSIDE(new Hazard(true, false, 0, 0)),
        CUSTOM(new Hazard(true, true, 0, 0));

        public final Hazard hazard;

        HazardKind(Hazard hazard) {
            this.hazard = hazard;
        }
    }

    public static final class Options {
        /** D-30/D-40: do not use leaves, scaffolding or user-listed blocks as torch support. */
        public boolean avoidUnstableSupport = true;
        /** D-21: light of variable emitters does not count as protection. */
        public boolean distrustVariableLight = true;
        /** D-27: sneaking is allowed to place torches on interactive blocks. */
        public boolean sneakToPlaceOnInteractive = true;
    }

    private final Map<Category, Set<String>> defaults = new EnumMap<>(Category.class);
    private final Map<String, HazardKind> defaultHazardKinds;
    private final Map<Category, Set<String>> userAdded = new EnumMap<>(Category.class);
    private final Map<Category, Set<String>> userRemoved = new EnumMap<>(Category.class);
    private final Options options;

    public CategoryResolver(Map<Category, Set<String>> defaults, Map<String, HazardKind> defaultHazardKinds, Options options) {
        for (Category c : Category.values()) {
            this.defaults.put(c, Set.copyOf(defaults.getOrDefault(c, Set.of())));
            userAdded.put(c, new HashSet<>());
            userRemoved.put(c, new HashSet<>());
        }
        this.defaultHazardKinds = Map.copyOf(defaultHazardKinds);
        this.options = options;
    }

    /** Built-in L2 defaults of the mod. Entries starting with '#' are block tags. */
    public static CategoryResolver withModDefaults(Options options) {
        Map<Category, Set<String>> d = new EnumMap<>(Category.class);
        d.put(Category.HAZARD, Set.of(
            "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:wither_rose",
            "minecraft:powder_snow", "minecraft:pointed_dripstone", "minecraft:big_dripleaf"));
        d.put(Category.UNSTABLE_SUPPORT, Set.of("#minecraft:leaves", "minecraft:scaffolding"));
        d.put(Category.CONSEQUENCES, Set.of(
            "minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor", "minecraft:sculk_shrieker",
            "minecraft:tripwire", "minecraft:turtle_egg", "minecraft:farmland", "#minecraft:pressure_plates"));

        Map<String, HazardKind> kinds = Map.of(
            "minecraft:cactus", HazardKind.CONTACT,
            "minecraft:sweet_berry_bush", HazardKind.INSIDE,
            "minecraft:wither_rose", HazardKind.INSIDE,
            "minecraft:powder_snow", HazardKind.INSIDE,
            "minecraft:pointed_dripstone", HazardKind.STAND_ON,
            "minecraft:big_dripleaf", HazardKind.STAND_ON);
        return new CategoryResolver(d, kinds, options);
    }

    // L3 user overrides

    public void userAdd(Category category, String id) {
        userRemoved.get(category).remove(id);
        userAdded.get(category).add(id);
    }

    public void userRemove(Category category, String id) {
        userAdded.get(category).remove(id);
        userRemoved.get(category).add(id);
    }

    public void resetUser(Category category) {
        userAdded.get(category).clear();
        userRemoved.get(category).clear();
    }

    public Set<String> userAdded(Category category) {
        return Set.copyOf(userAdded.get(category));
    }

    public Set<String> userRemoved(Category category) {
        return Set.copyOf(userRemoved.get(category));
    }

    // Membership

    public boolean isIn(Category category, BlockTraits t) {
        if (userRemoved.get(category).contains(t.id())) return false;
        return source(category, t) != Source.NONE;
    }

    public enum Source { NONE, GAME, MOD_DEFAULT, USER }

    /** Why a block is in a category (for the info command). Ignores user removal. */
    public Source source(Category category, BlockTraits t) {
        if (automatic(category, t)) return Source.GAME;
        if (matches(defaults.get(category), t)) return Source.MOD_DEFAULT;
        if (userAdded.get(category).contains(t.id())) return Source.USER;
        return Source.NONE;
    }

    private static boolean automatic(Category category, BlockTraits t) {
        return switch (category) {
            case HAZARD -> t.fluid() == BlockTraits.Fluid.LAVA || t.fireDamaging();
            case NO_CLICK -> t.interactive();
            case VARIABLE_LIGHT -> t.variableLight();
            // Falling blocks (sand, gravel) are not unstable by default (D-40): a desert must be lightable.
            case UNSTABLE_SUPPORT, DO_NOT_TOUCH, CONSEQUENCES -> false;
        };
    }

    private static boolean matches(Set<String> entries, BlockTraits t) {
        if (entries.contains(t.id())) return true;
        for (String e : entries) {
            if (e.startsWith("#") && t.hasTag(e.substring(1))) return true;
        }
        return false;
    }

    // Roles

    /**
     * Can {@code t} hold a torch attached to its face {@code face} ({@link Dir#UP} for a floor torch, a horizontal
     * direction for a wall torch)? L0: the face must be solid enough; L4: no fluids; then mod/user preferences.
     */
    public boolean canSupportTorch(BlockTraits t, int face) {
        boolean physical = face == Dir.UP ? t.topCenterSolid()
            : Dir.isHorizontal(face) && (t.sideFullSolidMask() & Dir.bit(face)) != 0;
        if (!physical || t.fluid() != BlockTraits.Fluid.NONE) return false;

        if (isIn(Category.DO_NOT_TOUCH, t)) return false;
        if (options.avoidUnstableSupport && isIn(Category.UNSTABLE_SUPPORT, t)) return false;
        return options.sneakToPlaceOnInteractive || !isIn(Category.NO_CLICK, t);
    }

    /** D-21 option: light of variable emitters is not trusted. */
    public boolean distrustsVariableLight() {
        return options.distrustVariableLight;
    }

    /** Placing on this block requires sneaking so the block's own click action is skipped. */
    public boolean needsSneak(BlockTraits t) {
        return isIn(Category.NO_CLICK, t);
    }

    public boolean avoidStepping(BlockTraits t) {
        return isIn(Category.CONSEQUENCES, t);
    }

    /** Does the light of this block count as protection? */
    public boolean isStableLight(BlockTraits t) {
        if (t.luminance() <= 0) return false;
        return !(options.distrustVariableLight && isIn(Category.VARIABLE_LIGHT, t));
    }

    /** Movement/placement limits: L4 absolute part, plus soft buffers only while the block is in {@link Category#HAZARD}. */
    public Hazard hazard(BlockTraits t) {
        Hazard absolute = Hazard.NONE;
        if (t.fluid() == BlockTraits.Fluid.LAVA) absolute = new Hazard(true, true, 0, 0);
        else if (t.fireDamaging() && !t.fullCollision()) absolute = new Hazard(true, false, 0, 0);

        if (!isIn(Category.HAZARD, t)) return absolute;
        return absolute.strictest(kindOf(t).hazard);
    }

    private HazardKind kindOf(BlockTraits t) {
        if (t.fluid() == BlockTraits.Fluid.LAVA) return HazardKind.LAVA;
        if (t.fireDamaging()) return t.fullCollision() ? HazardKind.STAND_ON : HazardKind.FIRE;
        return defaultHazardKinds.getOrDefault(t.id(), HazardKind.CUSTOM);
    }
}
