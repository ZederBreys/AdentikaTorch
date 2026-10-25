package adentika.torch.mc;

import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Single localisation and chat entry point. Texts live in {@code assets/torch-automation/lang/<locale>.json}
 * ({@code en_us}, {@code ru_ru}); Minecraft picks the file of the game language and falls back to English.
 * Meteor 0.5.8 settings/module names are plain strings (also used as config keys) and stay English.
 */
public final class Msg {
    private static final String PREFIX = "torch-automation.";

    /** Message ids so a repeating status line replaces its previous version instead of flooding the chat. */
    public static final int ID_AREA_PROGRESS = "torch-automation:area-progress".hashCode();
    public static final int ID_HIGHLIGHT = "torch-automation:highlight".hashCode();

    private Msg() {
    }

    public static MutableText t(String key, Object... args) {
        return Text.translatable(PREFIX + key, args);
    }

    /** Plain string, e.g. for the module info string in the HUD. */
    public static String s(String key, Object... args) {
        return t(key, args).getString();
    }

    public static MutableText value(Object v) {
        return Text.literal(String.valueOf(v)).formatted(Formatting.WHITE, Formatting.BOLD);
    }

    public static MutableText good(Object v) {
        return Text.literal(String.valueOf(v)).formatted(Formatting.GREEN, Formatting.BOLD);
    }

    public static MutableText bad(Object v) {
        return Text.literal(String.valueOf(v)).formatted(Formatting.RED, Formatting.BOLD);
    }

    public static MutableText warn(Object v) {
        return Text.literal(String.valueOf(v)).formatted(Formatting.GOLD, Formatting.BOLD);
    }

    /** Approximate number: shown as "≈N" (estimates must not look like guarantees). */
    public static MutableText approx(int n) {
        return Text.literal("≈" + n).formatted(Formatting.YELLOW, Formatting.BOLD);
    }

    // Chat (prefix = localised source name, e.g. "Torch Area")

    public static void info(String sourceKey, Text message) {
        send(0, sourceKey, message.copy().formatted(Formatting.GRAY));
    }

    public static void ok(String sourceKey, Text message) {
        send(0, sourceKey, message.copy().formatted(Formatting.GREEN));
    }

    public static void warning(String sourceKey, Text message) {
        send(0, sourceKey, message.copy().formatted(Formatting.YELLOW));
    }

    public static void error(String sourceKey, Text message) {
        send(0, sourceKey, message.copy().formatted(Formatting.RED));
    }

    /** Replaces the previous message with the same id (when Meteor's "delete chat feedback" is on). */
    public static void status(int id, String sourceKey, Text message) {
        send(id, sourceKey, message.copy().formatted(Formatting.GRAY));
    }

    private static void send(int id, String sourceKey, Text message) {
        ChatUtils.sendMsg(id, s("source." + sourceKey), Formatting.GOLD, message);
    }
}
