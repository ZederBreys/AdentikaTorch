package adentika.torch.modules;

import adentika.torch.TorchAutomationAddon;
import adentika.torch.mc.Msg;
import adentika.torch.mc.PlacementDriver;
import adentika.torch.mc.TorchPlacer;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Mode 1 "Escort" (PRODUCT_SPEC §2): you walk, the mod places planned torches that are within reach.
 * Uses the plan of torch-highlight (enables it if needed), so you see dark spots and markers while walking.
 * Never moves the player; only a short visible head turn and a normal click. Toggle the module to stop instantly.
 */
public class TorchEscort extends Module {
    private static boolean serverWarningShown;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<PlacementDriver.Mode> placementMode = sgGeneral.add(new EnumSetting.Builder<PlacementDriver.Mode>()
        .name("placement-mode")
        .description("Aggressive: turn and click in the same tick, several torches in a row. Legit: wait for the server's answer and a fresh scan before the next torch (more compatible).")
        .defaultValue(PlacementDriver.Mode.Aggressive)
        .build()
    );

    private final Setting<Integer> fastDelay = sgGeneral.add(new IntSetting.Builder()
        .name("fast-delay-ticks")
        .description("Aggressive mode: ticks between two clicks (1 = every tick, 4 = like holding right click).")
        .defaultValue(2)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(() -> placementMode.get() == PlacementDriver.Mode.Aggressive)
        .build()
    );

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Legit mode: pause between two torches, in ticks (20 ticks = 1 second).")
        .defaultValue(4)
        .range(2, 40)
        .sliderRange(2, 20)
        .visible(() -> placementMode.get() == PlacementDriver.Mode.Legit)
        .build()
    );

    private final Setting<Integer> maxFailures = sgGeneral.add(new IntSetting.Builder()
        .name("max-failures")
        .description("Stop after this many torches in a row did not appear (rejected by the server or blocked).")
        .defaultValue(3)
        .range(1, 10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> restoreView = sgGeneral.add(new BoolSetting.Builder()
        .name("restore-view")
        .description("Turn the camera back after each torch.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> targetColor = sgGeneral.add(new ColorSetting.Builder()
        .name("target-color")
        .description("Colour of the torch being placed right now.")
        .defaultValue(new SettingColor(80, 255, 80, 200))
        .build()
    );

    private final PlacementDriver driver = new PlacementDriver();
    private boolean enabledHighlight;
    /** True only for a toggle by the player inside a world; Meteor re-activates saved modules on join without it. */
    private boolean userStarted;
    private boolean silent;

    @Override
    public void toggle() {
        userStarted = !isActive() && Utils.canUpdate();
        super.toggle();
        userStarted = false;
    }

    public TorchEscort() {
        super(TorchAutomationAddon.CATEGORY, "torch-escort",
            "You walk, the mod places planned torches within reach. Uses torch-highlight's plan. Toggle off to stop.");
    }

    private TorchHighlight highlight() {
        return Modules.get().get(TorchHighlight.class);
    }

    @Override
    public void onActivate() {
        if (!userStarted) {
            // Restored from the saved config on world join: automation never starts by itself.
            silent = true;
            super.toggle();
            silent = false;
            return;
        }
        configureDriver();
        driver.reset();
        if (mc.world == null || mc.world.getRegistryKey() != World.OVERWORLD) {
            Msg.error("escort", Msg.t("common.overworld_only"));
            toggle();
            return;
        }
        if (!serverWarningShown) {
            Msg.warning("escort", Msg.t("common.server_warning"));
            serverWarningShown = true;
        }
        enabledHighlight = !highlight().isActive();
        if (enabledHighlight) highlight().toggle();
        Msg.info("escort", Msg.t("escort.started"));
        if (!TorchPlacer.hasTorch()) Msg.warning("escort", Msg.t("common.no_torches_hotbar"));
    }

    @Override
    public void onDeactivate() {
        driver.cancel();
        if (enabledHighlight && highlight().isActive()) highlight().toggle();
        enabledHighlight = false;
        if (!silent && mc.world != null) Msg.info("escort", Msg.t("escort.stopped", Msg.good(driver.placed())));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    private void configureDriver() {
        driver.configure(placementMode.get(), delayTicks.get(), fastDelay.get(), maxFailures.get(), restoreView.get());
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        handle(driver.tickPost(highlight()));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.world.getRegistryKey() != World.OVERWORLD) {
            stop("common.stop.world");
            return;
        }
        if (!highlight().isActive()) {
            stop("escort.stop.highlight_off");
            return;
        }
        configureDriver();
        handle(driver.tickPre(highlight()));
    }

    private void handle(PlacementDriver.Stop stop) {
        switch (stop) {
            case NO_TORCHES -> stop("common.stop.no_torches");
            case REPEATED_FAILURES -> stop("common.stop.failures");
            case DEAD -> stop("common.stop.dead");
            case NONE -> {
            }
        }
    }

    private void stop(String reasonKey) {
        Msg.warning("escort", Msg.t(reasonKey));
        toggle();
    }

    @Override
    public String getInfoString() {
        if (driver.placing()) return Msg.s("escort.status.placing", driver.placed());
        if (!driver.targetInReach()) return Msg.s("escort.status.walk", driver.placed());
        return Msg.s("escort.status.placed", driver.placed());
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        TorchPlacer.Target t = driver.currentTarget();
        if (t == null) return;
        BlockPos c = t.cell();
        SettingColor color = targetColor.get();
        event.renderer.box(c.getX() + 0.3, c.getY(), c.getZ() + 0.3, c.getX() + 0.7, c.getY() + 0.8, c.getZ() + 0.7,
            color, color, ShapeMode.Lines, 0);
    }
}
