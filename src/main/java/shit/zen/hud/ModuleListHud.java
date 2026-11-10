package shit.zen.hud;

import java.awt.Color;
import java.util.List;
import java.util.stream.Collectors;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import shit.zen.ZenClient;
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.gui.newclickgui.Theme;
import shit.zen.modules.Module;
import shit.zen.modules.impl.render.Interface;
import shit.zen.render.DrawContext;
import shit.zen.render.FontPresets;
import shit.zen.render.FontRenderer;
import shit.zen.render.GlHelper;
import shit.zen.render.Paint;
import shit.zen.render.Renderer;
import shit.zen.render.RoundedRectangle;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.RenderUtil;

/**
 * Enabled-module list rendered as a stack of frosted glass pills.
 *
 * <p>Every row has its own soft drop shadow, a dark translucent body and a
 * vertical accent bar on the right edge. The bar uses
 * {@link Theme#accentAt(float)} so it follows the colour picker in the
 * ClickGUI (including rainbow mode).</p>
 */
public class ModuleListHud
extends HudElement {
    private static final float ROW_HEIGHT = 18.0f;
    private static final float ROW_GAP = 3.0f;
    private static final float RADIUS = 5.0f;
    private static final float LEFT_PADDING = 8.0f;
    private static final float BAR_WIDTH = 2.0f;
    private static final float BAR_RIGHT_PADDING = 5.0f;
    private static final float BAR_TEXT_GAP = 6.0f;
    private static final float BLUR_RADIUS = 14.0f;
    private static final FontRenderer FONT = FontPresets.pingfang(16.0f);
    private static final int BODY_COLOR = new Color(12, 14, 20, 165).getRGB();
    private static final int RIM_COLOR = new Color(255, 255, 255, 30).getRGB();
    private static final int SHADOW_COLOR = new Color(0, 0, 0, 120).getRGB();
    private static final int TEXT_COLOR = new Color(235, 235, 242, 245).getRGB();
    private static final int SUB_TEXT_COLOR = new Color(200, 200, 212, 235).getRGB();

    public ModuleListHud() {
        super("ModuleList");
        this.setX(4.0f);
        this.setY(4.0f);
    }

    private List<Module> getVisibleModules() {
        return ZenClient.getInstance().getModuleManager().getModules().stream()
                .filter(module -> !(module instanceof ModuleListHud) && !(module instanceof Interface))
                .filter(Module::isEnabled)
                .filter(module -> !module.getName().isEmpty())
                .sorted((a, b) -> Mth.ceil(GlHelper.getStringWidth(b.getName(), FONT)
                        - GlHelper.getStringWidth(a.getName(), FONT)))
                .collect(Collectors.toList());
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
        if (!this.isEnabled()) {
            return;
        }
        if (!ZenClient.getInstance().getModuleManager().getModule(Interface.class).isEnabled()) {
            return;
        }
        List<Module> visibleModules = this.getVisibleModules();
        String header = "Zen";
        String fps = "(" + mc.getFps() + "FPS)";
        float headerWidth = GlHelper.getStringWidth(header, FONT) + GlHelper.getStringWidth(fps, FONT);
        float textWidth = headerWidth;
        for (Module module : visibleModules) {
            textWidth = Math.max(textWidth, GlHelper.getStringWidth(module.getName(), FONT));
        }
        float rowWidth = LEFT_PADDING + textWidth + BAR_TEXT_GAP + BAR_WIDTH + BAR_RIGHT_PADDING;
        float totalHeight = ROW_HEIGHT + (visibleModules.isEmpty() ? 0.0f
                : ROW_GAP + visibleModules.size() * ROW_HEIGHT + (visibleModules.size() - 1) * ROW_GAP);
        this.setWidth(rowWidth);
        this.setHeight(totalHeight);

        PoseStack poseStack = render2DEvent.poseStack();
        float rowY = y;
        // Backdrop pass: all shadows first, then the frosted glass of every row.
        RenderUtil.drawShadow(poseStack, x + 1.5f, rowY + 2.5f, rowWidth - 3.0f, ROW_HEIGHT,
                8, SHADOW_COLOR);
        RenderUtil.drawBlurredRect(poseStack, x, rowY, rowWidth, ROW_HEIGHT, RADIUS, BLUR_RADIUS, 0.88f, 0);
        rowY += ROW_HEIGHT + ROW_GAP;
        for (int i = 0; i < visibleModules.size(); i++) {
            RenderUtil.drawShadow(poseStack, x + 1.5f, rowY + 2.5f, rowWidth - 3.0f, ROW_HEIGHT,
                    8, SHADOW_COLOR);
            RenderUtil.drawBlurredRect(poseStack, x, rowY, rowWidth, ROW_HEIGHT, RADIUS, BLUR_RADIUS, 0.88f, 0);
            rowY += ROW_HEIGHT + ROW_GAP;
        }

        Renderer.renderConsumer(drawContext -> {
            this.drawPill(drawContext, x, y, rowWidth, header, fps, 0);
            float moduleY = y + ROW_HEIGHT + ROW_GAP;
            for (int i = 0; i < visibleModules.size(); i++) {
                this.drawPill(drawContext, x, moduleY, rowWidth,
                        visibleModules.get(i).getName(), null, i + 1);
                moduleY += ROW_HEIGHT + ROW_GAP;
            }
        });
    }

    private void drawPill(DrawContext drawContext, float x, float y, float width,
                          String title, String suffix, int accentIndex) {
        try (Paint body = new Paint()) {
            body.setColor(BODY_COLOR);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, width, ROW_HEIGHT, RADIUS), body);
        }
        try (Paint rim = new Paint()) {
            rim.setColor(RIM_COLOR);
            rim.setStrokeCap(Paint.StrokeCap.STROKE);
            rim.setStrokeWidth(1.0f);
            drawContext.drawRoundedRect(
                    RoundedRectangle.ofXYWHR(x + 0.5f, y + 0.5f, width - 1.0f, ROW_HEIGHT - 1.0f, RADIUS - 0.5f),
                    rim);
        }

        int accent = Theme.accentAt(accentIndex * 12.0f);
        try (Paint bar = new Paint()) {
            bar.setColor(accent);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(
                    x + width - BAR_RIGHT_PADDING - BAR_WIDTH,
                    y + 4.0f,
                    BAR_WIDTH,
                    ROW_HEIGHT - 8.0f,
                    BAR_WIDTH / 2.0f), bar);
        }

        // DrawContext adds the font ascent and CustomFont shifts by one more
        // pixel, so compensate for both to centre the glyph box in the pill.
        float textY = y + (ROW_HEIGHT - FONT.getMetrics().getLineHeight()) / 2.0f
                - GlHelper.getFontAscent(FONT) - FONT.getMetrics().ascent() + 1.0f;
        float textX = x + LEFT_PADDING;
        GlHelper.drawTextShadowLegacy(title, textX, textY, FONT,
                suffix == null ? TEXT_COLOR : ColorUtil.withAlpha(accent, 1.0f));
        if (suffix != null) {
            float titleWidth = GlHelper.getStringWidth(title, FONT);
            GlHelper.drawTextShadowLegacy(suffix, textX + titleWidth, textY, FONT, SUB_TEXT_COLOR);
        }
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
    }

    @Override
    public void onSettings() {
    }
}
