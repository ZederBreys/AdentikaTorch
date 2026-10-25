package adentika.torch.mc;

import adentika.torch.core.blocks.BlockTraits;
import net.minecraft.block.*;
import net.minecraft.entity.ai.pathing.PathNodeMaker;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Minecraft 1.21.1 adapter: {@link BlockState} -> {@link BlockTraits} from the game API (BLOCK_CLASSIFICATION §5).
 * Shapes are evaluated in an empty world at the origin; position-dependent checks are done by {@link WorldScan}.
 * Main thread only.
 */
public final class TraitsProvider {
    private static final Map<BlockState, BlockTraits> CACHE = new IdentityHashMap<>();

    private TraitsProvider() {
    }

    public static BlockTraits of(BlockState state) {
        return CACHE.computeIfAbsent(state, TraitsProvider::compute);
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static BlockTraits compute(BlockState s) {
        BlockView view = EmptyBlockView.INSTANCE;
        BlockPos pos = BlockPos.ORIGIN;
        Block block = s.getBlock();

        Set<String> tags = s.streamTags().map(t -> t.id().toString()).collect(Collectors.toSet());

        int sides = 0;
        for (Direction d : Direction.values()) {
            if (s.isSideSolidFullSquare(view, pos, d)) sides |= 1 << d.getId();
        }

        FluidState fluid = s.getFluidState();
        BlockTraits.Fluid f = fluid.isIn(FluidTags.LAVA) ? BlockTraits.Fluid.LAVA
            : fluid.isIn(FluidTags.WATER) ? BlockTraits.Fluid.WATER : BlockTraits.Fluid.NONE;

        boolean variableLight = block.getStateManager().getStates().stream().mapToInt(BlockState::getLuminance).distinct().count() > 1;

        return new BlockTraits(
            Registries.BLOCK.getId(block).toString(),
            tags,
            s.getLuminance(),
            s.isSideSolid(view, pos, Direction.UP, SideShapeType.CENTER),
            sides,
            f,
            PathNodeMaker.isFireDamaging(s),
            Block.isShapeFullCube(s.getCollisionShape(view, pos)),
            block instanceof FallingBlock,
            isInteractive(block),
            variableLight
        );
    }

    /**
     * Heuristic: right-clicking the block with a torch in hand does something else than placing (opens a screen,
     * toggles, takes or inserts items). Verified behaviour: without sneaking, {@code interactBlockInternal} runs the
     * block's use action first. Unknown modded block entities are assumed interactive (conservative: sneak).
     */
    static boolean isInteractive(Block b) {
        if (b instanceof BlockWithEntity) {
            return !(b instanceof AbstractSkullBlock || b instanceof AbstractBannerBlock || b instanceof SculkSensorBlock
                || b instanceof SculkShriekerBlock || b instanceof SculkCatalystBlock || b instanceof SpawnerBlock
                || b instanceof TrialSpawnerBlock || b instanceof VaultBlock || b instanceof CampfireBlock
                || b instanceof PistonExtensionBlock || b instanceof EndPortalBlock || b instanceof EndGatewayBlock
                || b instanceof ConduitBlock);
        }
        return b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock
            || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CraftingTableBlock
            || b instanceof CartographyTableBlock || b instanceof AnvilBlock || b instanceof GrindstoneBlock
            || b instanceof StonecutterBlock || b instanceof LoomBlock || b instanceof NoteBlock
            || b instanceof AbstractRedstoneGateBlock || b instanceof CakeBlock || b instanceof CandleCakeBlock
            || b instanceof CandleBlock || b instanceof RespawnAnchorBlock || b instanceof ComposterBlock
            || b instanceof AbstractCauldronBlock || b instanceof FlowerPotBlock || b instanceof DragonEggBlock
            || b instanceof RedstoneWireBlock || b instanceof CaveVines || b instanceof SweetBerryBushBlock;
    }
}
