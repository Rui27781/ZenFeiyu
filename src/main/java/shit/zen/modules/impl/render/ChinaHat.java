package shit.zen.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import java.awt.Color;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Spinning cone hat with an optional wake trail and glow, ported from NeoRaven's
 * {@code ChinaHatModule}.
 *
 * <p>Upstream emitted immediate-mode quads in third person only. This port keeps the same
 * geometry and easing, but writes into a single reused {@link BufferBuilder} and uses a static
 * sine/cosine table for the 48-segment ring, so the per-frame cost is two draw calls and no
 * allocations. The static ring, the trail and the glow all read from that table; only the moving
 * trail angle needs real trigonometry.</p>
 */
public class ChinaHat extends Module {
    private static final int SEGMENTS = 48;
    private static final int BANDS = 8;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float STEP = TWO_PI / SEGMENTS;
    private static final float[] COS = new float[SEGMENTS + 1];
    private static final float[] SIN = new float[SEGMENTS + 1];

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = i * STEP;
            COS[i] = (float) Math.cos(angle);
            SIN[i] = (float) Math.sin(angle);
        }
    }

    private final NumberSetting radiusSetting = new NumberSetting("Radius", 0.65, 0.2, 3.0, 0.05);
    private final NumberSetting heightSetting = new NumberSetting("Height", 0.35, 0.05, 1.5, 0.05);
    private final NumberSetting offsetSetting = new NumberSetting("Offset", -0.02, -0.5, 1.5, 0.02);
    private final NumberSetting tiltSetting = new NumberSetting("Tilt", 100, 0, 100, 5);
    private final ModeSetting colorsSetting = new ModeSetting("Colors", "Static", "Gradient", "Rainbow")
            .withDefault("Static");
    private final NumberSetting redSetting = new NumberSetting("Red", 100, 0, 255, 1,
            () -> !"Rainbow".equals(this.colorsSetting.getValue()));
    private final NumberSetting greenSetting = new NumberSetting("Green", 192, 0, 255, 1,
            () -> !"Rainbow".equals(this.colorsSetting.getValue()));
    private final NumberSetting blueSetting = new NumberSetting("Blue", 255, 0, 255, 1,
            () -> !"Rainbow".equals(this.colorsSetting.getValue()));
    private final NumberSetting secondRedSetting = new NumberSetting("Second Red", 180, 0, 255, 1,
            () -> "Gradient".equals(this.colorsSetting.getValue()));
    private final NumberSetting secondGreenSetting = new NumberSetting("Second Green", 255, 0, 255, 1,
            () -> "Gradient".equals(this.colorsSetting.getValue()));
    private final NumberSetting secondBlueSetting = new NumberSetting("Second Blue", 220, 0, 255, 1,
            () -> "Gradient".equals(this.colorsSetting.getValue()));
    private final NumberSetting colorSpeedSetting = new NumberSetting("Color Speed", 40, 0, 360, 5);
    private final NumberSetting opacitySetting = new NumberSetting("Opacity", 90, 5, 100, 5);
    private final NumberSetting spinSpeedSetting = new NumberSetting("Spin Speed", 65, 0, 360, 5);
    private final BooleanSetting glowSetting = new BooleanSetting("Glow", true);
    private final BooleanSetting trailSetting = new BooleanSetting("Trail", true);
    private final NumberSetting trailLengthSetting = new NumberSetting("Trail Length", 350, 20, 360, 10);
    private final BooleanSetting throughWallsSetting = new BooleanSetting("Through Walls", false);

    private final int[] surfaceColors = new int[SEGMENTS + 1];
    private final int[] wakeColors = new int[SEGMENTS + 1];
    private final float[] surfaceY = new float[BANDS + 1];
    private final float[] wakeY = new float[BANDS + 1];
    private final float[] radiusFactor = new float[BANDS + 1];
    private final float[] hsb = new float[3];
    private long lastNanos;
    private float spinPhase;
    private float colorPhase;

    public ChinaHat() {
        super("ChinaHat", Category.RENDER);
    }

    @Override
    protected void onEnable() {
        this.lastNanos = 0L;
        this.spinPhase = 0.0f;
        this.colorPhase = 0.0f;
    }

    @Override
    protected void onDisable() {
        this.lastNanos = 0L;
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        Player player = mc.player;
        if (mc.level == null || player == null || mc.options.getCameraType().isFirstPerson()) {
            this.lastNanos = 0L;
            return;
        }
        long now = System.nanoTime();
        float dt = this.lastNanos == 0L ? 0.0f : Math.min(0.25f, (now - this.lastNanos) / 1.0e9f);
        this.lastNanos = now;
        this.spinPhase = (this.spinPhase + (float) Math.toRadians(this.spinSpeedSetting.getValue().doubleValue()) * dt) % TWO_PI;
        this.colorPhase = (this.colorPhase + (float) Math.toRadians(this.colorSpeedSetting.getValue().doubleValue()) * dt) % TWO_PI;

        float partial = event.partialTick();
        Vec3 position = WorldRenderUtil.interpolated(player, partial).subtract(WorldRenderUtil.camera());
        float yaw = WorldRenderUtil.headYaw(player, partial);
        float pitch = WorldRenderUtil.pitch(player, partial);
        boolean sneaking = player.isCrouching();
        float radius = this.radiusSetting.getValue().floatValue();
        float height = this.heightSetting.getValue().floatValue();
        float opacity = this.opacitySetting.getValue().floatValue() / 100.0f;

        PoseStack poseStack = event.poseStack();
        poseStack.pushPose();
        try {
            poseStack.translate(position.x, position.y + 1.5 - (sneaking ? 0.22 : 0.0), position.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(pitch * this.tiltSetting.getValue().floatValue() / 100.0f));
            poseStack.translate(0.0, player.getBbHeight() + this.offsetSetting.getValue().floatValue() - 1.5, 0.0);
            Matrix4f matrix = poseStack.last().pose();

            this.fillSurfaceColors(false);
            this.prepareBands(height, 0.0f, this.surfaceY);
            BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, this.throughWallsSetting.getValue());
            this.emitSurface(buffer, matrix, radius, opacity, false, this.surfaceY);
            if (this.trailSetting.getValue()) {
                this.fillSurfaceColors(true);
                this.prepareBands(height, 0.012f, this.wakeY);
                this.emitSurface(buffer, matrix, radius, opacity, true, this.wakeY);
            }
            WorldRenderUtil.end();

            if (this.glowSetting.getValue()) {
                buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, this.throughWallsSetting.getValue());
                WorldRenderUtil.additiveBlend();
                this.emitGlow(buffer, matrix, radius, height);
                WorldRenderUtil.end();
            }

            buffer = WorldRenderUtil.begin(VertexFormat.Mode.DEBUG_LINES, this.throughWallsSetting.getValue());
            RenderSystem.lineWidth(2.0f);
            this.emitOutline(buffer, matrix, radius, opacity);
            WorldRenderUtil.end();
            RenderSystem.lineWidth(1.0f);
        } finally {
            poseStack.popPose();
        }
    }

    private void fillSurfaceColors(boolean wake) {
        int[] target = wake ? this.wakeColors : this.surfaceColors;
        float span = (float) Math.toRadians(this.trailLengthSetting.getValue().doubleValue());
        for (int i = 0; i <= SEGMENTS; i++) {
            float angle = wake ? this.spinPhase - span * i / SEGMENTS : i * STEP;
            target[i] = this.angularColor(angle);
        }
    }

    private void prepareBands(float height, float lift, float[] targetY) {
        for (int band = 0; band <= BANDS; band++) {
            float t = band / (float) BANDS;
            this.radiusFactor[band] = 1.0f - t;
            targetY[band] = height * ((float) Math.pow(t, 1.35)
                    + 0.12f * (float) Math.pow(1.0f - t, 5.0) + lift);
        }
    }

    private void emitSurface(BufferBuilder buffer, Matrix4f matrix, float radius,
                             float opacity, boolean wake, float[] bandY) {
        int[] colors = wake ? this.wakeColors : this.surfaceColors;
        float span = (float) Math.toRadians(this.trailLengthSetting.getValue().doubleValue());
        for (int i = 0; i < SEGMENTS; i++) {
            float alphaA = wake ? smooth(1.0f - i / (float) SEGMENTS) * 0.8f : 0.7f;
            float alphaB = wake ? smooth(1.0f - (i + 1) / (float) SEGMENTS) * 0.8f : 0.7f;
            float cosA;
            float sinA;
            float cosB;
            float sinB;
            if (wake) {
                float angleA = this.spinPhase - span * i / SEGMENTS;
                float angleB = this.spinPhase - span * (i + 1) / SEGMENTS;
                cosA = Mth.cos(angleA);
                sinA = Mth.sin(angleA);
                cosB = Mth.cos(angleB);
                sinB = Mth.sin(angleB);
            } else {
                cosA = COS[i];
                sinA = SIN[i];
                cosB = COS[i + 1];
                sinB = SIN[i + 1];
            }
            float scale = wake ? 1.01f : 1.0f;
            int colorA = colors[i];
            int colorB = colors[i + 1];
            if (wake) {
                colorA = WorldRenderUtil.mix(colorA, 0xFFFFFF, 0.35f * alphaA / 0.8f);
                colorB = WorldRenderUtil.mix(colorB, 0xFFFFFF, 0.35f * alphaB / 0.8f);
            }
            for (int band = 0; band < BANDS; band++) {
                float inner = radius * this.radiusFactor[band] * scale;
                float outer = radius * this.radiusFactor[band + 1] * scale;
                float yInner = bandY[band];
                float yOuter = bandY[band + 1];
                this.vertex(buffer, matrix, cosA, sinA, inner, yInner, colorA, alphaA * opacity);
                this.vertex(buffer, matrix, cosA, sinA, outer, yOuter, colorA, alphaA * opacity);
                this.vertex(buffer, matrix, cosB, sinB, outer, yOuter, colorB, alphaB * opacity);
                this.vertex(buffer, matrix, cosB, sinB, inner, yInner, colorB, alphaB * opacity);
            }
        }
    }

    private void vertex(BufferBuilder buffer, Matrix4f matrix, float cos, float sin,
                        float radius, float y, int color, float alpha) {
        WorldRenderUtil.vertex(buffer, matrix, radius * cos, y, radius * sin, color, alpha);
    }

    private void emitGlow(BufferBuilder buffer, Matrix4f matrix, float radius, float height) {
        float outer = radius * 1.07f;
        float skirt = height * 0.14f;
        for (int i = 0; i < SEGMENTS; i++) {
            float cosA = COS[i];
            float sinA = SIN[i];
            float cosB = COS[i + 1];
            float sinB = SIN[i + 1];
            // Flat halo ring.
            WorldRenderUtil.vertex(buffer, matrix, radius * cosA, 0.0, radius * sinA, 0xFFFFFF, 0.22f);
            WorldRenderUtil.vertex(buffer, matrix, radius * cosB, 0.0, radius * sinB, 0xFFFFFF, 0.22f);
            WorldRenderUtil.vertex(buffer, matrix, outer * cosB, 0.0, outer * sinB, 0xFFFFFF, 0.0f);
            WorldRenderUtil.vertex(buffer, matrix, outer * cosA, 0.0, outer * sinA, 0xFFFFFF, 0.0f);
            // Short vertical skirt under the brim.
            WorldRenderUtil.vertex(buffer, matrix, radius * cosA, 0.0, radius * sinA, 0xFFFFFF, 0.22f);
            WorldRenderUtil.vertex(buffer, matrix, radius * cosB, 0.0, radius * sinB, 0xFFFFFF, 0.22f);
            WorldRenderUtil.vertex(buffer, matrix, radius * cosB, skirt, radius * sinB, 0xFFFFFF, 0.0f);
            WorldRenderUtil.vertex(buffer, matrix, radius * cosA, skirt, radius * sinA, 0xFFFFFF, 0.0f);
        }
    }

    private void emitOutline(BufferBuilder buffer, Matrix4f matrix, float radius, float opacity) {
        int color = this.angularColor(0.0f);
        for (int i = 0; i < SEGMENTS; i++) {
            WorldRenderUtil.vertex(buffer, matrix, radius * COS[i], 0.0, radius * SIN[i], color, opacity);
            WorldRenderUtil.vertex(buffer, matrix, radius * COS[i + 1], 0.0, radius * SIN[i + 1], color, opacity);
        }
        if (this.trailSetting.getValue()) {
            int trailColor = WorldRenderUtil.mix(this.angularColor(this.spinPhase), 0xFFFFFF, 0.35f);
            float cos = Mth.cos(this.spinPhase);
            float sin = Mth.sin(this.spinPhase);
            for (int band = 0; band < BANDS; band++) {
                float r0 = radius * this.radiusFactor[band] * 1.01f;
                float r1 = radius * this.radiusFactor[band + 1] * 1.01f;
                WorldRenderUtil.vertex(buffer, matrix, r0 * cos, this.wakeY[band], r0 * sin, trailColor, opacity);
                WorldRenderUtil.vertex(buffer, matrix, r1 * cos, this.wakeY[band + 1], r1 * sin, trailColor, opacity);
            }
        }
    }

    private int angularColor(float angle) {
        int red = this.redSetting.getValue().intValue();
        int green = this.greenSetting.getValue().intValue();
        int blue = this.blueSetting.getValue().intValue();
        switch (this.colorsSetting.getValue()) {
            case "Gradient": {
                int second = WorldRenderUtil.rgba(
                        this.secondRedSetting.getValue().intValue(),
                        this.secondGreenSetting.getValue().intValue(),
                        this.secondBlueSetting.getValue().intValue(), 255);
                return WorldRenderUtil.mix(WorldRenderUtil.rgba(red, green, blue, 255), second,
                        0.5f - 0.5f * Mth.cos(angle + this.colorPhase));
            }
            case "Rainbow": {
                Color.RGBtoHSB(red, green, blue, this.hsb);
                return Color.HSBtoRGB(this.hsb[0] + (angle + this.colorPhase) / TWO_PI, this.hsb[1], this.hsb[2]);
            }
            default: {
                return WorldRenderUtil.rgba(red, green, blue, 255);
            }
        }
    }

    private static float smooth(float t) {
        return t * t * (3.0f - 2.0f * t);
    }
}
