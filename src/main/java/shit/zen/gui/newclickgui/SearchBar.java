package shit.zen.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.gui.GuiGraphics;
import shit.zen.gui.NewClickGui;
import shit.zen.render.FontStore;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.misc.CursorUtil;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.RenderUtil;

/**
 * Search box shown at the top of the NewClickGui. Typing filters the category
 * panels live; {@link NewClickGui#getSearchQuery()} exposes the normalised text.
 */
public class SearchBar
extends UIElement {
    private static final int MAX_QUERY_LENGTH = 40;
    private static final String SEARCH_ICON = "\ue8b6";

    @Getter @Setter
    private String query = "";
    @Getter @Setter
    private boolean focused;
    private final SmoothAnimationTimer focusTimer = new SmoothAnimationTimer();
    private boolean hovered;

    @Override
    public void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks) {
        this.hovered = CursorUtil.isInBounds(mouseX, mouseY, this.x, this.y, this.width, this.height);
        this.focusTimer.animate(this.focused ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW2);
        this.focusTimer.tick();
        float focusAmount = this.focusTimer.getValueF();

        if (focusAmount > 0.0f) {
            RenderUtil.drawShadow(poseStack, this.x - 1.0f, this.y - 1.0f, this.width + 2.0f, this.height + 2.0f, 10,
                    ColorUtil.withAlpha(Theme.accent(), 0.35f * alpha * focusAmount));
        }
        RenderUtil.drawRoundedRect(poseStack, this.x, this.y, this.width, this.height, 6.0f,
                ColorUtil.withAlpha(CategoryPanel.BG_COLOR, alpha));
        RenderUtil.drawRoundedRect(poseStack, this.x + 0.5f, this.y + 0.5f, this.width - 1.0f, this.height - 1.0f, 5.5f,
                ColorUtil.withAlpha(ColorUtil.fromRGB(31, 31, 31), alpha));
        if (focusAmount > 0.0f) {
            RenderUtil.drawRoundedRect(poseStack, this.x + 1.0f, this.y + this.height - 2.0f,
                    (this.width - 2.0f) * focusAmount, 1.5f, 0.75f,
                    ColorUtil.withAlpha(Theme.accent(), alpha * focusAmount));
        }

        FontStore.MATERIAL_20.drawString(poseStack, SEARCH_ICON, this.x + 8.0f,
                this.y + (this.height - FontStore.MATERIAL_20.getFontHeight()) / 2.0f,
                ColorUtil.withAlpha(-1, alpha * (this.hovered || this.focused ? 0.9f : 0.65f)));

        float textX = this.x + 30.0f;
        float textY = this.y + (this.height - FontStore.AXIFORMA_REGULAR_16.getFontHeight()) / 2.0f;
        if (this.query.isEmpty() && !this.focused) {
            FontStore.AXIFORMA_REGULAR_16.drawString(poseStack, "Search modules...", textX, textY,
                    ColorUtil.withAlpha(-1, alpha * 0.4f));
        } else {
            FontStore.AXIFORMA_REGULAR_16.drawString(poseStack, this.query, textX, textY,
                    ColorUtil.withAlpha(-1, alpha * 0.9f));
            if (this.focused && System.currentTimeMillis() % 1000L < 500L) {
                float cursorX = textX + FontStore.AXIFORMA_REGULAR_16.getStringWidth(this.query) + 1.0f;
                RenderUtil.drawFilledRect(poseStack, cursorX, textY, 1.0f,
                        FontStore.AXIFORMA_REGULAR_16.getFontHeight(), ColorUtil.withAlpha(-1, alpha));
            }
        }

        if (!this.query.isEmpty()) {
            float clearX = this.x + this.width - 20.0f;
            float clearY = this.y + (this.height - 14.0f) / 2.0f;
            boolean clearHovered = CursorUtil.isInBounds(mouseX, mouseY, clearX, clearY, 14.0f, 14.0f);
            RenderUtil.drawRoundedRect(poseStack, clearX, clearY, 14.0f, 14.0f, 4.0f,
                    ColorUtil.withAlpha(-1, (clearHovered ? 0.18f : 0.08f) * alpha));
            FontStore.AXIFORMA_BOLD_13.drawStringCentered(poseStack, "x", clearX + 7.0f,
                    clearY + (14.0f - FontStore.AXIFORMA_BOLD_13.getFontHeight()) / 2.0f,
                    ColorUtil.withAlpha(-1, alpha * 0.8f));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.x, this.y, this.width, this.height)) {
            this.focused = false;
            return false;
        }
        if (!this.query.isEmpty()) {
            float clearX = this.x + this.width - 20.0f;
            float clearY = this.y + (this.height - 14.0f) / 2.0f;
            if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, clearX, clearY, 14.0f, 14.0f)) {
                this.query = "";
            }
        }
        this.focused = true;
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.focused) {
            return false;
        }
        if (keyCode == 259) { // backspace
            if (!this.query.isEmpty()) {
                this.query = this.query.substring(0, this.query.length() - 1);
            }
            return true;
        }
        if (keyCode == 256 || keyCode == 257 || keyCode == 335) { // esc / enter / numpad enter
            this.focused = false;
            return true;
        }
        return false;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.focused || this.query.length() >= MAX_QUERY_LENGTH) {
            return false;
        }
        if (codePoint < 32 || codePoint == 167) {
            return false;
        }
        this.query = this.query + codePoint;
        return true;
    }

    public void clear() {
        this.query = "";
    }

    @Override
    public void reset() {
        this.query = "";
        this.focused = false;
    }
}
