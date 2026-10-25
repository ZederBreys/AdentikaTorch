package adentika.torch.core.blocks;

import java.util.Set;

/**
 * Game-derived properties of one block state (layer L0/L1 in docs/BLOCK_CLASSIFICATION.md). Filled by the version
 * adapter from the Minecraft API, so modded blocks work without configuration. No behaviour rules here.
 *
 * @param id                 registry id, e.g. {@code minecraft:stone}
 * @param tags               block tag ids this block is in, e.g. {@code minecraft:leaves}
 * @param luminance          {@code getLuminance()} of this state
 * @param topCenterSolid     top face covers the centre square ({@code isSideSolid(UP, CENTER)}): supports a floor torch
 * @param sideFullSolidMask  bit per {@link adentika.torch.core.Dir}: that face is a full solid square (wall torch support)
 * @param fluid              fluid in the block
 * @param fireDamaging       {@code PathNodeMaker.isFireDamaging}: fire, lava, magma, lit campfire, lava cauldron
 * @param fullCollision      collision shape is a full cube
 * @param gravityAffected    falls when unsupported (sand, gravel, concrete powder...)
 * @param interactive        clicking it does something (opens, toggles) unless sneaking
 * @param variableLight      luminance depends on a changing state (lit, candles, level...)
 */
public record BlockTraits(
    String id,
    Set<String> tags,
    int luminance,
    boolean topCenterSolid,
    int sideFullSolidMask,
    Fluid fluid,
    boolean fireDamaging,
    boolean fullCollision,
    boolean gravityAffected,
    boolean interactive,
    boolean variableLight
) {
    public enum Fluid {
        NONE,
        WATER,
        LAVA
    }

    public BlockTraits {
        tags = Set.copyOf(tags);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }
}
