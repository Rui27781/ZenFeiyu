package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.EntityHurtEvent;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.JumpEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Frozen hit/landing afterimages, ported from NeoRaven's {@code HitAfterimageModule}.
 *
 * <p>Upstream re-rendered the captured {@code ModelBase} for ten expanding shells. 1.20.1's model
 * API is shader/vertex-consumer based and would need a full multi-buffer-source clone per entity, so
 * this port keeps the state machine (hurt/health-loss detection, self timers, jump/land triggers)
 * but draws the shells as stacked camera-relative boxes. The result reads the same as the original
 * glow-outline burst without touching entity renderers or allocating a render pass per image.</p>
 *
 * <p>Every shell shares one {@code DEBUG_LINES} batch and all scratch arrays are instance fields.</p>
 */
public class HitAfterimage extends Module {
    private static final float[] SHELL_SCALES = {1.03f, 1.08f, 1.14f, 1.21f, 1.29f, 1.39f, 1.51f, 1.64f};
    private static final float[] SHELL_ALPHAS = {0.30f, 0.255f, 0.215f, 0.18f, 0.15f, 0.125f, 0.103f, 0.084f};
    private static final int[][] EDGES = {
            {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3},
            {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}
    };
    private static final int MAX_IMAGES = 16;
    private static final long IMAGE_COOLDOWN = 50_000_000L;

    private final NumberSetting durationSetting = new NumberSetting("Duration", 0.8, 0.1, 3.0, 0.1);
    private final NumberSetting opacitySetting = new NumberSetting("Opacity", 100, 5, 100, 5);
    private final BooleanSetting playersSetting = new BooleanSetting("Players", true);
    private final BooleanSetting mobsSetting = new BooleanSetting("Mobs", true);
    private final BooleanSetting selfSetting = new BooleanSetting("Self", false);
    private final BooleanSetting selfTimedSetting = new BooleanSetting("Self: Timed", false);
    private final NumberSetting selfIntervalSetting = new NumberSetting("Self: Interval", 0.5, 0.05, 2.0, 0.05);
    private final BooleanSetting selfJumpSetting = new BooleanSetting("Self: On jump", false);
    private final BooleanSetting selfLandSetting = new BooleanSetting("Self: On land", true);
    private final NumberSetting redSetting = new NumberSetting("Red", 85, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 220, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 255, 0, 255, 1);

    private final List<Image> images = new ArrayList<>();
    private final Map<LivingEntity, Long> lastImages = new IdentityHashMap<>();
    private final Map<LivingEntity, Float> lastHealth = new IdentityHashMap<>();
    private final float[] cornerX = new float[8];
    private final float[] cornerY = new float[8];
    private final float[] cornerZ = new float[8];

    private ClientLevel world;
    private Player selfPlayer;
    private boolean wasGrounded;
    private boolean timerRunning;
    private long nextSelfTime;
    private long timerPeriod;
    private int lastSelfTick = Integer.MIN_VALUE;

    public HitAfterimage() {
        super("Hit Afterimage", Category.RENDER);
    }

    @Override
    protected void onEnable() {
        this.clear();
    }

    @Override
    protected void onDisable() {
        this.clear();
    }

    private void clear() {
        this.images.clear();
        this.lastImages.clear();
        this.lastHealth.clear();
        this.resetSelf();
        this.world = mc.level;
    }

    private long lifetime() {
        return (long) (this.durationSetting.getValue().floatValue() * 1_000_000_000L);
    }

    private void prune(long now) {
        long lifetime = this.lifetime();
        for (int i = this.images.size() - 1; i >= 0; i--) {
            if (this.images.get(i).alpha(now, lifetime) <= 0.0f) {
                this.images.remove(i);
            }
        }
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (this.world != mc.level) {
            this.clear();
        }
        if (this.world == null || mc.player == null) {
            this.clear();
            return;
        }
        long now = System.nanoTime();
        this.prune(now);

        // Health polling catches damage that does not go through EntityHurtEvent (fall, void, etc.).
        Iterator<Map.Entry<LivingEntity, Float>> iterator = this.lastHealth.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<LivingEntity, Float> entry = iterator.next();
            LivingEntity entity = entry.getKey();
            if (entity == null || !entity.isAlive() || !this.wantsEntity(entity)) {
                iterator.remove();
                this.lastImages.remove(entity);
                continue;
            }
            float health = entity.getHealth();
            if (entry.getValue() != null && health < entry.getValue().floatValue() - 0.01f) {
                this.addImage(entity, now);
            }
            entry.setValue(Float.valueOf(health));
        }
        for (LivingEntity entity : this.world.players()) {
            if (entity != null && entity.isAlive() && this.wantsEntity(entity) && !this.lastHealth.containsKey(entity)) {
                this.lastHealth.put(entity, Float.valueOf(entity.getHealth()));
            }
        }
        for (net.minecraft.world.entity.Mob mob : this.world.getEntitiesOfClass(
                net.minecraft.world.entity.Mob.class, mc.player.getBoundingBox().inflate(64.0))) {
            if (mob.isAlive() && this.wantsEntity(mob) && !this.lastHealth.containsKey(mob)) {
                this.lastHealth.put(mob, Float.valueOf(mob.getHealth()));
            }
        }
        this.sampleSelf(now);
    }

    @EventTarget
    public void onHurt(EntityHurtEvent event) {
        LivingEntity entity = event.entity();
        if (entity == null || entity == mc.player || !this.wantsEntity(entity)) {
            return;
        }
        this.addImage(entity, System.nanoTime());
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        if (mc.player == null || this.world == null) {
            return;
        }
        long now = System.nanoTime();
        if (this.prepareSelf(now) && this.selfJumpSetting.getValue()) {
            this.addSelf(now);
        }
    }

    private boolean wantsEntity(LivingEntity entity) {
        if (entity == null || !entity.isAlive() || entity.isSpectator() || entity == mc.player) {
            return false;
        }
        return entity instanceof Player ? this.playersSetting.getValue() : this.mobsSetting.getValue();
    }

    private void addImage(LivingEntity entity, long now) {
        Long previous = this.lastImages.get(entity);
        if (previous != null && now - previous.longValue() < IMAGE_COOLDOWN) {
            return;
        }
        this.lastImages.put(entity, Long.valueOf(now));
        if (this.images.size() >= MAX_IMAGES) {
            this.images.remove(0);
        }
        this.images.add(new Image(entity, now, false));
    }

    private void resetSelf() {
        this.selfPlayer = null;
        this.wasGrounded = false;
        this.timerRunning = false;
        this.nextSelfTime = 0L;
        this.timerPeriod = 0L;
        this.lastSelfTick = Integer.MIN_VALUE;
    }

    private boolean prepareSelf(long now) {
        Player player = mc.player;
        if (!this.selfSetting.getValue() || player == null || !player.isAlive() || player.isSpectator()) {
            if (this.selfPlayer != null) {
                for (int i = this.images.size() - 1; i >= 0; i--) {
                    if (this.images.get(i).localPlayer) {
                        this.images.remove(i);
                    }
                }
            }
            this.resetSelf();
            return false;
        }
        if (this.selfPlayer != player) {
            this.resetSelf();
            this.selfPlayer = player;
            this.wasGrounded = player.onGround();
        }
        long period = Math.max(50_000_000L,
                (long) (this.selfIntervalSetting.getValue().floatValue() * 1_000_000_000L));
        if (this.selfTimedSetting.getValue()) {
            if (!this.timerRunning || this.timerPeriod != period) {
                this.nextSelfTime = now + period;
            }
            this.timerRunning = true;
            this.timerPeriod = period;
        } else {
            this.timerRunning = false;
            this.nextSelfTime = 0L;
        }
        return true;
    }

    private void sampleSelf(long now) {
        if (!this.prepareSelf(now)) {
            return;
        }
        boolean landed = !this.wasGrounded && this.selfPlayer.onGround();
        this.wasGrounded = this.selfPlayer.onGround();
        boolean due = this.timerRunning && now >= this.nextSelfTime;
        if (due) {
            this.nextSelfTime = now + this.timerPeriod;
        }
        if (due || landed && this.selfLandSetting.getValue()) {
            this.addSelf(now);
        }
    }

    private void addSelf(long now) {
        if (this.selfPlayer == null || this.lastSelfTick == this.selfPlayer.tickCount) {
            return;
        }
        this.lastSelfTick = this.selfPlayer.tickCount;
        if (this.images.size() >= MAX_IMAGES) {
            this.images.remove(0);
        }
        this.images.add(new Image(this.selfPlayer, now, true));
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (this.world != mc.level) {
            this.clear();
        }
        if (this.images.isEmpty() || mc.player == null) {
            return;
        }
        long now = System.nanoTime();
        long lifetime = this.lifetime();
        this.prune(now);
        if (this.images.isEmpty()) {
            return;
        }
        int color = WorldRenderUtil.rgba(
                this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(),
                this.blueSetting.getValue().intValue(), 255);
        float opacity = this.opacitySetting.getValue().floatValue() / 100.0f;
        boolean hideSelf = mc.options.getCameraType().isFirstPerson();
        Vec3 camera = WorldRenderUtil.camera();
        Matrix4f matrix = event.poseStack().last().pose();

        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.DEBUG_LINES, false);
        for (Image image : this.images) {
            if (image.localPlayer && hideSelf) {
                continue;
            }
            float age = image.alpha(now, lifetime) * opacity;
            if (age <= 0.001f) {
                continue;
            }
            float cos = Mth.cos(image.yaw * Mth.DEG_TO_RAD);
            float sin = Mth.sin(image.yaw * Mth.DEG_TO_RAD);
            double x = image.x - camera.x;
            double y = image.y - camera.y;
            double z = image.z - camera.z;
            for (int shell = 0; shell < SHELL_SCALES.length; shell++) {
                float scale = SHELL_SCALES[shell];
                float jitter = 0.85f + 0.15f * (0.5f + 0.5f * Mth.sin((image.id + shell) * 12.9898f));
                float alpha = age * SHELL_ALPHAS[shell] * jitter;
                this.emitBox(buffer, matrix, x, y, z, 0.34f * scale, 0.72f * scale,
                        0.22f * scale, cos, sin, color, alpha);
                this.emitBox(buffer, matrix, x, y + 1.42f * scale, z, 0.22f * scale, 0.26f * scale,
                        0.22f * scale, cos, sin, color, alpha * 1.15f);
            }
        }
        WorldRenderUtil.end();
    }

    private void emitBox(BufferBuilder buffer, Matrix4f matrix, double x, double y, double z,
                         float halfX, float halfY, float halfZ, float cos, float sin,
                         int color, float alpha) {
        for (int i = 0; i < 8; i++) {
            float ox = (i & 1) == 0 ? -halfX : halfX;
            float oy = (i & 2) == 0 ? 0.0f : halfY;
            float oz = (i & 4) == 0 ? -halfZ : halfZ;
            this.cornerX[i] = (float) x + ox * cos - oz * sin;
            this.cornerY[i] = (float) y + oy;
            this.cornerZ[i] = (float) z + ox * sin + oz * cos;
        }
        for (int[] edge : EDGES) {
            int a = edge[0];
            int b = edge[1];
            WorldRenderUtil.vertex(buffer, matrix,
                    this.cornerX[a], this.cornerY[a], this.cornerZ[a], color, alpha);
            WorldRenderUtil.vertex(buffer, matrix,
                    this.cornerX[b], this.cornerY[b], this.cornerZ[b], color, alpha);
        }
    }

    private static final class Image {
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final int id;
        private final boolean localPlayer;
        private final long time;

        private Image(LivingEntity entity, long time, boolean localPlayer) {
            this.x = entity.getX();
            this.y = entity.getY();
            this.z = entity.getZ();
            this.yaw = WorldRenderUtil.bodyYaw(entity, 0.0f);
            this.id = entity.getId();
            this.localPlayer = localPlayer;
            this.time = time;
        }

        private float alpha(long now, long lifetime) {
            if (lifetime <= 0L) {
                return 0.0f;
            }
            float alpha = 1.0f - (now - this.time) / (float) lifetime;
            return alpha < 0.0f ? 0.0f : Math.min(alpha, 1.0f);
        }
    }
}
