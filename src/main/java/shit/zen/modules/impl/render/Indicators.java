package shit.zen.modules.impl.render;

import com.mojang.math.Axis;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.utils.render.OverlayUtil;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;

/**
 * Directional indicators for incoming projectiles, ported from NeoRaven's {@code IndicatorsModule}.
 *
 * <p>Arrows, ender pearls and fireballs are gathered once per tick and drawn as a caret, {@code >}
 * or triangle on a circle around the crosshair, with distance and optional item icon. The vanilla
 * camera is used for projection instead of the 1.8.9 fixed-function helper, so off-screen targets
 * keep their direction. Two upstream details have no 1.20.1 equivalent here: friend / bot / Hypixel
 * team filtering is dropped (no such services exist in ZenFeiyu), and "Render only threats" uses a
 * velocity-towards-player test rather than the old block-by-block flight ray march. The pearl and
 * fireball throw-grace timestamps are approximated by the existing distance check.</p>
 */
public class Indicators extends Module {
    private static final int MAX_TRACKED = 64;
    private static final double THREAT_DIST_SQ = 49.0;
    private static final double OWN_DIST_SQ = 8.0;
    private static final float FOV = 70.0F;

    private final BooleanSetting renderArrowsSetting = new BooleanSetting("Render arrows", true);
    private final BooleanSetting renderEnderPearlsSetting = new BooleanSetting("Render ender pearls", true);
    private final BooleanSetting renderFireballsSetting = new BooleanSetting("Render fireballs", true);
    private final ModeSetting arrowSetting = new ModeSetting("Arrow", "Caret", "Greater_than", "Triangle").withDefault("Caret");
    private final NumberSetting circleRadiusSetting = new NumberSetting("Circle radius", 60.0, 10.0, 200.0, 1.0);
    private final BooleanSetting itemColorsSetting = new BooleanSetting("Item colors", true);
    private final BooleanSetting renderItemSetting = new BooleanSetting("Render item", true);
    private final BooleanSetting onlyThreatsSetting = new BooleanSetting("Render only threats", false);
    private final NumberSetting fireballRedSetting = new NumberSetting("Fireball red", 255, 0, 255, 1,
            () -> this.itemColorsSetting.getValue());
    private final NumberSetting fireballGreenSetting = new NumberSetting("Fireball green", 150, 0, 255, 1,
            () -> this.itemColorsSetting.getValue());
    private final NumberSetting fireballBlueSetting = new NumberSetting("Fireball blue", 0, 0, 255, 1,
            () -> this.itemColorsSetting.getValue());
    private final NumberSetting pearlRedSetting = new NumberSetting("Ender pearl red", 210, 0, 255, 1,
            () -> this.itemColorsSetting.getValue());
    private final NumberSetting pearlGreenSetting = new NumberSetting("Ender pearl green", 0, 0, 255, 1,
            () -> this.itemColorsSetting.getValue());
    private final NumberSetting pearlBlueSetting = new NumberSetting("Ender pearl blue", 255, 0, 255, 1,
            () -> this.itemColorsSetting.getValue());

    private final ItemStack arrowIcon = new ItemStack(Items.ARROW);
    private final ItemStack pearlIcon = new ItemStack(Items.ENDER_PEARL);
    private final ItemStack fireballIcon = new ItemStack(Items.FIRE_CHARGE);
    private final List<Entity> tracked = new ArrayList<Entity>();
    private final String[] distanceText = new String[MAX_TRACKED];
    private final Vector3f relative = new Vector3f();
    private final Quaternionf cameraRotation = new Quaternionf();

    public Indicators() {
        super("Indicators", Category.RENDER);
    }

    @Override
    protected void onDisable() {
        this.tracked.clear();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc.level == null || mc.player == null) {
            this.tracked.clear();
            return;
        }
        for (int i = this.tracked.size() - 1; i >= 0; i--) {
            Entity entity = this.tracked.get(i);
            if (entity == null || !entity.isAlive() || mc.level.getEntity(entity.getId()) == null) {
                this.tracked.remove(i);
            }
        }
        for (int i = 0; i < this.tracked.size(); i++) {
            this.distanceText[i] = (int) mc.player.distanceTo(this.tracked.get(i)) + "m";
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (this.tracked.size() >= MAX_TRACKED || this.tracked.contains(entity)) {
                continue;
            }
            byte kind = this.kindOf(entity);
            if (kind == 0 || this.tracked.size() >= MAX_TRACKED) {
                continue;
            }
            if (mc.player.distanceToSqr(entity) <= (kind == 1 ? THREAT_DIST_SQ : OWN_DIST_SQ)) {
                continue;
            }
            this.tracked.add(entity);
            int index = this.tracked.size() - 1;
            this.distanceText[index] = (int) mc.player.distanceTo(entity) + "m";
        }
    }

    private byte kindOf(Entity entity) {
        if (entity instanceof Arrow) {
            return this.renderArrowsSetting.getValue() && !entity.isOnGround() ? (byte) 1 : 0;
        }
        if (entity instanceof ThrownEnderpearl) {
            return this.renderEnderPearlsSetting.getValue() ? (byte) 2 : 0;
        }
        if (entity instanceof LargeFireball) {
            return this.renderFireballsSetting.getValue() ? (byte) 3 : 0;
        }
        return 0;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender() || mc.player == null || mc.level == null || this.tracked.isEmpty()) {
            return;
        }
        GuiGraphics graphics = event.guiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float centerX = screenWidth * 0.5F;
        float centerY = screenHeight * 0.5F;
        float radius = this.circleRadiusSetting.getValue().floatValue();
        if (this.renderItemSetting.getValue()) {
            radius += 20.0F;
        }
        String mode = this.arrowSetting.getValue();

        for (int i = 0; i < this.tracked.size(); i++) {
            Entity entity = this.tracked.get(i);
            byte kind = this.kindOf(entity);
            if (kind == 0 || !this.isThreat(entity, kind)) {
                continue;
            }
            double y = entity.getY() + entity.getBbHeight() * 0.5;
            Projected point = this.project(entity.getX(), y, entity.getZ(), centerX, centerY);
            if (point == null) {
                continue;
            }
            double dx = point.x - centerX;
            double dy = point.y - centerY;
            if (!point.inFront) {
                dx = -dx;
                dy = -dy;
            }
            double distance = Math.hypot(dx, dy);
            if (point.inFront && distance < radius + 15.0) {
                continue;
            }
            double sine = dx / Math.max(0.0001, distance);
            double cosine = dy / Math.max(0.0001, distance);
            float px = (float) (centerX + radius * sine);
            float py = (float) (centerY + radius * cosine);
            float rotation = (float) (Math.atan2(dy, dx) * 57.29578 + 90.0);
            int color = this.colorFor(kind);

            PoseStack pose = event.poseStack();
            pose.pushPose();
            pose.translate(px, py, 0.0F);
            pose.mulPose(Axis.ZP.rotationDegrees(rotation));
            if ("Greater_than".equals(mode)) {
                pose.scale(1.5F, 1.5F, 1.5F);
                graphics.drawString(mc.font, ">", -2, -4, color, false);
            } else if ("Triangle".equals(mode)) {
                triangle(graphics, color);
            } else {
                caret(graphics, color);
            }
            pose.popPose();

            int textX = (int) (centerX + (radius - 13.0) * sine);
            int textY = (int) (centerY + (radius - 13.0) * cosine) - 4;
            graphics.drawCenteredString(mc.font, this.distanceText[i] == null ? "" : this.distanceText[i], textX, textY, 0xFFFFFFFF);

            if (this.renderItemSetting.getValue() && kind != 0) {
                int itemX = (int) (centerX + (radius - 26.0) * sine);
                int itemY = (int) (centerY + (radius - 26.0) * cosine);
                graphics.renderItem(this.iconFor(kind), itemX - 8, itemY - 8);
            }
        }
    }

    private int colorFor(byte kind) {
        if (!this.itemColorsSetting.getValue() || kind == 1) {
            return 0xFFFFFFFF;
        }
        if (kind == 2) {
            return ColorUtil.fromARGB(this.pearlRedSetting.getValue().intValue(),
                    this.pearlGreenSetting.getValue().intValue(), this.pearlBlueSetting.getValue().intValue(), 255);
        }
        return ColorUtil.fromARGB(this.fireballRedSetting.getValue().intValue(),
                this.fireballGreenSetting.getValue().intValue(), this.fireballBlueSetting.getValue().intValue(), 255);
    }

    private ItemStack iconFor(byte kind) {
        if (kind == 2) {
            return this.pearlIcon;
        }
        return kind == 3 ? this.fireballIcon : this.arrowIcon;
    }

    private boolean isThreat(Entity entity, byte kind) {
        if (!this.onlyThreatsSetting.getValue() || kind != 3) {
            return true;
        }
        Player player = mc.player;
        Vec3 velocity = entity.getDeltaMovement();
        double length = velocity.length();
        if (length < 0.0001) {
            return false;
        }
        double towardsX = player.getX() - entity.getX();
        double towardsY = player.getY() - entity.getY();
        double towardsZ = player.getZ() - entity.getZ();
        double towardsLength = Math.sqrt(towardsX * towardsX + towardsY * towardsY + towardsZ * towardsZ);
        if (towardsLength > 200.0 || towardsLength < 0.0001) {
            return false;
        }
        return (velocity.x * towardsX + velocity.y * towardsY + velocity.z * towardsZ) / (length * towardsLength) > 0.55;
    }

    private Projected project(double worldX, double worldY, double worldZ, float centerX, float centerY) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        this.relative.set((float) (worldX - cameraPos.x), (float) (worldY - cameraPos.y), (float) (worldZ - cameraPos.z));
        this.cameraRotation.set(camera.rotation());
        this.cameraRotation.conjugate();
        this.relative.rotate(this.cameraRotation);
        boolean inFront = this.relative.z < -0.05F;
        float depth = Math.max(0.05F, -this.relative.z);
        float scale = (centerY / depth) / (float) Math.tan(Math.toRadians(FOV * 0.5));
        this.projected.x = centerX + this.relative.x * scale;
        this.projected.y = centerY - this.relative.y * scale;
        this.projected.inFront = inFront;
        return this.projected;
    }

    private final Projected projected = new Projected();

    private static void caret(GuiGraphics graphics, int color) {
        line(graphics, -5, -4, 0, 1, 3, color);
        line(graphics, 5, -4, 0, 1, 3, color);
        line(graphics, -5, -4, 5, -4, 2, color);
    }

    private static void triangle(GuiGraphics graphics, int color) {
        line(graphics, 0, -6, 6, 5, 2, color);
        line(graphics, 6, 5, -6, 5, 2, color);
        line(graphics, -6, 5, 0, -6, 2, color);
    }

    private static void line(GuiGraphics graphics, int x1, int y1, int x2, int y2, int thickness, int color) {
        int dx = Math.abs(x2 - x1);
        int sx = x1 < x2 ? 1 : -1;
        int dy = -Math.abs(y2 - y1);
        int sy = y1 < y2 ? 1 : -1;
        int error = dx + dy;
        int half = thickness / 2;
        while (true) {
            graphics.fill(x1 - half, y1 - half, x1 - half + thickness, y1 - half + thickness, color);
            if (x1 == x2 && y1 == y2) {
                return;
            }
            int twice = error * 2;
            if (twice >= dy) {
                error += dy;
                x1 += sx;
            }
            if (twice <= dx) {
                error += dx;
                y1 += sy;
            }
        }
    }

    private static final class Projected {
        private float x;
        private float y;
        private boolean inFront;
    }
}
