package shit.zen.modules.impl.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.OverlayUtil;

/**
 * Compact FPS / ping / coordinates / velocity HUD, ported from NeoRaven's {@code client.InfoHud}.
 *
 * <p>Upstream was a generic line sink that other modules appended to; here the four standard rows
 * are sampled directly. Every formatted value (and the row count) is computed once in
 * {@link #onTick(GameTickEvent)} — the render pass only measures already-built strings with
 * {@code Font.width} and issues {@link GuiGraphics} draws. The upstream two-column layout and its
 * three background styles are kept; rounded corners are drawn as geometry instead of a shader.</p>
 */
public class InfoHud extends Module {
    public static InfoHud INSTANCE;

    private static final int MAX_ROWS = 4;
    private static final int ACCENT_DEFAULT = 0xFF6750A4;

    private final BooleanSetting fpsSetting = new BooleanSetting("FPS", true);
    private final BooleanSetting pingSetting = new BooleanSetting("Ping", true);
    private final BooleanSetting coordsSetting = new BooleanSetting("Coordinates", true);
    private final BooleanSetting velocitySetting = new BooleanSetting("Velocity", true);
    private final ModeSetting backgroundSetting = new ModeSetting("Background", "None", "Rect", "Rounded")
            .withDefault("Rect");
    private final BooleanSetting shadowSetting = new BooleanSetting("Shadow", false);
    private final NumberSetting scaleSetting = new NumberSetting("Scale", 1.0, 0.5, 2.0, 0.05);
    private final NumberSetting positionXSetting = new NumberSetting("Position X", 0.0, 0.0, 100.0, 0.01);
    private final NumberSetting positionYSetting = new NumberSetting("Position Y", 10.0, 0.0, 100.0, 0.01);
    private final ModeSetting accentSetting = new ModeSetting("Accent", "Default", "Custom").withDefault("Default");
    private final NumberSetting redSetting = new NumberSetting("Red", 103, 0, 255, 1,
            () -> this.accentSetting.is("Custom"));
    private final NumberSetting greenSetting = new NumberSetting("Green", 80, 0, 255, 1,
            () -> this.accentSetting.is("Custom"));
    private final NumberSetting blueSetting = new NumberSetting("Blue", 164, 0, 255, 1,
            () -> this.accentSetting.is("Custom"));
    private final BooleanSetting editPositionSetting = new BooleanSetting("Edit position", false);

    private final String[] labels = new String[MAX_ROWS];
    private final String[] values = new String[MAX_ROWS];
    private int rowCount;
    private boolean dragging;
    private float dragOffsetX;
    private float dragOffsetY;

    public InfoHud() {
        super("InfoHUD", Category.RENDER);
        INSTANCE = this;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc.player == null) {
            this.rowCount = 0;
            return;
        }
        int row = 0;
        if (this.fpsSetting.getValue()) {
            this.labels[row] = "FPS";
            this.values[row] = String.valueOf(mc.getFps());
            row++;
        }
        if (this.pingSetting.getValue()) {
            int latency = -1;
            if (mc.player.connection != null) {
                PlayerInfo info = mc.player.connection.getPlayerInfo(mc.player.getUUID());
                if (info != null) {
                    latency = info.getLatency();
                }
            }
            this.labels[row] = "Ping";
            this.values[row] = latency < 0 ? "-- ms" : latency + " ms";
            row++;
        }
        if (this.coordsSetting.getValue()) {
            this.labels[row] = "XYZ";
            this.values[row] = round(mc.player.getX(), 1) + " / " + round(mc.player.getY(), 1)
                    + " / " + round(mc.player.getZ(), 1);
            row++;
        }
        if (this.velocitySetting.getValue()) {
            double dx = mc.player.getDeltaMovement().x;
            double dz = mc.player.getDeltaMovement().z;
            this.labels[row] = "Velocity";
            this.values[row] = round(Math.sqrt(dx * dx + dz * dz), 2) + " b/t";
            row++;
        }
        this.rowCount = row;
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
        int x = (int) (screenWidth * this.positionXSetting.getValue().floatValue() / 100.0F);
        int y = (int) (screenHeight * this.positionYSetting.getValue().floatValue() / 100.0F);

        int labelWidth = 0;
        int valueWidth = 0;
        for (int i = 0; i < this.rowCount; i++) {
            labelWidth = Math.max(labelWidth, mc.font.width(this.labels[i]));
            valueWidth = Math.max(valueWidth, mc.font.width(this.values[i]));
        }
        int lineHeight = Math.max(8, (int) Math.ceil(9.0F * scale));
        int step = Math.max(10, (int) Math.ceil(12.0F * scale));
        int width = labelWidth + 12 + valueWidth;
        int height = this.rowCount * step;

        if (this.editPositionSetting.getValue() && mc.mouseHandler.isLeftPressed()) {
            float mouseX = (float) mc.mouseHandler.xpos() * screenWidth / mc.getWindow().getWidth();
            float mouseY = (float) mc.mouseHandler.ypos() * screenHeight / mc.getWindow().getHeight();
            if (!this.dragging && mouseX >= x - 5 && mouseX <= x + width + 5
                    && mouseY >= y - 5 && mouseY <= y + height + 5) {
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

        String background = this.backgroundSetting.getValue();
        int accent = this.accent();
        if (!"None".equals(background)) {
            roundedFill(graphics, x - 5, y - 5, x + width + 5, y + height + 5, "Rounded".equals(background));
            graphics.fill(x - 5, y - 5, x - 3, y + height + 5, accent);
        }

        int textColor = ColorUtil.fromARGB(255, 255, 255, 255);
        boolean shadow = this.shadowSetting.getValue();
        for (int i = 0; i < this.rowCount; i++) {
            int rowY = y + i * step + (step - lineHeight) / 2;
            graphics.drawString(mc.font, this.labels[i], x, rowY, textColor, shadow);
            graphics.drawString(mc.font, this.values[i], x + width - mc.font.width(this.values[i]),
                    rowY, textColor, shadow);
        }
    }

    private int accent() {
        if (this.accentSetting.is("Custom")) {
            return ColorUtil.fromRGB(this.redSetting.getValue().intValue(),
                    this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue());
        }
        return ACCENT_DEFAULT;
    }

    private static void roundedFill(GuiGraphics graphics, int left, int top, int right, int bottom, boolean rounded) {
        graphics.fill(left + 2, top, right - 2, bottom, 0x7D000000);
        graphics.fill(left, top + 2, right, bottom - 2, 0x7D000000);
        if (rounded) {
            graphics.fill(left + 1, top + 1, right - 1, top + 2, 0x7D000000);
            graphics.fill(left + 1, bottom - 2, right - 1, bottom - 1, 0x7D000000);
        } else {
            graphics.fill(left, top, right, top + 2, 0x7D000000);
            graphics.fill(left, bottom - 2, right, bottom, 0x7D000000);
        }
    }

    private static String round(double value, int decimals) {
        double factor = decimals == 1 ? 10.0 : 100.0;
        return String.valueOf(Math.round(value * factor) / factor);
    }

    private static float clamp(float value) {
        return value < 0.0F ? 0.0F : Math.min(value, 100.0F);
    }
}
