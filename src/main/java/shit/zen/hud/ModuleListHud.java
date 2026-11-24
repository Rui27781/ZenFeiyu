package shit.zen.hud;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
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
 * Enabled-module list rendered as one continuous frosted-glass panel.
 *
 * <p>The visual language follows the NeoRaven screenshots: a compact dark
 * header with a small gradient logo tile, a hairline rim, and a dense list of
 * left-aligned module names with the bound key on the right. Rows keep the
 * per-row accent colour used by the old pill layout, but no longer allocate a
 * separate panel/shadow for every module.</p>
 */
public class ModuleListHud
extends HudElement {
    private static final float HEADER_HEIGHT = 24.0f;
    private static final float ROW_HEIGHT = 16.0f;
    private static final float PANEL_PADDING = 6.0f;
    private static final float PANEL_RADIUS = 7.0f;
    private static final float LOGO_SIZE = 17.0f;
    private static final float BLUR_RADIUS = 16.0f;
    private static final float ACCENT_DOT = 2.5f;
    private static final FontRenderer FONT = FontPresets.pingfang(14.0f);
    private static final FontRenderer FONT_TITLE = FontPresets.poppinsBold(12.5f);
    private static final FontRenderer FONT_SUB = FontPresets.poppinsMedium(9.0f);
    private static final FontRenderer FONT_KEY = FontPresets.poppinsMedium(9.0f);
    private static final FontRenderer FONT_LOGO = FontPresets.zenIcon(14.0f);

    private static final int PANEL_COLOR = new Color(11, 12, 17, 205).getRGB();
    private static final int HEADER_COLOR = new Color(21, 23, 31, 225).getRGB();
    private static final int RIM_COLOR = new Color(255, 255, 255, 34).getRGB();
    private static final int SHADOW_COLOR = new Color(0, 0, 0, 135).getRGB();
    private static final int TEXT_COLOR = new Color(238, 239, 246, 245).getRGB();
    private static final int SUB_TEXT_COLOR = new Color(184, 187, 200, 230).getRGB();
    private static final int KEY_COLOR = new Color(156, 160, 176, 220).getRGB();
    private static final int SEPARATOR_COLOR = new Color(255, 255, 255, 15).getRGB();

    private List<Module> visibleModules = Collections.emptyList();
    private long visibleSignature = Long.MIN_VALUE;
    private float widestModule;
    private String cachedFpsText = "";
    private float cachedFpsWidth;
    private float cachedTitleWidth = -1.0f;
    private float cachedVersionWidth = -1.0f;

    public ModuleListHud() {
        super("ModuleList");
        this.setX(4.0f);
        this.setY(4.0f);
    }

    /**
     * Rebuilds the sorted visible-module list only when the enabled set
     * changes. The previous version filtered, sorted and measured glyph widths
     * on every frame, allocating a list and a stream pipeline 60+ times per
     * second for a list that almost never changes.
     */
    private void refreshVisibleModules() {
        List<Module> modules = ZenClient.getInstance().getModuleManager().getModules();
        long signature = 1469598103934665603L;
        int enabled = 0;
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            if (!module.isEnabled() || module instanceof ModuleListHud || module instanceof Interface) {
                continue;
            }
            String name = module.getName();
            if (name.isEmpty()) {
                continue;
            }
            signature = (signature ^ name.hashCode()) * 1099511628211L;
            ++enabled;
        }
        if (signature == this.visibleSignature && enabled == this.visibleModules.size()) {
            return;
        }
        ArrayList<Module> visible = new ArrayList<>(enabled);
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            if (module.isEnabled() && !(module instanceof ModuleListHud) && !(module instanceof Interface)
                    && !module.getName().isEmpty()) {
                visible.add(module);
            }
        }
        visible.sort((a, b) -> Float.compare(
                GlHelper.getStringWidth(b.getName(), FONT),
                GlHelper.getStringWidth(a.getName(), FONT)));
        float widest = 0.0f;
        for (int i = 0; i < visible.size(); i++) {
            widest = Math.max(widest, GlHelper.getStringWidth(visible.get(i).getName(), FONT));
        }
        this.visibleModules = visible;
        this.widestModule = widest;
        this.visibleSignature = signature;
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
        if (!this.isEnabled()) {
            return;
        }
        if (!ZenClient.getInstance().getModuleManager().getModule(Interface.class).isEnabled()) {
            return;
        }
        this.refreshVisibleModules();
        List<Module> visibleModules = this.visibleModules;

        if (this.cachedTitleWidth < 0.0f) {
            this.cachedTitleWidth = GlHelper.getStringWidth("Zen", FONT_TITLE);
            this.cachedVersionWidth = GlHelper.getStringWidth("v" + ZenClient.VERSION, FONT_SUB);
        }
        String fps = "(" + mc.getFps() + " FPS)";
        if (!fps.equals(this.cachedFpsText)) {
            this.cachedFpsText = fps;
            this.cachedFpsWidth = GlHelper.getStringWidth(fps, FONT_SUB);
        }

        float maxKeyWidth = 0.0f;
        for (int i = 0; i < visibleModules.size(); i++) {
            String key = visibleModules.get(i).getBind().getName();
            if (key != null && !"None".equals(key)) {
                maxKeyWidth = Math.max(maxKeyWidth, GlHelper.getStringWidth(key, FONT_KEY));
            }
        }

        float headerWidth = LOGO_SIZE + 7.0f + this.cachedTitleWidth + 5.0f
                + this.cachedVersionWidth + 12.0f + this.cachedFpsWidth;
        float rowTextWidth = LOGO_SIZE + 7.0f + this.widestModule + 8.0f + maxKeyWidth;
        float contentWidth = Math.max(headerWidth, rowTextWidth);
        float panelWidth = Math.max(104.0f, PANEL_PADDING * 2.0f + contentWidth);
        float panelHeight = HEADER_HEIGHT + visibleModules.size() * ROW_HEIGHT + PANEL_PADDING;
        this.setWidth(panelWidth);
        this.setHeight(panelHeight);

        PoseStack poseStack = render2DEvent.poseStack();
        RenderUtil.drawShadow(poseStack, x + 1.5f, y + 2.5f, panelWidth - 3.0f, panelHeight,
                10, SHADOW_COLOR);
        RenderUtil.drawBlurredRect(poseStack, x, y, panelWidth, panelHeight, PANEL_RADIUS, BLUR_RADIUS, 0.9f, 0);

        float textX = x + PANEL_PADDING + LOGO_SIZE + 7.0f;
        float rowY = y + HEADER_HEIGHT;
        final float panelX = x;
        final float panelW = panelWidth;
        final float panelH = panelHeight;
        final float headerTextX = textX;
        final float firstRowY = rowY;
        final float keyWidth = maxKeyWidth;
        final float moduleWidth = this.widestModule;
        final String fpsText = this.cachedFpsText;
        final float fpsWidth = this.cachedFpsWidth;

        Renderer.renderConsumer(drawContext -> {
            try (Paint panel = new Paint()) {
                panel.setColor(PANEL_COLOR);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(panelX, y, panelW, panelH, PANEL_RADIUS), panel);
            }
            try (Paint header = new Paint()) {
                header.setColor(HEADER_COLOR);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(panelX, y, panelW, HEADER_HEIGHT,
                        new float[]{PANEL_RADIUS, PANEL_RADIUS, 0.0f, 0.0f, 0.0f, 0.0f,
                                PANEL_RADIUS, PANEL_RADIUS}), header);
            }
            // Hairline rim and a very soft top sheen.
            try (Paint rim = new Paint()) {
                rim.setColor(RIM_COLOR);
                rim.setStrokeCap(Paint.StrokeCap.STROKE);
                rim.setStrokeWidth(1.0f);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(panelX + 0.5f, y + 0.5f,
                        panelW - 1.0f, panelH - 1.0f, PANEL_RADIUS - 0.5f), rim);
            }
            try (Paint sheen = new Paint()) {
                sheen.setGradCoords(new Paint.GradientCoords(panelX, y, panelX, y + HEADER_HEIGHT,
                        new Color(255, 255, 255, 24).getRGB(), new Color(255, 255, 255, 0).getRGB()));
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHRadii(panelX, y, panelW, HEADER_HEIGHT,
                        new float[]{PANEL_RADIUS, PANEL_RADIUS, 0.0f, 0.0f, 0.0f, 0.0f,
                                PANEL_RADIUS, PANEL_RADIUS}), sheen);
            }

            // Gradient logo tile.
            try (Paint logo = new Paint()) {
                logo.setGradCoords(new Paint.GradientCoords(panelX + PANEL_PADDING, y + 3.5f,
                        panelX + PANEL_PADDING + LOGO_SIZE, y + 3.5f + LOGO_SIZE,
                        Theme.accentAt(0.0f), Theme.accentAt(42.0f)));
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(panelX + PANEL_PADDING, y + 3.5f,
                        LOGO_SIZE, LOGO_SIZE, 4.5f), logo);
            }
            float logoTextX = panelX + PANEL_PADDING + (LOGO_SIZE - GlHelper.getStringWidth("Z", FONT_LOGO)) / 2.0f;
            float logoTextY = y + 3.5f + (LOGO_SIZE - FONT_LOGO.getMetrics().getLineHeight()) / 2.0f
                    - GlHelper.getFontAscent(FONT_LOGO) - FONT_LOGO.getMetrics().ascent() + 1.5f;
            GlHelper.drawTextShadowLegacy("Z", logoTextX, logoTextY, FONT_LOGO,
                    ColorUtil.withAlpha(-1, 1.0f));

            float titleY = y + (HEADER_HEIGHT - FONT_TITLE.getMetrics().getLineHeight()) / 2.0f
                    - GlHelper.getFontAscent(FONT_TITLE) - FONT_TITLE.getMetrics().ascent() + 0.5f;
            GlHelper.drawTextShadowLegacy("Zen", headerTextX, titleY, FONT_TITLE,
                    ColorUtil.withAlpha(-1, 1.0f));
            GlHelper.drawTextShadowLegacy("v" + ZenClient.VERSION,
                    headerTextX + this.cachedTitleWidth + 5.0f, titleY + 1.5f, FONT_SUB,
                    ColorUtil.withAlpha(Theme.accentAt(30.0f), 0.9f));
            float fpsX = panelX + panelW - PANEL_PADDING - fpsWidth;
            GlHelper.drawTextShadowLegacy(fpsText, fpsX, titleY + 1.5f, FONT_SUB,
                    ColorUtil.withAlpha(SUB_TEXT_COLOR, 0.85f));

            // Accent line under the header.
            try (Paint accent = new Paint()) {
                accent.setGradCoords(new Paint.GradientCoords(panelX, y + HEADER_HEIGHT - 1.0f,
                        panelX + panelW, y + HEADER_HEIGHT - 1.0f,
                        Theme.accentAt(0.0f), Theme.accentAt(65.0f)));
                drawContext.drawRectXYWH(panelX, y + HEADER_HEIGHT - 1.0f, panelW, 1.0f, accent);
            }

            float moduleY = firstRowY;
            for (int i = 0; i < visibleModules.size(); i++) {
                Module module = visibleModules.get(i);
                if (i > 0) {
                    try (Paint separator = new Paint()) {
                        separator.setColor(SEPARATOR_COLOR);
                        drawContext.drawRectXYWH(panelX + PANEL_PADDING, moduleY, panelW - PANEL_PADDING * 2.0f,
                                0.5f, separator);
                    }
                }
                int accentColor = Theme.accentAt(i * 16.0f);
                try (Paint dot = new Paint()) {
                    dot.setColor(accentColor);
                    drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(panelX + PANEL_PADDING + 1.0f,
                            moduleY + (ROW_HEIGHT - ACCENT_DOT) / 2.0f, ACCENT_DOT, ACCENT_DOT,
                            ACCENT_DOT / 2.0f), dot);
                }
                float moduleTextY = moduleY + (ROW_HEIGHT - FONT.getMetrics().getLineHeight()) / 2.0f
                        - GlHelper.getFontAscent(FONT) - FONT.getMetrics().ascent() + 1.0f;
                GlHelper.drawTextShadowLegacy(module.getName(), headerTextX, moduleTextY, FONT,
                        ColorUtil.withAlpha(TEXT_COLOR, 0.96f));
                String key = module.getBind().getName();
                if (key != null && !"None".equals(key)) {
                    float keyX = panelX + panelW - PANEL_PADDING - GlHelper.getStringWidth(key, FONT_KEY);
                    GlHelper.drawTextShadowLegacy(key, keyX, moduleTextY + 1.0f, FONT_KEY,
                            ColorUtil.withAlpha(KEY_COLOR, 0.9f));
                }
                moduleY += ROW_HEIGHT;
            }
        });
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
    }

    @Override
    public void onSettings() {
    }
}
