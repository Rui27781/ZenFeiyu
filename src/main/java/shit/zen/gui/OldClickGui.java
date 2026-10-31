package shit.zen.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import shit.zen.ZenClient;
import shit.zen.gui.legacy.CategoryPanel;
import shit.zen.gui.legacy.ModuleButton;
import shit.zen.gui.legacy.SettingComponent;
import shit.zen.gui.legacy.StringComponent;
import shit.zen.modules.Category;

public class OldClickGui
extends Screen {
    private final List<CategoryPanel> categoryPanels = new ArrayList<>();
    private static final String TITLE = "Click GUI";

    public OldClickGui() {
        super(Component.nullToEmpty(TITLE));
        int x = 20;
        for (Category category : Category.values()) {
            this.categoryPanels.add(new CategoryPanel(x, 20, 140, 20, category));
            x += 160;
        }
    }

    public void init() {
        super.init();
        // Eight categories no longer fit at 140px on common GUI scales. Use the
        // original 140px/20px layout when there is room, otherwise shrink the
        // panels (never below 84px) so the Client category stays reachable.
        int count = this.categoryPanels.size();
        int gap = 20;
        int panelWidth = 140;
        int available = Math.max(120, this.width - 16);
        if (count * panelWidth + Math.max(0, count - 1) * gap > available) {
            gap = 4;
            panelWidth = Math.max(84, (available - Math.max(0, count - 1) * gap) / count);
        }
        int totalWidth = count * panelWidth + Math.max(0, count - 1) * gap;
        int x = Math.max(4, (this.width - totalWidth) / 2);
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.width = panelWidth;
            categoryPanel.x = x;
            x += panelWidth + gap;
            for (ModuleButton moduleButton : categoryPanel.moduleButtons) {
                moduleButton.reset();
            }
        }
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.render(guiGraphics, mouseX, mouseY, partialTicks);
            categoryPanel.mouseDragged(mouseX, mouseY);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.mouseReleased(mouseX, mouseY, button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        StringComponent active = this.activeStringComponent();
        if (active != null && !active.isHovered(mouseX, mouseY)) {
            active.commit();
        }
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.mouseClicked(mouseX, mouseY, button);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        StringComponent active = this.activeStringComponent();
        if (active != null && active.keyPressed(keyCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        StringComponent active = this.activeStringComponent();
        if (active != null && active.charTyped(codePoint)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private StringComponent activeStringComponent() {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            for (ModuleButton moduleButton : categoryPanel.moduleButtons) {
                for (SettingComponent settingComponent : moduleButton.settingComponents) {
                    if (settingComponent instanceof StringComponent stringComponent && stringComponent.isEditing()) {
                        return stringComponent;
                    }
                }
            }
        }
        return null;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        for (CategoryPanel categoryPanel : this.categoryPanels) {
            categoryPanel.mouseScrolled(mouseX, mouseY, scrollDelta);
        }
        return true;
    }

    public void onClose() {
        if (ZenClient.isReady()) {
            ZenClient.instance.getConfigManager().saveAll();
        }
        super.onClose();
    }

    static {
        OldClickGui oldClickGui = new OldClickGui();
    }
}