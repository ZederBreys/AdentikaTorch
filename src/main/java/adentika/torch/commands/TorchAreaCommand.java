package adentika.torch.commands;

import adentika.torch.mc.Msg;
import adentika.torch.modules.TorchArea;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * {@code .torcharea pos1|pos2 [x y z] | clear | info} (coordinates absolute or {@code ~}-relative): selection for the {@code torch-area} module without the keybind.
 * Without coordinates pos1/pos2 use the block you look at, or the block under your feet when you look at nothing.
 */
public class TorchAreaCommand extends Command {
    public TorchAreaCommand() {
        super("torcharea", "Selects the area for torch-area: pos1, pos2, clear, info.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        for (int which = 1; which <= 2; which++) {
            int corner = which;
            builder.then(literal("pos" + corner)
                .executes(ctx -> corner(corner))
                .then(argument("x", CoordinateArgumentType.coordinate())
                    .then(argument("y", CoordinateArgumentType.coordinate())
                        .then(argument("z", CoordinateArgumentType.coordinate())
                            .executes(ctx -> corner(corner, at(ctx)))))));
        }
        builder.then(literal("clear").executes(ctx -> {
            area().clearSelection();
            Msg.info("area", Msg.t("area.cleared"));
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("info").executes(ctx -> {
            TorchArea area = area();
            Text problem = area.validate();
            if (problem != null) Msg.error("area", problem);
            else if (area.result() == null) Msg.info("area", Msg.t("area.no_result"));
            else area.report(area.result());
            return SINGLE_SUCCESS;
        }));
    }

    private int corner(int which) {
        if (mc.player == null) return SINGLE_SUCCESS;
        BlockPos pos = mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            ? hit.getBlockPos() : mc.player.getBlockPos().down();
        return corner(which, pos);
    }

    private int corner(int which, BlockPos pos) {
        if (mc.player == null) return SINGLE_SUCCESS;
        area().setCorner(which, pos);
        return SINGLE_SUCCESS;
    }

    /** Absolute or {@code ~}-relative to the block the player stands in (feet), like vanilla commands. */
    private static BlockPos at(CommandContext<CommandSource> ctx) {
        BlockPos base = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
        return new BlockPos(CoordinateArgumentType.get(ctx, "x").resolve(base.getX()),
            CoordinateArgumentType.get(ctx, "y").resolve(base.getY()), CoordinateArgumentType.get(ctx, "z").resolve(base.getZ()));
    }

    private static TorchArea area() {
        return Modules.get().get(TorchArea.class);
    }
}
