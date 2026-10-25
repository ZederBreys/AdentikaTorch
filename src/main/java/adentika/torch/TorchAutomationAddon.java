package adentika.torch;

import adentika.torch.modules.TorchHighlight;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class TorchAutomationAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Torch");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Torch Automation");

        Modules.get().add(new TorchHighlight());
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
