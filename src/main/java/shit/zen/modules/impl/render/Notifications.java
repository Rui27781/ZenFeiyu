package shit.zen.modules.impl.render;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import shit.zen.ZenClient;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.OverlayUtil;

/**
 * Transient toast stack, ported from NeoRaven's {@code NotificationsModule} / Onyx
 * {@code gui.onyx.hud.Notifications}.
 *
 * <p>The upstream renderer is replaced by a geometry-only card stack so no external GUI class is
 * needed. Toasts fade in, slide in from the right and fade out; the newest sits on top and the
 * stack is capped by "Max stack". Other modules post through the static {@link #push(String,
 * String, int)} API. A fixed toast pool means posting and drawing never allocate. The module-toggle
 * event source is kept; the scaffold / bed-breaker / now-playing sources are dropped because they
 * depend on client services this port does not own.</p>
 */
public class Notifications extends Module {
    public static final int LEVEL_INFO = 0;
    public static final int LEVEL_SUCCESS = 1;
    public static final int LEVEL_WARNING = 2;
    public static final int LEVEL_ERROR = 3;

    public static Notifications INSTANCE;

    private static final int MAX_TOASTS = 8;
    private static final int MAX_TRACKED = 256;
    private static final int[] LEVEL_COLORS = {0xFF67B7FF, 0xFF4CAF50, 0xFFFFC107, 0xFFF44336};

    private final NumberSetting scaleSetting = new NumberSetting("Scale", 0.85, 0.5, 1.5, 0.05);
    private final NumberSetting positionXSetting = new NumberSetting("Position X", 82.0, 0.0, 100.0, 0.01);
    private final NumberSetting positionYSetting = new NumberSetting("Position Y", 8.0, 0.0, 100.0, 0.01);
    private final NumberSetting holdSetting = new NumberSetting("Hold", 3.0, 0.5, 10.0, 0.5);
    private final NumberSetting maxStackSetting = new NumberSetting("Max stack", 5, 1, 8, 1);
    private final BooleanSetting frostedSetting = new BooleanSetting("Frosted glass", true);
    private final BooleanSetting moduleTogglesSetting = new BooleanSetting("Module toggles", true);
    private final BooleanSetting progressSetting = new BooleanSetting("Progress bar", true);
    private final BooleanSetting editPositionSetting = new BooleanSetting("Edit position", false);
    private final ModeSetting accentSetting = new ModeSetting("Accent", "Level", "Custom").withDefault("Level");
    private final NumberSetting redSetting = new NumberSetting("Red", 103, 0, 255, 1,
            () -> this.accentSetting.is("Custom"));
    private final NumberSetting greenSetting = new NumberSetting("Green", 80, 0, 255, 1,
            () -> this.accentSetting.is("Custom"));
    private final NumberSetting blueSetting = new NumberSetting("Blue", 164, 0, 255, 1,
            () -> this.accentSetting.is("Custom"));

    private final Toast[] toasts = new Toast[MAX_TOASTS];
    private final Module[] observed = new Module[MAX_TRACKED];
    private final boolean[] observedState = new boolean[MAX_TRACKED];
    private int head;
    private int observedCount;
    private boolean primed;
    private boolean dragging;
    private float dragOffsetX;
    private float dragOffsetY;

    public Notifications() {
        super("Notifications", Category.RENDER);
        INSTANCE = this;
        for (int i = 0; i < MAX_TOASTS; i++) {
            this.toasts[i] = new Toast();
        }
    }

    /** Posts a toast. Safe to call from another thread; level is clamped to 0..3. */
    public static void push(String title, String message, int level) {
        Notifications module = INSTANCE;
        if (module == null || !module.isEnabled()) {
            return;
        }
        module.add(title, message, level);
    }

    private synchronized void add(String title, String message, int level) {
        Toast toast = this.toasts[this.head];
        this.head = (this.head + 1) % MAX_TOASTS;
        toast.title = title == null ? "" : title;
        toast.message = message == null ? "" : message;
        toast.level = level < LEVEL_INFO ? LEVEL_INFO : Math.min(level, LEVEL_ERROR);
        toast.created = System.currentTimeMillis();
        toast.hold = (long) (this.holdSetting.getValue().doubleValue() * 1000.0);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            for (int i = 0; i < MAX_TOASTS; i++) {
                Toast toast = this.toasts[i];
                if (toast.title != null && now - toast.created > toast.hold) {
                    toast.title = null;
                }
            }
        }
        this.sampleModules();
    }

    private void sampleModules() {
        if (!this.moduleTogglesSetting.getValue()) {
            this.observedCount = 0;
            this.primed = false;
            return;
        }
        List<Module> modules = ZenClient.getInstance().getModuleManager().getModules();
        int count = Math.min(modules.size(), MAX_TRACKED);
        for (int i = 0; i < count; i++) {
            Module module = modules.get(i);
            boolean enabled = module.isEnabled();
            if (i < this.observedCount && this.observed[i] == module) {
                if (this.primed && this.observedState[i] != enabled && module != this) {
                    push(module.getName(), enabled ? "Enabled" : "Disabled",
                            enabled ? LEVEL_SUCCESS : LEVEL_INFO);
                }
            }
            this.observed[i] = module;
            this.observedState[i] = enabled;
        }
        this.observedCount = count;
        this.primed = true;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender()) {
            return;
        }
        GuiGraphics graphics = event.guiGraphics();
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        float scale = this.scaleSetting.getValue().floatValue();
        int cardHeight = Math.max(20, (int) Math.round(34.0F * scale));
        int stackLimit = this.maxStackSetting.getValue().intValue();
        int x = (int) (screenWidth * this.positionXSetting.getValue().floatValue() / 100.0F);
        int y = (int) (screenHeight * this.positionYSetting.getValue().floatValue() / 100.0F);

        if (this.editPositionSetting.getValue() && mc.mouseHandler.isLeftPressed()) {
            float mouseX = (float) mc.mouseHandler.xpos() * screenWidth / mc.getWindow().getWidth();
            float mouseY = (float) mc.mouseHandler.ypos() * screenHeight / mc.getWindow().getHeight();
            if (!this.dragging && mouseX >= x - 140 && mouseX <= x + 10
                    && mouseY >= y - 5 && mouseY <= y + cardHeight * stackLimit) {
                this.dragging = true;
                this.dragOffsetX = mouseX - x;
                this.dragOffsetY = mouseY - y;
            }
            if (this.dragging) {
                this.positionXSetting.setValue(clamp((mouseX - this.dragOffsetX) / screenWidth * 100.0F));
                this.positionYSetting.setValue(clamp((mouseY - this.dragOffsetY) / screenHeight * 100.0F));
                x = (int) (screenWidth * this.positionXSetting.getValue().floatValue() / 100.0F);
                y = (int) (screenHeight * this.positionYSetting.getValue().floatValue() / 100.0F);
            }
        } else {
            this.dragging = false;
        }

        long now = System.currentTimeMillis();
        synchronized (this) {
            int shown = 0;
            for (int offset = 0; offset < MAX_TOASTS && shown < stackLimit; offset++) {
                int index = (this.head - 1 - offset + MAX_TOASTS * 2) % MAX_TOASTS;
                Toast toast = this.toasts[index];
                if (toast.title == null) {
                    continue;
                }
                long age = now - toast.created;
                if (age < 0L || age > toast.hold) {
                    continue;
                }
                float entering = clamp((float) age / 140.0F);
                float leaving = clamp((float) (toast.hold - age) / 260.0F);
                float alpha = Math.min(entering, leaving);
                int slide = (int) ((1.0F - entering) * 28.0F);
                int tx = x + slide;
                int ty = y + shown * (cardHeight + 4);

                int titleWidth = mc.font.width(toast.title);
                int messageWidth = mc.font.width(toast.message);
                int width = Math.max(96, Math.max(titleWidth, messageWidth) + 20);
                int levelColor = this.accent(toast.level);
                int background = this.frostedSetting.getValue() ? 0x99000000 : 0x7D000000;
                graphics.fill(tx - width, ty, tx, ty + cardHeight, withAlpha(background, alpha));
                graphics.fill(tx - 3, ty, tx, ty + cardHeight, withAlpha(levelColor, alpha));

                int textColor = withAlpha(0xFFFFFFFF, alpha);
                int subColor = withAlpha(0xFFDDDDDD, alpha);
                graphics.drawString(mc.font, toast.title, tx - width + 8, ty + 4, textColor, false);
                graphics.drawString(mc.font, toast.message, tx - width + 8, ty + 4 + 12, subColor, false);
                if (this.progressSetting.getValue()) {
                    int progress = (int) (width * clamp((float) age / (float) toast.hold));
                    graphics.fill(tx - width, ty + cardHeight - 2, tx - width + progress,
                            ty + cardHeight, withAlpha(levelColor, alpha));
                }
                shown++;
            }
        }
    }

    private int accent(int level) {
        if (this.accentSetting.is("Custom")) {
            return ColorUtil.fromRGB(this.redSetting.getValue().intValue(),
                    this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue());
        }
        return LEVEL_COLORS[level >= 0 && level < LEVEL_COLORS.length ? level : LEVEL_INFO];
    }

    private static int withAlpha(int color, float scale) {
        int alpha = (int) (((color >>> 24) & 0xFF) * scale);
        return (Math.min(255, alpha) << 24) | (color & 0x00FFFFFF);
    }

    private static float clamp(float value) {
        return value < 0.0F ? 0.0F : Math.min(value, 1.0F);
    }

    private static final class Toast {
        private String title;
        private String message;
        private int level;
        private long created;
        private long hold;
    }
}
