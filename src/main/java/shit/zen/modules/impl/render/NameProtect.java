package shit.zen.modules.impl.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import shit.zen.ZenClient;
import shit.zen.event.impl.ChatReceiveEvent;
import shit.zen.event.impl.DisconnectEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.StringSetting;
import shit.zen.event.EventTarget;

public class NameProtect
extends Module {
    /** 名称保护固定显示的 ID，按需求固定为“大肥鱼”。 */
    public static final String DEFAULT_FIXED_NAME = "大肥鱼";
    public static NameProtect INSTANCE;
    private final ModeSetting modeSetting = new ModeSetting("Mode", "Fixed", "Random").withDefault("Fixed");
    /** Fixed 模式下使用的昵称；空值时回退到“大肥鱼”。 */
    private final StringSetting fixedNameSetting = new StringSetting("Fixed Name", DEFAULT_FIXED_NAME, 24)
            .withDefault(DEFAULT_FIXED_NAME);
    private String cachedRandomName = null;
    private final Random random = new Random();

    public NameProtect() {
        super("NameProtect", Category.RENDER);
        INSTANCE = this;
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        if (this.modeSetting.is("Random")) {
            this.cachedRandomName = null;
        }
    }

    public static String replacePlayerName(String string) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) {
            return string;
        }
        if (mc.player == null) {
            return string;
        }
        try {
            String string2 = mc.player.getName().getString();
            String string3 = INSTANCE.protectedName();
            if (string3 != null && !string3.equals(string2) && string.contains(string2)) {
                return NameProtect.replaceNameToken(string, string2, string3);
            }
        } catch (Exception exception) {
            logger.error("Failed to replace player name", exception);
        }
        return string;
    }

    /**
     * Replaces {@code target} only when it appears as a standalone player name.
     * Substring replacement would rewrite unrelated words when the name is short
     * (e.g. a player called {@code a} turning "game" into "gSomeName").
     */
    private static String replaceNameToken(String text, String target, String replacement) {
        if (text == null || text.isEmpty() || target.isEmpty() || !text.contains(target)) {
            return text;
        }
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_\\u00a7])" + Pattern.quote(target) + "(?![A-Za-z0-9_])");
        return pattern.matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
    }

    /**
     * Replaces the local player's name inside a serialised chat component without
     * touching JSON keys. Replacing raw JSON would corrupt keys such as
     * {@code extra}/{@code translate}/{@code text} whenever the player name is a
     * substring of them (e.g. a one-letter name like {@code a}).
     *
     * @param json serialised {@link Component} JSON
     * @return the rewritten JSON, or the original string when nothing changed
     */
    public static String protectComponentJson(String json) {
        if (json == null || json.isEmpty() || INSTANCE == null || !INSTANCE.isEnabled() || mc.player == null) {
            return json;
        }
        try {
            String target = mc.player.getName().getString();
            if (target.isEmpty()) {
                return json;
            }
            String replacement = INSTANCE.protectedName();
            if (replacement == null || replacement.equals(target)) {
                return json;
            }
            JsonElement root = JsonParser.parseString(json);
            if (isText(root)) {
                String text = root.getAsString();
                if (!text.contains(target)) {
                    return json;
                }
                root = new JsonPrimitive(NameProtect.replaceNameToken(text, target, replacement));
            } else if (!protectElement(root, target, replacement)) {
                return json;
            }
            return root.toString();
        } catch (Exception exception) {
            logger.error("Failed to protect component JSON", exception);
            return json;
        }
    }

    private static boolean protectElement(JsonElement element, String target, String replacement) {
        if (element == null) {
            return false;
        }
        if (element.isJsonObject()) {
            return protectObject(element.getAsJsonObject(), target, replacement);
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            boolean changed = false;
            for (int i = 0; i < array.size(); i++) {
                JsonElement child = array.get(i);
                if (isText(child)) {
                    String text = child.getAsString();
                    if (text.contains(target)) {
                        array.set(i, new JsonPrimitive(NameProtect.replaceNameToken(text, target, replacement)));
                        changed = true;
                    }
                    continue;
                }
                changed |= protectElement(child, target, replacement);
            }
            return changed;
        }
        return false;
    }

    private static boolean protectObject(JsonObject object, String target, String replacement) {
        boolean changed = false;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if ("text".equals(entry.getKey()) && isText(value)) {
                String text = value.getAsString();
                if (text.contains(target)) {
                    entry.setValue(new JsonPrimitive(NameProtect.replaceNameToken(text, target, replacement)));
                    changed = true;
                }
                continue;
            }
            changed |= protectElement(value, target, replacement);
        }
        return changed;
    }

    private static boolean isText(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    public static String getProtectedName() {
        if (mc.player == null) {
            return DEFAULT_FIXED_NAME;
        }
        if (INSTANCE == null || !INSTANCE.isEnabled()) {
            return mc.player.getName().getString();
        }
        String string = mc.player.getName().getString();
        String string2 = INSTANCE.protectedName();
        if (string2 != null && !string2.equals(string)) {
            return string2;
        }
        return string;
    }

    /**
     * 当前应该展示的受保护名称：Fixed 模式永远使用固定 ID（默认“大肥鱼”），
     * Random 模式才从在线玩家中随机挑一个名字。
     */
    public String protectedName() {
        if (this.modeSetting.is("Random")) {
            String randomName = this.generateRandomName();
            return randomName == null ? this.fixedName() : randomName;
        }
        return this.fixedName();
    }

    /** 固定名称允许用户在设置里修改，但为空时始终回退到“大肥鱼”。 */
    private String fixedName() {
        String value = this.fixedNameSetting.getValue();
        if (value == null || value.isBlank()) {
            return DEFAULT_FIXED_NAME;
        }
        return value.trim();
    }

    private String generateRandomName() {
        if (mc.getConnection() == null) {
            return null;
        }
        ArrayList<PlayerInfo> arrayList = new ArrayList<>(mc.getConnection().getOnlinePlayers());
        ArrayList<String> arrayList2 = new ArrayList<>();
        String string = mc.player.getName().getString();
        for (PlayerInfo playerInfo : arrayList) {
            String string2 = playerInfo.getProfile().getName();
            if (string2.equals(string)) continue;
            arrayList2.add(string2);
        }
        if (arrayList2.isEmpty()) {
            return null;
        }
        if (this.cachedRandomName == null || !arrayList2.contains(this.cachedRandomName)) {
            this.cachedRandomName = arrayList2.get(this.random.nextInt(arrayList2.size()));
        }
        return this.cachedRandomName;
    }

    @EventTarget
    public void onChatReceive(ChatReceiveEvent chatReceiveEvent) {
        chatReceiveEvent.setComponent(Component.literal(NameProtect.replacePlayerName(chatReceiveEvent.getComponent().getString())));
    }
}