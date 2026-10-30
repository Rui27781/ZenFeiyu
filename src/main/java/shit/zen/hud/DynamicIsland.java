package shit.zen.hud;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import shit.zen.ClientBase;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.render.Paint;
import shit.zen.render.Rectangle;
import shit.zen.render.Renderer;
import shit.zen.render.RoundedRectangle;
import shit.zen.utils.animation.SpringAnimation;
import shit.zen.utils.render.RenderUtil;

public class DynamicIsland {
    public static final class ActiveElementSelector {
        private final DynamicIsland owner;

        public ActiveElementSelector(DynamicIsland owner) {
            this.owner = owner;
        }

        public IHudElement visible() {
            for (IHudElement element : this.owner.elements) {
                if (element.isVisible()) return element;
            }
            return null;
        }
    }

    final List<IHudElement> elements = Arrays.asList(new TabListHud(), new ScaffoldHud(), new EventAlertHud(), new AutoPlayHud(), new WatermarkHud());
    private final DynamicIsland.ActiveElementSelector activeElementSelector = new DynamicIsland.ActiveElementSelector(this);
    private final SpringAnimation widthAnim = new SpringAnimation(300.0f, 1.2f, 20.0f, 170.0f);
    private final SpringAnimation heightAnim = new SpringAnimation(300.0f, 1.2f, 20.0f, 18.0f);
    private final SpringAnimation transitionAnim = new SpringAnimation(250.0f, 1.0f, 22.0f, 1.0f);
    private IHudElement activeElement = null;
    private IHudElement outgoingElement = null;
    private final long lastFrameTime = 0L;
    private long lastFrameTimestamp = 0L;

    public void onRender2D(Render2DEvent render2DEvent) {
        float islandY;
        float outgoingY;
        IHudElement.Size size;
        if (ClientBase.mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.lastFrameTimestamp == 0L) {
            this.lastFrameTimestamp = now;
        }
        float deltaSec = (float)(now - this.lastFrameTimestamp) / 1000.0f;
        this.lastFrameTimestamp = now;
        deltaSec = Math.min(deltaSec, 0.033333335f);
        IHudElement visibleElement = this.activeElementSelector.visible();
        if (this.activeElement != visibleElement) {
            this.outgoingElement = this.activeElement;
            this.activeElement = visibleElement;
            this.transitionAnim.reset(0.0f);
            this.transitionAnim.setTargetValue(1.0f);
            if (this.outgoingElement == null) {
                size = this.activeElement.getHudAlignment();
                this.widthAnim.reset(size.width());
                this.heightAnim.reset(size.height());
                this.transitionAnim.reset(1.0f);
            }
        }
        size = this.activeElement.getHudAlignment();
        float targetWidth = size.width();
        float targetHeight = size.height();
        float progress = this.transitionAnim.getValue();
        if (this.outgoingElement != null && progress < 1.0f) {
            IHudElement.Size outgoingSize = this.outgoingElement.getHudAlignment();
            targetWidth = Mth.lerp(progress, outgoingSize.width(), size.width());
            targetHeight = Mth.lerp(progress, outgoingSize.height(), size.height());
        }
        this.widthAnim.setTargetValue(targetWidth);
        this.heightAnim.setTargetValue(targetHeight);
        this.widthAnim.update(deltaSec);
        this.heightAnim.update(deltaSec);
        this.transitionAnim.update(deltaSec);
        float islandWidth = Math.max(0.0f, this.widthAnim.getValue() + 30.0f);
        float islandHeight = Math.max(0.0f, this.heightAnim.getValue() + 3.0f);
        float islandX = ((float)ClientBase.mc.getWindow().getGuiScaledWidth() - islandWidth) / 2.0f;
        float anchorSize = 25.0f;
        float topMargin = 25.0f;
        float anchorCenterY = topMargin + anchorSize / 2.0f;
        float activeY = this.activeElement.getHudSize() == IHudElement.Alignment.CENTER ? anchorCenterY - islandHeight / 2.0f : topMargin;
        if (this.outgoingElement != null && progress < 1.0f) {
            outgoingY = this.outgoingElement.getHudSize() == IHudElement.Alignment.CENTER ? anchorCenterY - islandHeight / 2.0f : topMargin;
            islandY = Mth.lerp(progress, outgoingY, activeY);
        } else {
            islandY = activeY;
        }
        outgoingY = 12.0f;
        final float finalY = islandY;
        final float finalCornerRadius = outgoingY;
        if (this.activeElement.hasBackground()) {
            PoseStack poseStack = render2DEvent.poseStack();
            poseStack.pushPose();
            RenderUtil.drawBlurredRect(poseStack, islandX, finalY, islandWidth, islandHeight, finalCornerRadius, 16.0f, 0.9f, 0);
            poseStack.popPose();
            Renderer.renderConsumer((drawContext -> {
                RoundedRectangle panel = RoundedRectangle.ofXYWHR(islandX, finalY, islandWidth, islandHeight, finalCornerRadius);
                // Soft white sheen fading from the top edge.
                drawContext.save();
                drawContext.clipRoundedRect(panel, true);
                try (Paint sheen = new Paint()){
                    sheen.setGradCoords(new Paint.GradientCoords(islandX, finalY, islandX, finalY + islandHeight * 0.7f,
                            new Color(255, 255, 255, 46).getRGB(), new Color(255, 255, 255, 0).getRGB()));
                    drawContext.drawRect(Rectangle.ofXYWH(islandX, finalY, islandWidth, islandHeight * 0.7f), sheen);
                }
                drawContext.restore();
                // Hairline rim to sell the glass edge.
                try (Paint rim = new Paint()){
                    rim.setColor(new Color(255, 255, 255, 60).getRGB());
                    rim.setStrokeCap(Paint.StrokeCap.STROKE);
                    rim.setStrokeWidth(1.0f);
                    drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(islandX + 0.5f, finalY + 0.5f,
                            Math.max(0.0f, islandWidth - 1.0f), Math.max(0.0f, islandHeight - 1.0f),
                            Math.max(1.0f, finalCornerRadius - 0.5f)), rim);
                }
                drawContext.save();
                drawContext.clipRoundedRect(panel, true);
                this.activeElement.render(drawContext, islandX, finalY, islandWidth, islandHeight, progress);
                drawContext.restore();
            }));
        }
        RenderUtil.pushScissor((int)islandX, (int)islandY, (int)islandWidth, (int)islandHeight);
        this.activeElement.renderGui(render2DEvent.guiGraphics(), render2DEvent.poseStack(), islandX, islandY, islandWidth, islandHeight, progress);
        RenderUtil.popScissor();
        if (progress >= 1.0f) {
            this.outgoingElement = null;
        }
    }
}