package shit.zen.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.EntityHurtEvent;
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.impl.combat.KillAura;
import shit.zen.modules.impl.render.NameProtect;
import shit.zen.render.FontPresets;
import shit.zen.render.FontRenderer;
import shit.zen.render.GlHelper;
import shit.zen.render.Paint;
import shit.zen.render.Renderer;
import shit.zen.render.RoundedRectangle;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.render.RenderUtil;

/**
 * Floating panel that follows the entity the player last attacked (or the
 * current KillAura target). Shows the head, name, health and distance.
 * While a chat screen is open the local player is previewed, which also
 * makes the panel draggable through the regular HUD drag path.
 */
public class AttackTargetHud
extends HudElement {
    private static final float PANEL_WIDTH = 150.0f;
    private static final float PANEL_HEIGHT = 44.0f;
    private static final float HEAD_SIZE = 30.0f;
    private static final long LINGER_MS = 4000L;
    private static final FontRenderer nameFont = FontPresets.pingfang(14.0f);
    private static final FontRenderer valueFont = FontPresets.poppinsMedium(11.0f);

    private final SmoothAnimationTimer fadeAnim = new SmoothAnimationTimer();
    private final SmoothAnimationTimer healthAnim = new SmoothAnimationTimer();
    private final SmoothAnimationTimer headShakeAnim = new SmoothAnimationTimer();
    private final SmoothAnimationTimer headBlurAnim = new SmoothAnimationTimer();
    private LivingEntity target;
    private long lastAttackTime;
    private LivingEntity renderedTarget;
    private boolean wasAttacking = false;

    public AttackTargetHud() {
        super("AttackTargetHUD");
        this.setX(10.0f);
        this.setY(90.0f);
        this.setWidth(PANEL_WIDTH);
        this.setHeight(PANEL_HEIGHT);
        this.setEnabled(true);
    }

    @EventTarget
    public void onHurt(EntityHurtEvent entityHurtEvent) {
        if (mc.player == null || entityHurtEvent.entity() == mc.player) {
            return;
        }
        Entity source = entityHurtEvent.damageSource().getEntity();
        if (source != mc.player) {
            return;
        }
        this.target = entityHurtEvent.entity();
        this.lastAttackTime = System.currentTimeMillis();
    }

    private LivingEntity resolveTarget() {
        if (mc.screen instanceof ChatScreen) {
            return mc.player;
        }
        if (KillAura.aimingTarget instanceof LivingEntity aimed && aimed.isAlive()) {
            return aimed;
        }
        long now = System.currentTimeMillis();
        if (this.target != null && this.target.isAlive() && now - this.lastAttackTime < LINGER_MS) {
            return this.target;
        }
        return null;
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        LivingEntity display = this.resolveTarget();
        if (display != null) {
            this.renderedTarget = display;
        }
        this.fadeAnim.animate(display != null ? 1.0 : 0.0, display != null ? 0.18 : 0.25, Easings.EASE_OUT_POW2);
        this.fadeAnim.tick();
        float fade = this.fadeAnim.getValueF();
        if (fade <= 0.01f || this.renderedTarget == null) {
            return;
        }
        LivingEntity target = display != null ? display : this.renderedTarget;
        float maxHealth = Math.max(1.0f, target.getMaxHealth());
        float healthRatio = Math.max(0.0f, Math.min(1.0f, target.getHealth() / maxHealth));
        this.healthAnim.animate(healthRatio, 0.35, Easings.EASE_OUT_POW3);
        this.healthAnim.tick();

        // Check if currently attacking this target
        boolean isAttacking = KillAura.aimingTarget == target;
        if (isAttacking && !this.wasAttacking) {
            // Just started attacking - trigger shake and blur
            this.headShakeAnim.animate(1.0, 0.15, Easings.EASE_OUT_ELASTIC);
            this.headBlurAnim.animate(1.0, 0.1, Easings.EASE_OUT_QUAD);
        } else if (!isAttacking && this.wasAttacking) {
            // Stopped attacking
            this.headShakeAnim.animate(0.0, 0.3, Easings.EASE_OUT_QUAD);
            this.headBlurAnim.animate(0.0, 0.3, Easings.EASE_OUT_QUAD);
        }
        this.wasAttacking = isAttacking;
        this.headShakeAnim.tick();
        this.headBlurAnim.tick();

        float shakeOffset = this.headShakeAnim.getValueF() * 3.0f * (float) Math.sin(System.currentTimeMillis() * 0.05);
        float blurAmount = this.headBlurAnim.getValueF() * 8.0f;

        // Calculate distance
        float distance = mc.player.distanceTo(target);

        PoseStack poseStack = render2DEvent.poseStack();
        poseStack.pushPose();
        RenderUtil.drawBlurredRect(poseStack, x, y, PANEL_WIDTH, PANEL_HEIGHT, 7.0f, 15.0f, 0.92f * fade, 0);
        poseStack.popPose();

        String name = target == mc.player ? NameProtect.getProtectedName() : target.getName().getString();
        float healthValue = target.getHealth();
        float barRatio = this.healthAnim.getValueF();
        float barWidth = PANEL_WIDTH - HEAD_SIZE - 19.0f;
        Color barColor1;
        Color barColor2;
        if (healthRatio > 0.6f) {
            barColor1 = new Color(70, 214, 160);
            barColor2 = new Color(45, 168, 255);
        } else if (healthRatio > 0.3f) {
            barColor1 = new Color(255, 190, 70);
            barColor2 = new Color(255, 140, 60);
        } else {
            barColor1 = new Color(255, 92, 92);
            barColor2 = new Color(214, 48, 96);
        }
        Renderer.renderConsumer((drawContext -> {
            RoundedRectangle panel = RoundedRectangle.ofXYWHR(x, y, PANEL_WIDTH, PANEL_HEIGHT, 7.0f);
            try (Paint paint = new Paint()) {
                paint.setColor(new Color(14, 16, 22, (int) (120.0f * fade)).getRGB());
                drawContext.drawRoundedRect(panel, paint);
            }
            try (Paint rim = new Paint()) {
                rim.setColor(new Color(255, 255, 255, (int) (52.0f * fade)).getRGB());
                rim.setStrokeCap(Paint.StrokeCap.STROKE);
                rim.setStrokeWidth(1.0f);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x + 0.5f, y + 0.5f,
                        PANEL_WIDTH - 1.0f, PANEL_HEIGHT - 1.0f, 6.5f), rim);
            }
            float headX = x + 5.0f + shakeOffset;
            float headY = y + (PANEL_HEIGHT - HEAD_SIZE) / 2.0f;
            if (target instanceof AbstractClientPlayer player) {
                // Apply blur effect when attacking
                if (blurAmount > 0.5f) {
                    PoseStack ps = render2DEvent.poseStack();
                    ps.pushPose();
                    RenderUtil.drawBlurredRect(ps, headX, headY, HEAD_SIZE, HEAD_SIZE, 6.0f, blurAmount, fade, 0);
                    ps.popPose();
                }
                GlHelper.drawPlayerHeadRounded(player, headX, headY, HEAD_SIZE, HEAD_SIZE, fade, 6.0f);
            } else {
                try (Paint headBg = new Paint()) {
                    headBg.setColor(new Color(40, 44, 54, (int) (200.0f * fade)).getRGB());
                    drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(headX, headY, HEAD_SIZE, HEAD_SIZE, 6.0f), headBg);
                }
            }
            float contentX = headX + HEAD_SIZE + 6.0f - shakeOffset;
            float nameY = y + 6.0f;
            GlHelper.drawTextShadowLegacy(name, contentX, nameY, nameFont,
                    new Color(1.0f, 1.0f, 1.0f, fade).getRGB());

            // Distance display
            String distText = String.format("%.1fm", distance);
            float distWidth = GlHelper.getStringWidth(distText, valueFont);
            GlHelper.drawTextShadowLegacy(distText, x + PANEL_WIDTH - 6.0f - distWidth, nameY + 16.0f, valueFont,
                    new Color(0.7f, 0.7f, 1.0f, fade * 0.85f).getRGB());

            String hpText = String.format("%.1f", healthValue);
            float hpWidth = GlHelper.getStringWidth(hpText, valueFont);
            GlHelper.drawTextShadowLegacy(hpText, x + PANEL_WIDTH - 6.0f - hpWidth, nameY + 3.0f, valueFont,
                    new Color(1.0f, 1.0f, 1.0f, fade * 0.9f).getRGB());

            float barY = y + PANEL_HEIGHT - 14.0f;
            float barH = 4.0f;
            try (Paint bg = new Paint()) {
                bg.setColor(new Color(0, 0, 0, (int) (110.0f * fade)).getRGB());
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(contentX, barY, barWidth, barH, 2.0f), bg);
            }
            float filled = Math.max(0.0f, Math.min(1.0f, barRatio)) * barWidth;
            if (filled > 0.5f) {
                GlHelper.drawGradientRoundedRect(contentX, barY, filled, barH, 2.0f,
                        new Color(barColor1.getRed(), barColor1.getGreen(), barColor1.getBlue(), (int) (255.0f * fade)),
                        new Color(barColor2.getRed(), barColor2.getGreen(), barColor2.getBlue(), (int) (255.0f * fade)));
            }
        }));
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
    }

    @Override
    public void onSettings() {
    }

    @Override
    public boolean isHovered(int mouseX, int mouseY) {
        return RenderUtil.isHovered(this.getX(), this.getY(), PANEL_WIDTH, PANEL_HEIGHT, mouseX, mouseY);
    }
}
