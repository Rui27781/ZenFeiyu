package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.ClientBase;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.ProjectionUtil;
import shit.zen.utils.render.RenderUtil;
import shit.zen.event.EventTarget;

public class BlockHighlight extends Module {
    public static BlockHighlight INSTANCE;

    public final ModeSetting modeSetting = new ModeSetting("Mode", "Outline", "Both", "Fill").withDefault("Outline");
    public final ModeSetting colorMode = new ModeSetting("Color Mode", "Single", "Rainbow", "Pulse").withDefault("Pulse");
    public final NumberSetting redSetting = new NumberSetting("Red", 160, 0, 255, 1, () -> "Single".equals(this.colorMode.getValue()));
    public final NumberSetting greenSetting = new NumberSetting("Green", 80, 0, 255, 1, () -> "Single".equals(this.colorMode.getValue()));
    public final NumberSetting blueSetting = new NumberSetting("Blue", 255, 0, 255, 1, () -> "Single".equals(this.colorMode.getValue()));
    public final NumberSetting alphaSetting = new NumberSetting("Alpha", 200, 0, 255, 1);
    public final NumberSetting lineWidthSetting = new NumberSetting("Line Width", 2.0, 0.5, 5.0, 0.1);
    public final NumberSetting pulseSpeedSetting = new NumberSetting("Pulse Speed", 2.0, 0.5, 5.0, 0.1, () -> "Pulse".equals(this.colorMode.getValue()));
    public final NumberSetting rainbowSpeedSetting = new NumberSetting("Rainbow Speed", 0.5, 0.1, 2.0, 0.05, () -> "Rainbow".equals(this.colorMode.getValue()));
    public final BooleanSetting targetBlockOnly = new BooleanSetting("Target Block Only", true);

    private float pulseTime = 0.0f;
    private float rainbowOffset = 0.0f;

    public BlockHighlight() {
        super("BlockHighlight", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        pulseTime = 0.0f;
        rainbowOffset = 0.0f;
        super.onEnable();
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.player == null || mc.hitResult == null) return;
        if (mc.hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult blockHit = (BlockHitResult) mc.hitResult;
        BlockPos pos = blockHit.getBlockPos();

        if (this.targetBlockOnly.getValue() && !pos.equals(ClientBase.getTargetBlockPos())) {
            return;
        }

        float partial = event.partialTick();
        AABB box = new AABB(pos).inflate(0.002).move(-mc.getCameraEntity().getX(partial), -mc.getCameraEntity().getY(partial), -mc.getCameraEntity().getZ(partial));
        PoseStack poseStack = event.poseStack();
        Matrix4f matrix = poseStack.last().pose();

        Color color = getCurrentColor();
        float lineWidth = this.lineWidthSetting.getValue().floatValue();
        float alpha = this.alphaSetting.getValue().floatValue() / 255.0f;

        switch (this.modeSetting.getValue()) {
            case "Outline" -> RenderUtil.drawOutlinedBox(box, poseStack, color, lineWidth, alpha);
            case "Fill" -> RenderUtil.drawFilledBox(box, poseStack, color, alpha);
            case "Both" -> {
                RenderUtil.drawFilledBox(box, poseStack, color, alpha * 0.3f);
                RenderUtil.drawOutlinedBox(box, poseStack, color, lineWidth, alpha);
            }
        }
    }

    private Color getCurrentColor() {
        float r = this.redSetting.getValue().floatValue() / 255.0f;
        float g = this.greenSetting.getValue().floatValue() / 255.0f;
        float b = this.blueSetting.getValue().floatValue() / 255.0f;

        switch (this.colorMode.getValue()) {
            case "Rainbow" -> {
                rainbowOffset += this.rainbowSpeedSetting.getValue().floatValue() * 0.01f;
                float hue = (rainbowOffset % 1.0f);
                return Color.getHSBColor(hue, 1.0f, 1.0f);
            }
            case "Pulse" -> {
                pulseTime += this.pulseSpeedSetting.getValue().floatValue() * 0.02f;
                float pulse = (Mth.sin(pulseTime) + 1.0f) * 0.5f;
                return new Color(r, g, b, pulse);
            }
            default -> {
                return new Color(r, g, b);
            }
        }
    }
}