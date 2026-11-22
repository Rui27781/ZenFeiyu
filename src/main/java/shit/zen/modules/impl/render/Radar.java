package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.gui.newclickgui.Theme;
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
 * Small player radar, ported from NeoRaven's {@code RadarModule}.
 *
 * <p>The 100x100 panel, centre marker, 19-block radius and yaw-relative dots are
 * drawn as a layered glass card with custom-font header and rounded markers. The old GL scissor
 * and immediate-mode lines are replaced by {@code GuiGraphics.enableScissor} and rotated rounded
 * capsules. Bot filtering is dropped because ZenFeiyu has no BotCheck service; dead players,
 * spectators and the local player are still skipped. All original setting labels are kept, plus
 * separate RGB sliders for the dot.</p>
 */
public class Radar extends Module {
    private static final int LEFT = 5;
    private static final int TOP = 70;
    private static final int SIZE = 100;
    private static final int CENTER_X = LEFT + 50;
    private static final int CENTER_Y = TOP + 52;
    private static final double MAX_DIST_SQ = 360.0;

    private final BooleanSetting showTracerLinesSetting = new BooleanSetting("Show tracer lines", true);
    private final NumberSetting redSetting = new NumberSetting("Red", 255, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 40, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 40, 0, 255, 1);

    private final SmoothAnimationTimer introAnim = new SmoothAnimationTimer();

    public Radar() {
        super("Radar", Category.RENDER);
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

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender() || mc.player == null || mc.level == null) {
            return;
        }
        this.introAnim.animate(1.0, 0.25, Easings.EASE_OUT_POW2);
        this.introAnim.tick();
        float alpha = Math.max(0.0F, Math.min(1.0F, this.introAnim.getValueF()));
        if (alpha <= 0.01F) {
            return;
        }

        PoseStack pose = event.poseStack();
        int right = LEFT + SIZE;
        int bottom = TOP + SIZE;
        int accent = Theme.accent();

        // Layered glass card: shadow, one frosted pane, tinted body and inset rim.
        RenderUtil.drawShadow(pose, LEFT, TOP, SIZE, SIZE, 10,
                ColorUtil.fromARGB(0, 0, 0, (int) (150.0F * alpha)));
        RenderUtil.drawBlurredRect(pose, LEFT, TOP, SIZE, SIZE, 10.0F, 8.0F, 0.82F * alpha, 0);
        RenderUtil.drawRoundedRect(pose, LEFT, TOP, SIZE, SIZE, 10.0F,
                ColorUtil.fromARGB(16, 18, 26, (int) (178.0F * alpha)));
        RenderUtil.drawRoundedRect(pose, LEFT + 1, TOP + 1, SIZE - 2, SIZE - 2, 9.0F,
                ColorUtil.fromARGB(255, 255, 255, (int) (42.0F * alpha)));
        RenderUtil.drawRoundedRect(pose, LEFT + 12, TOP + 6, SIZE - 24, 3.0F, 1.5F,
                Radar.alphaColor(accent, alpha));
        FontStore.AXIFORMA_BOLD_13.drawStringWithShadow(pose, "RADAR", LEFT + 12, TOP + 15,
                ColorUtil.fromARGB(228, 230, 238, (int) (230.0F * alpha)));

        int red = this.redSetting.getValue().intValue();
        int green = this.greenSetting.getValue().intValue();
        int blue = this.blueSetting.getValue().intValue();
        boolean tracers = this.showTracerLinesSetting.getValue();

        // Colours are composed once per frame so the player loop stays allocation-free.
        int markerOuter = ColorUtil.fromARGB(255, 255, 255, (int) (40.0F * alpha));
        int markerInner = ColorUtil.fromARGB(255, 255, 255, (int) (100.0F * alpha));
        int tracerColor = ColorUtil.fromARGB(255, 255, 255, (int) (85.0F * alpha));
        int dotGlow = ColorUtil.fromARGB(red, green, blue, (int) (62.0F * alpha));
        int dotCore = ColorUtil.fromARGB(red, green, blue, (int) (255.0F * alpha));

        GuiGraphics graphics = event.guiGraphics();
        graphics.enableScissor(LEFT, TOP, right, bottom);
        // Crosshair rendered as a small stack of rounded discs instead of per-pixel scanlines.
        RenderUtil.drawRoundedRect(pose, CENTER_X - 6, CENTER_Y - 6, 12, 12, 6, markerOuter);
        RenderUtil.drawRoundedRect(pose, CENTER_X - 4, CENTER_Y - 4, 8, 8, 4, markerInner);
        RenderUtil.drawRoundedRect(pose, CENTER_X - 2, CENTER_Y - 2, 4, 4, 2,
                Radar.alphaColor(accent, alpha));

        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isDeadOrDying() || player.isSpectator()) {
                continue;
            }
            double distanceSq = mc.player.distanceToSqr(player);
            if (distanceSq > MAX_DIST_SQ) {
                continue;
            }
            double offsetX = player.getX() - mc.player.getX();
            double offsetZ = player.getZ() - mc.player.getZ();
            double angle = Math.atan2(offsetX, offsetZ) * 57.29578;
            double relative = (mc.player.getYRot() + angle) % 360.0;
            double distance = distanceSq / 5.0;
            double radians = Math.toRadians(relative);
            int dotX = CENTER_X - (int) (distance * Math.sin(radians));
            int dotY = CENTER_Y - (int) (distance * Math.cos(radians));
            if (tracers) {
                float deltaX = dotX - CENTER_X;
                float deltaY = dotY - CENTER_Y;
                float length = (float) Math.hypot(deltaX, deltaY);
                if (length > 2.0F) {
                    float rotation = (float) Math.atan2(deltaY, deltaX) * 57.29578F;
                    pose.pushPose();
                    pose.translate(CENTER_X, CENTER_Y, 0.0F);
                    pose.mulPose(Axis.ZP.rotationDegrees(rotation));
                    RenderUtil.drawRoundedRect(pose, 0.0F, -1.0F, length, 2.0F, 1.0F, tracerColor);
                    pose.popPose();
                }
            }
            RenderUtil.drawRoundedRect(pose, dotX - 4, dotY - 4, 8, 8, 4, dotGlow);
            RenderUtil.drawRoundedRect(pose, dotX - 2.5F, dotY - 2.5F, 5, 5, 2.5F, dotCore);
        }
        graphics.disableScissor();
    }

    /** Alpha-scaled copy of an ARGB colour without allocating a {@code java.awt.Color}. */
    private static int alphaColor(int color, float scale) {
        int alpha = (int) (ColorUtil.getAlpha(color) * Math.max(0.0F, Math.min(1.0F, scale)));
        return ColorUtil.fromARGB(ColorUtil.getRed(color), ColorUtil.getGreen(color),
                ColorUtil.getBlue(color), Math.max(0, Math.min(255, alpha)));
    }
}
