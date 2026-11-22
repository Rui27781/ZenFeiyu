package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.gui.newclickgui.Theme;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.render.FontStore;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.animation.SmoothAnimationTimer;
import shit.zen.utils.math.Easings;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.OverlayUtil;
import shit.zen.utils.render.RenderUtil;

/**
 * WASD / click indicators, ported from NeoRaven's {@code client.Keystrokes}.
 *
 * <p>The upstream {@code Gui.drawRect} boxes are now layered glass key cards on one frosted panel,
 * and the {@code Keyboard.isKeyDown} polls became GLFW key-state reads using the actual bound
 * keycode from {@link KeyMapping}. Press shading and text fade are kept, including the six-colour
 * palette and the rainbow option. CPS labels are produced by a small fixed ring buffer of click
 * timestamps: the rising edge is sampled during the HUD pass (without consuming the click) and
 * formatted once per tick, so drawing allocates nothing. "Edit position" replaces the upstream
 * dedicated editor screen with click-drag repositioning, and "Custom" plus the three RGB sliders
 * extend the palette.</p>
 */
public class Keystrokes extends Module {
    public static Keystrokes INSTANCE;

    private static final String[] PALETTE = {"White", "Red", "Green", "Blue", "Yellow", "Purple", "Rainbow", "Custom"};
    private static final int[] PALETTE_COLORS = {0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xAA00AA, 0, 0};
    private static final String[] NAMES = {"W", "S", "A", "D", "LMB", "RMB"};
    private static final int[] OFFSET_X = {26, 26, 2, 50, 2, 38};
    private static final int[] OFFSET_Y = {2, 26, 26, 26, 50, 50};
    private static final int[] WIDTHS = {22, 22, 22, 22, 34, 34};
    private static final int CLICK_WINDOW = 48;

    private final ModeSetting colorSetting = new ModeSetting("Color", PALETTE).withDefault("White");
    private final BooleanSetting outlineSetting = new BooleanSetting("Outline", false);
    private final BooleanSetting showMouseSetting = new BooleanSetting("Show mouse buttons", false);
    private final BooleanSetting showCpsSetting = new BooleanSetting("Show CPS", true,
            () -> this.showMouseSetting.getValue());
    private final NumberSetting redSetting = new NumberSetting("Red", 103, 0, 255, 1,
            () -> this.colorSetting.is("Custom"));
    private final NumberSetting greenSetting = new NumberSetting("Green", 80, 0, 255, 1,
            () -> this.colorSetting.is("Custom"));
    private final NumberSetting blueSetting = new NumberSetting("Blue", 164, 0, 255, 1,
            () -> this.colorSetting.is("Custom"));
    private final NumberSetting positionXSetting = new NumberSetting("Position X", 0.0, 0.0, 100.0, 0.01);
    private final NumberSetting positionYSetting = new NumberSetting("Position Y", 0.0, 0.0, 100.0, 0.01);
    private final BooleanSetting editPositionSetting = new BooleanSetting("Edit position", false);

    private final SmoothAnimationTimer introAnim = new SmoothAnimationTimer();

    private final boolean[] down = new boolean[6];
    private final long[] changed = new long[6];
    private final long[] leftClicks = new long[CLICK_WINDOW];
    private final long[] rightClicks = new long[CLICK_WINDOW];
    private int leftHead;
    private int rightHead;
    private int leftUsed;
    private int rightUsed;
    private boolean leftWasDown;
    private boolean rightWasDown;
    private String leftCpsText = "0 CPS";
    private String rightCpsText = "0 CPS";
    private boolean dragging;
    private float dragOffsetX;
    private float dragOffsetY;

    public Keystrokes() {
        super("Keystrokes", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        this.resetIntro();
    }

    @Override
    protected void onDisable() {
        this.resetIntro();
    }

    private void resetIntro() {
        this.introAnim.setCurrentValue(0.0);
        this.introAnim.setToValue(0.0);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        long cutoff = System.currentTimeMillis() - 1000L;
        int left = 0;
        for (int i = 0; i < this.leftUsed; i++) {
            if (this.leftClicks[i] >= cutoff) {
                left++;
            }
        }
        int right = 0;
        for (int i = 0; i < this.rightUsed; i++) {
            if (this.rightClicks[i] >= cutoff) {
                right++;
            }
        }
        this.leftCpsText = left + " CPS";
        this.rightCpsText = right + " CPS";
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender() || mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        sampleClicks(now);

        this.introAnim.animate(1.0, 0.22, Easings.EASE_OUT_POW2);
        this.introAnim.tick();
        float alpha = Math.max(0.0F, Math.min(1.0F, this.introAnim.getValueF()));
        if (alpha <= 0.01F) {
            return;
        }

        PoseStack pose = event.poseStack();
        int screenWidth = event.guiGraphics().guiWidth();
        int screenHeight = event.guiGraphics().guiHeight();
        int panelHeight = this.showMouseSetting.getValue() ? 74 : 50;
        int x = clamp((int) (screenWidth * this.positionXSetting.getValue().floatValue() / 100.0F),
                0, Math.max(0, screenWidth - 74));
        int y = clamp((int) (screenHeight * this.positionYSetting.getValue().floatValue() / 100.0F),
                0, Math.max(0, screenHeight - panelHeight));

        if (this.editPositionSetting.getValue() && mc.mouseHandler.isLeftPressed()) {
            float mouseX = (float) mc.mouseHandler.xpos() * screenWidth / mc.getWindow().getWidth();
            float mouseY = (float) mc.mouseHandler.ypos() * screenHeight / mc.getWindow().getHeight();
            if (!this.dragging && mouseX >= x && mouseX <= x + 74 && mouseY >= y && mouseY <= y + panelHeight) {
                this.dragging = true;
                this.dragOffsetX = mouseX - x;
                this.dragOffsetY = mouseY - y;
            }
            if (this.dragging) {
                this.positionXSetting.setValue(clamp((mouseX - this.dragOffsetX) / screenWidth * 100.0F, 0.0F, 100.0F));
                this.positionYSetting.setValue(clamp((mouseY - this.dragOffsetY) / screenHeight * 100.0F, 0.0F, 100.0F));
                x = clamp((int) (screenWidth * this.positionXSetting.getValue().floatValue() / 100.0F),
                        0, Math.max(0, screenWidth - 74));
                y = clamp((int) (screenHeight * this.positionYSetting.getValue().floatValue() / 100.0F),
                        0, Math.max(0, screenHeight - panelHeight));
            }
        } else {
            this.dragging = false;
        }

        int accent = this.accent(now);
        long window = mc.getWindow().getWindow();
        boolean outline = this.outlineSetting.getValue();
        int accentColor = Keystrokes.alphaColor(Theme.accent(), alpha);
        int keyAccent = Keystrokes.alphaColor(accent, alpha);

        // One frosted panel for the module, with the same layered treatment as the other HUD cards.
        RenderUtil.drawShadow(pose, x, y, 74, panelHeight, 9,
                ColorUtil.fromARGB(0, 0, 0, (int) (142.0F * alpha)));
        RenderUtil.drawBlurredRect(pose, x, y, 74, panelHeight, 9.0F, 8.0F, 0.86F * alpha, 0);
        RenderUtil.drawRoundedRect(pose, x, y, 74, panelHeight, 9.0F,
                ColorUtil.fromARGB(16, 18, 26, (int) (178.0F * alpha)));
        RenderUtil.drawRoundedRect(pose, x + 1, y + 1, 72, panelHeight - 2, 8.0F,
                ColorUtil.fromARGB(255, 255, 255, (int) (42.0F * alpha)));
        RenderUtil.drawRoundedRect(pose, x + 12, y + panelHeight - 3, 50, 2.5F, 1.25F, accentColor);

        for (int i = 0; i < 6; i++) {
            if (i >= 4 && !this.showMouseSetting.getValue()) {
                continue;
            }
            boolean pressed = i == 4 ? mc.options.keyAttack.isDown()
                    : i == 5 ? mc.options.keyUse.isDown() : this.keyDown(i, window);
            if (pressed != this.down[i]) {
                this.down[i] = pressed;
                this.changed[i] = now;
            }
            long elapsed = now - this.changed[i];
            int shade;
            float textFade;
            if (pressed) {
                shade = (int) Math.min(255L, 2L * elapsed);
                textFade = (float) Math.max(0.0, 1.0 - elapsed / 20.0);
            } else {
                shade = (int) Math.max(0L, 255L - 2L * elapsed);
                textFade = (float) Math.min(1.0, elapsed / 20.0);
            }

            int bx = x + OFFSET_X[i];
            int by = y + OFFSET_Y[i];
            int width = WIDTHS[i];

            int tone = Math.max(12, shade >> 1);
            RenderUtil.drawShadow(pose, bx, by, width, 22, 4,
                    ColorUtil.fromARGB(0, 0, 0, (int) (96.0F * alpha)));
            RenderUtil.drawRoundedRect(pose, bx, by, width, 22, 5.0F,
                    ColorUtil.fromARGB(tone, tone, Math.min(255, tone + 3), (int) (176.0F * alpha)));
            RenderUtil.drawRoundedRect(pose, bx + 1, by + 1, width - 2, 20, 4.0F,
                    ColorUtil.fromARGB(255, 255, 255,
                            (int) ((16.0F + shade * 0.10F) * alpha)));
            if (outline) {
                RenderUtil.drawRoundedRect(pose, bx + 4, by + 19, width - 8, 2.5F, 1.25F, keyAccent);
            }
            int r = (int) (((accent >> 16) & 0xFF) * textFade);
            int g = (int) (((accent >> 8) & 0xFF) * textFade);
            int b = (int) ((accent & 0xFF) * textFade);
            int textColor = ColorUtil.fromARGB(r, g, b, (int) (255.0F * alpha));
            FontStore.AXIFORMA_BOLD_16.drawStringWithShadow(pose, NAMES[i], bx + 7, by + 6, textColor);

            if (i >= 4 && this.showCpsSetting.getValue()) {
                String cps = i == 4 ? this.leftCpsText : this.rightCpsText;
                float cpsWidth = FontStore.AXIFORMA_BOLD_13.getStringWidth(cps);
                pose.pushPose();
                pose.scale(0.5F, 0.5F, 1.0F);
                FontStore.AXIFORMA_BOLD_13.drawStringWithShadow(pose, cps,
                        (bx + 17) * 2 - cpsWidth * 0.5F, (by + 14) * 2, textColor);
                pose.popPose();
            }
        }
    }

    private void sampleClicks(long now) {
        boolean left = mc.options.keyAttack.isDown();
        if (left && !this.leftWasDown) {
            this.leftClicks[this.leftHead] = now;
            this.leftHead = (this.leftHead + 1) % CLICK_WINDOW;
            if (this.leftUsed < CLICK_WINDOW) {
                this.leftUsed++;
            }
        }
        this.leftWasDown = left;

        boolean right = mc.options.keyUse.isDown();
        if (right && !this.rightWasDown) {
            this.rightClicks[this.rightHead] = now;
            this.rightHead = (this.rightHead + 1) % CLICK_WINDOW;
            if (this.rightUsed < CLICK_WINDOW) {
                this.rightUsed++;
            }
        }
        this.rightWasDown = right;
    }

    private boolean keyDown(int index, long window) {
        KeyMapping mapping;
        if (index == 0) {
            mapping = mc.options.keyUp;
        } else if (index == 1) {
            mapping = mc.options.keyDown;
        } else if (index == 2) {
            mapping = mc.options.keyLeft;
        } else {
            mapping = mc.options.keyRight;
        }
        int code = mapping.getKey().getValue();
        if (code < 0) {
            return mapping.isDown();
        }
        return mapping.isDown() || GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
    }

    private int accent(long now) {
        String mode = this.colorSetting.getValue();
        if ("Custom".equals(mode)) {
            return ColorUtil.fromRGB(this.redSetting.getValue().intValue(),
                    this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue());
        }
        if ("Rainbow".equals(mode)) {
            return Color.HSBtoRGB((now % 3750L) / 3750.0F, 1.0F, 1.0F) | 0xFF000000;
        }
        int index = indexOf(PALETTE, mode);
        int rgb = index >= 0 && index < PALETTE_COLORS.length ? PALETTE_COLORS[index] : 0xFFFFFF;
        return rgb | 0xFF000000;
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return -1;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    /** Alpha-scaled copy of an ARGB colour without allocating a {@code java.awt.Color}. */
    private static int alphaColor(int color, float scale) {
        int alpha = (int) (ColorUtil.getAlpha(color) * Math.max(0.0F, Math.min(1.0F, scale)));
        return ColorUtil.fromARGB(ColorUtil.getRed(color), ColorUtil.getGreen(color),
                ColorUtil.getBlue(color), Math.max(0, Math.min(255, alpha)));
    }
}
