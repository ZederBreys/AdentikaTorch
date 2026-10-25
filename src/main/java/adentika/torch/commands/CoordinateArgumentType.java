package adentika.torch.commands;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Collection;
import java.util.List;

/**
 * One block coordinate for client-side commands: absolute ({@code 169}, {@code -308}) or relative to the player like
 * vanilla commands ({@code ~}, {@code ~5}, {@code ~-3}). Parsed locally by Meteor's dispatcher, never sent to a server.
 */
public class CoordinateArgumentType implements ArgumentType<CoordinateArgumentType.Coordinate> {
    private static final List<String> EXAMPLES = List.of("0", "-308", "~", "~5", "~-3");

    public record Coordinate(boolean relative, int value) {
        public int resolve(int base) {
            return relative ? base + value : value;
        }
    }

    public static CoordinateArgumentType coordinate() {
        return new CoordinateArgumentType();
    }

    public static Coordinate get(CommandContext<?> ctx, String name) {
        return ctx.getArgument(name, Coordinate.class);
    }

    @Override
    public Coordinate parse(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && reader.peek() == '~') {
            reader.skip();
            if (!reader.canRead() || reader.peek() == ' ') return new Coordinate(true, 0);
            return new Coordinate(true, reader.readInt());
        }
        return new Coordinate(false, reader.readInt());
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
