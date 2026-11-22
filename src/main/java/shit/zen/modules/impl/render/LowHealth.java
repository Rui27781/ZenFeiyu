package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.render.FontStore;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.OverlayUtil;
import shit.zen.utils.render.RenderUtil;

/**
 * Low-health alarm, ported from NeoRaven's {@code LowHealthModule}.
 *
 * <p>Upstream this module was data-only: it fed the warning icon into Watermark's dynamic island.
 * ZenFeiyu's island renderer is owned by the client and is deliberately left untouched, so this
 * port draws its own compact "Low Health" pill just below the crosshair as a layered glass card.
 * The setting labels and semantics are unchanged: {@code Min hearts} is converted to health points
 * (1 heart = 2 HP) and {@code Red island} tints the pill with the error colour.</p>
 */
public class LowHealth extends Module {
    public static LowHealth INSTANCE;

    private final NumberSetting minHeartsSetting = new NumberSetting("Min hearts", 4.0, 0.5, 20.0, 0.5);
    private final BooleanSetting redIslandSetting = new BooleanSetting("Red island", true);

    private final SmoothAnimationTimer introAnim = new SmoothAnimationTimer();

    private String healthText = "Low Health";
    private int cachedHealth = -1;
    private int cachedTextWidth = 0;

    public LowHealth() {
        super("Low Health", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        this.resetIntro();
    }

    @Override
    protected void onDisable() {
        this.resetIntro();
    }

    private void resetIntro() {
        this.introAnim.setCurrentValue(0.0);
        this.introAnim.setToValue(0.0);
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
            this.cachedTextWidth = (int) FontStore.AXIFORMA_BOLD_16.getStringWidth(this.healthText);
        }
        this.introAnim.animate(1.0, 0.20, Easings.EASE_OUT_POW2);
        this.introAnim.tick();
        float alpha = Math.max(0.0F, Math.min(1.0F, this.introAnim.getValueF()));
        if (alpha <= 0.01F) {
            return;
        }

        PoseStack pose = event.poseStack();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int width = Math.max(128, this.cachedTextWidth + 34);
        int height = 24;
        int x = screenWidth / 2 - width / 2;
        int y = screenHeight / 2 + 24;
        int accent = this.redIsland() ? 0xFFFF5555 : 0xFF6750A4;
        int bodyRed = this.redIsland() ? 59 : 34;
        int bodyGreen = this.redIsland() ? 20 : 34;
        int bodyBlue = this.redIsland() ? 20 : 34;
        float pulse = 0.88F + 0.12F * (float) Math.sin(System.currentTimeMillis() * 0.006);

        RenderUtil.drawShadow(pose, x, y, width, height, 10,
                ColorUtil.fromARGB(0, 0, 0, (int) (150.0F * alpha)));
        RenderUtil.drawBlurredRect(pose, x, y, width, height, 10.0F, 8.0F, 0.90F * alpha, 0);
        RenderUtil.drawRoundedRect(pose, x, y, width, height, 10.0F,
                ColorUtil.fromARGB(bodyRed, bodyGreen, bodyBlue, (int) (190.0F * alpha)));
        RenderUtil.drawRoundedRect(pose, x + 1, y + 1, width - 2, height - 2, 9.0F,
                ColorUtil.fromARGB(255, 255, 255, (int) (46.0F * alpha)));
        RenderUtil.drawRoundedRect(pose, x + 2, y + 3, 8, height - 6, 4.0F,
                LowHealth.alphaColor(accent, 0.28F * alpha * pulse));
        RenderUtil.drawRoundedRect(pose, x + 4, y + 5, 3, height - 10, 1.5F,
                LowHealth.alphaColor(accent, alpha * pulse));
        FontStore.AXIFORMA_BOLD_16.drawStringWithShadow(pose, this.healthText,
                screenWidth * 0.5F - this.cachedTextWidth * 0.5F, y + 6,
                ColorUtil.fromARGB(255, 255, 255, (int) (255.0F * alpha)));
    }

    /** Alpha-scaled copy of an ARGB colour without allocating a {@code java.awt.Color}. */
    private static int alphaColor(int color, float scale) {
        int alpha = (int) (ColorUtil.getAlpha(color) * Math.max(0.0F, Math.min(1.0F, scale)));
        return ColorUtil.fromARGB(ColorUtil.getRed(color), ColorUtil.getGreen(color),
                ColorUtil.getBlue(color), Math.max(0, Math.min(255, alpha)));
    }
}
