package shit.zen.modules.impl.render;

import com.mojang.math.Axis;
import java.time.LocalDateTime;
import net.minecraft.client.gui.GuiGraphics;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.utils.render.OverlayUtil;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;

/**
 * Material 3 expressive clock HUD, ported from NeoRaven's {@code ClockModule}.
 *
 * <p>The upstream dial, hands and seconds dot came from a custom renderer with a texture-backed
 * scalloped face. Since this port is geometry and text only, the face is drawn as a translucent
 * disc with tick marks, the hands and the rim dot are stroked with {@link GuiGraphics#fill}, and
 * the date is vanilla font text. The scale / percentage position / drag-while-chat gesture and all
 * upstream setting labels are preserved: dragging with left mouse while the chat screen is open
 * changes the two percentage sliders. Time is sampled once per tick, so the render loop allocates
 * nothing.</p>
 */
public class Clock extends Module {
    public static Clock INSTANCE;

    private static final int DIAL = 96;
    private static final int MARGIN = 10;
    private static final int EDGE = 20;
    private static final int SEGMENTS = 48;
    private static final float[] COS = new float[SEGMENTS];
    private static final float[] SIN = new float[SEGMENTS];
    private static final String[] DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

    static {
        for (int i = 0; i < SEGMENTS; i++) {
            double angle = i * Math.PI * 2.0 / SEGMENTS;
            COS[i] = (float) Math.cos(angle);
            SIN[i] = (float) Math.sin(angle);
        }
    }

    private final NumberSetting scaleSetting = new NumberSetting("Scale", 1.0, 0.5, 2.5, 0.05);
    private final NumberSetting positionXSetting = new NumberSetting("Position X", 50.0, 0.0, 100.0, 0.01);
    private final NumberSetting positionYSetting = new NumberSetting("Position Y", 12.0, 0.0, 100.0, 0.01);
    private final BooleanSetting shadowSetting = new BooleanSetting("Shadow", false);
    private final BooleanSetting showDateSetting = new BooleanSetting("Show date", false);
    private final BooleanSetting dateFollowsSetting = new BooleanSetting("Date follows seconds", false,
            () -> this.showDateSetting.getValue());
    private final BooleanSetting chineseSetting = new BooleanSetting("Chinese date", true);
    private final NumberSetting redSetting = new NumberSetting("Red", 103, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 80, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 164, 0, 255, 1);

    private int hour;
    private int minute;
    private int second;
    private String dateText = "1/1";
    private boolean dragging;
    private float dragOffsetX;
    private float dragOffsetY;

    public Clock() {
        super("Clock", Category.RENDER);
        INSTANCE = this;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        LocalDateTime now = LocalDateTime.now();
        this.hour = now.getHour();
        this.minute = now.getMinute();
        this.second = now.getSecond();
        if (this.chineseSetting.getValue()) {
            this.dateText = chinese(now.getMonthValue()) + "月" + chinese(now.getDayOfMonth()) + "日";
        } else {
            this.dateText = now.getMonthValue() + "/" + now.getDayOfMonth();
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender() || mc.player == null) {
            return;
        }
        GuiGraphics graphics = event.guiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float size = DIAL * this.scaleSetting.getValue().floatValue();
        float fit = Math.min(1.0F, Math.min((screenWidth - EDGE) / size, (screenHeight - EDGE) / size));
        float side = Math.max(16.0F, size * fit);
        float maxX = Math.max(0.0F, screenWidth - side - EDGE);
        float maxY = Math.max(0.0F, screenHeight - side - EDGE);
        int x = (int) (MARGIN + maxX * clamp(this.positionXSetting.getValue().floatValue() / 100.0F));
        int y = (int) (MARGIN + maxY * clamp(this.positionYSetting.getValue().floatValue() / 100.0F));

        if (mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen && mc.mouseHandler.leftDown) {
            float mouseX = (float) mc.mouseHandler.xpos * screenWidth / mc.getWindow().getWidth();
            float mouseY = (float) mc.mouseHandler.ypos * screenHeight / mc.getWindow().getHeight();
            if (!this.dragging && mouseX >= x && mouseX <= x + side && mouseY >= y && mouseY <= y + side) {
                this.dragging = true;
                this.dragOffsetX = mouseX - x;
                this.dragOffsetY = mouseY - y;
            }
            if (this.dragging) {
                this.positionXSetting.setValue(clamp((mouseX - this.dragOffsetX - MARGIN) / Math.max(1.0F, maxX) * 100.0F) * 100.0F);
                this.positionYSetting.setValue(clamp((mouseY - this.dragOffsetY - MARGIN) / Math.max(1.0F, maxY) * 100.0F) * 100.0F);
                x = (int) (MARGIN + maxX * clamp(this.positionXSetting.getValue().floatValue() / 100.0F));
                y = (int) (MARGIN + maxY * clamp(this.positionYSetting.getValue().floatValue() / 100.0F));
            }
        } else {
            this.dragging = false;
        }

        int radius = (int) (side * 0.5F);
        int cx = x + radius;
        int cy = y + radius;
        int accent = ColorUtil.fromARGB(
                this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(),
                this.blueSetting.getValue().intValue(), 255);

        if (this.shadowSetting.getValue()) {
            fillCircle(graphics, cx + 2, cy + 3, radius, 0x66000000);
        }
        fillCircle(graphics, cx, cy, radius, 0x99000000);
        for (int i = 0; i < SEGMENTS; i++) {
            int px = cx + (int) (COS[i] * (radius - 2));
            int py = cy + (int) (SIN[i] * (radius - 2));
            graphics.fill(px - 1, py - 1, px + 2, py + 2, i % 4 == 0 ? accent : 0x88FFFFFF);
        }

        float seconds = this.second + event.partialTick() / 20.0F;
        float hourAngle = ((this.hour % 12) + this.minute / 60.0F) / 12.0F * (float) (Math.PI * 2.0) - 1.5707963F;
        float minuteAngle = (this.minute + seconds / 60.0F) / 60.0F * (float) (Math.PI * 2.0) - 1.5707963F;
        float secondAngle = seconds / 60.0F * (float) (Math.PI * 2.0) - 1.5707963F;
        line(graphics, cx, cy, cx + (int) (Math.cos(hourAngle) * radius * 0.48F),
                cy + (int) (Math.sin(hourAngle) * radius * 0.48F), Math.max(2, radius / 14), accent);
        line(graphics, cx, cy, cx + (int) (Math.cos(minuteAngle) * radius * 0.72F),
                cy + (int) (Math.sin(minuteAngle) * radius * 0.72F), Math.max(2, radius / 20), 0xFFFFFFFF);
        int dotX = cx + (int) (Math.cos(secondAngle) * (radius - 5));
        int dotY = cy + (int) (Math.sin(secondAngle) * (radius - 5));
        graphics.fill(dotX - 2, dotY - 2, dotX + 3, dotY + 3, accent);
        graphics.fill(cx - 2, cy - 2, cx + 3, cy + 3, accent);

        if (this.showDateSetting.getValue() && radius >= 22) {
            int textWidth = mc.font.width(this.dateText);
            if (this.dateFollowsSetting.getValue()) {
                event.poseStack().pushPose();
                event.poseStack().translate(cx, cy, 0.0);
                event.poseStack().mulPose(Axis.ZP.rotationDegrees(-this.second * 6.0F));
                graphics.drawString(mc.font, this.dateText, -textWidth / 2, -4, 0xFFFFFFFF, true);
                event.poseStack().popPose();
            } else {
                graphics.drawCenteredString(mc.font, this.dateText, cx, cy + radius / 3, 0xFFFFFFFF);
            }
        }
    }

    private static void fillCircle(GuiGraphics graphics, int cx, int cy, int radius, int color) {
        int inner = radius - 1;
        for (int dy = -inner; dy <= inner; dy += 2) {
            int half = (int) Math.sqrt(Math.max(0, inner * inner - dy * dy));
            graphics.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 2, color);
        }
    }

    private static void line(GuiGraphics graphics, int x1, int y1, int x2, int y2, int thickness, int color) {
        int dx = Math.abs(x2 - x1);
        int sx = x1 < x2 ? 1 : -1;
        int dy = -Math.abs(y2 - y1);
        int sy = y1 < y2 ? 1 : -1;
        int error = dx + dy;
        int half = thickness / 2;
        while (true) {
            graphics.fill(x1 - half, y1 - half, x1 - half + thickness, y1 - half + thickness, color);
            if (x1 == x2 && y1 == y2) {
                return;
            }
            int twice = error * 2;
            if (twice >= dy) {
                error += dy;
                x1 += sx;
            }
            if (twice <= dx) {
                error += dx;
                y1 += sy;
            }
        }
    }

    private static float clamp(float value) {
        return value < 0.0F ? 0.0F : Math.min(value, 1.0F);
    }

    private static String chinese(int value) {
        if (value < 10) {
            return DIGITS[value];
        }
        if (value < 20) {
            return value == 10 ? "十" : "十" + DIGITS[value - 10];
        }
        if (value < 30) {
            return value == 20 ? "廿" : "廿" + DIGITS[value - 20];
        }
        return value == 30 ? "三十" : "三十" + DIGITS[value - 30];
    }
}
