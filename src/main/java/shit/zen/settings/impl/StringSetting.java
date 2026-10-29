package shit.zen.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import shit.zen.settings.Setting;
import shit.zen.settings.SettingVisibility;

public class StringSetting
extends Setting<String> {
    @Getter
    private final int maxLength;

    public StringSetting(String name, String value) {
        this(name, value, 32);
    }

    public StringSetting(String name, String value, int maxLength) {
        super(name, value);
        this.maxLength = Math.max(1, maxLength);
    }

    public StringSetting(String name, String value, int maxLength, SettingVisibility settingVisibility) {
        super(name, value, settingVisibility);
        this.maxLength = Math.max(1, maxLength);
    }

    public StringSetting withDefault(String string) {
        this.setValue(string);
        return this;
    }

    public void append(char codePoint) {
        String current = this.getValue() == null ? "" : this.getValue();
        if (current.length() >= this.maxLength) {
            return;
        }
        this.setValue(current + codePoint);
    }

    public void backspace() {
        String current = this.getValue() == null ? "" : this.getValue();
        if (current.isEmpty()) {
            return;
        }
        this.setValue(current.substring(0, current.length() - 1));
    }

    @Override
    public void save(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getValue() == null ? "" : this.getValue());
    }

    @Override
    public void load(JsonElement jsonElement) {
        this.setValue(jsonElement.getAsString());
    }
}
