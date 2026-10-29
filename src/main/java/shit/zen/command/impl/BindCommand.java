package shit.zen.command.impl;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import shit.zen.ZenClient;
import shit.zen.command.Command;
import shit.zen.event.EventTarget;
import shit.zen.exception.ModuleNotFoundException;
import shit.zen.modules.Module;
import shit.zen.utils.misc.ChatUtil;

public class BindCommand
extends Command {
    public static final class EventHandler {
        private final BindCommand parent;
        private final Module module;
        private final String name;

        public EventHandler(BindCommand parent, Module module, String name) {
            this.parent = parent;
            this.module = module;
            this.name = name;
        }

        @EventTarget
        public void onKey(shit.zen.event.impl.KeyEvent event) {
            if (!event.isPressed()) return;
            int keyCode = event.getKeyCode();
            if (keyCode == 256) {
                module.setKey(0);
                ChatUtil.print("Unbound " + this.name + ".");
            } else {
                module.setKey(keyCode);
                ChatUtil.print("Bound " + this.name + " to key " + keyCode + ".");
            }
            ZenClient.getInstance().getEventBus().unregister(this);
            ZenClient.getInstance().getConfigManager().saveAll();
        }
    }

    public BindCommand() {
        super("bind", new String[]{"b"});
    }

    @Override
    public void onCommand(String[] args) {
        if (args.length == 1) {
            this.handleBindInteractive(args[0]);
        } else if (args.length == 2) {
            this.handleBindExplicit(args[0], args[1]);
        } else {
            ChatUtil.print("Usage: .bind <module> [key]");
        }
    }

    private void handleBindInteractive(String moduleName) {
        try {
            Module module = ZenClient.getInstance().getModuleManager().getModule(moduleName);
            if (module == null) {
                ChatUtil.print("Invalid module.");
                return;
            }
            ChatUtil.print("Press a key to bind " + moduleName + " to.");
            ZenClient.getInstance().getEventBus().register(new BindCommand.EventHandler(this, module, moduleName));
        } catch (ModuleNotFoundException e) {
            ChatUtil.print("Invalid module.");
        }
    }

    private void handleBindExplicit(String moduleName, String keyName) {
        try {
            Module module = ZenClient.getInstance().getModuleManager().getModule(moduleName);
            if (module == null) {
                ChatUtil.print("Invalid module.");
                return;
            }
            if (keyName.equalsIgnoreCase("none")) {
                module.setKey(InputConstants.UNKNOWN.getValue());
                ChatUtil.print("Unbound " + moduleName + ".");
                ZenClient.getInstance().getConfigManager().saveAll();
                return;
            }
            InputConstants.Key key = BindCommand.parseKey(keyName);
            if (key == InputConstants.UNKNOWN) {
                ChatUtil.print("Invalid key.");
                return;
            }
            module.setKey(key.getValue());
            ChatUtil.print("Bound " + moduleName + " to " + keyName.toUpperCase() + ".");
            ZenClient.getInstance().getConfigManager().saveAll();
        } catch (ModuleNotFoundException e) {
            ChatUtil.print("Invalid module.");
        }
    }

    /**
     * Resolves a user supplied key name. {@link InputConstants#getKey(String)}
     * throws {@link NumberFormatException} for anything it does not know, so the
     * lookup is guarded and common aliases are normalised first.
     */
    private static InputConstants.Key parseKey(String keyName) {
        String name = keyName == null ? "" : keyName.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return InputConstants.UNKNOWN;
        }
        if (name.chars().allMatch(Character::isDigit)) {
            try {
                return InputConstants.Type.KEYSYM.getOrCreate(Integer.parseInt(name));
            } catch (NumberFormatException e) {
                return InputConstants.UNKNOWN;
            }
        }
        try {
            return InputConstants.getKey("key.keyboard." + BindCommand.normalizeKeyName(name));
        } catch (Exception e) {
            return InputConstants.UNKNOWN;
        }
    }

    private static String normalizeKeyName(String name) {
        switch (name) {
            case "rshift":
            case "rightshift":
                return "right.shift";
            case "lshift":
            case "leftshift":
                return "left.shift";
            case "rctrl":
            case "rightctrl":
                return "right.control";
            case "lctrl":
            case "leftctrl":
            case "ctrl":
                return "left.control";
            case "ralt":
            case "rightalt":
                return "right.alt";
            case "lalt":
            case "leftalt":
            case "alt":
                return "left.alt";
            case "esc":
                return "escape";
            case "del":
                return "delete";
            case "ins":
                return "insert";
            case "pgup":
                return "page.up";
            case "pgdn":
            case "pgdown":
                return "page.down";
            case "return":
                return "enter";
            default:
                return name;
        }
    }

    @Override
    public String[] onTab(String[] stringArray) {
        return ZenClient.getInstance().getModuleManager().getModules().stream().map(Module::getName).filter(string -> string.toLowerCase().startsWith(stringArray.length == 0 ? "" : stringArray[0].toLowerCase())).toArray(String[]::new);
    }
}