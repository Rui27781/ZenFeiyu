package shit.zen.utils.render;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import shit.zen.ClientBase;
import shit.zen.gui.NewClickGui;
import shit.zen.gui.OldClickGui;
import shit.zen.gui.PanelClickGui;
import shit.zen.gui.music.MusicPlayerScreen;

/**
 * HUD visibility gates ported from NeoRaven's {@code client.Overlay}.
 *
 * <p>The original referenced its own ClickGUI classes and a {@code chatAsInventory} setting. This
 * port recognises ZenFeiyu's GUI screens directly and always treats the vanilla chat screen as a
 * valid HUD context, preserving the existing loader/GUI customizations.</p>
 */
public final class OverlayUtil extends ClientBase {
    private OverlayUtil() {
    }

    /** World-space and HUD effects should draw only when the normal in-game overlay is visible. */
    public static boolean canRender() {
        if (mc.player == null || mc.level == null || mc.options.renderDebug || mc.options.hideGui) {
            return false;
        }
        Screen screen = mc.screen;
        return screen == null || ownScreen() || screen instanceof ChatScreen;
    }

    /** True when a third-party/full-screen menu covers the world. */
    public static boolean screenOpen() {
        Screen screen = mc.screen;
        return screen != null && !ownScreen() && !(screen instanceof ChatScreen);
    }

    /** True for ZenFeiyu's own GUI screens. */
    public static boolean ownScreen() {
        Screen screen = mc.screen;
        return screen instanceof NewClickGui || screen instanceof OldClickGui
                || screen instanceof PanelClickGui || screen instanceof MusicPlayerScreen;
    }
}
