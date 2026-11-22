package shit.zen.utils.misc;

import lombok.Generated;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import shit.zen.ClientBase;

public final class ChatUtil
extends ClientBase {
    /** Chat prefix shown in front of every client message, e.g. {@code [大肥鱼] }. */
    private static final String PREFIX = "§7[§b大肥鱼§7] ";
    public static final char SECTION = '§';
    public static final String COLOR = String.valueOf(SECTION);

    public static void addMessage(Component component) {
        ChatComponent chatComponent = mc.gui.getChat();
        chatComponent.addMessage(component);
    }

    public static void print(String message) {
        ChatUtil.print(true, message);
    }

    public static void print(boolean withPrefix, String message) {
        ChatUtil.addMessage(Component.nullToEmpty((withPrefix ? PREFIX : "") + message));
    }

    /** True when a world and local player exist; used to suppress chat/sound in menus. */
    public static boolean inWorld() {
        return mc.player != null && mc.level != null;
    }

    /** Translates NeoRaven-style {@code &} codes into Minecraft legacy {@code §} codes. */
    public static String color(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        return message.replace('&', SECTION);
    }

    /** {@code §#RRGGBB}, understood by this client's own text pipeline. */
    public static String hex(int argb) {
        return COLOR + "#" + String.format(java.util.Locale.ROOT, "%06X", argb & 0xFFFFFF);
    }

    /** Strips both legacy {@code §x} and extended {@code §#RRGGBB} colour codes. */
    public static String strip(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(message.length());
        boolean skip = false;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (skip) {
                skip = false;
                continue;
            }
            if (c == SECTION) {
                if (i + 1 < message.length() && message.charAt(i + 1) == '#') {
                    i += 7;
                } else {
                    skip = true;
                }
                continue;
            }
            if (c <= '\u0014' || c >= '\u007f') {
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    /** Module-scoped message that keeps the ZenFeiyu prefix. */
    public static void moduleMessage(String moduleName, String message) {
        ChatUtil.print(ChatUtil.color("&b" + moduleName + "&7: &r" + message));
    }

    /** Raw coloured message with no client prefix. */
    public static void raw(String message) {
        ChatUtil.print(false, message);
    }

    @Generated
    private ChatUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}