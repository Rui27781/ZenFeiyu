package shit.zen.modules.impl.render;

import org.lwjgl.glfw.GLFW;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;

/**
 * Animated window title, ported from NeoRaven's {@code client.WindowTitle}.
 *
 * <p>LWJGL 3 replaced {@code Display.setTitle} with {@code GLFW.glfwSetWindowTitle}, so the title
 * is written to the Minecraft window handle instead. GLFW has no title getter, so the original title
 * is restored from a constant when the module is disabled. The animation itself is unchanged: the
 * prefix stays on screen, the body types out, holds, then deletes one character at a time. ZenFeiyu
 * branding is kept in the visible text.</p>
 */
public class WindowTitle extends Module {
    private static final String PREFIX = "Zen";
    private static final String BODY = "Feiyu";
    private static final String RARE_BODY = "Feiyu b2";
    private static final String ORIGINAL_TITLE = "Minecraft";

    private final NumberSetting typeMsSetting = new NumberSetting("Type ms", 120, 20, 400, 10);
    private final NumberSetting holdMsSetting = new NumberSetting("Hold ms", 2000, 250, 6000, 250);
    private final NumberSetting deleteMsSetting = new NumberSetting("Delete ms", 60, 10, 300, 10);
    private final BooleanSetting rareSetting = new BooleanSetting("Rare variant", true);

    private final java.util.Random random = new java.util.Random();
    private long cycleStart;
    private long lastApplied;
    private String body = BODY;
    private String applied = "";
    private String originalTitle;

    public WindowTitle() {
        super("WindowTitle", Category.RENDER);
    }

    @Override
    protected void onEnable() {
        this.cycleStart = 0L;
        this.lastApplied = 0L;
        this.applied = "";
        if (this.originalTitle == null) {
            this.originalTitle = ORIGINAL_TITLE;
        }
    }

    @Override
    protected void onDisable() {
        if (this.originalTitle != null && mc != null && mc.getWindow() != null) {
            try {
                GLFW.glfwSetWindowTitle(mc.getWindow().getWindow(), this.originalTitle);
            } catch (Throwable ignored) {
                // The window may already be gone during shutdown.
            }
        }
        this.applied = this.originalTitle;
        this.cycleStart = 0L;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc == null || mc.getWindow() == null || mc.getWindow().getWindow() == 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.cycleStart == 0L) {
            this.startCycle(now);
        }
        long elapsed = now - this.cycleStart;
        int typed = this.visibleAt(elapsed);
        if (elapsed >= this.cycleLength()) {
            this.startCycle(now);
            elapsed = 0L;
            typed = 0;
        }
        String text = PREFIX + this.body.substring(0, typed) + "_";
        if (!text.equals(this.applied) || now - this.lastApplied >= 1000L) {
            this.applied = text;
            this.lastApplied = now;
            try {
                GLFW.glfwSetWindowTitle(mc.getWindow().getWindow(), text);
            } catch (Throwable ignored) {
                // A headless or already-destroyed window must not kill the client tick.
            }
        }
    }

    private int visibleAt(long elapsed) {
        int length = this.body.length();
        long type = Math.max(1L, this.typeMsSetting.getValue().longValue());
        long typeEnd = length * type;
        if (elapsed < typeEnd) {
            return (int) (elapsed / type);
        }
        long holdEnd = typeEnd + this.holdMsSetting.getValue().longValue();
        if (elapsed < holdEnd) {
            return length;
        }
        long delete = Math.max(1L, this.deleteMsSetting.getValue().longValue());
        return (int) Math.max(0L, length - (elapsed - holdEnd) / delete);
    }

    private long cycleLength() {
        long type = Math.max(1L, this.typeMsSetting.getValue().longValue());
        long delete = Math.max(1L, this.deleteMsSetting.getValue().longValue());
        return this.body.length() * (type + delete) + this.holdMsSetting.getValue().longValue();
    }

    private void startCycle(long now) {
        this.cycleStart = now;
        this.body = this.rareSetting.getValue() && this.random.nextFloat() < 0.10f ? RARE_BODY : BODY;
    }
}
