package shit.zen.gui;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NonNull;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import shit.zen.ZenClient;
import shit.zen.gui.newclickgui.CategoryPanel;
import shit.zen.gui.newclickgui.SearchBar;
import shit.zen.gui.newclickgui.Theme;
import shit.zen.gui.newclickgui.ThemePopup;
import shit.zen.modules.Category;
import shit.zen.render.FontStore;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.misc.CursorUtil;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.RenderUtil;

public class NewClickGui
extends Screen {
    private static final float PANEL_Y = 52.0f;
    private static final float THEME_BUTTON_SIZE = 26.0f;

    private static final List<CategoryPanel> categoryPanels;
    public static CategoryPanel focusedPanel;
    @Getter
    private boolean closing = false;
    @Getter
    private final SmoothAnimationTimer closeAnim = new SmoothAnimationTimer();
    @Getter
    private final SearchBar searchBar = new SearchBar();
    @Getter
    private final ThemePopup themePopup = new ThemePopup();
    private float themeButtonX;
    private float themeButtonY;
    private boolean themeButtonHovered;

    public NewClickGui() {
        super(Component.literal("ClickGui"));
    }

    protected void init() {
        super.init();
        focusedPanel = categoryPanels.get(0);
        float panelX = (float)this.width / 2.0f - 380.0f;
        for (CategoryPanel categoryPanel : categoryPanels) {
            categoryPanel.setX(panelX);
            categoryPanel.setY(PANEL_Y);
            panelX += 128.0f;
        }

        this.searchBar.setWidth(300.0f);
        this.searchBar.setHeight(26.0f);
        this.searchBar.setX((float)this.width / 2.0f - 150.0f);
        this.searchBar.setY(12.0f);

        this.themeButtonX = Math.min(this.searchBar.getX() + this.searchBar.getWidth() + 8.0f,
                (float)this.width - THEME_BUTTON_SIZE - 4.0f);
        this.themeButtonY = 12.0f;

        this.themePopup.setWidth(214.0f);
        this.themePopup.setHeight(216.0f);
        this.themePopup.setX(Math.max(4.0f, Math.min(this.themeButtonX, (float)this.width - 224.0f)));
        this.themePopup.setY(this.themeButtonY + THEME_BUTTON_SIZE + 6.0f);
    }

    public void render(@NonNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.closeAnim.animate(this.closing ? 0.0 : 1.0, 0.2, Easings.EASE_OUT_POW2);
        this.closeAnim.tick();
        float closeProgress = this.closeAnim.getValueF();
        if (Mth.equal(closeProgress, 0.0f) && this.closing) {
            this.closing = false;
            super.onClose();
            categoryPanels.forEach(CategoryPanel::reset);
            if (ZenClient.isReady()) {
                ZenClient.getInstance().getConfigManager().saveAll();
            }
            return;
        }

        String query = this.getSearchQuery();
        for (CategoryPanel categoryPanel : categoryPanels) {
            categoryPanel.setSearchQuery(query);
        }
        if (focusedPanel == null || focusedPanel.isFilteredOut()) {
            focusedPanel = null;
            for (CategoryPanel categoryPanel : categoryPanels) {
                if (!categoryPanel.isFilteredOut()) {
                    focusedPanel = categoryPanel;
                    break;
                }
            }
        }

        for (CategoryPanel categoryPanel : categoryPanels) {
            categoryPanel.render(this, guiGraphics, guiGraphics.pose(), mouseX, mouseY, closeProgress, partialTicks);
        }

        this.searchBar.render(this, guiGraphics, guiGraphics.pose(), mouseX, mouseY, closeProgress, partialTicks);
        this.renderThemeButton(guiGraphics, mouseX, mouseY, closeProgress);
        this.themePopup.render(this, guiGraphics, guiGraphics.pose(), mouseX, mouseY, closeProgress, partialTicks);

        if (!query.isEmpty() && focusedPanel == null) {
            FontStore.AXIFORMA_REGULAR_16.drawStringCentered(guiGraphics.pose(), "No modules found",
                    this.width / 2.0f, 120.0f, ColorUtil.withAlpha(-1, 0.5f * closeProgress));
        }
    }

    private void renderThemeButton(GuiGraphics guiGraphics, int mouseX, int mouseY, float alpha) {
        this.themeButtonHovered = CursorUtil.isInBounds(mouseX, mouseY, this.themeButtonX, this.themeButtonY,
                THEME_BUTTON_SIZE, THEME_BUTTON_SIZE);
        RenderUtil.drawRoundedRect(guiGraphics.pose(), this.themeButtonX, this.themeButtonY, THEME_BUTTON_SIZE,
                THEME_BUTTON_SIZE, 6.0f, ColorUtil.withAlpha(CategoryPanel.BG_COLOR, alpha));
        if (this.themePopup.isOpen()) {
            RenderUtil.drawRoundedRect(guiGraphics.pose(), this.themeButtonX, this.themeButtonY, THEME_BUTTON_SIZE,
                    THEME_BUTTON_SIZE, 6.0f, ColorUtil.withAlpha(Theme.accent(), 0.2f * alpha));
        } else if (this.themeButtonHovered) {
            RenderUtil.drawRoundedRect(guiGraphics.pose(), this.themeButtonX, this.themeButtonY, THEME_BUTTON_SIZE,
                    THEME_BUTTON_SIZE, 6.0f, ColorUtil.withAlpha(-1, 0.08f * alpha));
        }
        FontStore.MATERIAL_20.drawStringCentered(guiGraphics.pose(), "\ue40a",
                this.themeButtonX + THEME_BUTTON_SIZE / 2.0f,
                this.themeButtonY + (THEME_BUTTON_SIZE - FontStore.MATERIAL_20.getFontHeight()) / 2.0f,
                ColorUtil.withAlpha(Theme.accent(), alpha * (this.themeButtonHovered ? 1.0f : 0.8f)));
    }

    public void onClose() {
        this.closing = true;
        this.searchBar.setFocused(false);
        this.themePopup.setOpen(false);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.themePopup.isOpen() && this.themePopup.mouseClicked(mouseX, mouseY, button)) {
            this.searchBar.setFocused(false);
            return true;
        }
        if (CursorUtil.isInBounds((float)mouseX, (float)mouseY, this.themeButtonX, this.themeButtonY,
                THEME_BUTTON_SIZE, THEME_BUTTON_SIZE)) {
            this.themePopup.toggle();
            this.searchBar.setFocused(false);
            return true;
        }
        if (this.searchBar.mouseClicked(mouseX, mouseY, button)) {
            this.themePopup.setOpen(false);
            this.clearSettingFocus();
            return true;
        }
        if (this.themePopup.isOpen()) {
            this.themePopup.setOpen(false);
            this.searchBar.setFocused(false);
            return true;
        }

        this.searchBar.setFocused(false);
        this.themePopup.setOpen(false);
        this.clearSettingFocus();
        String query = this.getSearchQuery();
        for (CategoryPanel categoryPanel : categoryPanels) {
            categoryPanel.setSearchQuery(query);
        }
        for (CategoryPanel categoryPanel : categoryPanels) {
            if (categoryPanel.isFilteredOut()) {
                continue;
            }
            if (!categoryPanel.mouseClicked(mouseX, mouseY, button)) continue;
            focusedPanel = categoryPanel;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.themePopup.mouseReleased(mouseX, mouseY, button);
        for (CategoryPanel categoryPanel : categoryPanels) {
            categoryPanel.mouseReleased(mouseX, mouseY, button);
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.themePopup.mouseDragged(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    public boolean isPauseScreen() {
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        if (this.themePopup.isOpen() && CursorUtil.isInBounds((float)mouseX, (float)mouseY,
                this.themePopup.getX(), this.themePopup.getY(), this.themePopup.getWidth(), this.themePopup.getHeight())) {
            return true;
        }
        String query = this.getSearchQuery();
        for (CategoryPanel categoryPanel : categoryPanels) {
            categoryPanel.setSearchQuery(query);
        }
        for (CategoryPanel categoryPanel : categoryPanels) {
            if (categoryPanel.isFilteredOut()) {
                continue;
            }
            if (!categoryPanel.mouseScrolled(mouseX, mouseY, scrollDelta)) continue;
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.themePopup.isOpen() && keyCode == 256) {
            this.themePopup.setOpen(false);
            return true;
        }
        if (this.searchBar.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        for (CategoryPanel categoryPanel : categoryPanels) {
            if (!categoryPanel.isFilteredOut() && categoryPanel.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        if (keyCode == 256) {
            if (!this.searchBar.getQuery().isEmpty()) {
                this.searchBar.clear();
                return true;
            }
            this.onClose();
            return true;
        }
        // Ctrl+F focuses the search box.
        if (keyCode == 70 && (modifiers & 2) != 0) {
            this.searchBar.setFocused(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (this.searchBar.charTyped(codePoint, modifiers)) {
            return true;
        }
        for (CategoryPanel categoryPanel : categoryPanels) {
            if (!categoryPanel.isFilteredOut() && categoryPanel.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void clearSettingFocus() {
        for (CategoryPanel categoryPanel : categoryPanels) {
            categoryPanel.clearSettingFocus();
        }
    }

    /** Normalised search text used by the category panels for filtering. */
    public String getSearchQuery() {
        return this.searchBar.getQuery().trim().toLowerCase();
    }

    static {
        categoryPanels = new ArrayList<>();
        for (Category category : Category.values()) {
            categoryPanels.add(new CategoryPanel(category));
        }
    }
}
