package shit.zen.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.utils.math.Vector2f;
import shit.zen.utils.render.ProjectionUtil;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Item drop ESP ported from NeoRaven's {@code ItemESPModule}.
 *
 * <p>Upstream grouped drops with {@code Hypixel.positionHash} (unavailable here) into per-item
 * counts. This port groups by item type plus rounded block coordinates using fixed, reused arrays,
 * so no map, stream or collection is created in the render loop. The "Render self" box is kept as a
 * plain outline; the item boxes share one QUADS batch and the count labels are drawn in the HUD
 * pass through {@link ProjectionUtil}.</p>
 */
public class ItemESP extends Module {
    private static final int MAX_GROUPS = 96;
    private static final int[] BOX_COLORS = {0xFFFFFF, 0xFAF049, 0x61E2FF, 0x17D262};
    private static final int[] TEXT_COLORS = {0xFFFFFF, 0xFFFF68, 0x8AFFFF, 0x20FF8C};

    private final BooleanSetting renderSelfSetting = new BooleanSetting("Render self", false);
    private final BooleanSetting disableDiamondSetting = new BooleanSetting("Disable diamond", false);
    private final BooleanSetting disableEmeraldSetting = new BooleanSetting("Disable emerald", false);
    private final BooleanSetting disableGoldSetting = new BooleanSetting("Disable gold", false);
    private final BooleanSetting disableIronSetting = new BooleanSetting("Disable iron", false);

    private final int[] groupType = new int[MAX_GROUPS];
    private final int[] groupCount = new int[MAX_GROUPS];
    private final double[] groupX = new double[MAX_GROUPS];
    private final double[] groupY = new double[MAX_GROUPS];
    private final double[] groupZ = new double[MAX_GROUPS];
    private final float[] groupScreenX = new float[MAX_GROUPS];
    private final float[] groupScreenY = new float[MAX_GROUPS];
    private final boolean[] groupOnScreen = new boolean[MAX_GROUPS];
    private int groups;

    public ItemESP() {
        super("ItemESP", Category.RENDER);
    }

    private int itemType(Item item) {
        if (item == Items.IRON_INGOT) {
            return 0;
        }
        if (item == Items.GOLD_INGOT) {
            return 1;
        }
        if (item == Items.DIAMOND) {
            return 2;
        }
        if (item == Items.EMERALD) {
            return 3;
        }
        return -1;
    }

    private boolean enabled(int type) {
        if (type == 0) {
            return !this.disableIronSetting.getValue();
        }
        if (type == 1) {
            return !this.disableGoldSetting.getValue();
        }
        if (type == 2) {
            return !this.disableDiamondSetting.getValue();
        }
        return type == 3 && !this.disableEmeraldSetting.getValue();
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null) {
            this.groups = 0;
            return;
        }
        float partial = event.partialTick();
        Vec3 camera = WorldRenderUtil.camera();
        Matrix4f matrix = event.poseStack().last().pose();
        ProjectionUtil.updateMatrices();
        this.groups = 0;

        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, true);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity) || entity.tickCount < 3) {
                continue;
            }
            ItemEntity itemEntity = (ItemEntity) entity;
            if (itemEntity.getItem().isEmpty()) {
                continue;
            }
            int type = this.itemType(itemEntity.getItem().getItem());
            if (type < 0 || !this.enabled(type)) {
                continue;
            }
            int size = itemEntity.getItem().getCount();
            if (size <= 0) {
                continue;
            }
            double rawX = Mth.lerp(partial, entity.xo, entity.getX());
            double rawY = Mth.lerp(partial, entity.yo, entity.getY());
            double rawZ = Mth.lerp(partial, entity.zo, entity.getZ());
            double x = rawX - camera.x;
            double y = rawY - camera.y;
            double z = rawZ - camera.z;
            float half = Mth.clamp((float) (0.01 * Math.sqrt(this.distanceSquared(x, y, z))), 0.2f, 0.4f);
            ItemESP.box(buffer, matrix, x - half, y, z - half, x + half, y + half * 2.0f, z + half,
                    BOX_COLORS[type], 0.56f);

            int group = -1;
            double gx = Math.floor(rawX);
            double gy = Math.floor(rawY);
            double gz = Math.floor(rawZ);
            for (int i = 0; i < this.groups; i++) {
                if (this.groupType[i] == type && this.groupX[i] == gx && this.groupY[i] == gy && this.groupZ[i] == gz) {
                    group = i;
                    break;
                }
            }
            if (group < 0 && this.groups < MAX_GROUPS) {
                group = this.groups++;
                this.groupType[group] = type;
                this.groupX[group] = gx;
                this.groupY[group] = gy;
                this.groupZ[group] = gz;
                this.groupCount[group] = 0;
            }
            if (group >= 0) {
                this.groupCount[group] += size;
            }
        }
        WorldRenderUtil.end();

        for (int i = 0; i < this.groups; i++) {
            Vector2f screen = ProjectionUtil.project(this.groupX[i] + 0.5, this.groupY[i] + 0.8, this.groupZ[i] + 0.5);
            this.groupOnScreen[i] = screen != null;
            if (screen != null) {
                this.groupScreenX[i] = screen.x;
                this.groupScreenY[i] = screen.y;
            }
        }

        if (!this.renderSelfSetting.getValue()) {
            return;
        }
        Player self = mc.player;
        double selfX = Mth.lerp(partial, self.xo, self.getX()) - camera.x;
        double selfY = Mth.lerp(partial, self.yo, self.getY()) - camera.y;
        double selfZ = Mth.lerp(partial, self.zo, self.getZ()) - camera.z;
        RenderSystem.lineWidth(2.0f);
        BufferBuilder lines = WorldRenderUtil.begin(VertexFormat.Mode.LINES, true);
        float halfWidth = self.getBbWidth() * 0.5f;
        ItemESP.outline(lines, matrix,
                selfX - halfWidth, selfY, selfZ - halfWidth,
                selfX + halfWidth, selfY + self.getBbHeight(), selfZ + halfWidth,
                0xFFFFFF, 1.0f);
        WorldRenderUtil.end();
        RenderSystem.lineWidth(1.0f);
    }

    private double distanceSquared(double x, double y, double z) {
        return x * x + y * y + z * z;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (this.groups == 0) {
            return;
        }
        PoseStack poseStack = event.poseStack();
        GuiGraphics graphics = event.guiGraphics();
        for (int i = 0; i < this.groups; i++) {
            if (!this.groupOnScreen[i] || this.groupCount[i] <= 0) {
                continue;
            }
            String label = String.valueOf(this.groupCount[i]);
            poseStack.pushPose();
            poseStack.translate(this.groupScreenX[i], this.groupScreenY[i], 0.0f);
            poseStack.scale(1.1f, 1.1f, 1.1f);
            graphics.drawString(mc.font, label, -mc.font.width(label) / 2, 0, TEXT_COLORS[this.groupType[i]], true);
            poseStack.popPose();
        }
    }

    private static void box(BufferBuilder buffer, Matrix4f matrix,
                            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                            int color, float alpha) {
        ItemESP.quad(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, color, alpha);
        ItemESP.quad(buffer, matrix, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ, color, alpha);
        ItemESP.quad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, color, alpha);
        ItemESP.quad(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, color, alpha);
        ItemESP.quad(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, color, alpha);
        ItemESP.quad(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, color, alpha);
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
        ItemESP.line(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, color, alpha);
        ItemESP.line(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, color, alpha);
        ItemESP.line(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, color, alpha);
        ItemESP.line(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, color, alpha);
        ItemESP.line(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, color, alpha);
        ItemESP.line(buffer, matrix, maxX, maxY, minZ, maxX, maxY, maxZ, color, alpha);
        ItemESP.line(buffer, matrix, maxX, maxY, maxZ, minX, maxY, maxZ, color, alpha);
        ItemESP.line(buffer, matrix, minX, maxY, maxZ, minX, maxY, minZ, color, alpha);
        ItemESP.line(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, color, alpha);
        ItemESP.line(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, color, alpha);
        ItemESP.line(buffer, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, color, alpha);
        ItemESP.line(buffer, matrix, minX, minY, maxZ, minX, maxY, maxZ, color, alpha);
    }

    private static void line(BufferBuilder buffer, Matrix4f matrix,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             int color, float alpha) {
        WorldRenderUtil.vertex(buffer, matrix, x1, y1, z1, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x2, y2, z2, color, alpha);
    }
}
