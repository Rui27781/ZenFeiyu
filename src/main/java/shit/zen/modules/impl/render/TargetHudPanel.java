package shit.zen.modules.impl.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.combat.KillAura;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.OverlayUtil;

/**
 * Target card, ported from NeoRaven's {@code TargetHudModule} / Onyx
 * {@code gui.onyx.hud.TargetHud}.
 *
 * <p><b>Target choice:</b> the vanilla crosshair raycast is used first — {@code mc.hitResult} is the
 * {@link HitResult} Minecraft recomputes each frame, so an {@link EntityHitResult} is the exact
 * crosshair target without another trace. When the crosshair is not on an entity the card falls
 * back to {@link KillAura#aimingTarget}, the client's existing one-tick target API, so a Killaura
 * lock still drives the panel. Calling {@code mc.player.pick(...)} directly is deliberately avoided:
 * it would duplicate work already done by the renderer and its signature is version-sensitive.</p>
 *
 * <p>All strings and the target reference are sampled in {@link #onTick(GameTickEvent)}; the draw
 * pass only reads them. Linger keeps the last target briefly, and the squash/flash and dot burst
 * are driven by a fixed particle array, so no assets, shaders or per-frame allocations are used.</p>
 */
public class TargetHudPanel extends Module {
    public static TargetHudPanel INSTANCE;

    private static final int MAX_PARTICLES = 24;
    private static final int ACCENT_DEFAULT = 0xFF6750A4;

    private final NumberSetting scaleSetting = new NumberSetting("Scale", 1.0, 0.5, 2.0, 0.05);
    private final NumberSetting positionXSetting = new NumberSetting("Position X", 50.0, 0.0, 100.0, 0.01);
    private final NumberSetting positionYSetting = new NumberSetting("Position Y", 35.0, 0.0, 100.0, 0.01);
    private final NumberSetting lingerSetting = new NumberSetting("Linger", 0.3, 0.0, 2.0, 0.05);
    private final BooleanSetting frostedSetting = new BooleanSetting("Frosted glass", true);
    private final BooleanSetting showHealthSetting = new BooleanSetting("Show health", true);
    private final BooleanSetting showDistanceSetting = new BooleanSetting("Show distance", true);
    private final BooleanSetting showArmorSetting = new BooleanSetting("Show armor", true);
    private final BooleanSetting hitAnimationSetting = new BooleanSetting("Hit animation", true);
    private final BooleanSetting hitParticlesSetting = new BooleanSetting("Hit particles", true);
    private final NumberSetting particleAmountSetting = new NumberSetting("Particle amount", 1.2, 0.0, 3.0, 0.1,
            () -> this.hitParticlesSetting.getValue());
    private final NumberSetting burstForceSetting = new NumberSetting("Burst force", 1.0, 0.0, 3.0, 0.05,
            () -> this.hitParticlesSetting.getValue());
    private final ModeSetting tintSetting = new ModeSetting("Particle color", "Theme", "Custom")
            .withDefault("Theme");
    private final NumberSetting redSetting = new NumberSetting("Red", 103, 0, 255, 1,
            () -> this.tintSetting.is("Custom"));
    private final NumberSetting greenSetting = new NumberSetting("Green", 80, 0, 255, 1,
            () -> this.tintSetting.is("Custom"));
    private final NumberSetting blueSetting = new NumberSetting("Blue", 164, 0, 255, 1,
            () -> this.tintSetting.is("Custom"));
    private final BooleanSetting editPositionSetting = new BooleanSetting("Edit position", false);

    private final float[] particleX = new float[MAX_PARTICLES];
    private final float[] particleY = new float[MAX_PARTICLES];
    private final float[] particleVX = new float[MAX_PARTICLES];
    private final float[] particleVY = new float[MAX_PARTICLES];
    private final float[] particleLife = new float[MAX_PARTICLES];
    private int particleCount;

    private LivingEntity target;
    private String nameText = "";
    private String healthText = "";
    private String distanceText = "";
    private float healthRatio;
    private float armorRatio;
    private long lastSeen;
    private long hitAt;
    private int lastHurtTime;
    private boolean dragging;
    private float dragOffsetX;
    private float dragOffsetY;

    public TargetHudPanel() {
        super("Target Hud Panel", Category.RENDER);
        INSTANCE = this;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        long now = System.currentTimeMillis();
        LivingEntity found = resolveTarget();
        if (found == null) {
            long lingerMs = (long) (this.lingerSetting.getValue().doubleValue() * 1000.0);
            if (this.target != null && now - this.lastSeen > lingerMs) {
                this.target = null;
                this.nameText = "";
                this.healthText = "";
                this.distanceText = "";
                this.particleCount = 0;
            }
            return;
        }
        if (found != this.target) {
            this.particleCount = 0;
            this.lastHurtTime = found.hurtTime;
        }
        this.target = found;
        this.lastSeen = now;
        this.nameText = found.getName().getString();

        float health = found.getHealth();
        float maxHealth = found.getMaxHealth();
        this.healthRatio = maxHealth > 0.0F ? clamp(health / maxHealth) : 0.0F;
        this.healthText = (int) Math.ceil(health) + " / " + (int) Math.ceil(maxHealth);
        this.armorRatio = clamp(found.getArmorValue() / 20.0F);
        if (mc.player != null) {
            this.distanceText = round(mc.player.distanceTo(found), 1) + "m";
        }

        int hurt = found.hurtTime;
        if (hurt > this.lastHurtTime && (this.hitAnimationSetting.getValue()
                || this.hitParticlesSetting.getValue())) {
            this.hitAt = now;
            this.spawnParticles();
        }
        this.lastHurtTime = hurt;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender() || this.target == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long lingerMs = (long) (this.lingerSetting.getValue().doubleValue() * 1000.0);
        long elapsed = now - this.lastSeen;
        float fade = lingerMs <= 0L ? 1.0F : 1.0F - clamp((float) elapsed / (float) lingerMs);
        if (fade <= 0.0F) {
            return;
        }
        float hit = this.hitAnimationSetting.getValue() && now - this.hitAt < 160L
                ? 1.0F - (now - this.hitAt) / 160.0F : 0.0F;

        GuiGraphics graphics = event.guiGraphics();
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        float scale = this.scaleSetting.getValue().floatValue();
        int width = Math.max(100, (int) Math.round(160.0F * scale));
        int height = Math.max(32, (int) Math.round(48.0F * scale));
        int x = clampInt((int) (screenWidth * this.positionXSetting.getValue().floatValue() / 100.0F - width / 2),
                2, Math.max(2, screenWidth - width - 2));
        int y = clampInt((int) (screenHeight * this.positionYSetting.getValue().floatValue() / 100.0F - height / 2),
                2, Math.max(2, screenHeight - height - 2));

        if (this.editPositionSetting.getValue() && mc.mouseHandler.leftDown) {
            float mouseX = (float) mc.mouseHandler.xpos * screenWidth / mc.getWindow().getWidth();
            float mouseY = (float) mc.mouseHandler.ypos * screenHeight / mc.getWindow().getHeight();
            if (!this.dragging && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height) {
                this.dragging = true;
                this.dragOffsetX = mouseX - x;
                this.dragOffsetY = mouseY - y;
            }
            if (this.dragging) {
                this.positionXSetting.setValue(clamp((mouseX - this.dragOffsetX) / screenWidth * 100.0F));
                this.positionYSetting.setValue(clamp((mouseY - this.dragOffsetY) / screenHeight * 100.0F));
                x = clampInt((int) (screenWidth * this.positionXSetting.getValue().floatValue() / 100.0F - width / 2),
                        2, Math.max(2, screenWidth - width - 2));
                y = clampInt((int) (screenHeight * this.positionYSetting.getValue().floatValue() / 100.0F - height / 2),
                        2, Math.max(2, screenHeight - height - 2));
            }
        } else {
            this.dragging = false;
        }

        int accent = ColorUtil.interpolateColor(ACCENT_DEFAULT, 0xFFFF5555, (double) hit);
        graphics.fill(x, y, x + width, y + height, withAlpha(this.frostedSetting.getValue() ? 0x99000000 : 0x7D000000, fade));
        graphics.fill(x, y, x + 3, y + height, withAlpha(accent, fade));

        int textX = x + 10 + (int) (hit * 3.0F);
        graphics.drawString(mc.font, this.nameText, textX, y + 5,
                withAlpha(ColorUtil.interpolateColor(0xFFFFFFFF, 0xFFFFAAAA, (double) hit), fade), false);
        if (this.showDistanceSetting.getValue()) {
            int distanceWidth = mc.font.width(this.distanceText);
            graphics.drawString(mc.font, this.distanceText, x + width - distanceWidth - 6, y + 5,
                    withAlpha(0xFFCCCCCC, fade), false);
        }

        int barY = y + 18;
        int barRight = x + width - 8;
        int barLeft = x + 10;
        graphics.fill(barLeft, barY, barRight, barY + 8, withAlpha(0x33000000, fade));
        int barWidth = barRight - barLeft;
        int lag = (int) (barWidth * this.healthRatio);
        graphics.fill(barLeft, barY, barLeft + lag, barY + 8, withAlpha(0x66FFFFFF, fade));
        graphics.fill(barLeft, barY, barLeft + lag, barY + 8, withAlpha(accent, fade));

        if (this.showHealthSetting.getValue()) {
            int healthWidth = mc.font.width(this.healthText);
            graphics.drawString(mc.font, this.healthText, x + width - healthWidth - 8, barY + 11,
                    withAlpha(0xFFFFFFFF, fade), false);
        }
        if (this.showArmorSetting.getValue()) {
            int pips = (int) Math.ceil(this.armorRatio * 10.0F);
            for (int i = 0; i < 10; i++) {
                int px = x + 10 + i * ((width - 20) / 10);
                graphics.fill(px, barY + 14, px + Math.max(2, (width - 20) / 10 - 2), barY + 17,
                        withAlpha(i < pips ? 0xFFAACCFF : 0x33000000, fade));
            }
        }

        if (this.hitParticlesSetting.getValue() && this.particleCount > 0) {
            int particleColor = this.particleColor();
            int cx = x + 20;
            int cy = y + 24;
            float step = 0.45F + event.partialTick() * 0.05F;
            for (int i = 0; i < this.particleCount; i++) {
                this.particleLife[i] -= 0.018F * step;
                if (this.particleLife[i] <= 0.0F) {
                    continue;
                }
                this.particleVX[i] *= 0.96F;
                this.particleVY[i] = this.particleVY[i] * 0.96F + 0.012F;
                this.particleX[i] += this.particleVX[i] * step;
                this.particleY[i] += this.particleVY[i] * step;
                graphics.fill(cx + (int) this.particleX[i], cy + (int) this.particleY[i],
                        cx + (int) this.particleX[i] + 2, cy + (int) this.particleY[i] + 2,
                        withAlpha(particleColor, this.particleLife[i] * fade));
            }
        }
    }

    private LivingEntity resolveTarget() {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hit).getEntity();
            if (entity instanceof LivingEntity && isValid((LivingEntity) entity)) {
                return (LivingEntity) entity;
            }
        }
        Entity aimed = KillAura.aimingTarget;
        if (aimed instanceof LivingEntity && isValid((LivingEntity) aimed)) {
            return (LivingEntity) aimed;
        }
        return null;
    }

    private boolean isValid(LivingEntity entity) {
        return entity != mc.player && entity.isAlive() && !entity.isDeadOrDying() && !entity.isSpectator();
    }

    private void spawnParticles() {
        float amount = this.particleAmountSetting.getValue().floatValue();
        float force = this.burstForceSetting.getValue().floatValue();
        int count = Math.min(MAX_PARTICLES, (int) Math.ceil(amount * 6.0F));
        for (int i = 0; i < count; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double speed = (0.4 + Math.random() * 1.2) * force;
            this.particleX[i] = 0.0F;
            this.particleY[i] = 0.0F;
            this.particleVX[i] = (float) (Math.cos(angle) * speed);
            this.particleVY[i] = (float) (Math.sin(angle) * speed - 0.4F);
            this.particleLife[i] = 1.0F;
        }
        this.particleCount = count;
    }

    private int particleColor() {
        if (this.tintSetting.is("Custom")) {
            return ColorUtil.fromARGB(this.redSetting.getValue().intValue(),
                    this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue());
        }
        return ACCENT_DEFAULT;
    }

    private static int withAlpha(int color, float scale) {
        int alpha = (int) (((color >>> 24) & 0xFF) * clamp(scale));
        return (Math.min(255, alpha) << 24) | (color & 0x00FFFFFF);
    }

    private static float clamp(float value) {
        return value < 0.0F ? 0.0F : Math.min(value, 1.0F);
    }

    private static int clampInt(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static String round(double value, int decimals) {
        double factor = decimals == 1 ? 10.0 : 100.0;
        return String.valueOf(Math.round(value * factor) / factor);
    }
}
