package shit.zen.modules.impl.render;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import shit.zen.ZenClient;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.manager.ModuleManager;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.OverlayUtil;

/**
 * Bound-module list, ported from NeoRaven's {@code KeybindsModule} / Onyx
 * {@code gui.onyx.hud.Keybinds}.
 *
 * <p>Rows are built from {@link ModuleManager#getModules()} once per tick (the snapshot avoids
 * rebuilding a stream every frame) and cached in fixed arrays. By default only enabled modules
 * with a non-zero {@link Module#getKey()} are shown; the "Show" mode can opt into the upstream
 * behaviour of listing every bound module with a dimmed off state. Key names come from
 * {@link shit.zen.modules.KeyBind}, so rebinding is reflected on the next tick. The Material card
 * and the compact retro style are both drawn with {@link GuiGraphics} geometry only.</p>
 */
public class Keybinds extends Module {
    public static Keybinds INSTANCE;

    private static final int MAX_ROWS = 64;
    private static final int ACCENT_DEFAULT = 0xFF6750A4;

    private final NumberSetting scaleSetting = new NumberSetting("Scale", 0.75, 0.4, 1.5, 0.05);
    private final NumberSetting positionXSetting = new NumberSetting("Position X", 0.0, 0.0, 100.0, 0.01);
    private final NumberSetting positionYSetting = new NumberSetting("Position Y", 25.0, 0.0, 100.0, 0.01);
    private final ModeSetting showSetting = new ModeSetting("Show", "Enabled only", "All")
            .withDefault("Enabled only");
    private final BooleanSetting showCountSetting = new BooleanSetting("Show count", true);
    private final ModeSetting styleSetting = new ModeSetting("Style", "Card", "Retro").withDefault("Card");
    private final BooleanSetting frostedSetting = new BooleanSetting("Frosted glass", true);
    private final BooleanSetting editPositionSetting = new BooleanSetting("Edit position", false);
    private final ModeSetting accentSetting = new ModeSetting("Accent", "Default", "Custom").withDefault("Default");
    private final NumberSetting redSetting = new NumberSetting("Red", 103, 0, 255, 1,
            () -> this.accentSetting.is("Custom"));
    private final NumberSetting greenSetting = new NumberSetting("Green", 80, 0, 255, 1,
            () -> this.accentSetting.is("Custom"));
    private final NumberSetting blueSetting = new NumberSetting("Blue", 164, 0, 255, 1,
            () -> this.accentSetting.is("Custom"));

    private final String[] names = new String[MAX_ROWS];
    private final String[] keyNames = new String[MAX_ROWS];
    private final boolean[] states = new boolean[MAX_ROWS];
    private int rowCount;
    private String countText = "0 bound";
    private boolean dragging;
    private float dragOffsetX;
    private float dragOffsetY;

    public Keybinds() {
        super("Keybinds", Category.RENDER);
        INSTANCE = this;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (ZenClient.getInstance() == null || ZenClient.getInstance().getModuleManager() == null) {
            this.rowCount = 0;
            return;
        }
        List<Module> modules = ZenClient.getInstance().getModuleManager().getModules();
        boolean enabledOnly = !this.showSetting.is("All");
        int count = 0;
        for (int i = 0; i < modules.size() && count < MAX_ROWS; i++) {
            Module module = modules.get(i);
            if (module == null || module == this || module.getKey() == 0) {
                continue;
            }
            boolean enabled = module.isEnabled();
            if (enabledOnly && !enabled) {
                continue;
            }
            this.names[count] = module.getName();
            this.keyNames[count] = module.getBind().getName();
            this.states[count] = enabled;
            count++;
        }
        this.rowCount = count;
        this.countText = count + " bound";
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender() || this.rowCount == 0) {
            return;
        }
        GuiGraphics graphics = event.guiGraphics();
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        float scale = this.scaleSetting.getValue().floatValue();
        int rowHeight = Math.max(12, (int) Math.round(18.0F * scale));
        int keyWidth = (int) Math.round(30.0F * scale) + 8;
        int header = this.showCountSetting.getValue() ? rowHeight : 0;
        int width = 140;
        int height = header + this.rowCount * rowHeight;

        int x = (int) (screenWidth * this.positionXSetting.getValue().floatValue() / 100.0F);
        int y = (int) (screenHeight * this.positionYSetting.getValue().floatValue() / 100.0F);
        if (this.editPositionSetting.getValue() && mc.mouseHandler.isLeftPressed()) {
            float mouseX = (float) mc.mouseHandler.xpos() * screenWidth / mc.getWindow().getWidth();
            float mouseY = (float) mc.mouseHandler.ypos() * screenHeight / mc.getWindow().getHeight();
            if (!this.dragging && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height) {
                this.dragging = true;
                this.dragOffsetX = mouseX - x;
                this.dragOffsetY = mouseY - y;
            }
            if (this.dragging) {
                this.positionXSetting.setValue(clamp((mouseX - this.dragOffsetX) / screenWidth * 100.0F));
                this.positionYSetting.setValue(clamp((mouseY - this.dragOffsetY) / screenHeight * 100.0F));
                x = (int) (screenWidth * this.positionXSetting.getValue().floatValue() / 100.0F);
                y = (int) (screenHeight * this.positionYSetting.getValue().floatValue() / 100.0F);
            }
        } else {
            this.dragging = false;
        }

        boolean retro = this.styleSetting.is("Retro");
        int accent = this.accent();
        if (!retro) {
            graphics.fill(x, y, x + width, y + height,
                    this.frostedSetting.getValue() ? 0x99000000 : 0x7D000000);
            graphics.fill(x, y, x + 3, y + height, accent);
        }
        int textY = y + 2;
        if (this.showCountSetting.getValue()) {
            graphics.drawString(mc.font, this.countText, x + 8, textY, 0xFFDDDDDD, false);
        }

        for (int i = 0; i < this.rowCount; i++) {
            int rowY = y + header + i * rowHeight;
            int textColor = this.states[i] ? 0xFFFFFFFF : 0xFF888888;
            int keyColor = this.states[i] ? accent : 0xFF666666;
            if (!retro && i % 2 == 1) {
                graphics.fill(x + 3, rowY, x + width, rowY + rowHeight, 0x0FFFFFFF);
            }
            graphics.drawString(mc.font, this.names[i], x + 8, rowY + (rowHeight - 8) / 2, textColor, false);

            int keyX = x + width - keyWidth - 6;
            graphics.fill(keyX, rowY + 2, keyX + keyWidth, rowY + rowHeight - 2, 0x66000000);
            graphics.fill(keyX, rowY + 2, keyX + keyWidth, rowY + 3, keyColor);
            graphics.fill(keyX, rowY + rowHeight - 3, keyX + keyWidth, rowY + rowHeight - 2, keyColor);
            graphics.fill(keyX, rowY + 2, keyX + 1, rowY + rowHeight - 2, keyColor);
            graphics.fill(keyX + keyWidth - 1, rowY + 2, keyX + keyWidth, rowY + rowHeight - 2, keyColor);
            String key = this.keyNames[i];
            graphics.drawCenteredString(mc.font, key, keyX + keyWidth / 2,
                    rowY + (rowHeight - 8) / 2, textColor);
        }
    }

    private int accent() {
        if (this.accentSetting.is("Custom")) {
            return ColorUtil.fromRGB(this.redSetting.getValue().intValue(),
                    this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue());
        }
        return ACCENT_DEFAULT;
    }

    private static float clamp(float value) {
        return value < 0.0F ? 0.0F : Math.min(value, 100.0F);
    }
}
