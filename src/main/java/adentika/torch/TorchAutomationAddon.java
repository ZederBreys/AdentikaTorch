package adentika.torch;

import adentika.torch.commands.TorchAreaCommand;
import adentika.torch.commands.TorchBlockCommand;
import adentika.torch.modules.TorchArea;
import adentika.torch.modules.TorchBlocks;
import adentika.torch.modules.TorchHighlight;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class TorchAutomationAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Torch");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Torch Automation");

        Modules.get().add(new TorchBlocks());
        Modules.get().add(new TorchHighlight());
        Modules.get().add(new TorchArea());

        Commands.add(new TorchBlockCommand());
        Commands.add(new TorchAreaCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "adentika.torch";
    }
}
