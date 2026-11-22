package shit.zen.modules.impl.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.awt.Color;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.event.impl.WorldChangeEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Nearby bed overlay, ported from NeoRaven's {@code BedESPModule}.
 *
 * <p>Upstream ran a {@link java.util.Timer} with a full cube scan and cached every bed in maps of
 * {@code BlockPos[]}. Here a {@link GameTickEvent} counter rescans every 10-20 ticks into four flat
 * int arrays (64 beds max), and the cached boxes are revalidated cheaply at render time. The scan
 * radius is capped at 24 blocks horizontally and six blocks vertically so a large "Range" value
 * cannot turn into a millions-of-blocks cube. Everything, including "Draw beam", covering-block
 * outlines and "Render self", is emitted into one reused QUADS batch.</p>
 *
 * <p>Upstream's "Theme" enum names are kept as a {@link ModeSetting}, with "Custom" and separate RGB
 * {@link NumberSetting}s added. "Disable in lobby", "Optimize for Bed Wars", "Show tools only" and
 * "Show whitelisted covering" are dropped: they depend on this client's absent Hypixel/BedWars
 * services and item-icon rendering. Covering blocks are still drawn as block outlines, and broken-bed
 * fade-out is dropped because the original block-break packet hook is not part of this event API.</p>
 */
public class BedESP extends Module {
    private static final int MAX_BEDS = 64;
    private static final String[] THEMES = {
            "Default", "Rainbow", "Aurora", "Cherry", "Cotton candy", "Flare", "Flower", "Forest",
            "Frost", "Gold", "Grayscale", "Inferno", "Royal", "Sandstorm", "Sky", "Vine", "Custom"
    };
    private static final int[] THEME_COLORS = {
            0xFF4040, 0xFFFFFF, 0x7800C8, 0xFFC8C8, 0x63F9FF, 0xF12711, 0xD7A6E7, 0x228B22,
            0xFDFDFD, 0xFFFA00, 0xF0F0F0, 0xFF3300, 0x7DCCF1, 0xC2B280, 0x8CFEFA, 0x00FF19
    };

    private final BooleanSetting renderSelfSetting = new BooleanSetting("Render self", false);
    private final ModeSetting themeSetting = new ModeSetting("Theme", THEMES).withDefault("Default");
    private final NumberSetting redSetting = new NumberSetting("Red", 255, 0, 255, 1,
            () -> "Custom".equals(this.themeSetting.getValue()));
    private final NumberSetting greenSetting = new NumberSetting("Green", 64, 0, 255, 1,
            () -> "Custom".equals(this.themeSetting.getValue()));
    private final NumberSetting blueSetting = new NumberSetting("Blue", 64, 0, 255, 1,
            () -> "Custom".equals(this.themeSetting.getValue()));
    private final NumberSetting rangeSetting = new NumberSetting("Range", 48, 8, 128, 8);
    private final NumberSetting rateSetting = new NumberSetting("Rate", 0.75, 0.5, 1.0, 0.05);
    private final BooleanSetting beamSetting = new BooleanSetting("Draw beam", false);
    private final BooleanSetting firstSetting = new BooleanSetting("Only render first bed", false);
    private final BooleanSetting fullBlockSetting = new BooleanSetting("Render full block", false);
    private final NumberSetting coveringSetting = new NumberSetting("Covering blocks", 0, 0, 4, 1);

    private final int[] bedX = new int[MAX_BEDS];
    private final int[] bedY = new int[MAX_BEDS];
    private final int[] bedZ = new int[MAX_BEDS];
    private final int[] facingX = new int[MAX_BEDS];
    private final int[] facingZ = new int[MAX_BEDS];
    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

    private int bedCount;
    private int tickCounter;

    public BedESP() {
        super("BedESP", Category.RENDER);
    }

    @Override
    protected void onDisable() {
        this.clear();
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        this.clear();
    }

    private void clear() {
        this.bedCount = 0;
        this.tickCounter = 0;
    }

    /** Rescans every 10-20 ticks; "Rate" is upstream's scan period in seconds. */
    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc.level == null || mc.player == null) {
            this.clear();
            return;
        }
        int interval = Math.round(this.rateSetting.getValue().floatValue() * 20.0f);
        interval = Mth.clamp(interval, 10, 20);
        if (++this.tickCounter < interval) {
            return;
        }
        this.tickCounter = 0;
        this.scan();
    }

    private void scan() {
        this.bedCount = 0;
        int radius = Math.min(this.rangeSetting.getValue().intValue(), 24);
        int px = Mth.floor(mc.player.getX());
        int py = Mth.floor(mc.player.getY());
        int pz = Mth.floor(mc.player.getZ());
        for (int y = py - 6; y <= py + 6; y++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    this.scanPos.set(px + dx, y, pz + dz);
                    BlockState state = mc.level.getBlockState(this.scanPos);
                    if (!(state.getBlock() instanceof BedBlock)
                            || state.getValue(BedBlock.PART) != BedBlock.Part.FOOT) {
                        continue;
                    }
                    if (this.bedCount >= MAX_BEDS) {
                        return;
                    }
                    Direction facing = state.getValue(BedBlock.FACING);
                    int index = this.bedCount++;
                    this.bedX[index] = this.scanPos.getX();
                    this.bedY[index] = this.scanPos.getY();
                    this.bedZ[index] = this.scanPos.getZ();
                    this.facingX[index] = facing.getStepX();
                    this.facingZ[index] = facing.getStepZ();
                    if (this.firstSetting.getValue()) {
                        return;
                    }
                }
            }
        }
    }

    private int currentColor() {
        String theme = this.themeSetting.getValue();
        if ("Custom".equals(theme)) {
            return WorldRenderUtil.rgba(this.redSetting.getValue().intValue(),
                    this.greenSetting.getValue().intValue(), this.blueSetting.getValue().intValue(), 255);
        }
        if ("Rainbow".equals(theme)) {
            float hue = (System.currentTimeMillis() % 3000L) / 3000.0f;
            return 0xFF000000 | Color.HSBtoRGB(hue, 1.0f, 1.0f) & 0xFFFFFF;
        }
        for (int i = 0; i < THEMES.length - 1; i++) {
            if (THEMES[i].equals(theme)) {
                return 0xFF000000 | THEME_COLORS[i];
            }
        }
        return 0xFFFF4040;
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null) {
            this.clear();
            return;
        }
        if (this.bedCount == 0 && !this.renderSelfSetting.getValue()) {
            return;
        }
        Vec3 camera = WorldRenderUtil.camera();
        Matrix4f matrix = event.poseStack().last().pose();
        int color = this.currentColor();
        int covering = this.coveringSetting.getValue().intValue();
        boolean beam = this.beamSetting.getValue();
        double height = this.fullBlockSetting.getValue() ? 1.0 : 0.5625;

        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.QUADS, true);
        for (int i = 0; i < this.bedCount; i++) {
            this.scanPos.set(this.bedX[i], this.bedY[i], this.bedZ[i]);
            BlockState state = mc.level.getBlockState(this.scanPos);
            if (!(state.getBlock() instanceof BedBlock)) {
                continue;
            }
            double minX = this.bedX[i] - camera.x;
            double minY = this.bedY[i] - camera.y;
            double minZ = this.bedZ[i] - camera.z;
            double maxX = minX + 1.0;
            double maxZ = minZ + 1.0;
            if (this.facingX[i] != 0) {
                if (this.facingX[i] > 0) {
                    maxX += 1.0;
                } else {
                    minX -= 1.0;
                }
            } else if (this.facingZ[i] != 0) {
                if (this.facingZ[i] > 0) {
                    maxZ += 1.0;
                } else {
                    minZ -= 1.0;
                }
            }
            double maxY = minY + height;
            BedESP.box(buffer, matrix, minX, minY, minZ, maxX, maxY, maxZ, color, 0.28f);
            BedESP.edges(buffer, matrix, minX, minY, minZ, maxX, maxY, maxZ, color, 0.85f);
            if (beam) {
                BedESP.beam(buffer, matrix,
                        (minX + maxX) * 0.5, maxY, (minZ + maxZ) * 0.5, color);
            }
            if (covering > 0) {
                this.covering(buffer, matrix, camera, i, covering, color);
            }
        }
        if (this.renderSelfSetting.getValue()) {
            Vec3 player = WorldRenderUtil.interpolated(mc.player, event.partialTick()).subtract(camera);
            double radius = 0.3;
            BedESP.box(buffer, matrix,
                    player.x - radius, player.y, player.z - radius,
                    player.x + radius, player.y + mc.player.getBbHeight(), player.z + radius,
                    color, 0.20f);
            BedESP.edges(buffer, matrix,
                    player.x - radius, player.y, player.z - radius,
                    player.x + radius, player.y + mc.player.getBbHeight(), player.z + radius,
                    color, 0.70f);
        }
        WorldRenderUtil.end();
    }

    /** Outlines the first few solid blocks stacked above either bed half. */
    private void covering(BufferBuilder buffer, Matrix4f matrix, Vec3 camera,
                          int index, int levels, int color) {
        for (int part = 0; part < 2; part++) {
            int bx = this.bedX[index] + (part == 0 ? 0 : this.facingX[index]);
            int bz = this.bedZ[index] + (part == 0 ? 0 : this.facingZ[index]);
            for (int level = 1; level <= levels; level++) {
                this.scanPos.set(bx, this.bedY[index] + level, bz);
                BlockState above = mc.level.getBlockState(this.scanPos);
                if (above.isAir()) {
                    break;
                }
                double minX = bx - camera.x;
                double minY = this.bedY[index] + level - camera.y;
                double minZ = bz - camera.z;
                BedESP.edges(buffer, matrix, minX, minY, minZ,
                        minX + 1.0, minY + 1.0, minZ + 1.0, color, 0.45f);
            }
        }
    }

    private static void box(BufferBuilder buffer, Matrix4f matrix,
                            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                            int color, float alpha) {
        BedESP.quad(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, color, alpha);
        BedESP.quad(buffer, matrix, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, minX, minY, minZ, color, alpha);
        BedESP.quad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, color, alpha);
        BedESP.quad(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, color, alpha);
        BedESP.quad(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, color, alpha);
        BedESP.quad(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, color, alpha);
    }

    /** Thin bright strips along the twelve edges — still QUADS, so no second batch is needed. */
    private static void edges(BufferBuilder buffer, Matrix4f matrix,
                              double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                              int color, float alpha) {
        double t = 0.02;
        BedESP.box(buffer, matrix, minX, minY, minZ, maxX, minY + t, minZ + t, color, alpha);
        BedESP.box(buffer, matrix, minX, minY, maxZ - t, maxX, minY + t, maxZ, color, alpha);
        BedESP.box(buffer, matrix, minX, maxY - t, minZ, maxX, maxY, minZ + t, color, alpha);
        BedESP.box(buffer, matrix, minX, maxY - t, maxZ - t, maxX, maxY, maxZ, color, alpha);
        BedESP.box(buffer, matrix, minX, minY, minZ, minX + t, maxY, maxZ, color, alpha);
        BedESP.box(buffer, matrix, maxX - t, minY, minZ, maxX, maxY, maxZ, color, alpha);
    }

    /** Two crossed vertical quads with a bottom-to-top alpha falloff. */
    private static void beam(BufferBuilder buffer, Matrix4f matrix,
                             double x, double y, double z, int color) {
        double top = y + 75.0;
        for (int i = 0; i < 2; i++) {
            double ox = i == 0 ? 0.08 : 0.0;
            double oz = i == 0 ? 0.0 : 0.08;
            WorldRenderUtil.vertex(buffer, matrix, x - ox, y, z - oz, color, 0.35f);
            WorldRenderUtil.vertex(buffer, matrix, x + ox, y, z + oz, color, 0.35f);
            WorldRenderUtil.vertex(buffer, matrix, x + ox, top, z + oz, color, 0.0f);
            WorldRenderUtil.vertex(buffer, matrix, x - ox, top, z - oz, color, 0.0f);
        }
    }

    private static void quad(BufferBuilder buffer, Matrix4f matrix,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             int color, float alpha) {
        WorldRenderUtil.vertex(buffer, matrix, x1, y1, z1, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x2, y2, z2, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x3, y3, z3, color, alpha);
        WorldRenderUtil.vertex(buffer, matrix, x4, y4, z4, color, alpha);
    }
}
