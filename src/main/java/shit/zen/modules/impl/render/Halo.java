package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
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
 * Blue Archive style halo rings over the player's head, ported from NeoRaven's {@code HaloModule}
 * and {@code HaloSprites}.
 *
 * <p>Upstream loaded up to three PNG layers per style as textured quads. Those assets are not part
 * of this client, so each sprite layer is replaced by procedural geometry: a soft annulus plus a
 * bright band, with a spiked outer radius for the upper layers. The three palettes approximate the
 * original Shiroko / Serika / Hoshino sprite colours. All settings, their names and their defaults
 * are kept (Style, Size, Spacing, X Rot, Y Rot, Render In First Person, Follow Pitch), including the
 * 0.1-block, two-second eased ping-pong of the original {@code floatingAnimation}.</p>
 *
 * <p>The whole assembly is one additive {@code QUADS} batch. Geometry is emitted in the pose
 * stack's local frame with static sine/cosine and spike tables, so no vertices allocate per frame.</p>
 */
public class Halo extends Module {
    private static final int SEGMENTS = 64;
    private static final double HEAD_HEIGHT = 1.4;
    private static final double FLOAT_HEIGHT = 0.1;
    private static final long FLOAT_MS = 2000L;
    private static final double LAYER_SPACING = 0.08;
    private static final double LAYER_SPACING_NARROW = 0.06;
    private static final int[] SPIKES = {8, 12, 16};
    private static final int[] PALETTE = {0x66C2FF, 0xFFD166, 0xC77DFF};
    private static final double[] COS = new double[SEGMENTS + 1];
    private static final double[] SIN = new double[SEGMENTS + 1];
    private static final double[][] SPIKE_FACTOR = new double[3][SEGMENTS + 1];

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = 2.0 * Math.PI * i / SEGMENTS;
            COS[i] = Math.cos(angle);
            SIN[i] = Math.sin(angle);
            for (int style = 0; style < SPIKES.length; style++) {
                double peak = Math.abs(Math.cos(angle * SPIKES[style] * 0.5));
                SPIKE_FACTOR[style][i] = 1.0 + 0.28 * Math.pow(peak, 6.0);
            }
        }
    }

    private final ModeSetting styleSetting = new ModeSetting("Style", "Shiroko", "Serika", "Hoshino")
            .withDefault("Shiroko");
    private final NumberSetting sizeSetting = new NumberSetting("Size", 1.5, 0.1, 3.0, 0.1);
    private final NumberSetting spacingSetting = new NumberSetting("Spacing", 0.65, 0.5, 2.0, 0.05);
    private final NumberSetting xRotSetting = new NumberSetting("X Rot", 0.0, -90.0, 90.0, 1.0);
    private final NumberSetting yRotSetting = new NumberSetting("Y Rot", 0.0, -90.0, 90.0, 1.0);
    private final BooleanSetting firstPersonSetting = new BooleanSetting("Render In First Person", false);
    private final BooleanSetting followPitchSetting = new BooleanSetting("Follow Pitch", false);

    public Halo() {
        super("Halo", Category.RENDER);
    }

    /** Upstream's eased ping-pong: 0 to 0.1 blocks and back over two seconds. */
    private static double floatOffset() {
        long period = FLOAT_MS * 2L;
        long phase = System.currentTimeMillis() % period;
        double t = phase < FLOAT_MS ? phase / (double) FLOAT_MS : (period - phase) / (double) FLOAT_MS;
        double eased = t < 0.5
                ? 4.0 * t * t * t
                : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) * 0.5;
        return FLOAT_HEIGHT * eased;
    }

    private int styleIndex() {
        String style = this.styleSetting.getValue();
        if ("Serika".equals(style)) {
            return 1;
        }
        if ("Hoshino".equals(style)) {
            return 2;
        }
        return 0;
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (mc.options.getCameraType().isFirstPerson() && !this.firstPersonSetting.getValue()) {
            return;
        }
        float partial = event.partialTick();
        Vec3 camera = WorldRenderUtil.camera();
        Vec3 position = WorldRenderUtil.interpolated(mc.player, partial).subtract(camera);
        float yaw = WorldRenderUtil.bodyYaw(mc.player, partial);
        float pitch = WorldRenderUtil.pitch(mc.player, partial);
        int style = this.styleIndex();
        int layers = style == 2 ? 3 : 2;
        double width = this.sizeSetting.getValue().doubleValue();
        double radius = width * 0.5;
        double spacing = LAYER_SPACING * (width / 1.5)
                * (style == 2 ? LAYER_SPACING_NARROW / LAYER_SPACING : 1.0);
        int color = PALETTE[style];

        PoseStack poseStack = event.poseStack();
        poseStack.pushPose();
        try {
            poseStack.translate(position.x, position.y + HEAD_HEIGHT
                    + this.spacingSetting.getValue().doubleValue() + floatOffset(), position.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
            if (this.followPitchSetting.getValue()) {
                poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
            }
            poseStack.mulPose(Axis.ZP.rotationDegrees(this.xRotSetting.getValue().floatValue()));
            poseStack.mulPose(Axis.XP.rotationDegrees(this.yRotSetting.getValue().floatValue()));

            Matrix4f matrix = poseStack.last().pose();
            BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, false);
            WorldRenderUtil.additiveBlend();
            for (int layer = 0; layer < layers; layer++) {
                // The first layer is a plain ring; the stacked layers carry the spikes.
                Halo.ring(buffer, matrix, radius, style, layer > 0, layer == 0 ? color
                        : WorldRenderUtil.mix(color, 0xFFFFFF, 0.18f));
                poseStack.translate(0.0, spacing, 0.0);
                // Re-read the matrix after the translation so every layer is lifted.
                matrix = poseStack.last().pose();
            }
            WorldRenderUtil.end();
        } finally {
            poseStack.popPose();
        }
    }

    /** One flat annulus, optionally with the style's precomputed spiked outer edge. */
    private static void ring(BufferBuilder buffer, Matrix4f matrix, double radius,
                             int style, boolean spiked, int color) {
        double inner = radius * 0.70;
        double bandInner = radius * 0.76;
        double bandOuter = radius * 0.88;
        for (int i = 0; i < SEGMENTS; i++) {
            double cosA = COS[i], sinA = SIN[i];
            double cosB = COS[i + 1], sinB = SIN[i + 1];
            double outerA = spiked ? radius * SPIKE_FACTOR[style][i] : radius;
            double outerB = spiked ? radius * SPIKE_FACTOR[style][i + 1] : radius;

            // Soft bloom around the sprite edge: bright inside, transparent outside.
            WorldRenderUtil.vertex(buffer, matrix, inner * cosA, 0.0, inner * sinA, color, 0.45f);
            WorldRenderUtil.vertex(buffer, matrix, outerA * cosA, 0.0, outerA * sinA, color, 0.0f);
            WorldRenderUtil.vertex(buffer, matrix, outerB * cosB, 0.0, outerB * sinB, color, 0.0f);
            WorldRenderUtil.vertex(buffer, matrix, inner * cosB, 0.0, inner * sinB, color, 0.45f);

            // The bright body of the ring.
            WorldRenderUtil.vertex(buffer, matrix, bandInner * cosA, 0.0, bandInner * sinA, color, 0.90f);
            WorldRenderUtil.vertex(buffer, matrix, bandOuter * cosA, 0.0, bandOuter * sinA, color, 0.22f);
            WorldRenderUtil.vertex(buffer, matrix, bandOuter * cosB, 0.0, bandOuter * sinB, color, 0.22f);
            WorldRenderUtil.vertex(buffer, matrix, bandInner * cosB, 0.0, bandInner * sinB, color, 0.90f);
        }
    }
}
