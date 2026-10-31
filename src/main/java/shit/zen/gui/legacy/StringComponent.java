package shit.zen.gui.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import shit.zen.render.FontStore;
import shit.zen.settings.Setting;
import shit.zen.settings.impl.StringSetting;
import shit.zen.utils.render.RenderUtil;

/**
 * Text-field row for {@link StringSetting} in the legacy ClickGui. Click the
 * row to start editing, Enter to commit, Esc to cancel. All keystrokes are
 * routed by {@link shit.zen.gui.OldClickGui}.
 */
public class StringComponent
extends SettingComponent {
    private final StringSetting stringSetting;
    private boolean editing;
    private String draft = "";

    public StringComponent(Setting<?> setting, ModuleButton moduleButton, int yOffset) {
        super(setting, moduleButton, yOffset);
        this.stringSetting = (StringSetting)setting;
    }

    public boolean isEditing() {
        return this.editing;
    }

    @Override
    public void renderWithAlpha(PoseStack poseStack, int mouseX, int mouseY, float partialTicks, float alpha) {
        int rowY = this.parentButton.panel.y + this.parentButton.yOffset + this.parentButton.panel.rowHeight + this.yOffset + 3;
        int panelX = this.parentButton.panel.x;
        int panelWidth = this.parentButton.panel.width;
        int rowHeight = this.parentButton.panel.rowHeight;
        int verticalPadding = 4;
        int contentHeight = rowHeight - verticalPadding * 2;
        int outerPadding = 8;
        int innerPadding = 4;
        int boxX = panelX + outerPadding + innerPadding;
        int boxWidth = panelWidth - (outerPadding + innerPadding) * 2;
        boolean hovered = mouseX >= boxX && mouseX <= boxX + boxWidth
                && mouseY >= rowY + verticalPadding && mouseY <= rowY + verticalPadding + contentHeight;
        Color boxColor = this.editing ? new Color(35, 35, 35, 220)
                : hovered ? new Color(45, 45, 45, 180) : new Color(25, 25, 25, 150);
        RenderUtil.drawFilledRect(poseStack, boxX, rowY + verticalPadding, boxWidth, contentHeight, boxColor.getRGB());
        Color edgeColor = this.editing ? new Color(138, 180, 248, 220) : new Color(100, 100, 100, 180);
        RenderUtil.drawFilledRect(poseStack, panelX + outerPadding + 2, rowY + verticalPadding,
                innerPadding - 2, contentHeight, edgeColor.getRGB());
        RenderUtil.drawFilledRect(poseStack, panelX + panelWidth - outerPadding - innerPadding, rowY + verticalPadding,
                innerPadding - 2, contentHeight, edgeColor.getRGB());

        String name = this.stringSetting.getName();
        float textY = (float)(rowY + verticalPadding)
                + ((float)contentHeight - FontStore.OPENSANS_16.getFontHeight()) / 2.0f - 1.5f;
        FontStore.OPENSANS_16.drawStringWithShadow(poseStack, name, boxX + innerPadding, textY,
                new Color(255, 255, 255, (int)(255.0f * alpha)).getRGB());

        String value = this.editing ? this.draft
                : this.stringSetting.getValue() == null ? "" : this.stringSetting.getValue();
        float valueMax = Math.max(8.0f, (float)boxWidth
                - FontStore.OPENSANS_16.getStringWidth(name) - (float)(innerPadding * 2 + 8));
        String shown = this.fit(value, valueMax);
        float valueX = (float)(boxX + boxWidth) - FontStore.OPENSANS_16.getStringWidth(shown) - (float)innerPadding;
        FontStore.OPENSANS_16.drawStringWithShadow(poseStack, shown, valueX, textY,
                new Color(138, 180, 248, (int)(255.0f * alpha)).getRGB());

        if (this.editing && System.currentTimeMillis() % 1000L < 500L) {
            float caretX = Math.min((float)(boxX + boxWidth - innerPadding),
                    valueX + FontStore.OPENSANS_16.getStringWidth(shown) + 1.0f);
            RenderUtil.drawFilledRect(poseStack, caretX, textY, 1.0f,
                    FontStore.OPENSANS_16.getFontHeight(), 0xFFFFFFFF);
        }
    }

    /** Keep the tail visible (URLs / long IDs), prefix with an ellipsis. */
    private String fit(String text, float maxWidth) {
        if (text == null) {
            return "";
        }
        if (FontStore.OPENSANS_16.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String out = text;
        while (!out.isEmpty() && FontStore.OPENSANS_16.getStringWidth("..." + out) > maxWidth) {
            out = out.substring(1);
        }
        return "..." + out;
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.isHovered(mouseX, mouseY) && !this.editing) {
            this.editing = true;
            this.draft = this.stringSetting.getValue() == null ? "" : this.stringSetting.getValue();
        }
    }

    public void commit() {
        if (!this.editing) {
            return;
        }
        this.editing = false;
        String trimmed = this.draft.trim();
        if (!trimmed.isEmpty()) {
            this.stringSetting.setValue(trimmed);
        }
        this.draft = "";
    }

    public void cancel() {
        this.editing = false;
        this.draft = "";
    }

    /** Returns true while editing so the ClickGui can consume the key. */
    public boolean keyPressed(int keyCode) {
        if (!this.editing) {
            return false;
        }
        if (keyCode == 259) {
            if (!this.draft.isEmpty()) {
                this.draft = this.draft.substring(0, this.draft.length() - 1);
            }
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            this.commit();
            return true;
        }
        if (keyCode == 256) {
            this.cancel();
            return true;
        }
        return true;
    }

    public boolean charTyped(char codePoint) {
        if (!this.editing || codePoint < 32 || codePoint == 127) {
            return false;
        }
        if (this.draft.length() >= this.stringSetting.getMaxLength()) {
            return false;
        }
        this.draft += codePoint;
        return true;
    }
}
