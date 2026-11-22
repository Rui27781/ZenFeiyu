package shit.zen.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
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
 * Eight wing silhouettes with flapping, ported from NeoRaven's {@code WingsModule}.
 *
 * <p>The upstream vertex tables ({@code WingShape}) are reproduced verbatim. NeoRaven emitted
 * three membranes per wing in immediate mode, then an outline and a few ribs. Here each player is
 * still two draw calls (triangles + lines), because all membranes and both sides share one batch:
 * the side transform is pushed on the {@code PoseStack} while the vertices are emitted, which
 * copies the matrix into the vertex data. No per-frame lists, streams or vector allocations.</p>
 */
public class Wings extends Module {
    private static final int[] SIDES = {-1, 1};

    private static final float[][] ANGELIC = {
            {0.08f, 0.10f, 0.88f}, {0.28f, 0.34f, 0.78f}, {0.56f, 0.82f, 0.62f},
            {0.86f, 0.30f, 0.52f}, {1.14f, 0.46f, 0.40f}, {1.24f, 0.04f, 0.30f},
            {1.02f, -0.18f, 0.28f}, {1.18f, -0.64f, 0.22f}, {0.86f, -0.46f, 0.20f},
            {0.80f, -0.98f, 0.14f}, {0.54f, -0.74f, 0.16f}, {0.30f, -1.16f, 0.12f},
            {0.10f, -0.54f, 0.18f}
    };
    private static final float[][] DRAGON = {
            {0.10f, 0.12f, 0.90f}, {0.22f, 0.40f, 0.80f}, {0.48f, 0.72f, 0.65f},
            {0.80f, 0.60f, 0.55f}, {1.10f, 0.70f, 0.42f}, {1.30f, 0.30f, 0.35f},
            {1.20f, -0.10f, 0.30f}, {1.05f, -0.50f, 0.25f}, {0.70f, -0.35f, 0.22f},
            {0.50f, -0.70f, 0.18f}, {0.20f, -0.50f, 0.15f}, {0.05f, -0.30f, 0.20f}
    };
    private static final float[][] BUTTERFLY = {
            {0.12f, 0.15f, 0.92f}, {0.30f, 0.50f, 0.85f}, {0.50f, 0.90f, 0.70f},
            {0.70f, 0.80f, 0.60f}, {0.85f, 0.55f, 0.50f}, {0.75f, 0.20f, 0.45f},
            {0.55f, -0.15f, 0.40f}, {0.40f, -0.60f, 0.30f}, {0.25f, -0.85f, 0.20f},
            {0.12f, -0.65f, 0.25f}, {0.06f, -0.35f, 0.30f}
    };
    private static final float[][] PHOENIX = {
            {0.10f, 0.14f, 0.90f}, {0.25f, 0.45f, 0.82f}, {0.52f, 0.78f, 0.68f},
            {0.82f, 0.50f, 0.55f}, {1.15f, 0.55f, 0.42f}, {1.28f, 0.15f, 0.32f},
            {1.20f, -0.25f, 0.28f}, {1.10f, -0.55f, 0.24f}, {1.25f, -0.85f, 0.18f},
            {0.90f, -0.65f, 0.16f}, {0.60f, -0.90f, 0.14f}, {0.30f, -0.70f, 0.12f},
            {0.08f, -0.40f, 0.16f}
    };
    private static final float[][] CRYSTAL = {
            {0.15f, 0.10f, 0.85f}, {0.40f, 0.35f, 0.75f}, {0.70f, 0.60f, 0.60f},
            {1.00f, 0.40f, 0.50f}, {0.85f, 0.10f, 0.45f}, {1.10f, -0.15f, 0.35f},
            {0.90f, -0.45f, 0.30f}, {0.65f, -0.30f, 0.25f}, {0.45f, -0.60f, 0.20f},
            {0.20f, -0.40f, 0.22f}, {0.08f, -0.15f, 0.30f}
    };
    private static final float[][] MECHANICAL = {
            {0.08f, 0.08f, 0.90f}, {0.20f, 0.25f, 0.82f}, {0.45f, 0.40f, 0.70f},
            {0.70f, 0.35f, 0.58f}, {0.95f, 0.25f, 0.48f}, {0.90f, 0.00f, 0.40f},
            {1.10f, -0.15f, 0.32f}, {0.80f, -0.30f, 0.28f}, {0.55f, -0.20f, 0.25f},
            {0.40f, -0.45f, 0.20f}, {0.15f, -0.30f, 0.22f}, {0.05f, -0.10f, 0.28f}
    };
    private static final float[][] FAIRY = {
            {0.10f, 0.12f, 0.90f}, {0.25f, 0.38f, 0.82f}, {0.42f, 0.65f, 0.68f},
            {0.55f, 0.70f, 0.58f}, {0.60f, 0.45f, 0.50f}, {0.50f, 0.15f, 0.42f},
            {0.38f, -0.10f, 0.35f}, {0.30f, -0.35f, 0.28f}, {0.18f, -0.45f, 0.22f},
            {0.08f, -0.25f, 0.26f}
    };
    private static final float[][] DEMON = {
            {0.10f, 0.12f, 0.88f}, {0.25f, 0.38f, 0.80f}, {0.55f, 0.65f, 0.65f},
            {0.85f, 0.50f, 0.52f}, {1.15f, 0.55f, 0.40f}, {1.25f, 0.20f, 0.32f},
            {1.10f, -0.10f, 0.28f}, {1.30f, -0.45f, 0.22f}, {1.15f, -0.70f, 0.18f},
            {0.85f, -0.55f, 0.16f}, {0.55f, -0.85f, 0.14f}, {0.25f, -0.65f, 0.12f},
            {0.08f, -0.35f, 0.18f}
    };
    private static final float[][][] SHAPES = {ANGELIC, DRAGON, BUTTERFLY, PHOENIX, CRYSTAL, MECHANICAL, FAIRY, DEMON};

    private final ModeSetting typeSetting = new ModeSetting("Wing Type",
            "Angelic", "Dragon", "Butterfly", "Phoenix", "Crystal", "Mechanical", "Fairy", "Demon")
            .withDefault("Angelic");
    private final ModeSetting fillSetting = new ModeSetting("Fill", "Solid", "Gradient").withDefault("Solid");
    private final BooleanSetting selfSetting = new BooleanSetting("Targets Self", true);
    private final BooleanSetting playersSetting = new BooleanSetting("Targets Players", false);
    private final NumberSetting scaleSetting = new NumberSetting("Scale", 1.0, 0.3, 3.0, 0.1);
    private final NumberSetting angleSetting = new NumberSetting("Angle", 20, 0, 90, 1);
    private final NumberSetting heightSetting = new NumberSetting("Height", 1.5, 0.8, 2.5, 0.05);
    private final NumberSetting depthSetting = new NumberSetting("Depth", 0.15, 0.0, 0.5, 0.01);
    private final BooleanSetting flappingSetting = new BooleanSetting("Flapping", true);
    private final NumberSetting strengthSetting = new NumberSetting("Flap Strength", 30, 5, 60, 1);
    private final NumberSetting speedSetting = new NumberSetting("Flap Speed", 3, 0.5, 8, 0.5);
    private final BooleanSetting throughWallsSetting = new BooleanSetting("Through Walls", false);
    private final NumberSetting redSetting = new NumberSetting("Red", 255, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 255, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 255, 0, 255, 1);
    private final NumberSetting opacitySetting = new NumberSetting("Opacity", 100, 5, 100, 1);

    public Wings() {
        super("Wings", Category.RENDER);
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        float partial = event.partialTick();
        Vec3 camera = WorldRenderUtil.camera();
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        for (Player player : mc.level.players()) {
            if (player == null || !player.isAlive() || player.isSpectator() || player.isInWater()) {
                continue;
            }
            if (player == mc.player) {
                if (!this.selfSetting.getValue() || firstPerson) {
                    continue;
                }
            } else if (!this.playersSetting.getValue()) {
                continue;
            }
            this.drawPlayer(event.poseStack(), player, camera, partial);
        }
    }

    private void drawPlayer(PoseStack poseStack, Player player, Vec3 camera, float partial) {
        Vec3 position = WorldRenderUtil.interpolated(player, partial).subtract(camera);
        float yaw = WorldRenderUtil.bodyYaw(player, partial);
        boolean sneaking = player.isCrouching();
        Vec3 motion = player.getDeltaMovement();
        float speed = Math.min(1.0f, (float) Math.sqrt(motion.x * motion.x + motion.z * motion.z) * 10.0f);
        float ticks = player.tickCount + partial;
        float flap = this.flappingSetting.getValue()
                ? (float) Math.sin(ticks * 0.12 * this.speedSetting.getValue().doubleValue() / 3.0)
                * 4.5f * this.strengthSetting.getValue().floatValue() / 30.0f
                : 0.0f;
        float open = flap + speed * 0.18f - this.angleSetting.getValue().floatValue();

        float scale = this.scaleSetting.getValue().floatValue();
        int base = WorldRenderUtil.rgba(
                this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(),
                this.blueSetting.getValue().intValue(), 255);
        float opacity = this.opacitySetting.getValue().floatValue() / 100.0f;
        float[][] shape = SHAPES[this.shapeIndex()];
        boolean gradient = "Gradient".equals(this.fillSetting.getValue());
        boolean throughWalls = this.throughWallsSetting.getValue();
        poseStack.pushPose();
        try {
            poseStack.translate(position.x, position.y, position.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - yaw));
            if (sneaking) {
                poseStack.mulPose(Axis.XP.rotationDegrees(18.0f));
            }
            poseStack.translate(0.0,
                    (sneaking ? 0.96 : 1.38) + this.heightSetting.getValue().floatValue() - 1.5,
                    0.1 + this.depthSetting.getValue().floatValue() - 0.15);
            poseStack.scale(scale, scale, scale);

            BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.TRIANGLES, throughWalls);
            for (int side : SIDES) {
                this.pushSide(poseStack, side, open);
                this.membranes(buffer, poseStack.last().pose(), shape, side, base, opacity, gradient);
                poseStack.popPose();
            }
            WorldRenderUtil.end();

            buffer = WorldRenderUtil.begin(VertexFormat.Mode.DEBUG_LINES, throughWalls);
            RenderSystem.lineWidth(2.2f);
            for (int side : SIDES) {
                this.pushSide(poseStack, side, open);
                this.outline(buffer, poseStack.last().pose(), shape, side, base, opacity);
                poseStack.popPose();
            }
            WorldRenderUtil.end();
            RenderSystem.lineWidth(1.0f);
        } finally {
            poseStack.popPose();
        }
    }

    private void pushSide(PoseStack poseStack, int side, float open) {
        poseStack.pushPose();
        poseStack.translate(side * 0.06, 0.0, 0.02);
        poseStack.mulPose(Axis.YP.rotationDegrees(side * open));
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * -11.0f));
        poseStack.mulPose(Axis.XP.rotationDegrees(-4.0f));
    }

    private void membranes(BufferBuilder buffer, Matrix4f matrix, float[][] shape, int side,
                           int base, float opacity, boolean gradient) {
        float innerMix = gradient ? 0.42f : 0.28f;
        float middleMix = gradient ? 0.72f : 0.55f;
        this.membrane(buffer, matrix, shape, side, 1.22f,
                WorldRenderUtil.mix(base, 0xFFFFFF, innerMix), opacity * 0.22f, 0.0f);
        this.membrane(buffer, matrix, shape, side, 0.84f,
                WorldRenderUtil.mix(base, 0xFFFFFF, middleMix), opacity * 0.26f, 0.0f);
        this.membrane(buffer, matrix, shape, side, 1.0f, base, opacity, opacity * 0.45f);
    }

    private void membrane(BufferBuilder buffer, Matrix4f matrix, float[][] shape, int side, float size,
                          int color, float rootAlpha, float edgeAlpha) {
        int count = shape.length;
        for (int i = 0; i < count; i++) {
            float[] a = shape[i];
            float[] b = shape[(i + 1) % count];
            WorldRenderUtil.vertex(buffer, matrix, 0.0, 0.0, 0.0, color, rootAlpha);
            WorldRenderUtil.vertex(buffer, matrix, side * a[0] * size, a[1] * size, 0.0, color, edgeAlpha * a[2]);
            WorldRenderUtil.vertex(buffer, matrix, side * b[0] * size, b[1] * size, 0.0, color, edgeAlpha * b[2]);
        }
    }

    private void outline(BufferBuilder buffer, Matrix4f matrix, float[][] shape, int side,
                         int base, float opacity) {
        int count = shape.length;
        int outlineColor = base;
        for (int i = 0; i < count; i++) {
            float[] a = shape[i];
            float[] b = shape[(i + 1) % count];
            WorldRenderUtil.vertex(buffer, matrix, side * a[0], a[1], 0.0, outlineColor, opacity * 0.95f);
            WorldRenderUtil.vertex(buffer, matrix, side * b[0], b[1], 0.0, outlineColor, opacity * 0.95f);
        }
        int[] ribs = count <= 10 ? RIBS_SHORT : RIBS_LONG;
        int ribColor = WorldRenderUtil.mix(base, 0xFFFFFF, 0.28f);
        float root = Math.max(8.0f / 255.0f * opacity, opacity * 0.15f);
        for (int index : ribs) {
            if (index >= count) {
                continue;
            }
            float[] point = shape[index];
            WorldRenderUtil.vertex(buffer, matrix, 0.0, 0.0, 0.0, ribColor, root);
            WorldRenderUtil.vertex(buffer, matrix, side * point[0] * 0.96, point[1] * 0.96, 0.0,
                    ribColor, opacity * 0.2f * point[2]);
        }
    }

    private int shapeIndex() {
        String[] modes = this.typeSetting.getModes();
        String selected = this.typeSetting.getValue();
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(selected)) {
                return i;
            }
        }
        return 0;
    }

    private static final int[] RIBS_SHORT = {2, 4, 6, 8};
    private static final int[] RIBS_LONG = {2, 4, 7, 9, 11};
}
