package shit.zen.music;

import shit.zen.render.CustomFont;
import shit.zen.render.FontRenderer;

/** Small formatting helpers shared by the music HUD and the music screen. */
public final class MusicUtil {
    private MusicUtil() {
    }

    public static String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d", minutes, seconds);
    }

    public static String trim(CustomFont font, String text, float maxWidth) {
        if (text == null || text.isEmpty() || font == null) {
            return "";
        }
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        float ellipsisWidth = font.getStringWidth(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (font.getStringWidth(builder.toString() + c) + ellipsisWidth > maxWidth) {
                break;
            }
            builder.append(c);
        }
        return builder.append(ellipsis).toString();
    }

    public static String trim(FontRenderer font, String text, float maxWidth) {
        if (text == null || text.isEmpty() || font == null) {
            return "";
        }
        if (font.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        float ellipsisWidth = font.getWidth(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (font.getWidth(builder.toString() + c) + ellipsisWidth > maxWidth) {
                break;
            }
            builder.append(c);
        }
        return builder.append(ellipsis).toString();
    }
}
