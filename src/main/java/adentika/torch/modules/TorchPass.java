package adentika.torch.modules;

import adentika.torch.core.GridSnapshot;
import adentika.torch.core.TileGrid;
import adentika.torch.mc.Analysis;
import adentika.torch.mc.Msg;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * Mode 2a "Auto pass" (PRODUCT_SPEC §2, M5): walks through the torch-area selection with Baritone and places the
 * planned torches tile by tile ({@link AbstractPass}). The selection box is the scope.
 * Commands: {@code .torcharea start [force] | pause | resume | cancel}.
 */
public class TorchPass extends AbstractPass {
    private final Setting<Integer> maxSize = sgGeneral.add(new IntSetting.Builder()
        .name("max-size")
        .description("Largest selection side for an auto pass, in blocks (processed tile by tile).")
        .defaultValue(512)
        .range(16, 2048)
        .sliderRange(16, 1024)
        .build()
    );

    private final Setting<Boolean> restrictBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("restrict-baritone")
        .description("While the pass runs, forbid Baritone to break or place blocks (off by default: Baritone may break/place to get through); your settings are restored afterwards.")
        .defaultValue(false)
        .build()
    );

    public TorchPass() {
        super("torch-pass", "Auto pass: walks through the torch-area selection with Baritone and places the planned torches, tile by tile.");
    }

    @Override
    protected String source() {
        return "pass";
    }

    @Override
    protected String storeKind() {
        return "pass";
    }

    @Override
    protected int[] regionAtStart() {
        int[] b = Modules.get().get(TorchArea.class).bounds();
        if (b == null) {
            Msg.error(source(), Msg.t("pass.no_selection"));
            return null;
        }
        if (b[3] - b[0] + 1 > maxSize.get() || b[5] - b[2] + 1 > maxSize.get()) {
            Msg.error(source(), Msg.t("pass.too_big", Msg.value((b[3] - b[0] + 1) + "×" + (b[5] - b[2] + 1)), Msg.value(maxSize.get())));
            return null;
        }
        return b;
    }

    @Override
    protected boolean restrictBaritone() {
        return restrictBaritone.get();
    }

    @Override
    protected Analysis.AreaRequest request(GridSnapshot grid, int[] surfaceY, int[] tileScope) {
        return boxRequest(grid, tileScope, false);
    }

    @Override
    protected void announceStart(boolean resumed) {
        int done = tiles.countIn(TileGrid.State.DONE);
        Msg.info(source(), resumed
            ? Msg.t("pass.resumed", Msg.good(done), Msg.value(tiles.count()))
            : Msg.t("pass.started", Msg.value(tiles.count())));
    }
}
