package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.EntityHurtEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.combat.KillAura;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Combat-target highlight, ported from NeoRaven's {@code TargetEspModule} and
 * {@code TargetEspRenderer}.
 *
 * <p><b>Target choice:</b> this client's KillAura already exposes its current victim as the public
 * static field {@link KillAura#target}, so that is used first whenever KillAura is enabled. When it
 * is not, the crosshair entity from {@code mc.hitResult} is used, matching upstream's fallback. This
 * keeps the overlay on the entity the player is actually attacking without a second target scan.</p>
 *
 * <p>All upstream settings and defaults (Style, Start, Size, Speed, Glow, Color) are kept; the
 * colour is now three RGB {@link NumberSetting}s. The Onyx bloom texture is unavailable, so shard
 * halos are camera-facing geometry with a soft alpha falloff. The {@code FuriahTargetAura} styles
 * in upstream indices 3+ are dropped because that dependency is not present. Crystals, Ring and Box
 * are drawn with static face/spike tables into at most two batches per frame.</p>
 */
public class TargetEsp extends Module {
    private static final float FADE_MS = 250.0f;
    private static final int SHARDS = 18;
    private static final float SHARD_STEP = 20.0f;
    private static final float[] FACE_SHADE = {1.0f, 0.8f, 0.6f, 0.9f, 0.7f, 0.5f, 0.4f, 0.6f};
    private static final float[][] CORNER = {
            {0.0f, 1.5f, 0.0f}, {0.0f, -1.5f, 0.0f},
            {1.0f, 0.0f, 0.0f}, {-1.0f, 0.0f, 0.0f},
            {0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, -1.0f}
    };
    private static final int[][] FACE = {
            {0, 4, 2}, {0, 3, 4}, {0, 5, 3}, {0, 2, 5},
            {1, 2, 4}, {1, 4, 3}, {1, 3, 5}, {1, 5, 2}
    };
    private static final int RING_POINTS = 96;
    private static final int TRAIL_SEGMENTS = 16;
    private static final float TRAIL_SPAN = 130.0f;
    private static final double[] COS = new double[RING_POINTS + 1];
    private static final double[] SIN = new double[RING_POINTS + 1];

    static {
        for (int i = 0; i <= RING_POINTS; i++) {
            double angle = 2.0 * Math.PI * i / RING_POINTS;
            COS[i] = Math.cos(angle);
            SIN[i] = Math.sin(angle);
        }
    }

    private final ModeSetting styleSetting = new ModeSetting("Style", "Crystals", "Ring", "Box")
            .withDefault("Crystals");
    private final ModeSetting startSetting = new ModeSetting("Start", "Feet", "Middle")
            .withDefault("Feet")
            .withVisibility(() -> !"Box".equals(this.styleSetting.getValue()));
    private final NumberSetting sizeSetting = new NumberSetting("Size", 1.0, 0.2, 3.0, 0.1);
    private final NumberSetting speedSetting = new NumberSetting("Speed", 4.0, 1.0, 12.0, 1.0,
            () -> !"Box".equals(this.styleSetting.getValue()));
    private final NumberSetting glowSetting = new NumberSetting("Glow", 75, 0, 100, 5,
            () -> !"Box".equals(this.styleSetting.getValue()));
    private final NumberSetting redSetting = new NumberSetting("Red", 85, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 207, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 255, 0, 255, 1);
    private final BooleanSetting throughWallsSetting = new BooleanSetting("Through walls", true);

    private final float[] strikeAge = new float[SHARDS];
    private final float[] strikeLife = new float[SHARDS];

    private LivingEntity shown;
    private LivingEntity struck;
    private float fade;
    private float ringPhase = -90.0f;
    private int lastHurtTime;
    private long frameNanos;
    private int strikeCursor;

    public TargetEsp() {
        super("TargetEsp", Category.RENDER);
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
        this.shown = null;
        this.struck = null;
        this.fade = 0.0f;
        this.ringPhase = -90.0f;
        this.lastHurtTime = 0;
        this.frameNanos = 0L;
        this.strikeCursor = 0;
        for (int i = 0; i < SHARDS; i++) {
            this.strikeAge[i] = 0.0f;
            this.strikeLife[i] = 0.0f;
        }
    }

    /** KillAura's live target first, then the vanilla crosshair entity. */
    private static LivingEntity resolveTarget() {
        if (KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled()
                && KillAura.target instanceof LivingEntity
                && ((LivingEntity) KillAura.target).isAlive()
                && ((LivingEntity) KillAura.target) != mc.player) {
            return (LivingEntity) KillAura.target;
        }
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hit = ((EntityHitResult) mc.hitResult).getEntity();
            if (hit instanceof LivingEntity && hit != mc.player && hit.isAlive()) {
                return (LivingEntity) hit;
            }
        }
        return null;
    }

    @EventTarget
    public void onHurt(EntityHurtEvent event) {
        if (this.shown != null && event.entity() == this.shown) {
            this.doStrike(3, 1.0f);
        }
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null) {
            this.reset();
            return;
        }
        long now = System.nanoTime();
        float delta = this.frameNanos == 0L ? 16.0f
                : Math.min(100.0f, (now - this.frameNanos) / 1000000.0f);
        this.frameNanos = now;
        this.tickStrikes(delta);

        this.updateFade(resolveTarget(), delta);
        LivingEntity target = this.shown;
        if (target == null || this.fade <= 0.0f || !target.isAlive() || target.isDeadOrDying()) {
            return;
        }
        Matrix4f matrix = event.poseStack().last().pose();
        if (target != this.struck) {
            this.struck = target;
            this.lastHurtTime = target.hurtTime;
        } else if (target.hurtTime > this.lastHurtTime) {
            // Hits that bypass EntityHurtEvent still animate the shards.
            this.doStrike(3, 1.0f);
        }
        this.lastHurtTime = target.hurtTime;

        String style = this.styleSetting.getValue();
        if ("Box".equals(style)) {
            this.renderBox(target, event.partialTick(), matrix);
            return;
        }
        Vec3 camera = WorldRenderUtil.camera();
        Vec3 pos = WorldRenderUtil.interpolated(target, event.partialTick()).subtract(camera);
        float height = target.getBbHeight();
        float scale = this.sizeSetting.getValue().floatValue();
        float glow = this.glowSetting.getValue().floatValue() / 100.0f;
        int rgb = WorldRenderUtil.rgba(this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue(), 255);
        boolean walls = this.throughWallsSetting.getValue();

        if ("Ring".equals(style)) {
            float speed = this.speedSetting.getValue().floatValue() / 4.0f;
            this.ringPhase += delta * 0.001f * 120.0f * speed;
            if (this.ringPhase > 100000.0f) {
                this.ringPhase -= 100000.0f;
            }
            double offset = "Middle".equals(this.startSetting.getValue()) ? 0.0 : -90.0;
            BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, walls);
            WorldRenderUtil.additiveBlend();
            this.renderRing(buffer, matrix, pos.x, pos.y, pos.z, height,
                    this.ringPhase + offset, scale, glow, rgb);
            WorldRenderUtil.end();
            return;
        }

        double startOffset = "Middle".equals(this.startSetting.getValue()) ? height * 0.025 : 0.0;
        double seconds = now / 1.0E9;
        double rightX;
        double rightY;
        double rightZ;
        double upX;
        double upY;
        double upZ;
        float yaw = (float) Math.toRadians(mc.player.getYRot());
        float pitch = (float) Math.toRadians(mc.player.getXRot());
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        rightX = -look.z;
        rightZ = look.x;
        double rightLength = Math.sqrt(rightX * rightX + rightZ * rightZ);
        if (rightLength < 1.0E-4) {
            rightX = 1.0;
            rightZ = 0.0;
        } else {
            rightX /= rightLength;
            rightZ /= rightLength;
        }
        upX = -rightZ * look.y;
        upY = rightZ * look.x - rightX * look.z;
        upZ = rightX * look.y;
        double upLength = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        if (upLength > 1.0E-4) {
            upX /= upLength;
            upY /= upLength;
            upZ /= upLength;
        }

        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.TRIANGLES, walls);
        WorldRenderUtil.additiveBlend();
        this.renderShards(buffer, matrix, pos.x, pos.y, pos.z, pos.y + height * 0.5,
                height, seconds, scale, rgb, startOffset);
        WorldRenderUtil.end();

        if (glow > 0.001f) {
            buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, walls);
            WorldRenderUtil.additiveBlend();
            this.renderGlow(buffer, matrix, pos.x, pos.y, pos.z, pos.y + height * 0.5,
                    height, seconds, scale, glow, rgb, startOffset, rightX, rightY, rightZ, upX, upY, upZ);
            WorldRenderUtil.end();
        }
    }

    private void updateFade(LivingEntity target, float delta) {
        float step = Math.max(0.0f, delta) / FADE_MS;
        if (target == this.shown) {
            this.fade = this.shown == null
                    ? Math.max(0.0f, this.fade - step)
                    : Math.min(1.0f, this.fade + step);
        } else if (this.shown != null && this.fade > 0.0f) {
            this.fade = Math.max(0.0f, this.fade - step);
        } else {
            this.shown = target;
            this.fade = 0.0f;
            this.lastHurtTime = target == null ? 0 : target.hurtTime;
        }
    }

    private void tickStrikes(float delta) {
        for (int i = 0; i < SHARDS; i++) {
            if (this.strikeLife[i] <= 0.0f) {
                continue;
            }
            this.strikeAge[i] += Math.max(0.0f, delta);
            if (this.strikeAge[i] >= this.strikeLife[i]) {
                this.strikeLife[i] = 0.0f;
            }
        }
    }

    private void doStrike(int count, float speed) {
        float inverse = 1.0f / Math.max(speed, 0.1f);
        int shards = Math.max(1, Math.min(SHARDS, count));
        int gap = Math.max(1, SHARDS / shards);
        for (int i = 0; i < shards; i++) {
            int slot = (this.strikeCursor + i * gap) % SHARDS;
            this.strikeAge[slot] = -i * 80.0f * inverse;
            this.strikeLife[slot] = 260.0f * inverse;
        }
        this.strikeCursor = (this.strikeCursor + 7) % SHARDS;
    }

    private float strike(int index) {
        if (this.strikeLife[index] <= 0.0f) {
            return 0.0f;
        }
        float progress = this.strikeAge[index] / this.strikeLife[index];
        if (progress <= 0.0f || progress >= 1.0f) {
            return 0.0f;
        }
        return progress < 0.3f ? TargetEsp.smooth(progress / 0.3f)
                : 1.0f - TargetEsp.smooth((progress - 0.3f) / 0.7f);
    }

    private static float smooth(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    /** The eight-faced shards, using the upstream corner and face-shade tables. */
    private void renderShards(BufferBuilder buffer, Matrix4f matrix,
                              double x, double y, double z, double centerY, float height,
                              double seconds, float scale, int rgb, double startOffset) {
        float spin = (float) (seconds * 120.0);
        float alpha = this.fade * 0.92f;
        double radius = 1.35 * Math.max(0.6, height * 0.5) * scale;
        for (int i = 0; i < SHARDS; i++) {
            float strike = this.strike(i);
            double angle = Math.toRadians(i * SHARD_STEP + spin);
            double px = x + Math.cos(angle) * radius;
            double pz = z + Math.sin(angle) * radius;
            double py = y + startOffset + 0.1 + height * 0.95
                    * Math.abs(Math.sin(Math.toRadians(i * SHARD_STEP)));
            double pull = strike * 0.55;
            double cx = px + (x - px) * pull;
            double cy = py + (centerY - py) * pull;
            double cz = pz + (z - pz) * pull;

            double ux = x - px, uy = centerY - py, uz = z - pz;
            double ul = Math.max(1.0E-4, Math.sqrt(ux * ux + uy * uy + uz * uz));
            ux /= ul; uy /= ul; uz /= ul;
            double ax = Math.abs(uy) < 0.9 ? 0.0 : 1.0;
            double ay = Math.abs(uy) < 0.9 ? 1.0 : 0.0;
            double rx = uy * 0.0 - uz * ay;
            double ry = uz * ax - ux * 0.0;
            double rz = ux * ay - uy * ax;
            double rl = Math.max(1.0E-4, Math.sqrt(rx * rx + ry * ry + rz * rz));
            rx /= rl; ry /= rl; rz /= rl;
            double fx = ry * uz - rz * uy;
            double fy = rz * ux - rx * uz;
            double fz = rx * uy - ry * ux;

            double shardScale = 0.1 * scale * (1.0 + strike * 0.5);
            int base = strike <= 0.0f ? rgb : WorldRenderUtil.mix(rgb, 0xFFFFFF, strike * 0.45f);
            for (int face = 0; face < FACE.length; face++) {
                float shade = FACE_SHADE[face] * (1.0f + strike * 0.6f);
                int faceColor = TargetEsp.shade(base, shade);
                int[] corners = FACE[face];
                for (int corner = 0; corner < 3; corner++) {
                    float[] local = CORNER[corners[corner]];
                    WorldRenderUtil.vertex(buffer, matrix,
                            cx + (rx * local[0] + ux * local[1] + fx * local[2]) * shardScale,
                            cy + (ry * local[0] + uy * local[1] + fy * local[2]) * shardScale,
                            cz + (rz * local[0] + uz * local[1] + fz * local[2]) * shardScale,
                            faceColor, alpha);
                }
            }
        }
    }

    /** Soft camera-facing glow quads in place of the upstream bloom sprite. */
    private void renderGlow(BufferBuilder buffer, Matrix4f matrix,
                            double x, double y, double z, double centerY, float height,
                            double seconds, float scale, float glow, int rgb, double startOffset,
                            double rightX, double rightY, double rightZ,
                            double upX, double upY, double upZ) {
        float spin = (float) (seconds * 120.0);
        float alpha = this.fade * 0.16f * glow;
        double radius = 1.35 * Math.max(0.6, height * 0.5) * scale;
        for (int i = 0; i < SHARDS; i++) {
            float strike = this.strike(i);
            double angle = Math.toRadians(i * SHARD_STEP + spin);
            double px = x + Math.cos(angle) * radius;
            double pz = z + Math.sin(angle) * radius;
            double py = y + startOffset + 0.1 + height * 0.95
                    * Math.abs(Math.sin(Math.toRadians(i * SHARD_STEP)));
            double pull = strike * 0.55;
            double cx = px + (x - px) * pull;
            double cy = py + (centerY - py) * pull;
            double cz = pz + (z - pz) * pull;
            float grow = 1.0f + strike * 0.8f;
            this.billboard(buffer, matrix, cx, cy, cz, 0.55f * scale * grow,
                    rightX, rightY, rightZ, upX, upY, upZ, rgb, alpha * (1.0f + strike * 0.7f));
            this.billboard(buffer, matrix, cx, cy, cz, 0.24f * scale * grow,
                    rightX, rightY, rightZ, upX, upY, upZ, rgb, alpha * 0.6f);
        }
    }

    private static void billboard(BufferBuilder buffer, Matrix4f matrix,
                                  double x, double y, double z, float half,
                                  double rx, double ry, double rz,
                                  double ux, double uy, double uz, int color, float alpha) {
        WorldRenderUtil.vertex(buffer, matrix,
                x - rx * half - ux * half, y - ry * half - uy * half, z - rz * half - uz * half,
                color, 0.0f);
        WorldRenderUtil.vertex(buffer, matrix,
                x - rx * half + ux * half, y - ry * half + uy * half, z - rz * half + uz * half,
                color, alpha);
        WorldRenderUtil.vertex(buffer, matrix,
                x + rx * half + ux * half, y + ry * half + uy * half, z + rz * half + uz * half,
                color, 0.0f);
        WorldRenderUtil.vertex(buffer, matrix,
                x + rx * half - ux * half, y + ry * half - uy * half, z + rz * half - uz * half,
                color, alpha);
    }

    /** Ring style: hoop, analytic fading curtain and two soft halo bands. */
    private void renderRing(BufferBuilder buffer, Matrix4f matrix,
                            double x, double y, double z, float height, float phase,
                            float scale, float glow, int rgb) {
        double radius = (Math.max(0.6, height * 0.45) * 0.86 + 0.18) * scale * (0.72 + 0.28 * this.fade);
        double topWave = (Math.sin(Math.toRadians(phase)) + 1.0) * 0.5;
        double ringY = y + height * topWave;
        float halo = this.fade * glow * 0.16f;

        for (int segment = 0; segment < TRAIL_SEGMENTS; segment++) {
            float topPhase = phase - TRAIL_SPAN * segment / TRAIL_SEGMENTS;
            float bottomPhase = phase - TRAIL_SPAN * (segment + 1) / TRAIL_SEGMENTS;
            float topAlpha = TargetEsp.fadeAt(segment) * this.fade * (0.28f + 0.32f * glow);
            float bottomAlpha = TargetEsp.fadeAt(segment + 1) * this.fade * (0.28f + 0.32f * glow);
            double topY = y + height * ((Math.sin(Math.toRadians(topPhase)) + 1.0) * 0.5);
            double bottomY = y + height * ((Math.sin(Math.toRadians(bottomPhase)) + 1.0) * 0.5);
            for (int i = 0; i < RING_POINTS; i++) {
                double ca = COS[i], sa = SIN[i];
                double cb = COS[i + 1], sb = SIN[i + 1];
                WorldRenderUtil.vertex(buffer, matrix, x + ca * radius, topY, z + sa * radius, rgb, topAlpha);
                WorldRenderUtil.vertex(buffer, matrix, x + ca * radius, bottomY, z + sa * radius, rgb, bottomAlpha);
                WorldRenderUtil.vertex(buffer, matrix, x + cb * radius, bottomY, z + sb * radius, rgb, bottomAlpha);
                WorldRenderUtil.vertex(buffer, matrix, x + cb * radius, topY, z + sb * radius, rgb, topAlpha);
            }
        }

        for (int layer = 0; layer < 2; layer++) {
            double inner = layer == 0 ? 0.94 : 0.74;
            double outer = layer == 0 ? 1.06 : 1.26;
            float alpha = Math.min(1.0f, halo * (layer == 0 ? 1.0f : 0.45f));
            for (int i = 0; i < RING_POINTS; i++) {
                double ca = COS[i], sa = SIN[i];
                double cb = COS[i + 1], sb = SIN[i + 1];
                WorldRenderUtil.vertex(buffer, matrix, x + ca * radius * inner, ringY, z + sa * radius * inner,
                        rgb, alpha);
                WorldRenderUtil.vertex(buffer, matrix, x + ca * radius * outer, ringY, z + sa * radius * outer,
                        rgb, 0.0f);
                WorldRenderUtil.vertex(buffer, matrix, x + cb * radius * outer, ringY, z + sb * radius * outer,
                        rgb, 0.0f);
                WorldRenderUtil.vertex(buffer, matrix, x + cb * radius * inner, ringY, z + sb * radius * inner,
                        rgb, alpha);
            }
        }

        double hoopInner = radius * 0.97;
        double hoopOuter = radius * 1.03;
        float hoopAlpha = this.fade * 0.92f;
        for (int i = 0; i < RING_POINTS; i++) {
            double ca = COS[i], sa = SIN[i];
            double cb = COS[i + 1], sb = SIN[i + 1];
            WorldRenderUtil.vertex(buffer, matrix, x + ca * hoopInner, ringY, z + sa * hoopInner, rgb, hoopAlpha);
            WorldRenderUtil.vertex(buffer, matrix, x + ca * hoopOuter, ringY, z + sa * hoopOuter, rgb, hoopAlpha * 0.7f);
            WorldRenderUtil.vertex(buffer, matrix, x + cb * hoopOuter, ringY, z + sb * hoopOuter, rgb, hoopAlpha * 0.7f);
            WorldRenderUtil.vertex(buffer, matrix, x + cb * hoopInner, ringY, z + sb * hoopInner, rgb, hoopAlpha);
        }
    }

    private static float fadeAt(int segment) {
        if (segment >= TRAIL_SEGMENTS) {
            return 0.0f;
        }
        float t = 1.0f - segment / (float) TRAIL_SEGMENTS;
        return t * t * t * 0.55f;
    }

    private static int shade(int rgb, float amount) {
        int r = (int) Math.min(255.0f, ((rgb >> 16) & 255) * amount);
        int g = (int) Math.min(255.0f, ((rgb >> 8) & 255) * amount);
        int b = (int) Math.min(255.0f, (rgb & 255) * amount);
        return WorldRenderUtil.rgba(r, g, b, 255);
    }

    /** Box style: translucent fill plus bright edge strips, both in the same batch. */
    private void renderBox(LivingEntity target, float partial, Matrix4f matrix) {
        Vec3 camera = WorldRenderUtil.camera();
        Vec3 pos = WorldRenderUtil.interpolated(target, partial).subtract(camera);
        double padding = 0.05 * this.sizeSetting.getValue().doubleValue();
        AABB bounds = target.getBoundingBox();
        double minX = pos.x + bounds.minX - target.getX() - padding;
        double minY = pos.y + bounds.minY - target.getY() - padding;
        double minZ = pos.z + bounds.minZ - target.getZ() - padding;
        double maxX = pos.x + bounds.maxX - target.getX() + padding;
        double maxY = pos.y + bounds.maxY - target.getY() + padding;
        double maxZ = pos.z + bounds.maxZ - target.getZ() + padding;
        int rgb = WorldRenderUtil.rgba(this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue(), 255);
        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, this.throughWallsSetting.getValue());
        TargetEsp.box(buffer, matrix, minX, minY, minZ, maxX, maxY, maxZ, rgb, this.fade * 0.16f);
        TargetEsp.edges(buffer, matrix, minX, minY, minZ, maxX, maxY, maxZ, rgb, this.fade * 0.75f);
        WorldRenderUtil.end();
    }

    private static void box(BufferBuilder buffer, Matrix4f matrix,
                            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                            int color, float alpha) {
        TargetEsp.quad(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, color, alpha);
        TargetEsp.quad(buffer, matrix, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ, color, alpha);
        TargetEsp.quad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, color, alpha);
        TargetEsp.quad(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, color, alpha);
        TargetEsp.quad(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, color, alpha);
        TargetEsp.quad(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, color, alpha);
    }

    private static void edges(BufferBuilder buffer, Matrix4f matrix,
                              double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                              int color, float alpha) {
        double t = 0.015;
        TargetEsp.box(buffer, matrix, minX, minY, minZ, maxX, minY + t, minZ + t, color, alpha);
        TargetEsp.box(buffer, matrix, minX, minY, maxZ - t, maxX, minY + t, maxZ, color, alpha);
        TargetEsp.box(buffer, matrix, minX, maxY - t, minZ, maxX, maxY, minZ + t, color, alpha);
        TargetEsp.box(buffer, matrix, minX, maxY - t, maxZ - t, maxX, maxY, maxZ, color, alpha);
        TargetEsp.box(buffer, matrix, minX, minY, minZ, minX + t, maxY, maxZ, color, alpha);
        TargetEsp.box(buffer, matrix, maxX - t, minY, minZ, maxX, maxY, maxZ, color, alpha);
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
}
