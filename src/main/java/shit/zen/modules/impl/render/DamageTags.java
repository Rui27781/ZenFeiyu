package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.event.impl.WorldChangeEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.modules.impl.combat.AntiBots;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.math.Vector2f;
import shit.zen.utils.render.ProjectionUtil;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Floating damage/heal numbers ported from NeoRaven's {@code DamageTagsModule}.
 *
 * <p>Upstream tracked health changes in {@code onUpdate} and drew world-space billboards in the
 * render-world event, using {@code BotCheck}, {@code Hypixel.sameTeam} and the Onyx theme palette.
 * Here the tracking stays on {@link GameTickEvent} with {@link AntiBots} and scoreboard teams, while
 * the labels are projected and drawn as flat HUD text in {@link Render2DEvent}. The Onyx theme enum
 * became a {@link ModeSetting}; heart-symbol settings from NeoRaven's global settings module are not
 * available, so labels are plain numbers.</p>
 *
 * <p>The tag list and health map are allocated once and pruned with index loops.</p>
 */
public class DamageTags extends Module {
    private static final long FADE_TIME = 200L;
    private static final String[] THEME_NAMES = {
            "Default", "Rainbow", "Aurora", "Cherry", "Cotton candy", "Flare", "Flower", "Forest",
            "Frost", "Gold", "Grayscale", "Inferno", "Royal", "Sandstorm", "Sky", "Vine"
    };
    private static final int[] THEME_FIRST = {
            0xFDFDFD, 0xFFFFFF, 0x7800C8, 0xFFC8C8, 0x63F9FF, 0xF12711, 0xD7A6E7, 0x228B22,
            0xFDFDFD, 0xFFFA00, 0xF0F0F0, 0xFF3300, 0x7DCCF1, 0xC2B280, 0x8CFEFA, 0x00FF19
    };
    private static final int[] THEME_SECOND = {
            0xD2E1E6, 0xFFFFFF, 0x00FFB4, 0xF33A6A, 0xFF68CC, 0xF5AF19, 0xD35AE8, 0x9ACD32,
            0xD2E1E6, 0xFFFDC3, 0x6E6E6E, 0x320000, 0x1E47AA, 0xFFECB3, 0x0ED2F7, 0xB4FFB4
    };
    private static final int HEAL_COLOR = 0x26FF26;
    private static final int BIG_HIT_COLOR = 0xFF2626;
    private static final int MED_HIT_COLOR = 0xFFD026;
    private static final int SMALL_HIT_COLOR = 0xFFFF26;

    private final NumberSetting lengthSetting = new NumberSetting("Length", 1.0, 0.2, 5.0, 0.1);
    private final NumberSetting scaleSetting = new NumberSetting("Scale", 1.0, 0.3, 3.0, 0.1);
    private final ModeSetting themeSetting = new ModeSetting("Theme", THEME_NAMES)
            .withDefault("Default");
    private final BooleanSetting showHealSetting = new BooleanSetting("Show heal", true);
    private final BooleanSetting showHurtSetting = new BooleanSetting("Show hurt", true);
    private final BooleanSetting showInvisSetting = new BooleanSetting("Show invis", false);
    private final BooleanSetting showMobsSetting = new BooleanSetting("Show mobs", false);
    private final BooleanSetting traditionalSetting = new BooleanSetting("Traditional health color", false);

    private final Map<Integer, Float> lastHealth = new HashMap<Integer, Float>();
    private final List<Tag> tags = new ArrayList<Tag>();
    private final Random random = new Random();
    private final DecimalFormat format = new DecimalFormat("0.0");
    private long lifetime = 1000L;

    public DamageTags() {
        super("Damage Tags", Category.RENDER);
    }

    @Override
    protected void onEnable() {
        this.lifetime = (long) (this.lengthSetting.getValue().floatValue() * 1000.0f);
    }

    @Override
    protected void onDisable() {
        this.clear();
    }

    private void clear() {
        this.lastHealth.clear();
        this.tags.clear();
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        this.clear();
    }

    private boolean isMob(Entity entity) {
        return entity instanceof Monster || entity instanceof Slime || entity instanceof AbstractGolem;
    }

    private boolean sameTeam(LivingEntity entity) {
        if (mc.player.getTeam() == null || entity.getTeam() == null) {
            return false;
        }
        return mc.player.getTeam() == entity.getTeam();
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc.level == null || mc.player == null) {
            this.clear();
            return;
        }
        this.lifetime = (long) (this.lengthSetting.getValue().floatValue() * 1000.0f);
        long now = System.currentTimeMillis();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity) || entity == mc.player) {
                continue;
            }
            LivingEntity living = (LivingEntity) entity;
            if (!this.showInvisSetting.getValue() && living.isInvisible()) {
                continue;
            }
            boolean antiBotsReady = AntiBots.INSTANCE != null;
            if ((antiBotsReady && AntiBots.isBot(entity)) || mc.player.distanceToSqr(entity) > 2500.0) {
                continue;
            }
            if (entity instanceof Player) {
                if (antiBotsReady && AntiBots.isBedWarsBot(entity)) {
                    continue;
                }
            } else if (!this.showMobsSetting.getValue() || !this.isMob(entity) || this.sameTeam(living)) {
                continue;
            }
            float health = living.getHealth() + living.getAbsorptionAmount();
            Float previous = this.lastHealth.get(entity.getId());
            float last = previous == null ? health : previous.floatValue();
            if (health != last) {
                this.tags.add(new Tag(health - last, entity.getX(),
                        entity.getY() + entity.getEyeHeight(), entity.getZ(),
                        health < last && living.deathTime != 0, now, this.format, this.random));
            }
            if (living.deathTime <= 0) {
                this.lastHealth.put(entity.getId(), Float.valueOf(health));
            } else {
                this.lastHealth.remove(entity.getId());
            }
        }
        for (int i = this.tags.size() - 1; i >= 0; i--) {
            if (now - this.tags.get(i).createdAt > this.lifetime) {
                this.tags.remove(i);
            }
        }
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (this.tags.isEmpty() || mc.level == null) {
            return;
        }
        ProjectionUtil.updateMatrices();
        long now = System.currentTimeMillis();
        for (int i = 0; i < this.tags.size(); i++) {
            Tag tag = this.tags.get(i);
            tag.draw = false;
            if (!(tag.delta > 0.0f ? this.showHealSetting.getValue() : this.showHurtSetting.getValue())) {
                continue;
            }
            long age = now - tag.createdAt;
            if (age < 0L || age > this.lifetime) {
                continue;
            }
            float fraction = Mth.clamp((float) age / (float) this.lifetime, 0.0f, 1.0f);
            double rise = tag.rise * (1.0 - (1.0 - fraction) * (1.0 - fraction));
            long fadeStart = this.lifetime - FADE_TIME;
            int alpha = 255;
            if (age > fadeStart && FADE_TIME > 0L) {
                float fade = Mth.clamp((float) (age - fadeStart) / (float) FADE_TIME, 0.0f, 1.0f);
                alpha = (int) (255.0f * (1.0f - fade));
            }
            if (alpha <= 4) {
                continue;
            }
            Vector2f screen = ProjectionUtil.project(tag.x, tag.y + rise, tag.z);
            if (screen == null) {
                continue;
            }
            tag.screenX = screen.x;
            tag.screenY = screen.y;
            tag.color = this.tagColor(tag, now);
            tag.alpha = alpha;
            tag.draw = true;
        }
    }

    private int tagColor(Tag tag, long now) {
        if (this.traditionalSetting.getValue()) {
            if (tag.delta > 0.0f) {
                return HEAL_COLOR;
            }
            if (tag.delta < -6.0f || tag.fatal) {
                return BIG_HIT_COLOR;
            }
            if (tag.delta < -3.0f) {
                return MED_HIT_COLOR;
            }
            return SMALL_HIT_COLOR;
        }
        String theme = this.themeSetting.getValue();
        if ("Rainbow".equals(theme)) {
            float hue = (float) ((now % 3000L) / 3000.0);
            return Color.HSBtoRGB(hue, 1.0f, 1.0f);
        }
        int index = 0;
        for (int i = 1; i < THEME_NAMES.length; i++) {
            if (THEME_NAMES[i].equals(theme)) {
                index = i;
                break;
            }
        }
        if (index < 0 || index >= THEME_FIRST.length) {
            index = 0;
        }
        float pulse = (float) (Math.sin(now * 0.002) * 0.5 + 0.5);
        return WorldRenderUtil.mix(THEME_FIRST[index], THEME_SECOND[index], pulse);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (this.tags.isEmpty()) {
            return;
        }
        PoseStack poseStack = event.poseStack();
        GuiGraphics graphics = event.guiGraphics();
        float scale = this.scaleSetting.getValue().floatValue() * 1.6f;
        for (int i = 0; i < this.tags.size(); i++) {
            Tag tag = this.tags.get(i);
            if (!tag.draw) {
                continue;
            }
            int color = WorldRenderUtil.withAlpha(tag.color, tag.alpha);
            poseStack.pushPose();
            poseStack.translate(tag.screenX, tag.screenY - 3.0f, 0.0f);
            poseStack.scale(scale, scale, scale);
            graphics.drawString(mc.font, tag.text, -mc.font.width(tag.text) / 2, 0, color, true);
            poseStack.popPose();
        }
    }

    private static final class Tag {
        final long createdAt;
        final boolean fatal;
        final String text;
        final double rise;
        final float delta;
        final double x;
        final double y;
        final double z;

        float screenX;
        float screenY;
        int color;
        int alpha;
        boolean draw;

        Tag(float delta, double x, double y, double z, boolean fatal, long now,
            DecimalFormat format, Random random) {
            this.delta = delta;
            this.x = x + (double) (random.nextBoolean() ? 1 : -1) * (0.15 + random.nextDouble() * 0.5);
            this.y = y - (0.1 + random.nextDouble() * 0.35);
            this.z = z + (double) (random.nextBoolean() ? 1 : -1) * (0.15 + random.nextDouble() * 0.5);
            this.fatal = fatal;
            this.createdAt = now;
            this.rise = 0.35 + random.nextDouble() * 0.35;
            this.text = format.format(Math.abs(delta));
        }
    }
}
