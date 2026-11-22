package shit.zen.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
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
 * Player movement trail, ported from NeoRaven's {@code TrailsModule}.
 *
 * <p>Upstream had three legacy modes plus several Furiah sprite styles. The sprite styles depended
 * on texture assets that are not part of this client, so this port keeps the two asset-free modes
 * ({@code Wall} and {@code Points}) and draws them as camera-relative geometry. Samples are captured
 * once per tick and rendered in a single batch per pass; no path list is copied per frame.</p>
 */
public class Trails extends Module {
    private static final int MAX_SAMPLES = 256;

    private final ModeSetting modeSetting = new ModeSetting("Mode", "Wall", "Points").withDefault("Wall");
    private final BooleanSetting firstPersonSetting = new BooleanSetting("First person", false);
    private final BooleanSetting throughWallsSetting = new BooleanSetting("Through walls", false);
    private final NumberSetting lengthSetting = new NumberSetting("Length", 1.0, 0.25, 5.0, 0.05);
    private final NumberSetting opacitySetting = new NumberSetting("Opacity", 60, 5, 100, 5);
    private final NumberSetting lineWidthSetting = new NumberSetting("Line width", 1.5, 0.5, 5.0, 0.5);
    private final NumberSetting pointSizeSetting = new NumberSetting("Point size", 4, 1, 12, 0.5);
    private final NumberSetting redSetting = new NumberSetting("Red", 155, 0, 255, 1);
    private final NumberSetting greenSetting = new NumberSetting("Green", 70, 0, 255, 1);
    private final NumberSetting blueSetting = new NumberSetting("Blue", 255, 0, 255, 1);

    private final List<Sample> samples = new ArrayList<>();
    private ClientLevel world;

    public Trails() {
        super("Trails", Category.RENDER);
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
        this.samples.clear();
        this.world = mc.level;
    }

    private long lifetime() {
        return (long) (this.lengthSetting.getValue().floatValue() * 1000.0f);
    }

    private void prune(long now) {
        long lifetime = this.lifetime();
        for (int i = this.samples.size() - 1; i >= 0; i--) {
            if (now - this.samples.get(i).time >= lifetime) {
                this.samples.remove(i);
            }
        }
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (this.world != mc.level) {
            this.clear();
        }
        Player player = mc.player;
        if (this.world == null || player == null || !player.isAlive()) {
            this.clear();
            return;
        }
        long now = System.currentTimeMillis();
        this.prune(now);
        Sample last = this.samples.isEmpty() ? null : this.samples.get(this.samples.size() - 1);
        if (last != null && last.squaredDistance(player.getX(), player.getY(), player.getZ()) < 1.0E-8) {
            return;
        }
        if (this.samples.size() >= MAX_SAMPLES) {
            this.samples.remove(0);
        }
        this.samples.add(new Sample(player.getX(), player.getY(), player.getZ(), now));
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (this.world != mc.level || mc.player == null || this.samples.isEmpty()) {
            return;
        }
        if (mc.options.getCameraType().isFirstPerson() && !this.firstPersonSetting.getValue()) {
            return;
        }
        long now = System.currentTimeMillis();
        this.prune(now);
        if (this.samples.isEmpty()) {
            return;
        }

        Vec3 camera = WorldRenderUtil.camera();
        Matrix4f matrix = event.poseStack().last().pose();
        int color = WorldRenderUtil.rgba(
                this.redSetting.getValue().intValue(),
                this.greenSetting.getValue().intValue(),
                this.blueSetting.getValue().intValue(), 255);
        float opacity = this.opacitySetting.getValue().floatValue() / 100.0f;
        long lifetime = this.lifetime();
        Player player = mc.player;
        double height = player.getBbHeight() * (player.isCrouching() ? 0.8 : 1.0);
        double currentX = player.getX() - camera.x;
        double currentY = player.getY() - camera.y;
        double currentZ = player.getZ() - camera.z;

        if ("Points".equals(this.modeSetting.getValue())) {
            float half = this.pointSizeSetting.getValue().floatValue() / 16.0f;
            BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, this.throughWallsSetting.getValue());
            for (Sample sample : this.samples) {
                float alpha = opacity * sample.remaining(now, lifetime);
                if (alpha <= 0.0f) {
                    continue;
                }
                this.emitSquare(buffer, matrix, sample.x - camera.x, sample.y + 0.02 - camera.y,
                        sample.z - camera.z, half, color, alpha * 0.8f);
            }
            this.emitSquare(buffer, matrix, currentX, currentY + 0.02, currentZ, half, color, opacity);
            WorldRenderUtil.end();
            return;
        }

        boolean throughWalls = this.throughWallsSetting.getValue();
        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, throughWalls);
        for (int i = 1; i < this.samples.size(); i++) {
            Sample from = this.samples.get(i - 1);
            Sample to = this.samples.get(i);
            float alpha = opacity * to.remaining(now, lifetime);
            if (alpha <= 0.0f) {
                continue;
            }
            this.emitSegment(buffer, matrix,
                    from.x - camera.x, from.y - camera.y, from.z - camera.z,
                    to.x - camera.x, to.y - camera.y, to.z - camera.z, height, color, alpha * 0.45f);
        }
        Sample last = this.samples.get(this.samples.size() - 1);
        this.emitSegment(buffer, matrix,
                last.x - camera.x, last.y - camera.y, last.z - camera.z,
                currentX, currentY, currentZ, height, color, opacity * 0.45f);
        WorldRenderUtil.end();

        buffer = WorldRenderUtil.begin(VertexFormat.Mode.DEBUG_LINES, throughWalls);
        RenderSystem.lineWidth(this.lineWidthSetting.getValue().floatValue());
        for (int i = 1; i < this.samples.size(); i++) {
            Sample from = this.samples.get(i - 1);
            Sample to = this.samples.get(i);
            float alpha = opacity * to.remaining(now, lifetime);
            if (alpha <= 0.0f) {
                continue;
            }
            this.emitEdge(buffer, matrix,
                    from.x - camera.x, from.y - camera.y, from.z - camera.z,
                    to.x - camera.x, to.y - camera.y, to.z - camera.z, height, color, alpha);
        }
        this.emitEdge(buffer, matrix,
                last.x - camera.x, last.y - camera.y, last.z - camera.z,
                currentX, currentY, currentZ, height, color, opacity);
        WorldRenderUtil.end();
        RenderSystem.lineWidth(1.0f);
    }

    private void emitSegment(BufferBuilder buffer, Matrix4f matrix,
                             double x0, double y0, double z0,
                             double x1, double y1, double z1,
                             double height, int color, float alpha) {
        WorldRenderUtil.vertex(buffer, matrix, x0, y0 + 0.004, z0, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x0, y0 + height - 0.004, z0, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x1, y1 + height - 0.004, z1, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x1, y1 + 0.004, z1, color, alpha);
    }

    private void emitEdge(BufferBuilder buffer, Matrix4f matrix,
                          double x0, double y0, double z0,
                          double x1, double y1, double z1,
                          double height, int color, float alpha) {
        for (int i = 0; i < 2; i++) {
            double lift = i == 0 ? 0.004 : height - 0.004;
            WorldRenderUtil.vertex(buffer, matrix, x0, y0 + lift, z0, color, alpha);
            WorldRenderUtil.vertex(buffer, matrix, x1, y1 + lift, z1, color, alpha);
        }
    }

    private void emitSquare(BufferBuilder buffer, Matrix4f matrix, double x, double y, double z,
                            float half, int color, float alpha) {
        WorldRenderUtil.vertex(buffer, matrix, x - half, y, z - half, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x - half, y, z + half, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x + half, y, z + half, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x + half, y, z - half, color, alpha);
    }

    private static final class Sample {
        private final double x;
        private final double y;
        private final double z;
        private final long time;

        private Sample(double x, double y, double z, long time) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = time;
        }

        private double squaredDistance(double px, double py, double pz) {
            double dx = this.x - px;
            double dy = this.y - py;
            double dz = this.z - pz;
            return dx * dx + dy * dy + dz * dz;
        }

        private float remaining(long now, long lifetime) {
            if (lifetime <= 0L) {
                return 0.0f;
            }
            float remaining = 1.0f - (now - this.time) / (float) lifetime;
            return remaining < 0.0f ? 0.0f : Math.min(remaining, 1.0f);
        }
    }
}
