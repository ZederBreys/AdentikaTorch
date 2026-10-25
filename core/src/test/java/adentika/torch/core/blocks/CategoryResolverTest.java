package adentika.torch.core.blocks;

import adentika.torch.core.Dir;
import adentika.torch.core.blocks.BlockTraits.Fluid;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryResolverTest {
    private static final int ALL_SIDES = 0b111111;

    private static BlockTraits solid(String id, String... tags) {
        return new BlockTraits(id, Set.of(tags), 0, true, ALL_SIDES, Fluid.NONE, false, true, false, false, false);
    }

    private static final BlockTraits STONE = solid("minecraft:stone");
    private static final BlockTraits LAVA = new BlockTraits("minecraft:lava", Set.of(), 15, false, 0, Fluid.LAVA, true, false, false, false, false);
    private static final BlockTraits FIRE = new BlockTraits("minecraft:fire", Set.of("minecraft:fire"), 15, false, 0, Fluid.NONE, true, false, false, false, false);
    private static final BlockTraits MAGMA = new BlockTraits("minecraft:magma_block", Set.of(), 3, true, ALL_SIDES, Fluid.NONE, true, true, false, false, false);
    private static final BlockTraits BOTTOM_SLAB = new BlockTraits("minecraft:stone_slab", Set.of(), 0, false, Dir.bit(Dir.DOWN), Fluid.NONE, false, false, false, false, false);
    private static final BlockTraits OAK_LEAVES = solid("minecraft:oak_leaves", "minecraft:leaves");
    private static final BlockTraits GRAVEL = new BlockTraits("minecraft:gravel", Set.of(), 0, true, ALL_SIDES, Fluid.NONE, false, true, true, false, false);
    private static final BlockTraits CHEST = new BlockTraits("minecraft:chest", Set.of(), 0, false, 0, Fluid.NONE, false, false, false, true, false);
    private static final BlockTraits CRAFTING_TABLE = new BlockTraits("minecraft:crafting_table", Set.of(), 0, true, ALL_SIDES, Fluid.NONE, false, true, false, true, false);
    private static final BlockTraits LIT_FURNACE = new BlockTraits("minecraft:furnace", Set.of(), 13, true, ALL_SIDES, Fluid.NONE, false, true, false, true, true);
    private static final BlockTraits GLOWSTONE = new BlockTraits("minecraft:glowstone", Set.of(), 15, true, ALL_SIDES, Fluid.NONE, false, true, false, false, false);
    private static final BlockTraits MODDED = solid("othermod:strange_block");

    private static CategoryResolver resolver() {
        return CategoryResolver.withModDefaults(new CategoryResolver.Options());
    }

    @Test
    void removingLavaFromHazardsKeepsItImpassable() {
        CategoryResolver r = resolver();
        assertEquals(new Hazard(true, true, 1, 2), r.hazard(LAVA));

        r.userRemove(Category.HAZARD, "minecraft:lava");
        Hazard h = r.hazard(LAVA);
        assertTrue(h.enterForbidden(), "L4: never enter lava");
        assertTrue(h.standForbidden());
        assertEquals(0, h.pathBuffer(), "soft buffer is gone");
        assertEquals(0, h.placeBuffer());
    }

    @Test
    void removingFireFromHazardsStillForbidsEntering() {
        CategoryResolver r = resolver();
        r.userRemove(Category.HAZARD, "minecraft:fire");
        assertTrue(r.hazard(FIRE).enterForbidden());
        assertEquals(0, r.hazard(FIRE).pathBuffer());
    }

    @Test
    void hazardousBlockCanStillSupportTorch() {
        CategoryResolver r = resolver();
        assertTrue(r.hazard(MAGMA).standForbidden());
        assertFalse(r.hazard(MAGMA).enterForbidden());
        assertTrue(r.canSupportTorch(MAGMA, Dir.UP), "place from a safe position, do not stand on it");
    }

    @Test
    void userCannotOverridePhysics() {
        CategoryResolver r = resolver();
        for (Category c : Category.values()) r.userRemove(c, BOTTOM_SLAB.id());
        assertFalse(r.canSupportTorch(BOTTOM_SLAB, Dir.UP), "L0: top face does not cover the centre");
        assertFalse(r.canSupportTorch(BOTTOM_SLAB, Dir.NORTH));
        assertFalse(r.canSupportTorch(LAVA, Dir.UP), "L4: fluids never support torches");
    }

    @Test
    void unstableSupportFollowsDefaultsAndUser() {
        CategoryResolver r = resolver();
        assertFalse(r.canSupportTorch(OAK_LEAVES, Dir.UP), "leaves via #minecraft:leaves");
        assertFalse(r.canSupportTorch(GRAVEL, Dir.UP), "falling block detected from traits");

        r.userRemove(Category.UNSTABLE_SUPPORT, OAK_LEAVES.id());
        assertTrue(r.canSupportTorch(OAK_LEAVES, Dir.UP));

        r.userAdd(Category.UNSTABLE_SUPPORT, STONE.id());
        assertFalse(r.canSupportTorch(STONE, Dir.UP));

        r.resetUser(Category.UNSTABLE_SUPPORT);
        assertTrue(r.canSupportTorch(STONE, Dir.UP));
        assertFalse(r.canSupportTorch(OAK_LEAVES, Dir.UP));
    }

    @Test
    void doNotTouchAlwaysWins() {
        CategoryResolver r = resolver();
        r.userAdd(Category.DO_NOT_TOUCH, STONE.id());
        assertFalse(r.canSupportTorch(STONE, Dir.UP));
        assertFalse(r.canSupportTorch(STONE, Dir.EAST));
    }

    @Test
    void interactiveBlocksNeedSneak() {
        CategoryResolver r = resolver();
        assertTrue(r.needsSneak(CRAFTING_TABLE));
        assertTrue(r.canSupportTorch(CRAFTING_TABLE, Dir.UP));
        assertFalse(r.canSupportTorch(CHEST, Dir.UP), "L0: chest top does not cover the centre square in traits");

        CategoryResolver.Options noSneak = new CategoryResolver.Options();
        noSneak.sneakToPlaceOnInteractive = false;
        assertFalse(CategoryResolver.withModDefaults(noSneak).canSupportTorch(CRAFTING_TABLE, Dir.UP));
    }

    @Test
    void variableLightIsNotTrustedByDefault() {
        CategoryResolver r = resolver();
        assertFalse(r.isStableLight(LIT_FURNACE));
        assertTrue(r.isStableLight(GLOWSTONE));

        CategoryResolver.Options trust = new CategoryResolver.Options();
        trust.distrustVariableLight = false;
        assertTrue(CategoryResolver.withModDefaults(trust).isStableLight(LIT_FURNACE));
    }

    @Test
    void unknownModdedBlockIsNeutralUnlessTraitsSayOtherwise() {
        CategoryResolver r = resolver();
        assertTrue(r.hazard(MODDED).isNone());
        assertTrue(r.canSupportTorch(MODDED, Dir.UP));

        BlockTraits hotModded = new BlockTraits("othermod:hot_plate", Set.of(), 0, true, ALL_SIDES, Fluid.NONE, true, true, false, false, false);
        assertTrue(r.hazard(hotModded).standForbidden(), "fire-damaging trait comes from the game");
    }

    @Test
    void userAddedHazardIsAvoidedAndSourceIsReported() {
        CategoryResolver r = resolver();
        r.userAdd(Category.HAZARD, MODDED.id());
        assertEquals(new Hazard(true, true, 0, 0), r.hazard(MODDED));
        assertEquals(CategoryResolver.Source.USER, r.source(Category.HAZARD, MODDED));
        assertEquals(CategoryResolver.Source.GAME, r.source(Category.HAZARD, LAVA));
        assertEquals(CategoryResolver.Source.MOD_DEFAULT, r.source(Category.UNSTABLE_SUPPORT, OAK_LEAVES));
    }

    @Test
    void consequencesUseTags() {
        CategoryResolver r = resolver();
        assertTrue(r.avoidStepping(solid("minecraft:stone_pressure_plate", "minecraft:pressure_plates")));
        assertTrue(r.avoidStepping(solid("minecraft:sculk_shrieker")));
        assertFalse(r.avoidStepping(STONE));
    }
}
