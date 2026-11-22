package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.game.EntityUtil;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Entity glow ported from NeoRaven's {@code GlowEspModule}.
 *
 * <p>Upstream rendered a silhouette mask into a framebuffer and blurred it with an Onyx shader,
 * which requires 1.8.9 render hooks and texture assets. Neither is available here, so the glow is
 * approximated with three nested, additively blended bounding-box shells around each accepted
 * living entity; "Glow radius" scales the shell thickness. Everything is emitted in a single QUADS
 * batch and no textures or framebuffer copies are used.</p>
 */
public class GlowESP extends Module {
    private final NumberSetting redSetting = new NumberSetting("Red", 255, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 255, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 255, 0, 255, 1);
    private final BooleanSetting playersSetting = new BooleanSetting("Players", true);
    private final BooleanSetting mobsSetting = new BooleanSetting("Mobs", true);
    private final BooleanSetting renderSelfSetting = new BooleanSetting("Render self", false);
    private final NumberSetting radiusSetting = new NumberSetting("Glow radius", 2.0, 0.2, 8.0, 0.1);

    public GlowESP() {
        super("Glow ESP", Category.RENDER);
    }

    private boolean accepts(Entity entity) {
        if (entity == null || !entity.isAlive() || entity.isInvisible() || entity == mc.cameraEntity) {
            return false;
        }
        if (entity == mc.player) {
            return this.renderSelfSetting.getValue();
        }
        if (!(entity instanceof LivingEntity) || entity instanceof ArmorStand) {
            return false;
        }
        if (entity instanceof Player) {
            return this.playersSetting.getValue();
        }
        return this.mobsSetting.getValue();
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null || this.groupEmpty()) {
            return;
        }
        float partial = event.partialTick();
        Vec3 camera = WorldRenderUtil.camera();
        Matrix4f matrix = event.poseStack().last().pose();
        int color = WorldRenderUtil.rgba(this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue(), 255);
        float radius = this.radiusSetting.getValue().floatValue();

        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, true);
        WorldRenderUtil.additiveBlend();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!this.accepts(entity) || !EntityUtil.isVisible(entity)) {
                continue;
            }
            double x = Mth.lerp(partial, entity.xo, entity.getX()) - camera.x;
            double y = Mth.lerp(partial, entity.yo, entity.getY()) - camera.y;
            double z = Mth.lerp(partial, entity.zo, entity.getZ()) - camera.z;
            double half = entity.getBbWidth() * 0.5;
            double height = entity.getBbHeight();
            for (int layer = 0; layer < 3; layer++) {
                double expand = radius * 0.09 * (layer + 1);
                float alpha = 0.20f / (layer + 1);
                GlowESP.box(buffer, matrix,
                        x - half - expand, y - expand, z - half - expand,
                        x + half + expand, y + height + expand, z + half + expand,
                        color, alpha);
            }
        }
        WorldRenderUtil.end();
    }

    private boolean groupEmpty() {
        return !this.playersSetting.getValue() && !this.mobsSetting.getValue() && !this.renderSelfSetting.getValue();
    }

    private static void box(BufferBuilder buffer, Matrix4f matrix,
                            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                            int color, float alpha) {
        GlowESP.quad(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, color, alpha);
        GlowESP.quad(buffer, matrix, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ, color, alpha);
        GlowESP.quad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, color, alpha);
        GlowESP.quad(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, color, alpha);
        GlowESP.quad(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, color, alpha);
        GlowESP.quad(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, color, alpha);
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
