package shit.zen.modules.impl.render;

import net.minecraft.client.gui.GuiGraphics;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.utils.render.OverlayUtil;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;

/**
 * Low-health alarm, ported from NeoRaven's {@code LowHealthModule}.
 *
 * <p>Upstream this module was data-only: it fed the warning icon into Watermark's dynamic island.
 * ZenFeiyu's island renderer is owned by the client and is deliberately left untouched, so this
 * port draws its own compact "Low Health" pill just below the crosshair. The setting labels and
 * semantics are unchanged: {@code Min hearts} is converted to health points (1 heart = 2 HP) and
 * {@code Red island} tints the pill with the error colour.</p>
 */
public class LowHealth extends Module {
    public static LowHealth INSTANCE;

    private final NumberSetting minHeartsSetting = new NumberSetting("Min hearts", 4.0, 0.5, 20.0, 0.5);
    private final BooleanSetting redIslandSetting = new BooleanSetting("Red island", true);
    private String healthText = "Low Health";
    private int cachedHealth = -1;

    public LowHealth() {
        super("Low Health", Category.RENDER);
        INSTANCE = this;
    }

    /** Alert threshold in health points (the slider is in hearts, 1 heart = 2 HP). */
    public float threshold() {
        return this.minHeartsSetting.getValue().floatValue() * 2.0F;
    }

    public boolean triggered(float health) {
        return health > 0.0F && health <= this.threshold();
    }

    public boolean redIsland() {
        return this.redIslandSetting.getValue();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender() || mc.player == null || !this.triggered(mc.player.getHealth())) {
            return;
        }
        int health = (int) Math.ceil(mc.player.getHealth());
        if (health != this.cachedHealth) {
            this.cachedHealth = health;
            this.healthText = "Low Health  " + health + "/" + (int) mc.player.getMaxHealth();
        }
        GuiGraphics graphics = event.guiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int width = 128;
        int height = 20;
        int x = screenWidth / 2 - width / 2;
        int y = screenHeight / 2 + 24;
        int accent = this.redIsland() ? 0xFFFF5555 : 0xFF6750A4;
        int background = this.redIsland() ? 0xB33B1414 : 0xB3222222;

        graphics.fill(x, y, x + width, y + height, background);
        graphics.fill(x, y, x + 3, y + height, accent);
        graphics.fill(x, y + height - 1, x + width, y + height, accent);
        graphics.drawCenteredString(mc.font, this.healthText, screenWidth / 2, y + 6, ColorUtil.fromARGB(255, 255, 255, 255));
    }
}
