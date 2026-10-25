package adentika.torch.modules;

import adentika.torch.TorchAutomationAddon;
import adentika.torch.core.blocks.Category;
import adentika.torch.core.blocks.CategoryResolver;
import adentika.torch.mc.BlockRules;
import adentika.torch.mc.Msg;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Block classification settings (layer L3, BLOCK_CLASSIFICATION §4). The module does nothing when toggled; it only
 * holds settings. Per category the user can add blocks and exclude blocks on top of the game- and mod-defined members.
 * Excluding never relaxes game mechanics or absolute limits (e.g. lava stays impassable).
 */
public class TorchBlocks extends Module {
    private final SettingGroup sgOptions = settings.getDefaultGroup();

    private final Setting<Boolean> avoidUnstable = sgOptions.add(new BoolSetting.Builder()
        .name("avoid-unstable-support")
        .description("Do not attach torches to leaves, scaffolding or blocks you add to unstable-support.")
        .defaultValue(true)
        .onChanged(v -> BlockRules.invalidate())
        .build()
    );

    private final Setting<Boolean> distrustVariableLight = sgOptions.add(new BoolSetting.Builder()
        .name("distrust-variable-light")
        .description("Light of furnaces, lamps, candles and other switchable sources does not count as protection.")
        .defaultValue(true)
        .onChanged(v -> BlockRules.invalidate())
        .build()
    );

    private final Setting<Boolean> sneakOnInteractive = sgOptions.add(new BoolSetting.Builder()
        .name("sneak-on-interactive")
        .description("Allow placing torches on chests, doors, levers etc. by sneaking (otherwise skip them).")
        .defaultValue(true)
        .onChanged(v -> BlockRules.invalidate())
        .build()
    );

    private final Map<Category, Setting<List<Block>>> added = new EnumMap<>(Category.class);
    private final Map<Category, Setting<List<Block>>> removed = new EnumMap<>(Category.class);

    public TorchBlocks() {
        super(TorchAutomationAddon.CATEGORY, "torch-blocks",
            "Block categories for Torch Automation (settings only, no need to enable). Use .torchblock info to inspect a block.");

        for (Category c : Category.values()) {
            String key = c.name().toLowerCase().replace('_', '-');
            SettingGroup group = settings.createGroup(key);
            added.put(c, group.add(new BlockListSetting.Builder()
                .name(key + "-add")
                .description("Blocks you add to " + key + ".")
                .onChanged(v -> BlockRules.invalidate())
                .build()));
            removed.put(c, group.add(new BlockListSetting.Builder()
                .name(key + "-exclude")
                .description("Blocks you exclude from " + key + " (game mechanics still apply).")
                .onChanged(v -> BlockRules.invalidate())
                .build()));
        }
    }

    public CategoryResolver buildResolver() {
        CategoryResolver.Options o = new CategoryResolver.Options();
        o.avoidUnstableSupport = avoidUnstable.get();
        o.distrustVariableLight = distrustVariableLight.get();
        o.sneakToPlaceOnInteractive = sneakOnInteractive.get();

        CategoryResolver r = CategoryResolver.withModDefaults(o);
        for (Category c : Category.values()) {
            for (Block b : added.get(c).get()) r.userAdd(c, Registries.BLOCK.getId(b).toString());
            for (Block b : removed.get(c).get()) r.userRemove(c, Registries.BLOCK.getId(b).toString());
        }
        return r;
    }

    @Override
    public void onActivate() {
        Msg.info("blocks", Msg.t("blocks.settings_only"));
        toggle();
    }
}
