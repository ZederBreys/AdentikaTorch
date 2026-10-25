package adentika.torch.commands;

import adentika.torch.modules.TorchAround;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

/**
 * {@code .torcharound start [radius] [force] | preview [radius] | pause | resume | cancel | info} for the
 * {@code torch-around} module (mode 2b, no selection needed). Radius 8–200.
 */
public class TorchAroundCommand extends Command {
    public TorchAroundCommand() {
        super("torcharound", "Torches around me: start [radius], preview [radius], pause, resume, cancel, info.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("start")
            .executes(ctx -> {
                if (!around().isActive()) around().toggle();
                return SINGLE_SUCCESS;
            })
            .then(literal("force").executes(ctx -> {
                around().forceStart();
                return SINGLE_SUCCESS;
            }))
            .then(argument("radius", IntegerArgumentType.integer(8, 200))
                .executes(ctx -> {
                    around().startWithRadius(IntegerArgumentType.getInteger(ctx, "radius"));
                    return SINGLE_SUCCESS;
                })));
        builder.then(literal("preview")
            .executes(ctx -> {
                around().preview(-1);
                return SINGLE_SUCCESS;
            })
            .then(argument("radius", IntegerArgumentType.integer(8, 200)).executes(ctx -> {
                around().preview(IntegerArgumentType.getInteger(ctx, "radius"));
                return SINGLE_SUCCESS;
            })));
        builder.then(literal("pause").executes(ctx -> {
            around().pause();
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("resume").executes(ctx -> {
            around().resume();
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("cancel").executes(ctx -> {
            around().cancelTask();
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("info").executes(ctx -> {
            around().info();
            return SINGLE_SUCCESS;
        }));
    }

    private static TorchAround around() {
        return Modules.get().get(TorchAround.class);
    }
}
