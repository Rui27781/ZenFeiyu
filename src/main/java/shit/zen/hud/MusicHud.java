package shit.zen.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import net.minecraft.client.renderer.texture.AbstractTexture;
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.music.MusicManager;
import shit.zen.music.MusicUtil;
import shit.zen.music.Song;
import shit.zen.render.DrawContext;
import shit.zen.render.FontPresets;
import shit.zen.render.FontRenderer;
import shit.zen.render.GlHelper;
import shit.zen.render.Renderer;
import shit.zen.render.ResourceLocationWrapper;
import shit.zen.render.RoundedRectShader;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.render.RenderUtil;

/**
 * Rounded now-playing card. It only appears while a track is loading, playing
 * or paused, so the HUD stays out of the way when the player is not using the
 * music player.
 */
public class MusicHud extends HudElement {
    private static final float PANEL_WIDTH = 190.0f;
    private static final float PANEL_HEIGHT = 62.0f;
    private static final float COVER_SIZE = 44.0f;
    private static final FontRenderer TITLE_FONT = FontPresets.pingfang(14.0f);
    private static final FontRenderer ARTIST_FONT = FontPresets.pingfang(10.0f);
    private static final FontRenderer TIME_FONT = FontPresets.poppinsMedium(9.0f);
    private static final FontRenderer ICON_FONT = FontPresets.materialIcons(13.0f);

    private final SmoothAnimationTimer appearAnim = new SmoothAnimationTimer();

    public MusicHud() {
        super("MusicHUD");
        this.setX(10.0f);
        this.setY(142.0f);
        this.setWidth(PANEL_WIDTH);
        this.setHeight(PANEL_HEIGHT);
        this.setEnabled(true);
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
        MusicManager manager = MusicManager.getInstance();
        Song song = manager.getCurrentSong();
        boolean visible = song != null && manager.isActive();
        this.appearAnim.animate(visible ? 1.0 : 0.0, visible ? 0.2 : 0.3, Easings.EASE_OUT_POW2);
        this.appearAnim.tick();
        float fade = this.appearAnim.getValueF();
        if (fade <= 0.01f || song == null) {
            return;
        }

        PoseStack poseStack = render2DEvent.poseStack();
        poseStack.pushPose();
        RenderUtil.drawBlurredRect(poseStack, x, y, PANEL_WIDTH, PANEL_HEIGHT, 9.0f, 18.0f, 0.92f * fade, 0);
        poseStack.popPose();

        // GlHelper text needs an active Renderer canvas, so everything after
        // the blur is drawn inside a renderConsumer block.
        Renderer.renderConsumer(drawContext -> {
            // 1px liquid-glass rim, then the dark glass body.
            RenderUtil.drawRoundedRect(poseStack, x, y, PANEL_WIDTH, PANEL_HEIGHT, 9.0f,
                    new Color(255, 255, 255, (int) (58.0f * fade)).getRGB());
            RenderUtil.drawRoundedRect(poseStack, x + 1.0f, y + 1.0f, PANEL_WIDTH - 2.0f, PANEL_HEIGHT - 2.0f, 8.0f,
                    new Color(14, 16, 22, (int) (205.0f * fade)).getRGB());

            float coverX = x + 8.0f;
            float coverY = y + (PANEL_HEIGHT - COVER_SIZE) / 2.0f;
            this.drawCover(poseStack, song, coverX, coverY, COVER_SIZE, 8.0f, fade);

            float textX = coverX + COVER_SIZE + 9.0f;
            float textWidth = x + PANEL_WIDTH - textX - 10.0f;
            int titleColor = new Color(1.0f, 1.0f, 1.0f, fade).getRGB();
            int artistColor = new Color(0.70f, 0.76f, 0.86f, fade * 0.9f).getRGB();
            int mutedColor = new Color(0.60f, 0.64f, 0.72f, fade * 0.75f).getRGB();

            GlHelper.drawTextShadowLegacy(MusicUtil.trim(TITLE_FONT, song.getTitle(), textWidth),
                    textX, y + 9.0f, TITLE_FONT, titleColor);
            GlHelper.drawTextShadowLegacy(MusicUtil.trim(ARTIST_FONT, song.getArtist(), textWidth),
                    textX, y + 25.0f, ARTIST_FONT, artistColor);

            float barX = textX;
            float barY = y + PANEL_HEIGHT - 18.0f;
            float barWidth = x + PANEL_WIDTH - barX - 10.0f;
            float barHeight = 3.0f;
            RenderUtil.drawRoundedRect(poseStack, barX, barY, barWidth, barHeight, 1.5f,
                    new Color(255, 255, 255, (int) (40.0f * fade)).getRGB());
            float progress = manager.getProgress();
            if (progress > 0.0f) {
                RenderUtil.drawRoundedRect(poseStack, barX, barY, Math.max(1.5f, barWidth * progress), barHeight, 1.5f,
                        new Color(120, 220, 255, (int) (255.0f * fade)).getRGB());
            }

            String position = MusicUtil.formatTime(manager.getPositionMs());
            String duration = manager.getDurationMs() > 0L ? MusicUtil.formatTime(manager.getDurationMs()) : "--:--";
            GlHelper.drawTextShadowLegacy(position, barX, barY + 5.0f, TIME_FONT, mutedColor);
            float durationWidth = GlHelper.getStringWidth(duration, TIME_FONT);
            GlHelper.drawTextShadowLegacy(duration, barX + barWidth - durationWidth, barY + 5.0f, TIME_FONT, mutedColor);

            String stateIcon = manager.isPlaying() ? "\ue034" : "\ue037";
            GlHelper.drawText(stateIcon, x + PANEL_WIDTH - 19.0f, y + 8.0f, ICON_FONT,
                    new Color(1.0f, 1.0f, 1.0f, fade * 0.8f).getRGB());
        });
    }

    private void drawCover(PoseStack poseStack, Song song, float x, float y, float size, float radius, float alpha) {
        ResourceLocationWrapper wrapper = MusicManager.getInstance().getCover(song);
        if (wrapper != null && mc != null) {
            AbstractTexture texture = mc.getTextureManager().getTexture(wrapper.get());
            if (texture != null) {
                int color = (int) (255.0f * alpha) << 24 | 0xFFFFFF;
                RoundedRectShader shader = DrawContext.getRoundedRectShader();
                shader.drawTextured(poseStack.last().pose(), x, y, x + size, y + size,
                        radius, radius, radius, radius, color, texture.getId(), 0.0f, 0.0f, 1.0f, 1.0f);
                return;
            }
        }
        RenderUtil.drawRoundedRect(poseStack, x, y, size, size, radius,
                new Color(42, 46, 58, (int) (255.0f * alpha)).getRGB());
        FontRenderer iconFont = FontPresets.materialIcons(20.0f);
        GlHelper.drawText("\ue405", x + size / 2.0f - GlHelper.getStringWidth("\ue405", iconFont) / 2.0f,
                y + (size - 20.0f) / 2.0f, iconFont,
                new Color(1.0f, 1.0f, 1.0f, alpha * 0.45f).getRGB());
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
    }

    @Override
    public void onSettings() {
    }

    @Override
    public boolean isHovered(int mouseX, int mouseY) {
        return RenderUtil.isHovered(this.getX(), this.getY(), PANEL_WIDTH, PANEL_HEIGHT, mouseX, mouseY);
    }
}
