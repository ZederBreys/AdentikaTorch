package adentika.torch.commands;

import adentika.torch.core.Dir;
import adentika.torch.core.blocks.BlockTraits;
import adentika.torch.core.blocks.Category;
import adentika.torch.core.blocks.CategoryResolver;
import adentika.torch.core.blocks.Hazard;
import adentika.torch.mc.BlockRules;
import adentika.torch.mc.Msg;
import adentika.torch.mc.TraitsProvider;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.block.BlockState;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
                    Msg.error("blocks", Msg.t("blocks.info.look"));
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

        Msg.info("blocks", Msg.t("blocks.info.header", state.getBlock().getName().copy().formatted(Formatting.WHITE, Formatting.BOLD)));

        boolean anyWall = false;
        for (int d = Dir.NORTH; d <= Dir.EAST; d++) anyWall |= r.canSupportTorch(t, d);
        Msg.info("blocks", Msg.t("blocks.info.torch", yes(r.canSupportTorch(t, Dir.UP)), yes(anyWall), yes(r.needsSneak(t))));

        if (t.luminance() > 0) {
            Msg.info("blocks", Msg.t(r.isStableLight(t) ? "blocks.info.light" : "blocks.info.light_variable", Msg.value(t.luminance())));
        }

        Hazard h = r.hazard(t);
        if (!h.isNone()) {
            Msg.warning("blocks", Msg.t("blocks.info.hazard", yes(h.enterForbidden()), yes(h.standForbidden()),
                Msg.value(h.pathBuffer()), Msg.value(h.placeBuffer())));
        }
        if (r.avoidStepping(t)) Msg.warning("blocks", Msg.t("blocks.info.consequences"));

        for (Category c : Category.values()) {
            CategoryResolver.Source source = r.source(c, t);
            boolean excluded = r.userRemoved(c).contains(t.id());
            if (source == CategoryResolver.Source.NONE && !excluded) continue;
            Text name = Msg.t("category." + c.name().toLowerCase()).formatted(Formatting.WHITE);
            Text who = excluded ? Msg.t("blocks.info.excluded").formatted(Formatting.RED)
                : Msg.t("blocks.info.source." + source.name().toLowerCase()).formatted(Formatting.AQUA);
            Msg.info("blocks", Msg.t("blocks.info.category", name, who));
        }

        Msg.info("blocks", Msg.t("blocks.info.game", Msg.value(t.id()), yes(t.fullCollision()), yes(t.gravityAffected()),
            yes(t.interactive()), yes(t.fireDamaging())));
    }

    private static Text yes(boolean b) {
        return b ? Msg.good(Msg.s("common.yes")) : Msg.bad(Msg.s("common.no"));
    }
}
