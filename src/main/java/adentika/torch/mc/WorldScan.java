package adentika.torch.mc;

import adentika.torch.core.Dir;
import adentika.torch.core.GridSnapshot;
import adentika.torch.core.blocks.BlockTraits;
import adentika.torch.core.blocks.CategoryResolver;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnRestriction;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

/**
 * Minecraft 1.21.1 adapter: fills a {@link GridSnapshot} from the client world, a few cells at a time so a large area
 * never blocks a client tick. Spawn, torch-support and light values come from vanilla methods, not from block lists.
 * Main thread only; once {@link #step} returns true the snapshot is complete and must not be modified.
 */
public final class WorldScan {
    /** Representative dark-spawning monsters with different hitboxes (TECHNICAL_RESEARCH §8.8). */
    private static final EntityType<?>[] PROBES = {EntityType.ZOMBIE, EntityType.SPIDER, EntityType.ENDERMAN};

    private final ClientWorld world;
    private final CategoryResolver rules;
    private final GridSnapshot grid;
    private final int[] surfaceY;
    private final boolean spawnChecks;
    private final boolean includeNoMonsterBiomes;
    private final BlockPos.Mutable pos = new BlockPos.Mutable();
    private final BlockPos.Mutable other = new BlockPos.Mutable();
    private int next;

    /**
     * @param spawnChecks            false outside the Overworld: nothing is marked spawnable (first release targets the Overworld)
     * @param includeNoMonsterBiomes also mark spots in biomes without natural monster spawns (mobs may walk in)
     */
    public WorldScan(ClientWorld world, CategoryResolver rules, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                     boolean spawnChecks, boolean includeNoMonsterBiomes) {
        this.world = world;
        this.rules = rules;
        this.grid = new GridSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ);
        this.spawnChecks = spawnChecks;
        this.includeNoMonsterBiomes = includeNoMonsterBiomes;
        this.surfaceY = new int[sizeX * sizeZ];
    }

    public GridSnapshot grid() {
        return grid;
    }

    /** World Y of the topmost solid, non-leaf block per column ({@code lz * sizeX + lx}). Valid after completion. */
    public int[] surfaceY() {
        return surfaceY;
    }

    public double progress() {
        return (double) next / grid.volume();
    }

    /** Scans until done or {@code deadlineNanos} passes. Returns true when the snapshot is complete. */
    public boolean step(long deadlineNanos) {
        int volume = grid.volume();
        int columns = surfaceY.length;
        while (next < columns + volume) {
            if (next < columns) scanColumn(next);
            else scanCell(next - columns);
            next++;
            if ((next & 31) == 0 && System.nanoTime() > deadlineNanos) return false;
        }
        return true;
    }

    private void scanColumn(int column) {
        int x = grid.originX() + column % grid.sizeX();
        int z = grid.originZ() + column / grid.sizeX();
        surfaceY[column] = world.isChunkLoaded(x >> 4, z >> 4) ? surfaceY(world, x, z) : Integer.MAX_VALUE / 2;
    }

    /**
     * World Y of the topmost solid, non-leaf block of a column: client heightmap {@code MOTION_BLOCKING} (sent to the
     * client), then down past leaves and blocks without collision (water, grass, flowers).
     */
    public static int surfaceY(ClientWorld world, int x, int z) {
        BlockPos.Mutable p = new BlockPos.Mutable();
        int bottom = world.getBottomY();
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
        while (y > bottom) {
            BlockState s = world.getBlockState(p.set(x, y, z));
            if (s.isIn(BlockTags.LEAVES) || s.getCollisionShape(world, p).isEmpty()) y--;
            else break;
        }
        return y;
    }

    private void scanCell(int i) {
        int x = grid.x(i), y = grid.y(i), z = grid.z(i);
        if (y < world.getBottomY() || y >= world.getTopY() || !world.isChunkLoaded(x >> 4, z >> 4)) {
            grid.set(i, GridSnapshot.UNKNOWN);
            return;
        }
        pos.set(x, y, z);
        BlockState state = world.getBlockState(pos);
        BlockTraits traits = TraitsProvider.of(state);

        grid.setBlockLight(i, world.getLightLevel(LightType.BLOCK, pos));
        grid.setOpacity(i, state.getOpacity(world, pos));
        grid.setEmission(i, state.getLuminance());
        if (state.getLuminance() > 0 && !rules.isStableLight(traits)) grid.set(i, GridSnapshot.VARIABLE_EMITTER);
        if (state.isOpaque() && state.hasSidedTransparency()) grid.setOccludedFaces(i, occludedFaces(state));
        if (state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH) || state.isOf(Blocks.SOUL_TORCH) || state.isOf(Blocks.SOUL_WALL_TORCH)) {
            grid.set(i, GridSnapshot.TORCH_BLOCK);
        }

        VoxelShape collision = state.getCollisionShape(world, pos);
        boolean lava = traits.fluid() == BlockTraits.Fluid.LAVA;
        if (Block.isShapeFullCube(collision) || lava) return;
        grid.set(i, GridSnapshot.PASSABLE);

        if (collision.isEmpty() && traits.fluid() == BlockTraits.Fluid.NONE && isStandable()) grid.set(i, GridSnapshot.STANDABLE);
        if (spawnChecks && isSpawnable()) grid.set(i, GridSnapshot.SPAWNABLE);

        if (state.isReplaceable() && state.getFluidState().isEmpty()) {
            if (canFloorTorch()) grid.set(i, GridSnapshot.FLOOR_TORCH_OK);
            grid.setWallTorchDirs(i, wallTorchDirs());
        }
    }

    private int occludedFaces(BlockState state) {
        int mask = 0;
        for (Direction d : Direction.values()) {
            if (VoxelShapes.unionCoversFullCube(state.getCullingFace(world, pos, d), VoxelShapes.empty())) mask |= 1 << d.getId();
        }
        return mask;
    }

    /** Player could stand here: free cell above, collidable non-hazardous floor below. */
    private boolean isStandable() {
        other.set(pos.getX(), pos.getY() + 1, pos.getZ());
        if (!world.getBlockState(other).getCollisionShape(world, other).isEmpty()) return false;
        other.set(pos.getX(), pos.getY() - 1, pos.getZ());
        BlockState below = world.getBlockState(other);
        if (below.getCollisionShape(world, other).isEmpty()) return false;
        BlockTraits t = TraitsProvider.of(below);
        return t.fluid() == BlockTraits.Fluid.NONE && !rules.hazard(t).standForbidden() && !rules.avoidStepping(t);
    }

    private boolean isSpawnable() {
        boolean any = false;
        for (EntityType<?> type : PROBES) {
            if (SpawnRestriction.isSpawnPosAllowed(type, world, pos)
                && world.isSpaceEmpty(type.getSpawnBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) {
                any = true;
                break;
            }
        }
        if (!any) return false;
        if (includeNoMonsterBiomes) return true;
        // Vanilla Overworld biomes without monster spawns. Spawn lists themselves are server-only: the client receives
        // biomes via Biome.NETWORK_CODEC with empty SpawnSettings, so they cannot be queried here.
        RegistryEntry<Biome> biome = world.getBiome(pos);
        return !biome.matchesKey(BiomeKeys.MUSHROOM_FIELDS) && !biome.matchesKey(BiomeKeys.DEEP_DARK);
    }

    private boolean canFloorTorch() {
        if (!Blocks.TORCH.getDefaultState().canPlaceAt(world, pos)) return false;
        other.set(pos.getX(), pos.getY() - 1, pos.getZ());
        return rules.canSupportTorch(TraitsProvider.of(world.getBlockState(other)), Dir.UP);
    }

    private int wallTorchDirs() {
        int mask = 0;
        for (Direction support : Direction.Type.HORIZONTAL) {
            BlockState torch = Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, support.getOpposite());
            if (!torch.canPlaceAt(world, pos)) continue;
            other.set(pos.getX() + support.getOffsetX(), pos.getY(), pos.getZ() + support.getOffsetZ());
            if (rules.canSupportTorch(TraitsProvider.of(world.getBlockState(other)), support.getOpposite().getId())) {
                mask |= 1 << support.getId();
            }
        }
        return mask;
    }
}
