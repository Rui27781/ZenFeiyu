package shit.zen.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import net.minecraft.client.gui.GuiGraphics;
import shit.zen.gui.NewClickGui;
import shit.zen.gui.newclickgui.BooleanSettingElement;
import shit.zen.gui.newclickgui.CategoryPanel;
import shit.zen.gui.newclickgui.ModeSettingElement;
import shit.zen.gui.newclickgui.MultiSelectSettingElement;
import shit.zen.gui.newclickgui.NumberSettingElement;
import shit.zen.gui.newclickgui.SettingElement;
import shit.zen.gui.newclickgui.StringSettingElement;
import shit.zen.gui.newclickgui.UIElement;
import shit.zen.modules.Module;
import shit.zen.render.CustomFont;
import shit.zen.render.FontStore;
import shit.zen.settings.Setting;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.MultiSelectSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.settings.impl.StringSetting;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.misc.CursorUtil;
import shit.zen.utils.misc.GuiI18n;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.RenderHelper;
import shit.zen.utils.render.RenderUtil;

public class ModuleElement
extends UIElement {
    public static final int BG_COLOR;
    @Getter
    private final List<SettingElement<?>> settingElements = new ArrayList<>();
    @Getter
    private final CategoryPanel parentPanel;
    @Getter
    private final Module module;
    @Getter
    private final SmoothAnimationTimer enabledTimer = new SmoothAnimationTimer();
    private final SmoothAnimationTimer hoveredTimer = new SmoothAnimationTimer();
    private final SmoothAnimationTimer expandTimer = new SmoothAnimationTimer();
    private final SmoothAnimationTimer settingsHeightTimer = new SmoothAnimationTimer();
    private boolean showingTooltip;
    private float posX;
    private float posY;
    private float totalHeight = 20.0f;
    @Getter @Setter
    private float scrollOffset;
    @Getter @Setter
    private boolean isHovered;
    @Getter @Setter
    private boolean isExpanded;
    private static final String BUILD_TAG;

    public ModuleElement(CategoryPanel categoryPanel, Module module) {
        this.parentPanel = categoryPanel;
        this.module = module;
        for (Setting setting : module.getSettings()) {
            if (setting instanceof BooleanSetting booleanSetting) {
                this.settingElements.add(new BooleanSettingElement(categoryPanel, booleanSetting));
                continue;
            }
            if (setting instanceof ModeSetting modeSetting) {
                this.settingElements.add(new ModeSettingElement(categoryPanel, modeSetting));
                continue;
            }
            if (setting instanceof MultiSelectSetting multiSelectSetting) {
                this.settingElements.add(new MultiSelectSettingElement(categoryPanel, multiSelectSetting));
                continue;
            }
            if (setting instanceof StringSetting stringSetting) {
                this.settingElements.add(new StringSettingElement(categoryPanel, stringSetting));
                continue;
            }
            if (!(setting instanceof NumberSetting numberSetting)) continue;
            this.settingElements.add(new NumberSettingElement(categoryPanel, numberSetting));
        }
    }

    @Override
    public void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks) {
        float titleY;
        float titleWidth;
        float enabledAmount;
        float settingsTotalHeight = 0.0f;
        for (SettingElement settingElement : this.settingElements) {
            if (!settingElement.getSetting().getVisibility().displayable()) continue;
            settingsTotalHeight += settingElement.getHeight();
        }
        this.settingsHeightTimer.animate(settingsTotalHeight, 0.2, Easings.EASE_OUT_POW2);
        this.settingsHeightTimer.tick();
        this.parentPanel.setCollapsed(!this.settingsHeightTimer.isDone());
        this.hoveredTimer.animate(this.isHovered ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW2);
        this.hoveredTimer.tick();
        this.enabledTimer.animate(this.module.isEnabled() ? 1.0 : 0.0, 0.3, Easings.EASE_OUT_POW2);
        this.enabledTimer.tick();
        this.expandTimer.animate(this.isExpanded ? 1.0 : 0.0, 0.2, Easings.EASE_OUT_POW3);
        this.expandTimer.tick();
        float expandAmount = this.expandTimer.getValueF();
        this.totalHeight = 20.0f + expandAmount * this.settingsHeightTimer.getValueF();
        this.isHovered = this.parentPanel.equals(NewClickGui.focusedPanel) && CursorUtil.isInBounds(mouseX, mouseY, this.posX, this.posY, 120.0f, this.totalHeight);
        if (this.isHovered) {
            String description = GuiI18n.description(this.module);
            this.parentPanel.setTooltipText(description);
            this.parentPanel.setShowTooltip(!description.isEmpty());
            this.showingTooltip = !description.isEmpty();
        } else if (this.showingTooltip) {
            this.parentPanel.setShowTooltip(false);
            this.showingTooltip = false;
        }
        RenderUtil.drawFilledRect(poseStack, this.posX + 1.0f, this.posY + 20.0f, 118.0f, this.totalHeight - 20.0f, ColorUtil.withAlpha(BG_COLOR, expandAmount * alpha));
        float hoverAmount = this.hoveredTimer.getValueF();
        enabledAmount = this.enabledTimer.getValueF();
        int rowAccent = Theme.accent();
        if (enabledAmount > 0.0f) {
            // 开启的模块铺一层极淡的强调色，并保留左侧色条，贴近 NeoRaven 的列表观感。
            RenderUtil.drawFilledRect(poseStack, this.posX + 0.5f, this.posY, 119.0f, 20.0f,
                    ColorUtil.withAlpha(rowAccent, 0.10f * alpha * enabledAmount));
        }
        if (hoverAmount > 0.0f) {
            RenderUtil.drawFilledRect(poseStack, this.posX + 0.5f, this.posY, 119.0f, 20.0f,
                    ColorUtil.withAlpha(-1, 0.06f * alpha * hoverAmount));
        }
        if (enabledAmount > 0.0f) {
            RenderUtil.drawRoundedRect(poseStack, this.posX + 2.0f, this.posY + 5.0f, 2.0f, 10.0f, 1.0f,
                    ColorUtil.withAlpha(rowAccent, alpha * enabledAmount));
        }
        CustomFont nameFont = GuiI18n.isChinese() ? FontStore.PINGFANG_16 : FontStore.AXIFORMA_REGULAR_16;
        CustomFont boldNameFont = GuiI18n.isChinese() ? FontStore.PINGFANG_16 : FontStore.AXIFORMA_BOLD_16;
        String nameText = GuiI18n.translateModule(this.module.getName());
        float textLeft = this.posX + 9.0f;
        if (1.0f - enabledAmount > 0.0f) {
            float disabledWidth = nameFont.getStringWidth(nameText);
            this.drawHighlightedName(poseStack, nameFont, nameText,
                    textLeft + disabledWidth / 2.0f, this.posY + (20.0f - nameFont.getFontHeight()) / 2.0f,
                    ColorUtil.fromRGB(196, 200, 212), rowAccent, alpha * (1.0f - enabledAmount) * 0.8f);
        }
        if (enabledAmount > 0.0f) {
            titleWidth = boldNameFont.getStringWidth(nameText);
            titleY = this.posY + (20.0f - boldNameFont.getFontHeight()) / 2.0f;
            RenderUtil.drawShadow(poseStack, textLeft, titleY + 1.5f, titleWidth, boldNameFont.getFontHeight() / 2.0f,
                    10, ColorUtil.withAlpha(rowAccent, alpha * enabledAmount * 0.30f));
            this.drawHighlightedName(poseStack, boldNameFont, nameText,
                    textLeft + titleWidth / 2.0f, titleY, -1, rowAccent, alpha * enabledAmount);
        }
        if (!this.module.getSettings().isEmpty()) {
            String arrowIcon = String.valueOf('\ueb4e');
            titleY = FontStore.MATERIAL_20.getStringWidth(arrowIcon);
            float arrowX = this.posX + 120.0f - titleY - 6.0f;
            float arrowY = this.posY + (20.0f - FontStore.MATERIAL_20.getFontHeight()) / 2.0f + 1.0f;
            RenderHelper.pushRotateAround(poseStack, arrowX + titleY / 2.0f, arrowY + FontStore.MATERIAL_20.getFontHeight() / 2.0f - 1.0f, 180.0f * expandAmount);
            FontStore.MATERIAL_20.drawString(poseStack, arrowIcon, arrowX, arrowY, ColorUtil.withAlpha(-1, (0.8f - 0.3f * expandAmount) * alpha));
            RenderHelper.popPose(poseStack);
        } else if (enabledAmount > 0.0f) {
            // 无设置项的模块用一个小圆点表示开启状态。
            RenderUtil.drawRoundedRect(poseStack, this.posX + 111.0f, this.posY + 8.5f, 3.0f, 3.0f, 1.5f,
                    ColorUtil.withAlpha(rowAccent, alpha * enabledAmount));
        }
        if (this.isExpanded) {
            titleWidth = this.posY + 20.0f;
            for (SettingElement settingElement : this.settingElements) {
                settingElement.setX(this.posX);
                settingElement.setY(titleWidth);
                settingElement.render(clickGui, guiGraphics, poseStack, mouseX, mouseY, alpha * expandAmount, partialTicks);
                titleWidth += settingElement.getAnimatedHeight() * settingElement.getVisibilityTimer().getValueF();
            }
        }
    }

    private void drawHighlightedName(PoseStack poseStack, CustomFont font, String text, float centerX, float y,
                                     int baseColor, int highlightColor, float alpha) {
        String query = this.parentPanel.getSearchQuery();
        float totalWidth = font.getStringWidth(text);
        float textX = centerX - totalWidth / 2.0f;
        int matchIndex = query.isEmpty() ? -1 : text.toLowerCase().indexOf(query);
        if (matchIndex < 0) {
            font.drawString(poseStack, text, textX, y, ColorUtil.withAlpha(baseColor, alpha));
            return;
        }
        String before = text.substring(0, matchIndex);
        String match = text.substring(matchIndex, matchIndex + query.length());
        String after = text.substring(matchIndex + query.length());
        if (!before.isEmpty()) {
            font.drawString(poseStack, before, textX, y, ColorUtil.withAlpha(baseColor, alpha));
            textX += font.getStringWidth(before);
        }
        font.drawString(poseStack, match, textX, y, ColorUtil.withAlpha(highlightColor, alpha));
        textX += font.getStringWidth(match);
        if (!after.isEmpty()) {
            font.drawString(poseStack, after, textX, y, ColorUtil.withAlpha(baseColor, alpha));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.isHovered) {
            return false;
        }
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.posX, this.posY, 120.0f, 20.0f)) {
            if (button == 0) {
                this.module.setEnabled(!this.module.isEnabled());
            } else if (button == 1 && !this.module.getSettings().isEmpty()) {
                this.isExpanded = !this.isExpanded;
            }
            return true;
        }
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.posX, this.posY + 20.0f, 120.0f, this.totalHeight - 20.0f)) {
            for (SettingElement<?> settingElement : this.settingElements) {
                if (settingElement.getSetting().getVisibility().displayable() && settingElement.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
        }
        return this.isHovered;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (SettingElement<?> settingElement : this.settingElements) {
            if (settingElement.mouseReleased(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.isExpanded) {
            return false;
        }
        for (SettingElement<?> settingElement : this.settingElements) {
            if (settingElement instanceof StringSettingElement stringElement
                    && stringElement.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return false;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.isExpanded) {
            return false;
        }
        for (SettingElement<?> settingElement : this.settingElements) {
            if (settingElement instanceof StringSettingElement stringElement
                    && stringElement.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return false;
    }

    public void clearSettingFocus() {
        for (SettingElement<?> settingElement : this.settingElements) {
            if (settingElement instanceof StringSettingElement stringElement) {
                stringElement.setFocused(false);
            }
        }
    }

    @Override
    @Generated
    public SmoothAnimationTimer getAnimTimer() {
        return this.hoveredTimer;
    }

    @Generated
    public SmoothAnimationTimer getHoveredTimer() {
        return this.expandTimer;
    }

    @Generated
    public SmoothAnimationTimer getExpandTimer() {
        return this.settingsHeightTimer;
    }

    @Override
    @Generated
    public float getX() {
        return this.posX;
    }

    @Override
    @Generated
    public float getY() {
        return this.posY;
    }

    @Override
    @Generated
    public float getHeight() {
        return this.totalHeight;
    }

    @Override
    @Generated
    public void setX(float x) {
        this.posX = x;
    }

    @Override
    @Generated
    public void setY(float y) {
        this.posY = y;
    }

    @Override
    @Generated
    public void setHeight(float height) {
        this.totalHeight = height;
    }

    static {
        BUILD_TAG = "17";
        BG_COLOR = ColorUtil.fromRGB(32, 32, 32);
    }
}