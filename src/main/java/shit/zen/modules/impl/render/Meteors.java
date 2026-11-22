package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Random;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.RenderEvent;
import shit.zen.event.impl.WorldChangeEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * World-space shooting stars, ported from NeoRaven's {@code MeteorsModule} and
 * {@code WorldRenderState}.
 *
 * <p>Upstream emitted immediate-mode triangles inside a raw GL state scope. This port writes the
 * same tape into one reused {@link BufferBuilder} (tapered trail plus two additive glow discs per
 * meteor), so a whole shower costs a single draw call. The 48 {@link Meteor} slots and their random
 * generator are allocated once; respawning fills those slots in place.</p>
 *
 * <p>Upstream's single {@code ColorSetting} became the three RGB {@link NumberSetting}s used by
 * this client; the world unload hook became {@link WorldChangeEvent}. The fixed-function shader
 * scope is unnecessary because {@link WorldRenderUtil} sets the position/colour shader and restores
 * the GL state for us.</p>
 */
public class Meteors extends Module {
    private static final int MAX_COUNT = 48;
    private static final int SIDES = 16;
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));
    private static final double[] COS = new double[SIDES + 1];
    private static final double[] SIN = new double[SIDES + 1];

    static {
        for (int i = 0; i <= SIDES; i++) {
            double angle = 2.0 * Math.PI * i / SIDES;
            COS[i] = Math.cos(angle);
            SIN[i] = Math.sin(angle);
        }
    }

    private final NumberSetting countSetting = new NumberSetting("Meteor count", 16, 1, MAX_COUNT, 1);
    private final NumberSetting sizeSetting = new NumberSetting("Size", 0.24, 0.05, 1.0, 0.01);
    private final NumberSetting distanceSetting = new NumberSetting("Distance", 96, 16, 256, 4);
    private final NumberSetting redSetting = new NumberSetting("Red", 140, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 205, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 255, 0, 255, 1);

    private final Random random = new Random();
    private final Meteor[] meteors = new Meteor[MAX_COUNT];

    private boolean worldReady;
    private double previousX;
    private double previousY;
    private double previousZ;
    private double previousDistance;
    private int activeCount;

    public Meteors() {
        super("Meteors", Category.RENDER);
        for (int i = 0; i < MAX_COUNT; i++) {
            this.meteors[i] = new Meteor();
        }
    }

    @Override
    protected void onDisable() {
        this.reset();
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        this.reset();
    }

    private void reset() {
        this.activeCount = 0;
        this.worldReady = false;
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null) {
            this.reset();
            return;
        }
        Vec3 camera = WorldRenderUtil.camera();
        double range = this.distanceSetting.getValue().doubleValue();
        double dx = camera.x - this.previousX;
        double dy = camera.y - this.previousY;
        double dz = camera.z - this.previousZ;
        if (!this.worldReady || range != this.previousDistance
                || dx * dx + dy * dy + dz * dz > range * range) {
            this.activeCount = 0;
        }
        this.worldReady = true;
        this.previousX = camera.x;
        this.previousY = camera.y;
        this.previousZ = camera.z;
        this.previousDistance = range;

        double now = mc.level.getGameTime() + event.partialTick();
        int wanted = Math.max(1, Math.min(MAX_COUNT, this.countSetting.getValue().intValue()));
        double radius = this.sizeSetting.getValue().doubleValue();
        int rgb = WorldRenderUtil.rgba(this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue(), 255);
        int core = WorldRenderUtil.mix(rgb, 0xFFFFFFFF, 0.70f);

        Matrix4f matrix = event.poseStack().last().pose();
        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.TRIANGLES, false);
        WorldRenderUtil.additiveBlend();
        for (int i = 0; i < wanted; i++) {
            Meteor meteor = this.meteors[i];
            boolean initial = i >= this.activeCount;
            if (initial || now < meteor.born || now >= meteor.born + meteor.life) {
                double angle;
                if (initial) {
                    angle = Math.toRadians(mc.player.getYRot()) + i * GOLDEN_ANGLE
                            + (this.random.nextDouble() - 0.5) * 0.4;
                } else {
                    angle = this.random.nextDouble() * Math.PI * 2.0;
                }
                this.spawn(meteor, camera, angle, range, now, initial);
            }
            meteor.draw(buffer, matrix, camera, now, radius, rgb, core);
        }
        WorldRenderUtil.end();
        this.activeCount = wanted;
    }

    /** Fills an existing slot; the initial shower is spread around the horizon with staggered ages. */
    private void spawn(Meteor meteor, Vec3 camera, double angle, double range, double now, boolean initial) {
        double depth = range * (0.85 + 0.30 * this.random.nextDouble());
        double altitude = depth * (0.45 + 0.58 * this.random.nextDouble());
        double travel = depth * (0.52 + 0.46 * this.random.nextDouble());
        if (this.random.nextBoolean()) {
            travel = -travel;
        }
        double life = 24.0 + this.random.nextDouble() * 24.0;
        double pathX = Math.cos(angle) * travel;
        double pathY = -altitude * 0.45;
        double pathZ = Math.sin(angle) * travel;
        double age = initial ? life * (0.12 + this.random.nextDouble() * 0.70) : 0.0;
        meteor.set(
                camera.x - Math.sin(angle) * depth - pathX * 0.5,
                camera.y + altitude,
                camera.z + Math.cos(angle) * depth - pathZ * 0.5,
                pathX, pathY, pathZ, now - age, life);
    }

    /** One meteor slot; all state is primitive so respawning never allocates. */
    private static final class Meteor {
        double startX, startY, startZ;
        double pathX, pathY, pathZ;
        double born, life;

        void set(double startX, double startY, double startZ, double pathX, double pathY,
                 double pathZ, double born, double life) {
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.pathX = pathX;
            this.pathY = pathY;
            this.pathZ = pathZ;
            this.born = born;
            this.life = life;
        }

        void draw(BufferBuilder buffer, Matrix4f matrix, Vec3 camera, double now,
                  double radius, int rgb, int core) {
            double t = (now - this.born) / this.life;
            float fade = (float) Math.min(1.0, Math.min(t * 9.0, (1.0 - t) * 6.0));
            if (fade <= 0.0f) {
                return;
            }
            double hx = this.startX - camera.x + this.pathX * t;
            double hy = this.startY - camera.y + this.pathY * t;
            double hz = this.startZ - camera.z + this.pathZ * t;

            // View ray is the head itself: the pose matrix is already camera relative.
            double length = Math.sqrt(hx * hx + hy * hy + hz * hz);
            if (length < 1.0E-4) {
                return;
            }
            double vx = hx / length, vy = hy / length, vz = hz / length;
            double sx = vy * this.pathZ - vz * this.pathY;
            double sy = vz * this.pathX - vx * this.pathZ;
            double sz = vx * this.pathY - vy * this.pathX;
            double sideLength = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sideLength < 1.0E-4) {
                return;
            }
            sx /= sideLength;
            sy /= sideLength;
            sz /= sideLength;
            double ux = sy * vz - sz * vy;
            double uy = sz * vx - sx * vz;
            double uz = sx * vy - sy * vx;

            double trail = Math.min(t, 0.25 * Math.sqrt(radius / 0.24));
            double tx = hx - this.pathX * trail;
            double ty = hy - this.pathY * trail;
            double tz = hz - this.pathZ * trail;
            for (int sign = -1; sign <= 1; sign += 2) {
                double width = radius * 1.5 * sign;
                WorldRenderUtil.vertex(buffer, matrix, tx, ty, tz, rgb, 0.0f);
                WorldRenderUtil.vertex(buffer, matrix,
                        hx + sx * width, hy + sy * width, hz + sz * width, rgb, fade);
                WorldRenderUtil.vertex(buffer, matrix, hx, hy, hz, rgb, fade);
            }
            glow(buffer, matrix, hx, hy, hz, sx, sy, sz, ux, uy, uz,
                    radius * 2.8, rgb, fade * 0.6f);
            glow(buffer, matrix, hx, hy, hz, sx, sy, sz, ux, uy, uz, radius, core, fade);
        }

        /** One fan of soft triangles in the camera-facing plane of the head. */
        private static void glow(BufferBuilder buffer, Matrix4f matrix,
                                 double hx, double hy, double hz,
                                 double sx, double sy, double sz,
                                 double ux, double uy, double uz,
                                 double radius, int rgb, float alpha) {
            for (int i = 0; i < SIDES; i++) {
                double ax = radius * COS[i], ay = radius * SIN[i];
                double bx = radius * COS[i + 1], by = radius * SIN[i + 1];
                WorldRenderUtil.vertex(buffer, matrix, hx, hy, hz, rgb, alpha);
                WorldRenderUtil.vertex(buffer, matrix,
                        hx + sx * ax + ux * ay, hy + sy * ax + uy * ay, hz + sz * ax + uz * ay,
                        rgb, 0.0f);
                WorldRenderUtil.vertex(buffer, matrix,
                        hx + sx * bx + ux * by, hy + sy * bx + uy * by, hz + sz * bx + uz * by,
                        rgb, 0.0f);
            }
        }
    }
}
