package adentika.torch.mc;

import adentika.torch.core.blocks.CategoryResolver;
import adentika.torch.modules.TorchBlocks;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** Current {@link CategoryResolver}: mod defaults plus the user overrides from the {@link TorchBlocks} module. */
public final class BlockRules {
    private static CategoryResolver current;
    private static int version;

    private BlockRules() {
    }

    public static CategoryResolver get() {
        if (current == null) {
            TorchBlocks config = Modules.get().get(TorchBlocks.class);
            current = config != null ? config.buildResolver() : CategoryResolver.withModDefaults(new CategoryResolver.Options());
        }
        return current;
    }

    /** Settings changed: rebuild on next use; running scans compare {@link #version()} and restart. */
    public static void invalidate() {
        current = null;
        version++;
    }

    public static int version() {
        return version;
    }
}
