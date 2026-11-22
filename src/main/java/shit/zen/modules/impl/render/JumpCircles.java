package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Ground rings, shockwaves and tile waves spawned when players jump or land, ported from
 * NeoRaven's {@code JumpCirclesModule} / {@code JumpCircleRenderer}.
 *
 * <p>Upstream sampled a ring texture and had an optional screen-space distortion pass. This port
 * draws the ring from geometry instead (three alpha bands plus a soft outer glow), so it needs no
 * texture and no framebuffer copy. The jump/landing state machine is unchanged: ring mode spawns
 * at take-off, shockwave and tiles spawn on landing.</p>
 *
 * <p>Performance notes: the 96-segment circle table is precomputed, all circles share one
 * {@link BufferBuilder} batch, and pruning uses an index loop instead of {@code removeIf} so no
 * lambda or iterator is allocated per tick.</p>
 */
public class JumpCircles extends Module {
    private static final int SEGMENTS = 96;
    private static final float ANGLE_STEP = (float) (Math.PI * 2.0 / SEGMENTS);
    private static final float[] COS = new float[SEGMENTS];
    private static final float[] SIN = new float[SEGMENTS];
    private static final int TILE_RADIUS = 6;
    private static final float TILE_TRAVEL = 0.64f;
    private static final float[] WAVE_WIDTHS = {-0.055f, -0.009f, 0.0f, 0.009f, 0.055f};
    private static final float[] WAVE_GLOW = {0.0f, 0.48f, 1.0f, 0.48f, 0.0f};

    static {
        for (int i = 0; i < SEGMENTS; i++) {
            COS[i] = (float) Math.cos(i * ANGLE_STEP);
            SIN[i] = (float) Math.sin(i * ANGLE_STEP);
        }
    }

    private final ModeSetting modeSetting = new ModeSetting("Mode", "Ring", "Shockwave", "Tiles")
            .withDefault("Ring");
    private final NumberSetting radiusSetting = new NumberSetting("Radius", 1.2, 0.4, 4.0, 0.1);
    private final NumberSetting opacitySetting = new NumberSetting("Opacity", 70, 5, 100, 5);
    private final NumberSetting durationSetting = new NumberSetting("Duration", 900, 200, 3000, 50);
    private final BooleanSetting othersSetting = new BooleanSetting("Other Players", false);
    private final BooleanSetting throughWallsSetting = new BooleanSetting("Through Walls", false);
    private final NumberSetting redSetting = new NumberSetting("Red", 100, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 192, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 255, 0, 255, 1);

    private final List<Circle> circles = new ArrayList<>();
    private final Set<Integer> airborne = new HashSet<>();
    private final Set<Integer> tracked = new HashSet<>();
    private final Map<Integer, Vec3> grounded = new HashMap<>();
    private ClientLevel world;

    public JumpCircles() {
        super("JumpCircles", Category.RENDER);
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
        this.circles.clear();
        this.airborne.clear();
        this.grounded.clear();
        this.world = mc.level;
    }

    private long lifetime() {
        return this.durationSetting.getValue().longValue();
    }

    private void prune(long now) {
        long lifetime = this.lifetime();
        for (int i = this.circles.size() - 1; i >= 0; i--) {
            if (now - this.circles.get(i).time >= lifetime) {
                this.circles.remove(i);
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
        long now = System.currentTimeMillis();
        this.prune(now);
        boolean landingMode = !"Ring".equals(this.modeSetting.getValue());
        boolean others = this.othersSetting.getValue();
        this.tracked.clear();
        for (Player player : this.world.players()) {
            if (player == null || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (player != mc.player && !others) {
                continue;
            }
            int id = player.getId();
            this.tracked.add(id);
            Vec3 current = new Vec3(player.getX(), player.getY(), player.getZ());
            if (player.onGround()) {
                if (this.airborne.remove(id) && landingMode) {
                    this.circles.add(new Circle(current, now));
                }
                this.grounded.put(id, current);
                continue;
            }
            this.airborne.add(id);
            Vec3 takeoff = this.grounded.remove(id);
            if (takeoff == null) {
                continue;
            }
            Vec3 motion = player.getDeltaMovement();
            if (motion.y <= 0.0 && player.getY() <= takeoff.y + 0.01) {
                continue;
            }
            if (!landingMode) {
                this.circles.add(new Circle(takeoff, now));
            }
        }
        this.grounded.keySet().retainAll(this.tracked);
        this.airborne.retainAll(this.tracked);
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (this.world != mc.level) {
            this.clear();
        }
        if (this.world == null || mc.player == null || this.circles.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        this.prune(now);
        if (this.circles.isEmpty()) {
            return;
        }
        long lifetime = this.lifetime();
        Vec3 camera = WorldRenderUtil.camera();
        float radius = this.radiusSetting.getValue().floatValue();
        float opacity = this.opacitySetting.getValue().floatValue() / 100.0f;
        int color = WorldRenderUtil.rgba(
                this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(),
                this.blueSetting.getValue().intValue(), 255);
        String mode = this.modeSetting.getValue();
        Matrix4f matrix = event.poseStack().last().pose();

        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, this.throughWallsSetting.getValue());
        if (!"Tiles".equals(mode)) {
            WorldRenderUtil.additiveBlend();
        }
        for (Circle circle : this.circles) {
            float age = circle.progress(now, lifetime);
            double x = circle.x - camera.x;
            double y = circle.y - camera.y;
            double z = circle.z - camera.z;
            switch (mode) {
                case "Shockwave": {
                    this.emitWave(buffer, matrix, x, y, z, age, radius, opacity, color, false);
                    if (age > 0.16f) {
                        this.emitWave(buffer, matrix, x, y, z, (age - 0.16f) / 0.84f, radius, opacity, color, true);
                    }
                    break;
                }
                case "Tiles":
                    this.emitTiles(buffer, matrix, x, y, z, age, radius, opacity, color);
                    break;
                default:
                    this.emitRing(buffer, matrix, x, y, z, age, radius, opacity, color);
            }
        }
        WorldRenderUtil.end();
    }

    private static float expansion(float age) {
        float inverse = 1.0f - age;
        return 1.0f - inverse * inverse * inverse * inverse;
    }

    private void emitRing(BufferBuilder buffer, Matrix4f matrix, double x, double y, double z,
                          float age, float radius, float opacity, int color) {
        float current = radius * expansion(age);
        float fade = opacity * (1.0f - age);
        if (current <= 0.0f || fade <= 0.0f) {
            return;
        }
        // inner glow, main band, outer glow
        this.ringBand(buffer, matrix, x, y, z, current * 0.86f, current * 0.97f, color, fade * 0.20f);
        this.ringBand(buffer, matrix, x, y, z, current * 0.97f, current, color, fade);
        this.ringBand(buffer, matrix, x, y, z, current, current * 1.05f, color, fade * 0.35f);
    }

    private void ringBand(BufferBuilder buffer, Matrix4f matrix, double x, double y, double z,
                          float inner, float outer, int color, float alpha) {
        for (int i = 0; i < SEGMENTS; i++) {
            int next = (i + 1) % SEGMENTS;
            WorldRenderUtil.vertex(buffer, matrix, x + inner * COS[i], y + 0.022, z + inner * SIN[i], color, alpha);
            WorldRenderUtil.vertex(buffer, matrix, x + inner * COS[next], y + 0.022, z + inner * SIN[next], color, alpha);
            WorldRenderUtil.vertex(buffer, matrix, x + outer * COS[next], y + 0.022, z + outer * SIN[next], color, alpha);
            WorldRenderUtil.vertex(buffer, matrix, x + outer * COS[i], y + 0.022, z + outer * SIN[i], color, alpha);
        }
    }

    private void emitWave(BufferBuilder buffer, Matrix4f matrix, double x, double y, double z,
                          float age, float radius, float opacity, int color, boolean outer) {
        if (age <= 0.0f || age >= 1.0f) {
            return;
        }
        double current = radius * expansion(age) * (outer ? 1.0 : 0.52);
        double crestHeight = outer ? current * 0.24 * Math.sin(Math.PI * Math.sqrt(age)) : 0.0;
        float light = opacity * Math.min(1.0f, age * 22.0f) * (1.0f - age * age);
        if (light <= 0.0f) {
            return;
        }
        int start = outer ? -1 : 0;
        float currentF = (float) current;
        float crestF = (float) crestHeight;
        for (int i = 0; i < SEGMENTS; i++) {
            int next = (i + 1) % SEGMENTS;
            float angleA = i * ANGLE_STEP;
            float angleB = next * ANGLE_STEP;
            float rippleA = 1.0f + 0.035f * Mth.sin(3.0f * angleA + age * 4.0f)
                    + 0.018f * Mth.cos(5.0f * angleA - age * 3.0f);
            float rippleB = 1.0f + 0.035f * Mth.sin(3.0f * angleB + age * 4.0f)
                    + 0.018f * Mth.cos(5.0f * angleB - age * 3.0f);
            float crestA = crestF * (0.84f + 0.16f * Mth.sin(4.0f * angleA + age * 3.0f));
            float crestB = crestF * (0.84f + 0.16f * Mth.sin(4.0f * angleB + age * 3.0f));

            for (int band = start; band < WAVE_WIDTHS.length - 1; band++) {
                float offsetA = band < 0 ? -0.04f : WAVE_WIDTHS[band];
                float offsetB = band < 0 ? 0.0f : WAVE_WIDTHS[band + 1];
                float alphaA = light * (band < 0 ? 0.0f : WAVE_GLOW[band]);
                float alphaB = light * (band < 0 ? 0.34f : WAVE_GLOW[band + 1]);
                float reachA = currentF * (rippleA + offsetA);
                float reachB = currentF * (rippleB + offsetB);
                double yA = y + 0.022 + (band < 0 ? 0.0 : crestA);
                double yB = y + 0.022 + (band < 0 ? 0.0 : crestB);

                WorldRenderUtil.vertex(buffer, matrix, x + reachA * COS[i], yA, z + reachA * SIN[i], color, alphaA);
                WorldRenderUtil.vertex(buffer, matrix, x + reachB * COS[i], yB, z + reachB * SIN[i], color, alphaB);
                WorldRenderUtil.vertex(buffer, matrix, x + reachB * COS[next], yB, z + reachB * SIN[next], color, alphaB);
                WorldRenderUtil.vertex(buffer, matrix, x + reachA * COS[next], yA, z + reachA * SIN[next], color, alphaA);
            }
        }
    }

    private void emitTiles(BufferBuilder buffer, Matrix4f matrix, double x, double y, double z,
                           float age, float radius, float opacity, int color) {
        double pitch = radius / (TILE_RADIUS + 0.5);
        double floor = y + 0.022;
        for (int row = -TILE_RADIUS; row <= TILE_RADIUS; row++) {
            for (int column = -TILE_RADIUS; column <= TILE_RADIUS; column++) {
                float distance = (float) Math.sqrt(row * row + column * column) / TILE_RADIUS;
                float phase = (age - distance * TILE_TRAVEL) / (1.0f - TILE_TRAVEL);
                if (distance > 1.0f || phase <= 0.0f || phase >= 1.0f) {
                    continue;
                }
                float flash = Math.min(1.0f, phase * 8.0f) * (1.0f - phase);
                double half = pitch * (0.38 + 0.09 * flash);
                double tileX = x + column * pitch;
                double tileZ = z + row * pitch;
                this.square(buffer, matrix, tileX, floor, tileZ, pitch * 0.49, color, opacity * flash * 0.32f);
                this.square(buffer, matrix, tileX, floor, tileZ, half, color, opacity * flash);
                float glint = Math.max(0.0f, 1.0f - Math.abs(phase - 0.125f) * 12.0f);
                if (glint > 0.0f) {
                    this.square(buffer, matrix, tileX, floor, tileZ, half * 0.25, 0xFFFFFF, opacity * glint * 0.8f);
                }
            }
        }
    }

    private void square(BufferBuilder buffer, Matrix4f matrix, double x, double y, double z,
                        double half, int color, float alpha) {
        WorldRenderUtil.vertex(buffer, matrix, x - half, y, z - half, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x - half, y, z + half, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x + half, y, z + half, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x + half, y, z - half, color, alpha);
    }

    private static final class Circle {
        private final double x;
        private final double y;
        private final double z;
        private final long time;

        private Circle(Vec3 position, long time) {
            this.x = position.x;
            this.y = position.y;
            this.z = position.z;
            this.time = time;
        }

        private float progress(long now, long lifetime) {
            if (lifetime <= 0L) {
                return 1.0f;
            }
            float progress = (now - this.time) / (float) lifetime;
            return progress < 0.0f ? 0.0f : Math.min(progress, 1.0f);
        }
    }
}
