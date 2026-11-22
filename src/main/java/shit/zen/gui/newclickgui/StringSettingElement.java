package shit.zen.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import shit.zen.gui.NewClickGui;
import shit.zen.render.CustomFont;
import shit.zen.render.FontStore;
import shit.zen.settings.impl.StringSetting;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.misc.CursorUtil;
import shit.zen.utils.misc.GuiI18n;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.RenderUtil;

/**
 * Text input row used by {@link StringSetting}. Click the field to start
 * editing, Esc/Enter to stop; all keystrokes are routed from
 * {@link NewClickGui#keyPressed(int, int, int)}.
 */
public class StringSettingElement
extends SettingElement<StringSetting> {
    private final SmoothAnimationTimer focusTimer = new SmoothAnimationTimer();
    private boolean focused;
    private boolean hovered;

    public StringSettingElement(CategoryPanel categoryPanel, StringSetting stringSetting) {
        super(categoryPanel, stringSetting);
    }

    @Override
    public float getHeight() {
        return 30.0f;
    }

    private float fieldX() {
        return this.x + 6.0f;
    }

    private float fieldY() {
        return this.y + 16.0f;
    }

    private float fieldWidth() {
        return 108.0f;
    }

    private float fieldHeight() {
        return 11.0f;
    }

    @Override
    public void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks) {
        this.hovered = CursorUtil.isInBounds(mouseX, mouseY, this.fieldX(), this.fieldY(), this.fieldWidth(), this.fieldHeight());
        this.visibilityTimer.animate(this.setting.getVisibility().displayable() ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW2);
        this.visibilityTimer.tick();
        this.focusTimer.animate(this.focused ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW2);
        this.focusTimer.tick();
        if (Mth.equal(alpha *= this.visibilityTimer.getValueF(), 0.0f)) {
            return;
        }
        float focusAmount = this.focusTimer.getValueF();
        CustomFont nameFont = GuiI18n.isChinese() ? FontStore.PINGFANG_12 : FontStore.AXIFORMA_REGULAR_14;
        String name = GuiI18n.translateSetting(this.setting.getName());
        if (nameFont.getStringWidth(name) > 100.0f) {
            name = name.substring(0, Math.min(12, name.length())) + "...";
        }
        nameFont.drawString(poseStack, name, this.x + 6.0f,
                this.y + (16.0f - nameFont.getFontHeight()) / 2.0f + 1.0f,
                ColorUtil.withAlpha(-1, alpha * 0.8f));

        float fx = this.fieldX();
        float fy = this.fieldY();
        float fw = this.fieldWidth();
        float fh = this.fieldHeight();
        RenderUtil.drawRoundedRect(poseStack, fx, fy, fw, fh, 3.0f,
                ColorUtil.withAlpha(ColorUtil.fromRGB(48, 48, 48), alpha * (0.55f + 0.25f * focusAmount)));
        if (focusAmount > 0.0f) {
            RenderUtil.drawRoundedRect(poseStack, fx - 0.5f, fy - 0.5f, fw + 1.0f, fh + 1.0f, 3.5f,
                    ColorUtil.withAlpha(Theme.accent(), alpha * 0.35f * focusAmount));
        }
        String value = this.setting.getValue() == null ? "" : this.setting.getValue();
        float textY = fy + (fh - FontStore.AXIFORMA_REGULAR_14.getFontHeight()) / 2.0f;
        FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, value, fx + 4.0f, textY,
                ColorUtil.withAlpha(-1, alpha * 0.95f));
        if (this.focused && System.currentTimeMillis() % 1000L < 500L) {
            float cursorX = fx + 4.0f + FontStore.AXIFORMA_REGULAR_14.getStringWidth(value) + 0.5f;
            RenderUtil.drawFilledRect(poseStack, cursorX, textY - 1.0f, 1.0f,
                    FontStore.AXIFORMA_REGULAR_14.getFontHeight(), ColorUtil.withAlpha(-1, alpha));
        }
        if (this.hovered && this.setting.getName().length() > 12) {
            this.parentPanel.setHoveredSettingElement(this);
            this.parentPanel.setTooltipText(GuiI18n.translateSetting(this.setting.getName()));
            this.parentPanel.setShowTooltip(true);
        } else if (this.parentPanel.getHoveredSettingElement() == this) {
            this.parentPanel.setShowTooltip(false);
            this.parentPanel.setHoveredSettingElement(null);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.setting.getVisibility().displayable()) {
            return false;
        }
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.fieldX(), this.fieldY(), this.fieldWidth(), this.fieldHeight())) {
            this.focused = true;
            return true;
        }
        this.focused = false;
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.focused) {
            return false;
        }
        if (keyCode == 259) {
            this.setting.backspace();
            return true;
        }
        if (keyCode == 256 || keyCode == 257 || keyCode == 335) {
            this.focused = false;
            return true;
        }
        return true;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.focused) {
            return false;
        }
        if (codePoint < 32 || codePoint == 167 || codePoint == '\t') {
            return false;
        }
        this.setting.append(codePoint);
        return true;
    }

    public boolean isFocused() {
        return this.focused;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
    }
}
