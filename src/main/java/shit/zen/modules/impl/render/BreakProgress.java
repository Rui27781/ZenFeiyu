package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.math.Vector2f;
import shit.zen.utils.render.ProjectionUtil;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Mining progress indicator ported from NeoRaven's {@code BreakProgressModule}.
 *
 * <p>The 1.8.9 field reflection for {@code curBlockDamageMP} is replaced by the mapped
 * {@code Player.mineProgress} field. "Show BedAura" is retained for configuration compatibility:
 * BedAura does not exist in this client, so the setting only widens the accepted target to the
 * current hit block while not attacking. The circle mode is drawn from geometry (a segmented ring)
 * instead of the Onyx {@code M3MiningProgress} widget, and the label is rendered in the HUD pass
 * instead of a world-space billboard.</p>
 */
public class BreakProgress extends Module {
    private final ModeSetting modeSetting = new ModeSetting("Mode", "Percentage", "Seconds", "Raw", "Circle")
            .withDefault("Percentage");
    private final BooleanSetting showManualSetting = new BooleanSetting("Show Manual", true);
    private final BooleanSetting showBedAuraSetting = new BooleanSetting("Show BedAura", false);
    private final BooleanSetting fadeInSetting = new BooleanSetting("Fade in", true);
    private final NumberSetting circleStyleSetting = new NumberSetting("Circle style", 0, 0, 3, 1,
            () -> "Circle".equals(this.modeSetting.getValue()));

    private static final float[] STYLE_SEGMENTS = {24.0f, 32.0f, 48.0f, 64.0f};
    private static final float[] STYLE_WIDTH = {0.22f, 0.30f, 0.16f, 0.40f};

    private String text = "";
    private float progress;
    private int blockX;
    private int blockY;
    private int blockZ;
    private boolean hasBlock;
    private boolean hasScreen;
    private float screenX;
    private float screenY;
    private float distance;
    private long startNanos;
    private int textColor;

    public BreakProgress() {
        super("BreakProgress", Category.RENDER);
    }

    @Override
    protected void onDisable() {
        this.reset();
    }

    private void reset() {
        this.progress = 0.0f;
        this.text = "";
        this.hasBlock = false;
        this.hasScreen = false;
        this.startNanos = 0L;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc.level == null || mc.player == null || mc.gameMode == null) {
            this.reset();
            return;
        }
        if (mc.player.getAbilities().instabuild || !mc.player.getAbilities().mayBuild) {
            this.reset();
            return;
        }
        boolean manual = this.showManualSetting.getValue() && mc.options.keyAttack.isDown();
        boolean aura = this.showBedAuraSetting.getValue() && mc.hitResult != null;
        if (!mc.gameMode.isDestroying() || (!manual && !aura) || mc.player.mineProgress <= 0.0f) {
            this.reset();
            return;
        }
        float value = Mth.clamp(mc.player.mineProgress, 0.0f, 1.0f);
        if (this.progress <= 0.0f || value < this.progress) {
            this.startNanos = System.nanoTime();
        }
        this.progress = value;
        if (manual && mc.hitResult != null && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            BlockPos pos = ((net.minecraft.world.phys.BlockHitResult) mc.hitResult).getBlockPos();
            this.blockX = pos.getX();
            this.blockY = pos.getY();
            this.blockZ = pos.getZ();
            this.hasBlock = true;
        } else if (!this.hasBlock) {
            this.hasBlock = false;
        }
        this.updateText();
    }

    private void updateText() {
        String mode = this.modeSetting.getValue();
        if ("Circle".equals(mode)) {
            this.text = (int) (100.0 * this.progress) + "%";
            return;
        }
        if ("Raw".equals(mode)) {
            this.text = this.progress >= 1.0f ? "1.00" : String.valueOf((int) (this.progress * 100.0f) / 100.0f);
            return;
        }
        if ("Seconds".equals(mode)) {
            float elapsed = Math.max(0.0f, (System.nanoTime() - this.startNanos) / 1.0E9f);
            float total = elapsed / Math.max(this.progress, 0.05f);
            this.text = String.valueOf(Math.round(Math.min(60.0f, total * (1.0f - this.progress)) * 10.0f) / 10.0f) + "s";
            return;
        }
        this.text = (int) (100.0 * this.progress) + "%";
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        this.hasScreen = false;
        if (!this.hasBlock || this.progress <= 0.0f || mc.level == null || mc.player == null) {
            return;
        }
        ProjectionUtil.updateMatrices();
        double worldX = this.blockX + 0.5;
        double worldY = this.blockY + 0.5;
        double worldZ = this.blockZ + 0.5;
        Vec3 camera = WorldRenderUtil.camera();
        double dx = worldX - camera.x;
        double dy = worldY - camera.y;
        double dz = worldZ - camera.z;
        this.distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        Vector2f screen = ProjectionUtil.project(worldX, worldY + 0.3, worldZ);
        if (screen != null) {
            this.screenX = screen.x;
            this.screenY = screen.y;
            this.hasScreen = true;
        }

        float alpha = this.fadeInSetting.getValue() ? 26.0f + 214.0f * this.progress : 240.0f;
        this.textColor = WorldRenderUtil.rgba(255, 255, 255, (int) alpha);
        if (!"Circle".equals(this.modeSetting.getValue())) {
            return;
        }

        int style = this.circleStyleSetting.getValue().intValue();
        if (style < 0 || style >= STYLE_SEGMENTS.length) {
            style = 0;
        }
        int segments = (int) STYLE_SEGMENTS[style];
        float radius = 0.45f * (0.35f + 0.65f * this.progress);
        float width = STYLE_WIDTH[style] * radius;
        Matrix4f matrix = event.poseStack().last().pose();
        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, true);
        WorldRenderUtil.additiveBlend();
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2.0 / segments;
            double a1 = (i + 1) * Math.PI * 2.0 / segments;
            BreakProgress.segment(buffer, matrix, dx, dy, dz,
                    (float) Math.cos(a0), (float) Math.sin(a0), (float) Math.cos(a1), (float) Math.sin(a1),
                    radius - width, radius, this.textColor, alpha / 255.0f);
        }
        WorldRenderUtil.end();
    }

    private static void segment(BufferBuilder buffer, Matrix4f matrix,
                                double x, double y, double z,
                                float cos0, float sin0, float cos1, float sin1,
                                float inner, float outer, int color, float alpha) {
        WorldRenderUtil.vertex(buffer, matrix, x + inner * cos0, y, z + inner * sin0, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x + outer * cos0, y, z + outer * sin0, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x + outer * cos1, y, z + outer * sin1, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x + inner * cos1, y, z + inner * sin1, color, alpha);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.hasScreen || this.text.length() == 0) {
            return;
        }
        PoseStack poseStack = event.poseStack();
        GuiGraphics graphics = event.guiGraphics();
        float scale = Mth.clamp(0.02266667f * this.distance * 45.0f, 0.8f, 1.6f);
        poseStack.pushPose();
        poseStack.translate(this.screenX, this.screenY - 3.0f, 0.0f);
        poseStack.scale(scale, scale, scale);
        graphics.drawString(mc.font, this.text, -mc.font.width(this.text) / 2, 0, this.textColor, true);
        poseStack.popPose();
    }
}
