package shit.zen.modules.impl.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.utils.render.OverlayUtil;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;

/**
 * M3 wavy Strength countdown, ported from NeoRaven's {@code StrengthTimerModule}.
 *
 * <p>The upstream shader-driven wavy ring is replaced by a precomputed 96-point circle whose
 * radius ripples slightly with time; the progress, refresh detection, world reset and countdown
 * logic are the same. Vanilla {@code MobEffects.DAMAGE_BOOST} is the effect source. Follow-theme
 * mode keeps the upstream purple palette; turning it off reveals the separate Red / Green / Blue
 * sliders for the ring and its track.</p>
 */
public class StrengthTimer extends Module {
    public static StrengthTimer INSTANCE;

    private static final int SEGMENTS = 96;
    private static final float[] COS = new float[SEGMENTS];
    private static final float[] SIN = new float[SEGMENTS];

    static {
        for (int i = 0; i < SEGMENTS; i++) {
            double angle = i * Math.PI * 2.0 / SEGMENTS;
            COS[i] = (float) Math.cos(angle);
            SIN[i] = (float) Math.sin(angle);
        }
    }

    private final NumberSetting sizeSetting = new NumberSetting("Size", 64.0, 16.0, 160.0, 1.0);
    private final NumberSetting thicknessSetting = new NumberSetting("Thickness", 4.0, 1.0, 12.0, 0.5);
    private final BooleanSetting showTimeSetting = new BooleanSetting("Show time", true);
    private final BooleanSetting followThemeSetting = new BooleanSetting("Follow theme", true);
    private final NumberSetting redSetting = new NumberSetting("Red", 103, 0, 255, 1,
            () -> !this.followThemeSetting.getValue());
    private final NumberSetting greenSetting = new NumberSetting("Green", 80, 0, 255, 1,
            () -> !this.followThemeSetting.getValue());
    private final NumberSetting blueSetting = new NumberSetting("Blue", 164, 0, 255, 1,
            () -> !this.followThemeSetting.getValue());
    private final NumberSetting trackRedSetting = new NumberSetting("Track red", 232, 0, 255, 1,
            () -> !this.followThemeSetting.getValue());
    private final NumberSetting trackGreenSetting = new NumberSetting("Track green", 222, 0, 255, 1,
            () -> !this.followThemeSetting.getValue());
    private final NumberSetting trackBlueSetting = new NumberSetting("Track blue", 248, 0, 255, 1,
            () -> !this.followThemeSetting.getValue());

    private Level world;
    private int totalTicks;
    private int remainingTicks;
    private int amplifier = -1;
    private int cachedSeconds = -1;
    private String timeText = "0:00";

    public StrengthTimer() {
        super("Strength Timer", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        this.reset();
    }

    @Override
    protected void onDisable() {
        this.reset();
    }

    private void reset() {
        this.totalTicks = 0;
        this.remainingTicks = 0;
        this.amplifier = -1;
        this.cachedSeconds = -1;
        this.world = mc == null ? null : mc.level;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        this.sample();
    }

    private void sample() {
        if (mc == null || mc.player == null || mc.level == null) {
            this.reset();
            return;
        }
        if (this.world != mc.level) {
            this.reset();
            this.world = mc.level;
        }
        MobEffectInstance effect = mc.player.getEffect(MobEffects.DAMAGE_BOOST);
        if (effect == null || effect.getDuration() <= 0) {
            if (this.remainingTicks > 0) {
                this.reset();
                this.world = mc.level;
            }
            return;
        }
        int ticks = effect.getDuration();
        int level = effect.getAmplifier();
        if (this.totalTicks == 0 || level != this.amplifier) {
            this.totalTicks = ticks;
        } else {
            int refreshThreshold = Math.max(40, this.totalTicks / 10);
            if ((long) ticks - this.remainingTicks >= refreshThreshold) {
                this.totalTicks = ticks;
            } else {
                this.totalTicks = Math.max(this.totalTicks, ticks);
            }
        }
        this.remainingTicks = ticks;
        this.amplifier = level;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender()) {
            return;
        }
        this.sample();
        if (this.remainingTicks <= 0 || this.totalTicks <= 0) {
            return;
        }
        GuiGraphics graphics = event.guiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int side = Math.max(16, Math.min(this.sizeSetting.getValue().intValue(),
                Math.min(screenWidth, screenHeight) - 16));
        int cx = screenWidth / 2;
        int cy = screenHeight / 2;
        int thickness = Math.max(1, this.thicknessSetting.getValue().intValue());
        float radius = side * 0.5F - thickness;
        float ticks = Math.max(0.0F, this.remainingTicks - event.partialTick());
        float progress = Math.min(1.0F, Math.max(0.0F, ticks / Math.max(1, this.totalTicks)));

        int ringColor = this.followThemeSetting.getValue() ? 0xFF6750A4 : ColorUtil.fromARGB(
                this.redSetting.getValue().intValue(), this.greenSetting.getValue().intValue(),
                this.blueSetting.getValue().intValue(), 255);
        int trackColor = this.followThemeSetting.getValue() ? 0xFFE8DEF8 : ColorUtil.fromARGB(
                this.trackRedSetting.getValue().intValue(), this.trackGreenSetting.getValue().intValue(),
                this.trackBlueSetting.getValue().intValue(), 255);

        long time = System.currentTimeMillis();
        float phase = (time % 1600L) / 1600.0F * (float) (Math.PI * 2.0);
        int squareSize = thickness + 1;
        int half = squareSize / 2;
        for (int i = 0; i < SEGMENTS; i++) {
            float wave = 1.0F + 0.035F * (float) Math.sin(COS[i] * 3.0F + SIN[i] * 2.0F + phase);
            float px = cx + COS[i] * radius * wave;
            float py = cy + SIN[i] * radius * wave;
            int color = i / (float) SEGMENTS <= progress ? ringColor : trackColor;
            graphics.fill((int) px - half, (int) py - half, (int) px - half + squareSize,
                    (int) py - half + squareSize, color);
        }

        if (this.showTimeSetting.getValue()) {
            int seconds = this.remainingTicks / 20 + (this.remainingTicks % 20 == 0 ? 0 : 1);
            if (seconds != this.cachedSeconds) {
                this.cachedSeconds = seconds;
                this.timeText = seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
            }
            int textWidth = mc.font.width(this.timeText);
            int textY = cy - 8;
            graphics.fill(cx - textWidth / 2 - 5, cy - 10, cx + textWidth / 2 + 5, cy + 10, 0xF0080C14);
            graphics.drawCenteredString(mc.font, this.timeText, cx, textY, 0xFFFFFFFF);
        }
    }
}
