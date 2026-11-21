package shit.zen.gui;

import java.awt.Color;
import shit.zen.ClientBase;
import shit.zen.ZenClient;
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.render.FontPresets;
import shit.zen.render.FontRenderer;
import shit.zen.render.GlHelper;
import shit.zen.render.Paint;
import shit.zen.event.EventTarget;

public class IntroAnimation
extends ClientBase {
    private static volatile boolean isActive = false;
    private long startTime = -1L;
    private boolean finished = false;

    public IntroAnimation() {
        isActive = true;
    }

    public static boolean isRunning() {
        return isActive;
    }

    @EventTarget(value=4)
    public void onRender(GlRenderEvent glRenderEvent) {
        float fadeFactor;
        float bgAlpha;
        long elapsed;
        if (this.finished) {
            return;
        }
        if (this.startTime < 0L) {
            this.startTime = System.currentTimeMillis();
        }
        long dup = elapsed = System.currentTimeMillis() - this.startTime;
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float centerX = screenWidth / 2.0f;
        float centerY = screenHeight / 2.0f;
        long zAppearStart = 1100L;
        long fadeOutStart = zAppearStart + 1000L + 1200L;
        if (elapsed <= 800L) {
            float fadeIn = IntroAnimation.easeOutCubic(IntroAnimation.clamp01((float)elapsed / 800.0f));
            bgAlpha = 0.6f * fadeIn;
        } else if (elapsed <= fadeOutStart) {
            bgAlpha = 0.6f;
        } else if (elapsed <= fadeOutStart + 700L) {
            float fadeOut = 1.0f - IntroAnimation.easeInCubic(IntroAnimation.clamp01((float)(elapsed - fadeOutStart) / 700.0f));
            bgAlpha = 0.6f * fadeOut;
        } else {
            this.finish();
            return;
        }
        Paint paint = GlHelper.toPaint(new Color(0, 0, 0, (int)(bgAlpha * 255.0f)));
        GlHelper.drawRect(0.0f, 0.0f, screenWidth, screenHeight, paint);
        float logoScale = 1.0f;
        float logoAlpha = 0.0f;
        if (elapsed >= zAppearStart) {
            long sinceAppear = elapsed - zAppearStart;
            if (sinceAppear <= 1000L) {
                float appearProgress = IntroAnimation.easeOutCubic(IntroAnimation.clamp01((float)sinceAppear / 1000.0f));
                logoScale = IntroAnimation.lerp(2.0f, 1.0f, appearProgress);
                logoAlpha = appearProgress;
            } else {
                logoScale = 1.0f;
                logoAlpha = 1.0f;
            }
        }
        fadeFactor = 1.0f;
        if (elapsed > fadeOutStart) {
            fadeFactor = 1.0f - IntroAnimation.clamp01((float)(elapsed - fadeOutStart) / 700.0f);
        }
        String logoText = "大肥鱼";
        FontRenderer logoFont = FontPresets.pingfang(64.0f * logoScale);
        float logoWidth = GlHelper.getStringWidth(logoText, logoFont);
        float logoX = centerX - logoWidth / 2.0f;
        float logoY = centerY - logoFont.getMetrics().capHeight() / 2.0f;
        int logoColor = new Color(1.0f, 1.0f, 1.0f, IntroAnimation.clamp01(logoAlpha * fadeFactor)).getRGB();
        // Soft blue halo behind the wordmark: a ring of low-alpha copies
        // gives the glyphs a glow without needing a blur shader. It grows as
        // the logo fades in from nothing.
        float glowStrength = IntroAnimation.clamp01(logoAlpha * fadeFactor);
        if (glowStrength > 0.01f) {
            float spread = 2.5f + 3.0f * (1.0f - logoAlpha);
            int outerGlow = new Color(0.47f, 0.66f, 1.0f, 0.13f * glowStrength).getRGB();
            int innerGlow = new Color(0.60f, 0.78f, 1.0f, 0.18f * glowStrength).getRGB();
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2.0 * i / 8.0;
                float ox = (float)Math.cos(angle);
                float oy = (float)Math.sin(angle);
                GlHelper.drawText(logoText, logoX + ox * spread, logoY + oy * spread,
                        logoFont, outerGlow);
                GlHelper.drawText(logoText, logoX + ox * spread * 0.5f, logoY + oy * spread * 0.5f,
                        logoFont, innerGlow);
            }
        }
        GlHelper.drawText(logoText, logoX, logoY, logoFont, logoColor);
    }

    private void finish() {
        if (!this.finished) {
            this.finished = true;
            try {
                ZenClient.instance.getEventBus().unregister(this);
            } catch (Throwable throwable) {
                // empty catch block
            }
            isActive = false;
        }
    }

    private static float clamp01(float value) {
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    private static float easeOutCubic(float t) {
        float clamped = IntroAnimation.clamp01(t);
        clamped = (float)(1.0 - Math.pow(1.0f - clamped, 3.0));
        return clamped;
    }

    private static float easeInCubic(float t) {
        float clamped = IntroAnimation.clamp01(t);
        clamped = clamped * clamped * clamped;
        return clamped;
    }
}
