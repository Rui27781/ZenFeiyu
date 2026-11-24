package shit.zen.hud;

import lombok.Getter;
import lombok.Setter;
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.utils.render.RenderUtil;

public abstract class HudElement
extends Module {
    @Getter @Setter
    protected float x;
    @Getter @Setter
    protected float y;
    @Getter @Setter
    protected float width;
    @Getter @Setter
    protected float height;
    @Getter @Setter
    private boolean dragging = false;
    @Getter @Setter
    private float dragOffsetX;
    @Getter @Setter
    private float dragOffsetY;

    public HudElement(String string) {
        super(string, Category.RENDER);
    }

    public abstract void onRender2D(Render2DEvent var1, float var2, float var3);

    public abstract void onGlRender(GlRenderEvent var1, float var2, float var3);

    public abstract void onSettings();

    public boolean mousePressed(int mouseX, int mouseY, int button) {
        if (this.isHovered(mouseX, mouseY) && button == 0) {
            this.dragging = true;
            this.dragOffsetX = (float)mouseX - this.getX();
            this.dragOffsetY = (float)mouseY - this.getY();
            return true;
        }
        return false;
    }

    public void mouseDragged(int mouseX, int mouseY) {
        float nextX = (float) mouseX - this.dragOffsetX;
        float nextY = (float) mouseY - this.dragOffsetY;
        // 拖出屏幕时贴边，避免 HUD 被拖到看不见的位置。
        if (mc != null && mc.getWindow() != null) {
            float maxX = Math.max(0.0f, mc.getWindow().getGuiScaledWidth() - Math.max(4.0f, this.width));
            float maxY = Math.max(0.0f, mc.getWindow().getGuiScaledHeight() - Math.max(4.0f, this.height));
            nextX = Math.max(0.0f, Math.min(maxX, nextX));
            nextY = Math.max(0.0f, Math.min(maxY, nextY));
            // 靠近边缘时轻微吸附，方便对齐。
            if (nextX < 4.0f) nextX = 0.0f;
            if (nextY < 4.0f) nextY = 0.0f;
            if (nextX > maxX - 4.0f) nextX = maxX;
            if (nextY > maxY - 4.0f) nextY = maxY;
        }
        this.x = nextX;
        this.y = nextY;
    }

    public boolean isHovered(int mouseX, int mouseY) {
        return RenderUtil.isHovered(this.x, this.y, this.width, this.height, mouseX, mouseY);
    }

    public void stopDragging() {
        this.dragging = false;
    }
}