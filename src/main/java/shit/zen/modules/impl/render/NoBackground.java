package shit.zen.modules.impl.render;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import shit.zen.gui.NewClickGui;
import shit.zen.gui.OldClickGui;
import shit.zen.gui.PanelClickGui;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;

/**
 * Removes the vanilla translucent grey backdrop behind GUIs.
 *
 * <p>This is the ZenFeiyu port of the NeoRaven style "no GUI background" module.
 * The actual cancel happens in {@code ScreenPatch}; keeping the decision here
 * means the ClickGUI, inventories and other screens can be toggled separately
 * without touching every screen class.</p>
 */
public class NoBackground extends Module {
    public static NoBackground INSTANCE;

    private final BooleanSetting clickGuiSetting = new BooleanSetting("ClickGui", true);
    private final BooleanSetting containersSetting = new BooleanSetting("Containers", true);
    private final BooleanSetting otherScreensSetting = new BooleanSetting("Other Screens", false);

    public NoBackground() {
        super("NoBackground", Category.RENDER);
        INSTANCE = this;
    }

    /** Called by {@code ScreenPatch} before vanilla paints its dark gradient. */
    public static boolean shouldHide(Screen screen) {
        NoBackground module = INSTANCE;
        if (module == null || !module.isEnabled() || screen == null) {
            return false;
        }
        if (screen instanceof NewClickGui || screen instanceof OldClickGui || screen instanceof PanelClickGui) {
            return module.clickGuiSetting.getValue();
        }
        // Never blank out the title screen / server list: only in-world screens
        // should become transparent.
        if (mc == null || mc.level == null) {
            return false;
        }
        if (screen instanceof AbstractContainerScreen) {
            return module.containersSetting.getValue();
        }
        return module.otherScreensSetting.getValue();
    }
}
