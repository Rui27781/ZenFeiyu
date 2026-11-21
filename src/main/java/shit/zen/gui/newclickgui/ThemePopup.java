package shit.zen.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import shit.zen.gui.NewClickGui;
import shit.zen.render.FontStore;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.misc.CursorUtil;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.RenderUtil;

/**
 * Small popup that lets the user pick the NewClickGui accent colour, enable a
 * rainbow accent, tune the rainbow speed, and reset the theme.
 */
public class ThemePopup
extends UIElement {
    private static final int PANEL_BG = ColorUtil.fromRGB(24, 24, 24);
    private static final int PANEL_BG_INNER = ColorUtil.fromRGB(30, 30, 30);

    private boolean open;
    private final SmoothAnimationTimer openTimer = new SmoothAnimationTimer();
    private boolean draggingHue;
    private boolean draggingSaturation;
    private boolean draggingBrightness;
    private boolean draggingSpeed;

    public boolean isOpen() {
        return this.open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public void toggle() {
        this.open = !this.open;
    }

    @Override
    public void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks) {
        this.openTimer.animate(this.open ? 1.0 : 0.0, 0.18, Easings.EASE_OUT_POW2);
        this.openTimer.tick();
        float openAmount = this.openTimer.getValueF();
        if (openAmount <= 0.01f) {
            return;
        }
        float a = alpha * openAmount;

        RenderUtil.drawShadow(poseStack, this.x, this.y, this.width, this.height, 14,
                ColorUtil.withAlpha(ColorUtil.fromARGB(0, 0, 0, 90), a));
        RenderUtil.drawRoundedRect(poseStack, this.x, this.y, this.width, this.height, 7.0f,
                ColorUtil.withAlpha(PANEL_BG, a));
        RenderUtil.drawRoundedRect(poseStack, this.x + 0.5f, this.y + 0.5f, this.width - 1.0f, this.height - 1.0f, 6.5f,
                ColorUtil.withAlpha(PANEL_BG_INNER, a));

        FontStore.AXIFORMA_BOLD_16.drawString(poseStack, "Theme", this.x + 10.0f, this.y + 9.0f,
                ColorUtil.withAlpha(-1, a));
        RenderUtil.drawRoundedRect(poseStack, this.x + this.width - 38.0f, this.y + 8.0f, 28.0f, 13.0f, 4.0f,
                ColorUtil.withAlpha(Theme.accent(), a));

        float rowY = this.y + 32.0f;

        // Rainbow toggle row.
        this.drawLabel(poseStack, "Rainbow", rowY, a);
        float switchX = this.x + this.width - 34.0f;
        float switchY = rowY + 1.0f;
        boolean rainbowHovered = CursorUtil.isInBounds(mouseX, mouseY, switchX, switchY, 22.0f, 12.0f);
        this.drawSwitch(poseStack, switchX, switchY, Theme.rainbow, a, rainbowHovered);

        // Hue row.
        rowY += 32.0f;
        this.drawSliderRow(poseStack, "Hue", String.valueOf(Math.round(Theme.hue)), rowY,
                Mth.clamp(Theme.hue / 360.0f, 0.0f, 1.0f), a, Theme.rainbow ? 0.35f : 1.0f);
        if (this.draggingHue) {
            this.updateHue(mouseX);
        }

        // Saturation row.
        rowY += 32.0f;
        this.drawSliderRow(poseStack, "Saturation", String.valueOf(Math.round(Theme.saturation * 100.0f)), rowY,
                Mth.clamp(Theme.saturation, 0.0f, 1.0f), a, 1.0f);
        if (this.draggingSaturation) {
            this.updateSaturation(mouseX);
        }

        // Brightness row.
        rowY += 32.0f;
        this.drawSliderRow(poseStack, "Brightness", String.valueOf(Math.round(Theme.brightness * 100.0f)), rowY,
                Mth.clamp((Theme.brightness - 0.2f) / 0.8f, 0.0f, 1.0f), a, 1.0f);
        if (this.draggingBrightness) {
            this.updateBrightness(mouseX);
        }

        // Rainbow speed row.
        rowY += 32.0f;
        this.drawSliderRow(poseStack, "Speed", String.format("%.2fx", Theme.rainbowSpeed), rowY,
                Mth.clamp((Theme.rainbowSpeed - 0.2f) / 2.8f, 0.0f, 1.0f), a, Theme.rainbow ? 1.0f : 0.35f);
        if (this.draggingSpeed) {
            this.updateSpeed(mouseX);
        }

        // Reset button.
        float resetY = this.y + this.height - 24.0f;
        boolean resetHovered = CursorUtil.isInBounds(mouseX, mouseY, this.x + 10.0f, resetY, this.width - 20.0f, 16.0f);
        RenderUtil.drawRoundedRect(poseStack, this.x + 10.0f, resetY, this.width - 20.0f, 16.0f, 4.0f,
                ColorUtil.withAlpha(-1, (resetHovered ? 0.16f : 0.08f) * a));
        FontStore.AXIFORMA_BOLD_13.drawStringCentered(poseStack, "Reset", this.x + this.width / 2.0f,
                resetY + (16.0f - FontStore.AXIFORMA_BOLD_13.getFontHeight()) / 2.0f,
                ColorUtil.withAlpha(-1, a * 0.85f));
    }

    private void drawSliderRow(PoseStack poseStack, String label, String value, float rowY, float progress,
                               float alpha, float opacity) {
        this.drawLabel(poseStack, label, rowY, alpha);
        FontStore.AXIFORMA_BOLD_13.drawString(poseStack, value,
                this.x + this.width - 10.0f - FontStore.AXIFORMA_BOLD_13.getStringWidth(value),
                rowY + 1.0f, ColorUtil.withAlpha(-1, alpha * 0.9f * opacity));
        this.drawSlider(poseStack, this.x + 10.0f, rowY + 14.0f, this.width - 20.0f, progress, alpha, opacity);
    }

    private void drawLabel(PoseStack poseStack, String text, float rowY, float alpha) {
        FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, text, this.x + 10.0f,
                rowY + (14.0f - FontStore.AXIFORMA_REGULAR_14.getFontHeight()) / 2.0f + 1.0f,
                ColorUtil.withAlpha(-1, alpha * 0.8f));
    }

    private void drawSwitch(PoseStack poseStack, float x, float y, boolean on, float alpha, boolean hovered) {
        int track = ColorUtil.fromRGB(hovered ? 75 : 60, hovered ? 75 : 60, hovered ? 75 : 60);
        RenderUtil.drawRoundedRect(poseStack, x, y, 22.0f, 12.0f, 5.0f, ColorUtil.withAlpha(track, alpha));
        if (on) {
            RenderUtil.drawRoundedRect(poseStack, x, y, 12.0f, 12.0f, 5.0f,
                    ColorUtil.withAlpha(Theme.accent(), alpha));
        }
        RenderUtil.drawRoundedRect(poseStack, x + (on ? 11.0f : 1.0f), y + 1.0f, 10.0f, 10.0f, 4.5f,
                ColorUtil.withAlpha(-1, alpha));
    }

    private void drawSlider(PoseStack poseStack, float x, float y, float width, float progress, float alpha, float opacity) {
        progress = Mth.clamp(progress, 0.0f, 1.0f);
        RenderUtil.drawRoundedRect(poseStack, x, y, width, 5.0f, 2.0f,
                ColorUtil.withAlpha(ColorUtil.fromRGB(58, 58, 58), alpha * opacity));
        if (progress > 0.0f) {
            RenderUtil.drawRoundedRect(poseStack, x, y, Math.max(5.0f, width * progress), 5.0f, 2.0f,
                    ColorUtil.withAlpha(Theme.accent(), alpha * opacity));
        }
        float knobX = x + width * progress - 4.0f;
        RenderUtil.drawRoundedRect(poseStack, knobX, y - 2.0f, 8.0f, 9.0f, 3.0f,
                ColorUtil.withAlpha(-1, alpha * opacity));
    }

    private void updateHue(double mouseX) {
        Theme.hue = this.sliderRatio(mouseX) * 360.0f;
    }

    private void updateSaturation(double mouseX) {
        Theme.saturation = this.sliderRatio(mouseX);
    }

    private void updateBrightness(double mouseX) {
        Theme.brightness = 0.2f + this.sliderRatio(mouseX) * 0.8f;
    }

    private void updateSpeed(double mouseX) {
        Theme.rainbowSpeed = 0.2f + this.sliderRatio(mouseX) * 2.8f;
    }

    private float sliderRatio(double mouseX) {
        return (float) Mth.clamp((mouseX - (this.x + 10.0f)) / (this.width - 20.0f), 0.0f, 1.0f);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.open || !CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.x, this.y, this.width, this.height)) {
            return false;
        }

        float rowY = this.y + 32.0f;
        float switchX = this.x + this.width - 34.0f;
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, switchX, rowY + 1.0f, 22.0f, 12.0f)) {
            Theme.rainbow = !Theme.rainbow;
            return true;
        }

        rowY += 32.0f;
        if (this.isOverSlider(mouseX, mouseY, rowY)) {
            this.draggingHue = true;
            this.updateHue(mouseX);
            return true;
        }

        rowY += 32.0f;
        if (this.isOverSlider(mouseX, mouseY, rowY)) {
            this.draggingSaturation = true;
            this.updateSaturation(mouseX);
            return true;
        }

        rowY += 32.0f;
        if (this.isOverSlider(mouseX, mouseY, rowY)) {
            this.draggingBrightness = true;
            this.updateBrightness(mouseX);
            return true;
        }

        rowY += 32.0f;
        if (this.isOverSlider(mouseX, mouseY, rowY)) {
            this.draggingSpeed = true;
            this.updateSpeed(mouseX);
            return true;
        }

        float resetY = this.y + this.height - 24.0f;
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.x + 10.0f, resetY, this.width - 20.0f, 16.0f)) {
            Theme.reset();
            return true;
        }

        return true;
    }

    private boolean isOverSlider(double mouseX, double mouseY, float rowY) {
        return CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.x + 10.0f, rowY + 10.0f,
                this.width - 20.0f, 12.0f);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (this.draggingHue) {
            this.updateHue(mouseX);
            return true;
        }
        if (this.draggingSaturation) {
            this.updateSaturation(mouseX);
            return true;
        }
        if (this.draggingBrightness) {
            this.updateBrightness(mouseX);
            return true;
        }
        if (this.draggingSpeed) {
            this.updateSpeed(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean wasDragging = this.draggingHue || this.draggingSaturation
                || this.draggingBrightness || this.draggingSpeed;
        this.draggingHue = false;
        this.draggingSaturation = false;
        this.draggingBrightness = false;
        this.draggingSpeed = false;
        return wasDragging;
    }
}
