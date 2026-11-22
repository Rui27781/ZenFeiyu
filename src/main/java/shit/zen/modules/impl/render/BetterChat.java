package shit.zen.modules.impl.render;

import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;

/**
 * Chat layout controls ported from NeoRaven's {@code BetterChatModule}.
 *
 * <p>ZenFeiyu already replaces vanilla chat backgrounds with a blurred rounded panel in
 * {@code ChatComponentPatch}. To preserve that loader/UI customization, this port does not bring
 * over NeoRaven's own chat mixin: it only adds the x/y offsets and reuses the existing fade for the
 * {@code Smooth} option. {@code Clear} removes the panel entirely while keeping message text.</p>
 */
public class BetterChat extends Module {
    public static BetterChat INSTANCE;

    public final BooleanSetting smoothSetting = new BooleanSetting("Smooth", true);
    public final BooleanSetting clearSetting = new BooleanSetting("Clear", false);
    public final NumberSetting xOffsetSetting = new NumberSetting("X offset", 0, -200, 200, 1);
    public final NumberSetting yOffsetSetting = new NumberSetting("Y offset", 0, -200, 200, 1);

    public BetterChat() {
        super("Better Chat", Category.RENDER);
        INSTANCE = this;
    }

    public static boolean smooth() {
        BetterChat module = INSTANCE;
        return module != null && module.isEnabled() && module.smoothSetting.getValue();
    }

    public static boolean clear() {
        BetterChat module = INSTANCE;
        return module != null && module.isEnabled() && module.clearSetting.getValue();
    }

    public static float appliedXOffset() {
        BetterChat module = INSTANCE;
        return module != null && module.isEnabled() ? module.xOffsetSetting.getValue().floatValue() : 0.0f;
    }

    public static float appliedYOffset() {
        BetterChat module = INSTANCE;
        return module != null && module.isEnabled() ? module.yOffsetSetting.getValue().floatValue() : 0.0f;
    }
}
