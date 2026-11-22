package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.render.FontStore;
import shit.zen.render.Paint;
import shit.zen.render.Renderer;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.OverlayUtil;
import shit.zen.utils.render.RenderUtil;

/**
 * M3 wavy Strength countdown, ported from NeoRaven's {@code StrengthTimerModule}.
 *
 * <p>The upstream shader-driven wavy ring is represented by a small fixed set of rounded
 * {@link Paint} arcs whose radius ripples with time; the progress, refresh detection, world reset
 * and countdown logic are the same. Vanilla {@code MobEffects.DAMAGE_BOOST} is the effect source.
 * Follow-theme mode keeps the upstream purple palette; turning it off reveals the separate Red /
 * Green / Blue sliders for the ring and its track.</p>
 */
public class StrengthTimer extends Module {
    public static StrengthTimer INSTANCE;

    private static final int SEGMENTS = 96;
    private static final int WAVE_SEGMENTS = 24;
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

    private final SmoothAnimationTimer introAnim = new SmoothAnimationTimer();

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
        this.introAnim.setCurrentValue(0.0);
        this.introAnim.setToValue(0.0);
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
        this.introAnim.animate(1.0, 0.24, Easings.EASE_OUT_POW2);
        this.introAnim.tick();
        final float alpha = Math.max(0.0F, Math.min(1.0F, this.introAnim.getValueF()));

        PoseStack pose = event.poseStack();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int side = Math.max(16, Math.min(this.sizeSetting.getValue().intValue(),
                Math.min(screenWidth, screenHeight) - 16));
        final float cx = screenWidth / 2;
        final float cy = screenHeight / 2;
        final float thickness = Math.max(1, this.thicknessSetting.getValue().intValue());
        final float radius = side * 0.5F - thickness;
        float ticks = Math.max(0.0F, this.remainingTicks - event.partialTick());
        final float progress = Math.min(1.0F, Math.max(0.0F, ticks / Math.max(1, this.totalTicks)));

        int ringColor = this.followThemeSetting.getValue() ? 0xFF6750A4 : ColorUtil.fromARGB(
                this.redSetting.getValue().intValue(), this.greenSetting.getValue().intValue(),
                this.blueSetting.getValue().intValue(), 255);
        int trackColor = this.followThemeSetting.getValue() ? 0xFFE8DEF8 : ColorUtil.fromARGB(
                this.trackRedSetting.getValue().intValue(), this.trackGreenSetting.getValue().intValue(),
                this.trackBlueSetting.getValue().intValue(), 255);

        // Layered glass card behind the ring.
        float cardSize = side + 20.0F;
        float cardX = cx - cardSize * 0.5F;
        float cardY = cy - cardSize * 0.5F;
        RenderUtil.drawShadow(pose, cardX, cardY, cardSize, cardSize, 12,
                ColorUtil.fromARGB(0, 0, 0, (int) (150.0F * alpha)));
        RenderUtil.drawBlurredRect(pose, cardX, cardY, cardSize, cardSize, 14.0F, 10.0F,
                0.85F * alpha, 0);
        RenderUtil.drawRoundedRect(pose, cardX, cardY, cardSize, cardSize, 14.0F,
                ColorUtil.fromARGB(14, 16, 24, (int) (178.0F * alpha)));
        RenderUtil.drawRoundedRect(pose, cardX + 1.0F, cardY + 1.0F, cardSize - 2.0F, cardSize - 2.0F,
                13.0F, ColorUtil.fromARGB(255, 255, 255, (int) (44.0F * alpha)));
        RenderUtil.drawRoundedRect(pose, cardX + 18.0F, cardY + 7.0F, cardSize - 36.0F, 2.5F, 1.25F,
                StrengthTimer.alphaColor(ringColor, alpha));

        long time = System.currentTimeMillis();
        final float phase = (time % 1600L) / 1600.0F * (float) (Math.PI * 2.0);
        final int finalRing = ringColor;
        final int finalTrack = trackColor;

        // One soft halo plus a fixed set of rounded arc segments: keeps the ripple
        // without the old 96 per-pixel fill calls.
        Renderer.renderConsumer(drawContext -> {
            try (Paint halo = new Paint()) {
                halo.setStrokeCap(Paint.StrokeCap.STROKE);
                halo.setStrokeWidth(thickness + 3.0F);
                halo.setColor(StrengthTimer.alphaColor(finalRing, 0.16F * alpha));
                drawContext.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                        0.0F, 360.0F, false, halo);
            }
            try (Paint paint = new Paint()) {
                paint.setStrokeCap(Paint.StrokeCap.STROKE);
                paint.setStrokeJoin(Paint.StrokeJoin.ROUND);
                paint.setStrokeWidth(thickness);
                float step = 360.0F / WAVE_SEGMENTS;
                for (int i = 0; i < WAVE_SEGMENTS; i++) {
                    int index = i * (SEGMENTS / WAVE_SEGMENTS);
                    float wave = 1.0F + 0.035F
                            * (float) Math.sin(COS[index] * 3.0F + SIN[index] * 2.0F + phase);
                    float segmentRadius = radius * wave;
                    paint.setColor(StrengthTimer.alphaColor(
                            (i + 1) / (float) WAVE_SEGMENTS <= progress ? finalRing : finalTrack, alpha));
                    drawContext.drawArc(cx - segmentRadius, cy - segmentRadius,
                            cx + segmentRadius, cy + segmentRadius,
                            i * step - 0.6F, step + 1.2F, false, paint);
                }
            }
        });

        if (this.showTimeSetting.getValue()) {
            int seconds = this.remainingTicks / 20 + (this.remainingTicks % 20 == 0 ? 0 : 1);
            if (seconds != this.cachedSeconds) {
                this.cachedSeconds = seconds;
                this.timeText = seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
            }
            float textWidth = FontStore.AXIFORMA_EXTRABOLD_18.getStringWidth(this.timeText);
            RenderUtil.drawRoundedRect(pose, cx - textWidth * 0.5F - 9.0F, cy - 12.0F,
                    textWidth + 18.0F, 24.0F, 9.0F,
                    ColorUtil.fromARGB(8, 10, 18, (int) (225.0F * alpha)));
            RenderUtil.drawRoundedRect(pose, cx - textWidth * 0.5F - 8.0F, cy - 11.0F,
                    textWidth + 16.0F, 22.0F, 8.0F,
                    ColorUtil.fromARGB(255, 255, 255, (int) (40.0F * alpha)));
            FontStore.AXIFORMA_EXTRABOLD_18.drawStringWithShadow(pose, this.timeText,
                    cx - textWidth * 0.5F, cy - 7.0F,
                    ColorUtil.fromARGB(255, 255, 255, (int) (255.0F * alpha)));
        }
    }

    /** Alpha-scaled copy of an ARGB colour without allocating a {@code java.awt.Color}. */
    private static int alphaColor(int color, float scale) {
        int alpha = (int) (ColorUtil.getAlpha(color) * Math.max(0.0F, Math.min(1.0F, scale)));
        return ColorUtil.fromARGB(ColorUtil.getRed(color), ColorUtil.getGreen(color),
                ColorUtil.getBlue(color), Math.max(0, Math.min(255, alpha)));
    }
}
