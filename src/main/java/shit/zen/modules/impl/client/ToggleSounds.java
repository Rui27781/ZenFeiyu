package shit.zen.modules.impl.client;

import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.utils.misc.Sounds;

/**
 * Module-toggle click sounds, ported from NeoRaven's {@code client.Sounds} toggle pipeline.
 *
 * <p>NeoRaven owned its toggle sound from the ClickGUI theme. ZenFeiyu keeps its own loader and GUI
 * customizations, so the feature lives in this small client module instead. Sound events are played
 * through {@link Sounds}, which safely falls back to {@code note.pling} when a sample is missing.</p>
 */
public class ToggleSounds extends Module {
    public static ToggleSounds INSTANCE;

    public final BooleanSetting soundSetting = new BooleanSetting("Toggle Sound", true);
    public final ModeSetting styleSetting = new ModeSetting("Style",
            "Tick", "Lever", "Pling", "Smooth", "Celestial", "Nursultan").withDefault("Tick");

    public ToggleSounds() {
        super("ToggleSounds", Category.CLIENT);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        Sounds.toggle(true, true);
    }

    @Override
    protected void onDisable() {
        Sounds.toggle(false, true);
    }
}
