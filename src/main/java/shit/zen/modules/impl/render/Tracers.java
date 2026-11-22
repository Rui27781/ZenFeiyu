package shit.zen.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.awt.Color;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * World-space tracers from the camera to entities, ported from NeoRaven's
 * {@code TracersModule} / {@code Render3D.tracer}.
 *
 * <p>Upstream drew one line per player from the camera origin. This port keeps that behaviour,
 * adds entity filters, and batches every tracer into one {@code DEBUG_LINES} draw call. Positions
 * are interpolated so the lines do not stutter between ticks.</p>
 */
public class Tracers extends Module {
    private final BooleanSetting playersSetting = new BooleanSetting("Players", true);
    private final BooleanSetting mobsSetting = new BooleanSetting("Mobs", false);
    private final BooleanSetting animalsSetting = new BooleanSetting("Animals", false);
    private final BooleanSetting itemsSetting = new BooleanSetting("Items", false);
    private final BooleanSetting invisibleSetting = new BooleanSetting("Show Invisible", true);
    private final BooleanSetting throughWallsSetting = new BooleanSetting("Through Walls", true);
    private final BooleanSetting rainbowSetting = new BooleanSetting("Rainbow", false);
    private final NumberSetting lineWidthSetting = new NumberSetting("Line Width", 1.5, 0.5, 5.0, 0.1);
    private final NumberSetting rangeSetting = new NumberSetting("Range", 256, 16, 512, 8);
    private final NumberSetting redSetting = new NumberSetting("Red", 255, 0, 255, 1,
            () -> !this.rainbowSetting.getValue());
    private final NumberSetting greenSetting = new NumberSetting("Green", 60, 0, 255, 1,
            () -> !this.rainbowSetting.getValue());
    private final NumberSetting blueSetting = new NumberSetting("Blue", 60, 0, 255, 1,
            () -> !this.rainbowSetting.getValue());

    public Tracers() {
        super("Tracers", Category.RENDER);
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        double rangeSq = Math.pow(this.rangeSetting.getValue().doubleValue(), 2.0);
        int color = this.currentColor();
        float partial = event.partialTick();
        Vec3 camera = WorldRenderUtil.camera();
        Matrix4f matrix = event.poseStack().last().pose();

        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.DEBUG_LINES,
                this.throughWallsSetting.getValue());
        RenderSystem.lineWidth(this.lineWidthSetting.getValue().floatValue());
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!this.shouldRender(entity, rangeSq)) {
                continue;
            }
            Vec3 position = WorldRenderUtil.interpolated(entity, partial);
            double targetX = position.x - camera.x;
            double targetY = position.y + entity.getBbHeight() * 0.5 - camera.y;
            double targetZ = position.z - camera.z;
            WorldRenderUtil.vertex(buffer, matrix, 0.0, 0.0, 0.0, color, 0.9f);
            WorldRenderUtil.vertex(buffer, matrix, targetX, targetY, targetZ, color, 0.9f);
        }
        WorldRenderUtil.end();
        RenderSystem.lineWidth(1.0f);
    }

    private boolean shouldRender(Entity entity, double rangeSq) {
        if (entity == null || entity == mc.player || entity.isSpectator() || !entity.isAlive()) {
            return false;
        }
        if (!this.invisibleSetting.getValue() && entity.isInvisible()) {
            return false;
        }
        if (mc.player.distanceToSqr(entity) > rangeSq) {
            return false;
        }
        if (entity instanceof Player) {
            return this.playersSetting.getValue();
        }
        if (entity instanceof ItemEntity) {
            return this.itemsSetting.getValue();
        }
        if (entity instanceof Animal) {
            return this.animalsSetting.getValue();
        }
        if (entity instanceof Mob) {
            return this.mobsSetting.getValue();
        }
        return false;
    }

    private int currentColor() {
        if (this.rainbowSetting.getValue()) {
            float hue = (System.currentTimeMillis() % 3000L) / 3000.0f;
            return 0xFF000000 | Color.HSBtoRGB(hue, 1.0f, 1.0f) & 0xFFFFFF;
        }
        return WorldRenderUtil.rgba(
                this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(),
                this.blueSetting.getValue().intValue(), 255);
    }
}
