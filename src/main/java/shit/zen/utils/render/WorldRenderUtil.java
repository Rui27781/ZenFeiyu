package shit.zen.utils.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import shit.zen.ClientBase;

/**
 * Shared plumbing for the world-space visual modules ported from NeoRaven.
 *
 * <p>NeoRaven drew with the 1.8.9 fixed-function pipeline ({@code GL11.glBegin}). On 1.20.1 the
 * equivalent is a {@link BufferBuilder} fed with a {@code PoseStack} matrix, so this helper keeps
 * the state setup, camera interpolation and colour packing in one place. Every method is static and
 * allocation free; modules reuse the single {@link Tesselator} buffer.</p>
 */
public final class WorldRenderUtil extends ClientBase {
    private WorldRenderUtil() {
    }

    /** Camera position for the current frame. */
    public static Vec3 camera() {
        return mc.gameRenderer.getMainCamera().getPosition();
    }

    /** Entity position interpolated between the previous and current tick. */
    public static Vec3 interpolated(Entity entity, float partial) {
        return new Vec3(
                entity.xo + (entity.getX() - entity.xo) * partial,
                entity.yo + (entity.getY() - entity.yo) * partial,
                entity.zo + (entity.getZ() - entity.zo) * partial);
    }

    /** Body yaw, interpolated; matches the 1.8.9 {@code renderYawOffset} used by NeoRaven. */
    public static float bodyYaw(Entity entity, float partial) {
        if (!(entity instanceof LivingEntity)) {
            return entity.getYRot();
        }
        LivingEntity living = (LivingEntity) entity;
        return Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot);
    }

    /** Head yaw, interpolated; matches 1.8.9 {@code rotationYawHead}. */
    public static float headYaw(Entity entity, float partial) {
        if (!(entity instanceof LivingEntity)) {
            return entity.getYRot();
        }
        LivingEntity living = (LivingEntity) entity;
        return Mth.rotLerp(partial, living.yHeadRotO, living.yHeadRot);
    }

    /** Head pitch, interpolated. */
    public static float pitch(Entity entity, float partial) {
        return Mth.lerp(partial, entity.xRotO, entity.getXRot());
    }

    /**
     * Starts a world-space batch.
     *
     * @param mode         primitive mode, usually {@code QUADS}, {@code TRIANGLES} or {@code DEBUG_LINES}
     * @param throughWalls when true the depth test is disabled, so the geometry draws over terrain
     */
    public static BufferBuilder begin(VertexFormat.Mode mode, boolean throughWalls) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (throughWalls) {
            RenderSystem.disableDepthTest();
        } else {
            RenderSystem.enableDepthTest();
        }
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(mode, DefaultVertexFormat.POSITION_COLOR);
        return buffer;
    }

    /** Uploads the batch started by {@link #begin} and restores the shared GL state. */
    public static void end() {
        BufferUploader.drawWithShader(Tesselator.getInstance().getBuilder().end());
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** Switches the active batch to additive blending, for glow layers. */
    public static void additiveBlend() {
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
    }

    /** Restores normal alpha blending after {@link #additiveBlend()}. */
    public static void normalBlend() {
        RenderSystem.defaultBlendFunc();
    }

    /** Channel-wise colour mix, {@code t} clamped to {@code [0, 1]}. */
    public static int mix(int from, int to, float t) {
        if (t <= 0.0f) {
            return from;
        }
        if (t >= 1.0f) {
            return to;
        }
        int a = (int) (((from >> 24) & 0xFF) + (((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** Packs RGB channels plus an explicit alpha. */
    public static int rgba(int red, int green, int blue, int alpha) {
        return (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | (blue & 0xFF);
    }

    /** Replaces the alpha channel of a packed colour. */
    public static int withAlpha(int color, int alpha) {
        return (alpha & 0xFF) << 24 | color & 0xFFFFFF;
    }

    /** Emits one vertex with a packed colour. */
    public static void vertex(BufferBuilder buffer, org.joml.Matrix4f matrix,
                              double x, double y, double z, int color, float alpha) {
        buffer.vertex(matrix, (float) x, (float) y, (float) z)
                .color((color >> 16 & 0xFF) / 255.0f, (color >> 8 & 0xFF) / 255.0f,
                        (color & 0xFF) / 255.0f, alpha)
                .endVertex();
    }
}
