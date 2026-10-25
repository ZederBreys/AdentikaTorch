package adentika.torch.commands;

import adentika.torch.core.Dir;
import adentika.torch.core.blocks.BlockTraits;
import adentika.torch.core.blocks.Category;
import adentika.torch.core.blocks.CategoryResolver;
import adentika.torch.core.blocks.Hazard;
import adentika.torch.mc.BlockRules;
import adentika.torch.mc.TraitsProvider;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.block.BlockState;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * {@code .torchblock info [block]}: shows how Torch Automation classifies a block, separating what the game defines
 * from what the mod and the user assign (BLOCK_CLASSIFICATION §4). Without an argument uses the block you look at.
 */
public class TorchBlockCommand extends Command {
    public TorchBlockCommand() {
        super("torchblock", "Shows how Torch Automation classifies a block.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("info")
            .executes(ctx -> {
                if (mc.world == null || !(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
                    error("Look at a block or pass one: .torchblock info <block>");
                    return SINGLE_SUCCESS;
                }
                print(mc.world.getBlockState(hit.getBlockPos()));
                return SINGLE_SUCCESS;
            })
            .then(argument("block", BlockStateArgumentType.blockState(REGISTRY_ACCESS)).executes(ctx -> {
                print(ctx.getArgument("block", BlockStateArgument.class).getBlockState());
                return SINGLE_SUCCESS;
            }))
        );
    }

    private void print(BlockState state) {
        BlockTraits t = TraitsProvider.of(state);
        CategoryResolver r = BlockRules.get();

        info("%s", state);
        info("[game] luminance %d, fluid %s, full collision %s, fire damage %s, falls %s, interactive %s, variable light %s",
            t.luminance(), t.fluid(), yes(t.fullCollision()), yes(t.fireDamaging()), yes(t.gravityAffected()),
            yes(t.interactive()), yes(t.variableLight()));
        info("[game] torch on top: %s, wall torch sides: %s", yes(t.topCenterSolid()), sides(t.sideFullSolidMask()));

        for (Category c : Category.values()) {
            CategoryResolver.Source source = r.source(c, t);
            boolean excluded = r.userRemoved(c).contains(t.id());
            if (source == CategoryResolver.Source.NONE && !excluded) continue;
            String who = switch (source) {
                case GAME -> "game";
                case MOD_DEFAULT -> "mod";
                case USER -> "you";
                case NONE -> "-";
            };
            info("[category] %s: %s%s", c, excluded ? "excluded by you" : "yes", excluded ? "" : " (" + who + ")");
        }

        Hazard h = r.hazard(t);
        boolean anyWall = false;
        for (int d = Dir.NORTH; d <= Dir.EAST; d++) anyWall |= r.canSupportTorch(t, d);
        info("[result] floor torch support: %s, wall torch support: %s, needs sneak: %s, light counts: %s",
            yes(r.canSupportTorch(t, Dir.UP)), yes(anyWall), yes(r.needsSneak(t)), yes(r.isStableLight(t)));
        info("[result] enter forbidden: %s, stand forbidden: %s, path buffer %d, place buffer %d, avoid stepping: %s",
            yes(h.enterForbidden()), yes(h.standForbidden()), h.pathBuffer(), h.placeBuffer(), yes(r.avoidStepping(t)));
    }

    private static String yes(boolean b) {
        return b ? "yes" : "no";
    }

    private static String sides(int mask) {
        StringBuilder sb = new StringBuilder();
        String[] names = {"down", "up", "north", "south", "west", "east"};
        for (int d = Dir.NORTH; d <= Dir.EAST; d++) if ((mask & Dir.bit(d)) != 0) sb.append(sb.isEmpty() ? "" : ",").append(names[d]);
        return sb.isEmpty() ? "none" : sb.toString();
    }
}
