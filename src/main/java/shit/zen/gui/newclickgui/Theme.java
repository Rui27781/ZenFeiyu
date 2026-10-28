package shit.zen.gui.newclickgui;

import java.awt.Color;
import shit.zen.utils.render.ColorUtil;

/**
 * Shared accent/theme state for the NewClickGui.
 *
 * <p>The values in here are written to {@code theme.cfg} through
 * {@link shit.zen.config.ThemeConfig}, so a theme picked in the GUI survives a
 * restart. Everything in the NewClickGui package reads its accent colour from
 * this class instead of hard-coding a single ARGB literal.</p>
 */
public final class Theme {
    public static final float DEFAULT_HUE = 170.3743f;
    public static final float DEFAULT_SATURATION = 0.806f;
    public static final float DEFAULT_BRIGHTNESS = 0.91f;
    public static final float DEFAULT_RAINBOW_SPEED = 1.0f;

    /** When true the accent colour cycles through the hue wheel. */
    public static boolean rainbow = false;
    /** Hue in degrees (0-360) used when rainbow mode is off. */
    public static float hue = DEFAULT_HUE;
    /** HSB saturation / brightness of the accent colour. */
    public static float saturation = DEFAULT_SATURATION;
    public static float brightness = DEFAULT_BRIGHTNESS;
    /** Rainbow cycle speed multiplier. */
    public static float rainbowSpeed = DEFAULT_RAINBOW_SPEED;

    private static final float RAINBOW_PERIOD_MS = 10000.0f;

    private Theme() {
    }

    /** Current accent colour as ARGB. */
    public static int accent() {
        return accentAt(0.0f);
    }

    /**
     * Accent colour with a hue offset (in degrees). In non-rainbow mode the
     * offset is ignored and the fixed accent colour is returned.
     */
    public static int accentAt(float hueOffsetDegrees) {
        if (!rainbow) {
            return hsb(hue / 360.0f);
        }
        float period = RAINBOW_PERIOD_MS / Math.max(0.1f, rainbowSpeed);
        float progress = (System.currentTimeMillis() % (long) period) / period;
        float h = (progress + hueOffsetDegrees / 360.0f) % 1.0f;
        if (h < 0.0f) {
            h += 1.0f;
        }
        return hsb(h);
    }

    /** A darker variant of the current accent, used for gradients/shadows. */
    public static int accentDark() {
        Color color = new Color(accent());
        int red = (int) (color.getRed() * 0.55f);
        int green = (int) (color.getGreen() * 0.55f);
        int blue = (int) (color.getBlue() * 0.55f);
        return ColorUtil.fromRGB(red, green, blue);
    }

    public static int accentWithAlpha(float alphaScale) {
        return ColorUtil.withAlpha(accent(), alphaScale);
    }

    /**
     * Pulsing accent used by the category header. In rainbow mode the offset
     * turns into a hue offset so the header becomes an actual rainbow gradient.
     */
    public static int shimmer(long offsetMs) {
        if (rainbow) {
            return accentAt((float) (offsetMs % 4000L) / 4000.0f * 360.0f);
        }
        double progress = (double) ((System.currentTimeMillis() + offsetMs) % 4000L) / 2000.0;
        if (progress > 1.0) {
            progress = 2.0 - progress;
        }
        return ColorUtil.interpolateColor(accent(), accentDark(), progress);
    }

    public static void reset() {
        rainbow = false;
        hue = DEFAULT_HUE;
        saturation = DEFAULT_SATURATION;
        brightness = DEFAULT_BRIGHTNESS;
        rainbowSpeed = DEFAULT_RAINBOW_SPEED;
    }

    private static int hsb(float h) {
        return Color.HSBtoRGB(h, saturation, brightness) | 0xFF000000;
    }
}
