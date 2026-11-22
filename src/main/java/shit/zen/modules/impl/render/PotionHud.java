package shit.zen.modules.impl.render;

import java.util.Collection;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.utils.render.OverlayUtil;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.ColorUtil;

/**
 * Material 3 potion list, ported from NeoRaven's {@code PotionHudModule}.
 *
 * <p>Active {@link MobEffectInstance}s are sampled once per tick into a fixed-size row cache, then
 * rendered with {@link GuiGraphics} geometry and vanilla font text. The cards, roman amplifier,
 * remaining duration, low-time warning, per-effect tint and all setting labels are kept. Two
 * adaptations: the vanilla status icon texture is replaced by a small effect-coloured square
 * (this port uses geometry only), and "Edit position" turns on click-drag repositioning instead of
 * opening the upstream dedicated editor screen. "Frosted glass" is a translucent panel rather than
 * a framebuffer blur, which keeps the HUD pass free of extra render-target copies.</p>
 */
public class PotionHud extends Module {
    public static PotionHud INSTANCE;

    private static final int MAX_ROWS = 24;
    private static final String[] ROMAN = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private final NumberSetting scaleSetting = new NumberSetting("Scale", 1.0, 0.5, 2.0, 0.05);
    private final NumberSetting positionXSetting = new NumberSetting("Position X", 0.0, 0.0, 100.0, 0.01);
    private final NumberSetting positionYSetting = new NumberSetting("Position Y", 60.0, 0.0, 100.0, 0.01);
    private final ModeSetting sortSetting = new ModeSetting("Sort", "Time left", "Name", "Level").withDefault("Time left");
    private final BooleanSetting frostedGlassSetting = new BooleanSetting("Frosted glass", true);
    private final BooleanSetting iconsSetting = new BooleanSetting("Icons", true);
    private final BooleanSetting durationSetting = new BooleanSetting("Duration", true);
    private final BooleanSetting amplifierSetting = new BooleanSetting("Amplifier", true);
    private final BooleanSetting potionColorSetting = new BooleanSetting("Potion color", true);
    private final BooleanSetting lowTimeWarningSetting = new BooleanSetting("Low time warning", true);
    private final BooleanSetting hideAmbientSetting = new BooleanSetting("Hide ambient", false);
    private final BooleanSetting editPositionSetting = new BooleanSetting("Edit position", false);

    private final EffectRow[] rows = new EffectRow[MAX_ROWS];
    private int rowCount;
    private boolean dragging;
    private float dragOffsetX;
    private float dragOffsetY;

    public PotionHud() {
        super("PotionHUD", Category.RENDER);
        INSTANCE = this;
        for (int i = 0; i < MAX_ROWS; i++) {
            this.rows[i] = new EffectRow();
        }
    }

    public boolean frosted() {
        return this.frostedGlassSetting.getValue();
    }

    public boolean showIcons() {
        return this.iconsSetting.getValue();
    }

    public boolean showDuration() {
        return this.durationSetting.getValue();
    }

    public boolean showAmplifier() {
        return this.amplifierSetting.getValue();
    }

    public boolean potionColor() {
        return this.potionColorSetting.getValue();
    }

    public boolean lowTimeWarning() {
        return this.lowTimeWarningSetting.getValue();
    }

    public boolean hideAmbient() {
        return this.hideAmbientSetting.getValue();
    }

    public int sortMode() {
        String sort = this.sortSetting.getValue();
        if ("Name".equals(sort)) {
            return 1;
        }
        return "Level".equals(sort) ? 2 : 0;
    }

    public float scale() {
        return this.scaleSetting.getValue().floatValue();
    }

    public float positionX() {
        return this.positionXSetting.getValue().floatValue();
    }

    public float positionY() {
        return this.positionYSetting.getValue().floatValue();
    }

    public void setPosition(float percentX, float percentY) {
        this.positionXSetting.setValue(percentX);
        this.positionYSetting.setValue(percentY);
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc.player == null || mc.level == null) {
            this.rowCount = 0;
            return;
        }
        Collection<MobEffectInstance> effects = mc.player.getActiveEffects();
        int count = 0;
        for (MobEffectInstance effect : effects) {
            if (count >= MAX_ROWS || effect == null || (this.hideAmbient() && effect.isAmbient())) {
                continue;
            }
            EffectRow row = this.rows[count++];
            row.effect = effect;
            row.name = effect.getEffect().getDisplayName().getString();
            row.duration = MobEffectUtil.formatDuration(effect, 1.0F).getString();
            int level = effect.getAmplifier() + 1;
            row.amplifier = level >= 0 && level < ROMAN.length ? ROMAN[level] : String.valueOf(level);
            int color = effect.getEffect().getColor();
            row.color = color != 0 ? color : 0x7BC77C;
        }
        this.rowCount = count;
        this.sortRows();
    }

    private void sortRows() {
        int mode = this.sortMode();
        if (mode == 0) {
            for (int i = 1; i < this.rowCount; i++) {
                EffectRow current = this.rows[i];
                int j = i - 1;
                while (j >= 0 && this.rows[j].effect.getDuration() > current.effect.getDuration()) {
                    this.rows[j + 1] = this.rows[j];
                    j--;
                }
                this.rows[j + 1] = current;
            }
        } else if (mode == 1) {
            for (int i = 1; i < this.rowCount; i++) {
                EffectRow current = this.rows[i];
                int j = i - 1;
                while (j >= 0 && this.rows[j].name.compareTo(current.name) > 0) {
                    this.rows[j + 1] = this.rows[j];
                    j--;
                }
                this.rows[j + 1] = current;
            }
        } else {
            for (int i = 1; i < this.rowCount; i++) {
                EffectRow current = this.rows[i];
                int j = i - 1;
                while (j >= 0 && this.rows[j].effect.getAmplifier() < current.effect.getAmplifier()) {
                    this.rows[j + 1] = this.rows[j];
                    j--;
                }
                this.rows[j + 1] = current;
            }
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !OverlayUtil.canRender() || this.rowCount == 0 || mc.player == null) {
            return;
        }
        GuiGraphics graphics = event.guiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float scale = this.scale();
        int rowWidth = (int) (152.0F * scale);
        int rowHeight = (int) (22.0F * scale);
        int x = (int) (screenWidth * this.positionX() / 100.0F);
        int y = (int) (screenHeight * this.positionY() / 100.0F);

        if (this.editPositionSetting.getValue() && mc.mouseHandler.isLeftPressed()) {
            float mouseX = (float) mc.mouseHandler.xpos() * screenWidth / mc.getWindow().getWidth();
            float mouseY = (float) mc.mouseHandler.ypos() * screenHeight / mc.getWindow().getHeight();
            if (!this.dragging && mouseX >= x && mouseX <= x + rowWidth && mouseY >= y
                    && mouseY <= y + rowHeight * this.rowCount) {
                this.dragging = true;
                this.dragOffsetX = mouseX - x;
                this.dragOffsetY = mouseY - y;
            }
            if (this.dragging) {
                this.setPosition(clamp((mouseX - this.dragOffsetX) / screenWidth * 100.0F, 0.0F, 100.0F),
                        clamp((mouseY - this.dragOffsetY) / screenHeight * 100.0F, 0.0F, 100.0F));
                x = (int) (screenWidth * this.positionX() / 100.0F);
                y = (int) (screenHeight * this.positionY() / 100.0F);
            }
        } else {
            this.dragging = false;
        }

        for (int i = 0; i < this.rowCount; i++) {
            EffectRow row = this.rows[i];
            int rowY = y + i * (rowHeight + 2);
            int background = this.frosted() ? 0x99000000 : 0x66000000;
            if (this.potionColor()) {
                int r = ColorUtil.getRed(row.color);
                int g = ColorUtil.getGreen(row.color);
                int b = ColorUtil.getBlue(row.color);
                background = ColorUtil.fromARGB(r / 2 + 40, g / 2 + 40, b / 2 + 40, this.frosted() ? 170 : 110);
            }
            graphics.fill(x, rowY, x + rowWidth, rowY + rowHeight, background);
            int accent = this.potionColor() ? 0xCC000000 | (row.color & 0x00FFFFFF) : 0xFF6750A4;
            if (this.showIcons()) {
                graphics.fill(x + 2, rowY + 2, x + rowHeight - 2, rowY + rowHeight - 2, accent);
            }
            if (this.showAmplifier()) {
                graphics.drawCenteredString(mc.font, row.amplifier, x + rowHeight / 2,
                        rowY + rowHeight / 2 - 4, 0xFFFFFFFF);
            }
            int textX = x + rowHeight + 4;
            graphics.drawString(mc.font, row.name, textX, rowY + rowHeight / 2 - 4, 0xFFFFFFFF, true);
            if (this.showDuration()) {
                boolean low = this.lowTimeWarning() && !row.effect.isInfiniteDuration() && row.effect.getDuration() <= 200;
                int durationColor = low ? 0xFFFF5555 : 0xFFDDDDDD;
                graphics.drawString(mc.font, row.duration, x + rowWidth - mc.font.width(row.duration) - 4,
                        rowY + rowHeight / 2 - 4, durationColor, false);
            }
        }
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    private static final class EffectRow {
        private MobEffectInstance effect;
        private String name = "";
        private String duration = "";
        private String amplifier = "";
        private int color = 0xFFFFFF;
    }
}
