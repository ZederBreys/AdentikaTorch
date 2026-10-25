package adentika.torch.mc;

import adentika.torch.TorchAutomationAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Saved progress of auto passes (D-41): one entry per world/server and dimension, holding the selection bounds and
 * the tile states. File: {@code <game dir>/meteor-client/torch-automation/passes.json}. Main thread only.
 */
public final class PassStore {
    public static final class Entry {
        public int[] bounds;
        public String tiles;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File(new File(MeteorClient.FOLDER, "torch-automation"), "passes.json");

    private PassStore() {
    }

    /** Where we are: singleplayer world name or server address, plus dimension. */
    public static String worldKey() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String where;
        if (mc.isInSingleplayer() && mc.getServer() != null) where = "sp:" + mc.getServer().getSaveProperties().getLevelName();
        else if (mc.getCurrentServerEntry() != null) where = "mp:" + mc.getCurrentServerEntry().address;
        else where = "unknown";
        return where + "|" + (mc.world != null ? mc.world.getRegistryKey().getValue() : "?");
    }

    /** Saved tile states for exactly this selection in this world, or null. */
    public static String load(int[] bounds) {
        Entry e = read().get(worldKey());
        return e != null && Arrays.equals(e.bounds, bounds) ? e.tiles : null;
    }

    public static void save(int[] bounds, String tiles) {
        Map<String, Entry> all = read();
        Entry e = new Entry();
        e.bounds = bounds;
        e.tiles = tiles;
        all.put(worldKey(), e);
        write(all);
    }

    public static void clear() {
        Map<String, Entry> all = read();
        if (all.remove(worldKey()) != null) write(all);
    }

    private static Map<String, Entry> read() {
        if (!FILE.isFile()) return new HashMap<>();
        try {
            Map<String, Entry> m = GSON.fromJson(Files.readString(FILE.toPath(), StandardCharsets.UTF_8),
                new TypeToken<Map<String, Entry>>() {}.getType());
            return m != null ? m : new HashMap<>();
        } catch (IOException | RuntimeException ex) {
            TorchAutomationAddon.LOG.warn("Torch Automation: cannot read {}", FILE, ex);
            return new HashMap<>();
        }
    }

    private static void write(Map<String, Entry> all) {
        try {
            Files.createDirectories(FILE.getParentFile().toPath());
            Files.writeString(FILE.toPath(), GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            TorchAutomationAddon.LOG.warn("Torch Automation: cannot write {}", FILE, ex);
        }
    }
}
