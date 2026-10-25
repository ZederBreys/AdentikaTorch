package adentika.torch.modules;

import adentika.torch.TorchAutomationAddon;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * Mode 3 "Highlight". M0 placeholder: registered and toggleable, does nothing yet (implemented in M2).
 */
public class TorchHighlight extends Module {
    public TorchHighlight() {
        super(TorchAutomationAddon.CATEGORY, "torch-highlight", "Shows dark spots where hostile mobs can spawn. (Not implemented yet.)");
    }
}
