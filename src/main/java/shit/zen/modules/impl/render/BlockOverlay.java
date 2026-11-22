package shit.zen.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.misc.ClientFieldAccess;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Custom block selection overlay ported from NeoRaven's {@code BlockOverlayModule}.
 *
 * <p>Upstream used the Forge draw-block-highlight event to hide the vanilla outline and an Onyx
 * shader for the "glint" theme. Neither hook exists here, so the "Hide vanilla outline" setting was
 * dropped and the glint theme is drawn as two nested, additively blended layers tinted with the
 * secondary colour. "Only on bed break" is interpreted as "only draw when the targeted block is a
 * bed", since NeoRaven's BedAura module is not part of this client.</p>
 *
 * <p>State (target, bounds, fades) lives in reusable fields; the render pass emits one fill batch
 * and one outline batch per frame.</p>
 */
public class BlockOverlay extends Module {
    private final ModeSetting themeSetting = new ModeSetting("Theme", "Outline", "Outline + fill", "Outline + glint")
            .withDefault("Outline + fill");
    private final NumberSetting redSetting = new NumberSetting("Red", 100, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 175, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 255, 0, 255, 1);
    private final NumberSetting secondRedSetting = new NumberSetting("Second Red", 148, 0, 255, 1,
            () -> "Outline + glint".equals(this.themeSetting.getValue()));
    private final NumberSetting secondGreenSetting = new NumberSetting("Second Green", 86, 0, 255, 1,
            () -> "Outline + glint".equals(this.themeSetting.getValue()));
    private final NumberSetting secondBlueSetting = new NumberSetting("Second Blue", 255, 0, 255, 1,
            () -> "Outline + glint".equals(this.themeSetting.getValue()));
    private final NumberSetting lineWidthSetting = new NumberSetting("Line width", 1.5, 0.5, 5.0, 0.5);
    private final NumberSetting outlineOpacitySetting = new NumberSetting("Outline opacity", 90, 5, 100, 5);
    private final NumberSetting fillOpacitySetting = new NumberSetting("Fill opacity", 22, 1, 80, 1);
    private final NumberSetting paddingSetting = new NumberSetting("Padding", 0.6, 0.0, 5.0, 0.1);
    private final NumberSetting glintSpeedSetting = new NumberSetting("Glint speed", 1.0, 0.1, 4.0, 0.1,
            () -> "Outline + glint".equals(this.themeSetting.getValue()));
    private final NumberSetting glintScaleSetting = new NumberSetting("Glint scale", 1.5, 0.5, 4.0, 0.1,
            () -> "Outline + glint".equals(this.themeSetting.getValue()));
    private final NumberSetting glintGlowSetting = new NumberSetting("Glint glow", 60, 0, 100, 5,
            () -> "Outline + glint".equals(this.themeSetting.getValue()));
    private final NumberSetting appearSetting = new NumberSetting("Animation: Appear", 140, 0, 600, 10);
    private final NumberSetting moveSetting = new NumberSetting("Animation: Move", 110, 0, 600, 10);
    private final NumberSetting fadeSetting = new NumberSetting("Animation: Fade", 180, 0, 600, 10);
    private final ModeSetting appearanceSetting = new ModeSetting("Animation: Appearance", "Fade", "Pop", "Grow")
            .withDefault("Pop");
    private final BooleanSetting breakProgressSetting = new BooleanSetting("Break progress", true);
    private final ModeSetting progressModeSetting = new ModeSetting("Break progress: Mode", "Fill", "Grow from centre")
            .withDefault("Fill")
            .withVisibility(() -> this.breakProgressSetting.getValue());
    private final ModeSetting directionSetting = new ModeSetting("Break progress: Direction", "Top to bottom", "Bottom to top")
            .withDefault("Top to bottom")
            .withVisibility(() -> this.breakProgressSetting.getValue());
    private final NumberSetting progressOpacitySetting = new NumberSetting("Progress opacity", 45, 1, 100, 1,
            () -> this.breakProgressSetting.getValue());
    private final BooleanSetting onlyBedSetting = new BooleanSetting("Only on bed break", false);
    private final BooleanSetting throughWallsSetting = new BooleanSetting("Through walls", true);

    private final float[] bounds = new float[6];
    private BlockPos target;
    private BlockPos candidate;
    private int anchorX;
    private int anchorY;
    private int anchorZ;
    private boolean visible;
    private boolean freshTarget;
    private long lastNanos;
    private float debounce;
    private float alpha;
    private float scale = 1.0f;
    private float progress;

    public BlockOverlay() {
        super("BlockOverlay", Category.RENDER);
    }

    @Override
    protected void onEnable() {
        this.clear();
    }

    @Override
    protected void onDisable() {
        this.clear();
    }

    private void clear() {
        this.target = null;
        this.candidate = null;
        this.visible = false;
        this.lastNanos = 0L;
        this.debounce = 0.0f;
        this.alpha = 0.0f;
        this.scale = 1.0f;
        this.progress = 0.0f;
    }

    private float initialScale() {
        String appearance = this.appearanceSetting.getValue();
        if ("Pop".equals(appearance)) {
            return 0.62f;
        }
        return "Grow".equals(appearance) ? 0.0f : 1.0f;
    }

    private BlockPos selected() {
        if (mc.level == null || mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
        if (this.onlyBedSetting.getValue() && !(mc.level.getBlockState(pos).getBlock() instanceof BedBlock)) {
            return null;
        }
        return pos;
    }

    private boolean valid(BlockPos pos) {
        return pos != null && mc.level != null && !mc.level.getBlockState(pos).isAir();
    }

    private float mining() {
        if (!this.breakProgressSetting.getValue() || mc.player == null || mc.gameMode == null
                || !mc.gameMode.isDestroying() || ClientFieldAccess.destroyProgress(mc.gameMode) <= 0.0f) {
            return 0.0f;
        }
        return Mth.clamp(ClientFieldAccess.destroyProgress(mc.gameMode), 0.0f, 1.0f);
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null || mc.cameraEntity == null) {
            this.clear();
            return;
        }
        long now = System.nanoTime();
        float dt = this.lastNanos == 0L ? 0.0f : (float) Math.min(100.0, (now - this.lastNanos) / 1.0E6);
        this.lastNanos = now;

        BlockPos selected = this.selected();
        if (this.target != null && !this.target.equals(selected)) {
            if (!selected.equals(this.candidate)) {
                this.candidate = selected;
                this.debounce = 0.0f;
            }
            this.debounce += dt;
            if (this.debounce < 60.0f) {
                selected = this.target;
            }
        } else {
            this.candidate = null;
            this.debounce = 0.0f;
        }

        boolean active = this.valid(selected);
        if (active) {
            this.freshTarget = this.target == null;
            double anchorDx = selected.getX() - this.anchorX;
            double anchorDy = selected.getY() - this.anchorY;
            double anchorDz = selected.getZ() - this.anchorZ;
            boolean distant = !this.freshTarget && anchorDx * anchorDx + anchorDy * anchorDy + anchorDz * anchorDz > 16.0;
            if (anchorDx != 0.0 || anchorDy != 0.0 || anchorDz != 0.0) {
                this.progress = 0.0f;
            }
            this.target = selected;
            if (this.freshTarget || distant) {
                this.anchorX = selected.getX();
                this.anchorY = selected.getY();
                this.anchorZ = selected.getZ();
            }
            double[] coords = {selected.getX(), selected.getY(), selected.getZ(),
                    selected.getX() + 1, selected.getY() + 1, selected.getZ() + 1};
            for (int i = 0; i < 6; i++) {
                double origin = i % 3 == 0 ? this.anchorX : (i % 3 == 1 ? this.anchorY : this.anchorZ);
                float local = (float) (coords[i] - origin);
                if (this.freshTarget || distant) {
                    this.bounds[i] = local;
                } else {
                    this.bounds[i] = Mth.lerp(Math.min(1.0f, dt / Math.max(1.0f, this.moveSetting.getValue().floatValue())),
                            this.bounds[i], local);
                }
            }
            if (this.freshTarget) {
                this.alpha = 0.0f;
                this.scale = this.initialScale();
            }
            float appear = Math.min(1.0f, dt / Math.max(1.0f, this.appearSetting.getValue().floatValue()));
            this.alpha = Mth.lerp(appear, this.alpha, 1.0f);
            this.scale = Mth.lerp(appear, this.scale, 1.0f);
            this.progress = Mth.lerp(Math.min(1.0f, dt / 70.0f), this.progress, this.mining());
            this.visible = true;
        } else if (this.visible) {
            this.visible = false;
            float fade = Math.min(1.0f, dt / Math.max(1.0f, this.fadeSetting.getValue().floatValue()));
            this.alpha = Mth.lerp(fade, this.alpha, 0.0f);
            this.scale = Mth.lerp(fade, this.scale, this.initialScale());
            this.progress = 0.0f;
        }

        if (this.target == null || this.alpha <= 0.004f) {
            if (!this.visible) {
                this.target = null;
            }
            return;
        }

        double minX = this.anchorX + this.bounds[0];
        double minY = this.anchorY + this.bounds[1];
        double minZ = this.anchorZ + this.bounds[2];
        double maxX = this.anchorX + this.bounds[3];
        double maxY = this.anchorY + this.bounds[4];
        double maxZ = this.anchorZ + this.bounds[5];
        double centerX = (minX + maxX) * 0.5;
        double centerY = (minY + maxY) * 0.5;
        double centerZ = (minZ + maxZ) * 0.5;
        double halfScale = this.scale;
        minX = centerX + (minX - centerX) * halfScale;
        minY = centerY + (minY - centerY) * halfScale;
        minZ = centerZ + (minZ - centerZ) * halfScale;
        maxX = centerX + (maxX - centerX) * halfScale;
        maxY = centerY + (maxY - centerY) * halfScale;
        maxZ = centerZ + (maxZ - centerZ) * halfScale;

        double padding = this.paddingSetting.getValue().doubleValue() / 100.0;
        Vec3 camera = WorldRenderUtil.camera();
        minX = minX - padding - camera.x;
        minY = minY - padding - camera.y;
        minZ = minZ - padding - camera.z;
        maxX = maxX + padding - camera.x;
        maxY = maxY + padding - camera.y;
        maxZ = maxZ + padding - camera.z;

        double fillMinX = minX, fillMinY = minY, fillMinZ = minZ;
        double fillMaxX = maxX, fillMaxY = maxY, fillMaxZ = maxZ;
        boolean mining = this.breakProgressSetting.getValue() && this.progress > 0.001f;
        if (mining) {
            if ("Grow from centre".equals(this.progressModeSetting.getValue())) {
                double cx = (minX + maxX) * 0.5, cy = (minY + maxY) * 0.5, cz = (minZ + maxZ) * 0.5;
                double s = this.progress;
                fillMinX = cx + (minX - cx) * s;
                fillMinY = cy + (minY - cy) * s;
                fillMinZ = cz + (minZ - cz) * s;
                fillMaxX = cx + (maxX - cx) * s;
                fillMaxY = cy + (maxY - cy) * s;
                fillMaxZ = cz + (maxZ - cz) * s;
            } else {
                double height = (maxY - minY) * this.progress;
                if ("Bottom to top".equals(this.directionSetting.getValue())) {
                    fillMinY = minY;
                    fillMaxY = minY + height;
                } else {
                    fillMinY = maxY - height;
                    fillMaxY = maxY;
                }
            }
        }

        PoseStack poseStack = event.poseStack();
        Matrix4f matrix = poseStack.last().pose();
        int primary = WorldRenderUtil.rgba(this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue(), 255);
        boolean glint = "Outline + glint".equals(this.themeSetting.getValue());
        boolean fill = glint || "Outline + fill".equals(this.themeSetting.getValue()) || mining;

        if (fill) {
            BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, this.throughWallsSetting.getValue());
            if (glint) {
                WorldRenderUtil.additiveBlend();
            }
            if (glint) {
                int secondary = WorldRenderUtil.rgba(this.secondRedSetting.getValue().intValue(),
                        this.secondGreenSetting.getValue().intValue(), this.secondBlueSetting.getValue().intValue(), 255);
                float pulse = (float) (Math.sin(System.nanoTime() * 0.000001 * this.glintSpeedSetting.getValue().doubleValue()) * 0.5 + 0.5);
                float glow = this.glintGlowSetting.getValue().floatValue() / 100.0f;
                float inner = this.glintScaleSetting.getValue().floatValue();
                BlockOverlay.box(buffer, matrix, fillMinX, fillMinY, fillMinZ, fillMaxX, fillMaxY, fillMaxZ,
                        WorldRenderUtil.mix(primary, secondary, pulse), this.alpha * this.fillOpacitySetting.getValue().floatValue() / 100.0f);
                if (glow > 0.0f && inner > 0.0f) {
                    double ix = (fillMinX + fillMaxX) * 0.5, iy = (fillMinY + fillMaxY) * 0.5, iz = (fillMinZ + fillMaxZ) * 0.5;
                    double shrink = 0.5 / inner;
                    BlockOverlay.box(buffer, matrix,
                            ix + (fillMinX - ix) * shrink, iy + (fillMinY - iy) * shrink, iz + (fillMinZ - iz) * shrink,
                            ix + (fillMaxX - ix) * shrink, iy + (fillMaxY - iy) * shrink, iz + (fillMaxZ - iz) * shrink,
                            secondary, this.alpha * glow);
                }
            } else {
                float opacity = mining ? this.progressOpacitySetting.getValue().floatValue()
                        : this.fillOpacitySetting.getValue().floatValue();
                BlockOverlay.box(buffer, matrix, fillMinX, fillMinY, fillMinZ, fillMaxX, fillMaxY, fillMaxZ,
                        primary, this.alpha * opacity / 100.0f);
            }
            WorldRenderUtil.end();
        }

        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.LINES, this.throughWallsSetting.getValue());
        RenderSystem.lineWidth(this.lineWidthSetting.getValue().floatValue());
        BlockOverlay.outline(buffer, matrix, minX, minY, minZ, maxX, maxY, maxZ,
                primary, this.alpha * this.outlineOpacitySetting.getValue().floatValue() / 100.0f);
        WorldRenderUtil.end();
        RenderSystem.lineWidth(1.0f);
    }

    private static void box(BufferBuilder buffer, Matrix4f matrix,
                            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                            int color, float alpha) {
        BlockOverlay.quad(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, color, alpha);
        BlockOverlay.quad(buffer, matrix, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ, color, alpha);
        BlockOverlay.quad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, color, alpha);
        BlockOverlay.quad(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, color, alpha);
        BlockOverlay.quad(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, color, alpha);
        BlockOverlay.quad(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, color, alpha);
    }

    private static void quad(BufferBuilder buffer, Matrix4f matrix,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             int color, float alpha) {
        WorldRenderUtil.vertex(buffer, matrix, x1, y1, z1, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x2, y2, z2, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x3, y3, z3, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x4, y4, z4, color, alpha);
    }

    private static void outline(BufferBuilder buffer, Matrix4f matrix,
                                double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                int color, float alpha) {
        BlockOverlay.line(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, color, alpha);
        BlockOverlay.line(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, color, alpha);
        BlockOverlay.line(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, color, alpha);
        BlockOverlay.line(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, color, alpha);
        BlockOverlay.line(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, color, alpha);
        BlockOverlay.line(buffer, matrix, maxX, maxY, minZ, maxX, maxY, maxZ, color, alpha);
        BlockOverlay.line(buffer, matrix, maxX, maxY, maxZ, minX, maxY, maxZ, color, alpha);
        BlockOverlay.line(buffer, matrix, minX, maxY, maxZ, minX, maxY, minZ, color, alpha);
        BlockOverlay.line(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, color, alpha);
        BlockOverlay.line(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, color, alpha);
        BlockOverlay.line(buffer, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, color, alpha);
        BlockOverlay.line(buffer, matrix, minX, minY, maxZ, minX, maxY, maxZ, color, alpha);
    }

    private static void line(BufferBuilder buffer, Matrix4f matrix,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             int color, float alpha) {
        WorldRenderUtil.vertex(buffer, matrix, x1, y1, z1, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x2, y2, z2, color, alpha);
    }
}
