package shit.zen.utils.misc;

import lombok.Generated;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import shit.zen.ClientBase;

public final class ChatUtil
extends ClientBase {
    public static void addMessage(Component component) {
        ChatComponent chatComponent = mc.gui.getChat();
        chatComponent.addMessage(component);
    }

    public static void print(String message) {
        ChatUtil.print(true, message);
    }

    /** Chat prefix shown in front of every client message, e.g. {@code [大肥鱼] }. */
    private static final String PREFIX = "§7[§b大肥鱼§7] ";

    public static void print(boolean withPrefix, String message) {
        ChatUtil.addMessage(Component.nullToEmpty((withPrefix ? PREFIX : "") + message));
    }

    @Generated
    private ChatUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}