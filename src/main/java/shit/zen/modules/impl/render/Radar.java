package shit.zen.modules.impl.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.utils.render.OverlayUtil;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;

/**
 * Small player radar, ported from NeoRaven's {@code RadarModule}.
 *
 * <p>The 100x100 panel, white border, centre marker, 19-block radius and yaw-relative dots are
 * drawn entirely with {@link GuiGraphics} rectangles; the old GL scissor and immediate-mode lines
 * are replaced by {@code GuiGraphics.enableScissor} and stepped fills. Bot filtering is dropped
 * because ZenFeiyu has no BotCheck service; dead players, spectators and the local player are
 * still skipped. All original setting labels are kept, plus separate RGB sliders for the dot.</p>
 */
public class Radar extends Module {
    private static final int LEFT = 5;
    private static final int TOP = 70;
    private static final int SIZE = 100;
    private static final int CENTER_X = LEFT + 50;
    private static final int CENTER_Y = TOP + 52;
    private static final double MAX_DIST_SQ = 360.0;

    private final BooleanSetting showTracerLinesSetting = new BooleanSetting("Show tracer lines", true);
    private final NumberSetting redSetting = new NumberSetting("Red", 255, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 40, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 40, 0, 255, 1);

    public Radar() {
        super("Radar", Category.RENDER);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender() || mc.player == null || mc.level == null) {
            return;
        }
        GuiGraphics graphics = event.guiGraphics();
        int right = LEFT + SIZE;
        int bottom = TOP + SIZE;

        graphics.fill(LEFT, TOP, right, bottom, 0x7D000000);
        graphics.fill(LEFT - 1, TOP - 1, right + 1, TOP, 0xFFFFFFFF);
        graphics.fill(LEFT - 1, bottom, right + 1, bottom + 1, 0xFFFFFFFF);
        graphics.fill(LEFT - 1, TOP, LEFT, bottom, 0xFFFFFFFF);
        graphics.fill(right, TOP, right + 1, bottom, 0xFFFFFFFF);
        circle(graphics, CENTER_X, CENTER_Y, 5, 0xFFFFFFFF);

        int dotColor = ColorUtil.fromARGB(this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue(), 255);
        boolean tracers = this.showTracerLinesSetting.getValue();
        graphics.enableScissor(LEFT, TOP, right, bottom);
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isDeadOrDying() || player.isSpectator()) {
                continue;
            }
            double distanceSq = mc.player.distanceToSqr(player);
            if (distanceSq > MAX_DIST_SQ) {
                continue;
            }
            double offsetX = player.getX() - mc.player.getX();
            double offsetZ = player.getZ() - mc.player.getZ();
            double angle = Math.atan2(offsetX, offsetZ) * 57.29578;
            double relative = (mc.player.getYRot() + angle) % 360.0;
            double distance = distanceSq / 5.0;
            double radians = Math.toRadians(relative);
            int dotX = CENTER_X - (int) (distance * Math.sin(radians));
            int dotY = CENTER_Y - (int) (distance * Math.cos(radians));
            if (tracers) {
                line(graphics, CENTER_X, CENTER_Y, dotX, dotY, 0xFFFFFFFF);
            }
            graphics.fill(dotX - 2, dotY - 2, dotX + 2, dotY + 2, dotColor);
        }
        graphics.disableScissor();
    }

    private static void circle(GuiGraphics graphics, int cx, int cy, int radius, int color) {
        int inner = radius - 1;
        for (int dy = -inner; dy <= inner; dy++) {
            int half = (int) Math.sqrt(Math.max(0, inner * inner - dy * dy));
            graphics.fill(cx - half, cy + dy, cx - half + 1, cy + dy + 1, color);
            graphics.fill(cx + half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    private static void line(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int sx = x1 < x2 ? 1 : -1;
        int dy = -Math.abs(y2 - y1);
        int sy = y1 < y2 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            graphics.fill(x1, y1, x1 + 1, y1 + 1, color);
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
}
