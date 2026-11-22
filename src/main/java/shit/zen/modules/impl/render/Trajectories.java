package shit.zen.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.event.impl.WorldChangeEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.render.WorldRenderUtil;

/**
 * Predicted projectile paths, ported from NeoRaven's {@code TrajectoriesModule}.
 *
 * <p>All upstream toggles and labels are kept. The old per-tick {@code GL11} line strips are replaced
 * by a lightweight, allocation-free stepper: positions live in reused {@code double[]} arrays, block
 * collision is sampled through {@link net.minecraft.world.level.Level#getBlockState(BlockPos)} and a
 * slab test against each block's collision bounds (no {@code ClipContext} or {@code Vec3} is created
 * in the render loop). Paths are recomputed once per frame into the cache and then emitted into a
 * single {@code DEBUG_LINES} batch, with a second batch only when "Draw block outline" is on.</p>
 *
 * <p>Steps are capped at 100 and at four paths per frame, and near-living entities are collected
 * once per frame instead of per step. Upstream's hardcoded path colours became RGB
 * {@link NumberSetting}s with visibility rules; BotCheck / Chat / Onyx theme checks were dropped
 * because those services do not exist here.</p>
 */
public class Trajectories extends Module {
    private static final int MAX_STEPS = 100;
    private static final int MAX_PATHS = 4;
    private static final int MAX_TRACKED = 16;
    private static final int MAX_CANDIDATES = 48;
    private static final double REACH = 75.0;

    private final BooleanSetting autoScaleSetting = new BooleanSetting("Auto-scale target", true);
    private final BooleanSetting unchargedSetting = new BooleanSetting("Disable uncharged bow", true);
    private final BooleanSetting highlightSetting = new BooleanSetting("Highlight on entity", true);
    private final BooleanSetting outlineSetting = new BooleanSetting("Draw block outline", true);
    private final BooleanSetting shortenSetting = new BooleanSetting("Shorten line", false);
    private final BooleanSetting bowSetting = new BooleanSetting("Bow", true);
    private final BooleanSetting eggSetting = new BooleanSetting("Egg", true);
    private final BooleanSetting pearlSetting = new BooleanSetting("Ender pearl", true);
    private final BooleanSetting thrownPearlSetting = new BooleanSetting("Ender pearl (thrown)", true);
    private final BooleanSetting enemyFireballSetting = new BooleanSetting("Fireball (enemy)", true);
    private final BooleanSetting thrownFireballSetting = new BooleanSetting("Fireball (thrown)", true);
    private final BooleanSetting snowballSetting = new BooleanSetting("Snowball", true);

    private final NumberSetting pearlRedSetting = new NumberSetting("Pearl Red", 115, 0, 255, 1,
            () -> this.pearlSetting.getValue() || this.thrownPearlSetting.getValue());
    private final NumberSetting pearlGreenSetting = new NumberSetting("Pearl Green", 38, 0, 255, 1,
            () -> this.pearlSetting.getValue() || this.thrownPearlSetting.getValue());
    private final NumberSetting pearlBlueSetting = new NumberSetting("Pearl Blue", 255, 0, 255, 1,
            () -> this.pearlSetting.getValue() || this.thrownPearlSetting.getValue());
    private final NumberSetting fireballRedSetting = new NumberSetting("Fireball Red", 255, 0, 255, 1,
            () -> this.enemyFireballSetting.getValue() || this.thrownFireballSetting.getValue());
    private final NumberSetting fireballGreenSetting = new NumberSetting("Fireball Green", 26, 0, 255, 1,
            () -> this.enemyFireballSetting.getValue() || this.thrownFireballSetting.getValue());
    private final NumberSetting fireballBlueSetting = new NumberSetting("Fireball Blue", 26, 0, 255, 1,
            () -> this.enemyFireballSetting.getValue() || this.thrownFireballSetting.getValue());
    private final NumberSetting entityRedSetting = new NumberSetting("Entity Red", 255, 0, 255, 1,
            () -> this.highlightSetting.getValue());
    private final NumberSetting entityGreenSetting = new NumberSetting("Entity Green", 180, 0, 255, 1,
            () -> this.highlightSetting.getValue());
    private final NumberSetting entityBlueSetting = new NumberSetting("Entity Blue", 40, 0, 255, 1,
            () -> this.highlightSetting.getValue());
    private final NumberSetting groundRedSetting = new NumberSetting("Ground Red", 210, 0, 255, 1);
    private final NumberSetting groundGreenSetting = new NumberSetting("Ground Green", 210, 0, 255, 1);
    private final NumberSetting groundBlueSetting = new NumberSetting("Ground Blue", 210, 0, 255, 1);

    private final double[] pathX = new double[MAX_STEPS + 1];
    private final double[] pathY = new double[MAX_STEPS + 1];
    private final double[] pathZ = new double[MAX_STEPS + 1];
    private final Entity[] tracked = new Entity[MAX_TRACKED];
    private final LivingEntity[] candidates = new LivingEntity[MAX_CANDIDATES];
    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

    private int trackedCount;
    private int candidateCount;
    private int pathCount;
    private int pathsThisFrame;
    private double hitX;
    private double hitY;
    private double hitZ;
    private boolean entityHit;
    private boolean hasBlockHit;
    private final BlockPos.MutableBlockPos hitBlock = new BlockPos.MutableBlockPos();
    private Direction hitFace;
    private Direction boxFace;

    public Trajectories() {
        super("Trajectories", Category.RENDER);
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
        this.trackedCount = 0;
        this.candidateCount = 0;
        this.pathCount = 0;
        this.hasBlockHit = false;
        this.hitFace = null;
        this.boxFace = null;
    }

    /** Tracks thrown pearls and fireballs at tick rate instead of hooking entity joins. */
    @EventTarget
    public void onTick(GameTickEvent event) {
        if (mc.level == null || mc.player == null) {
            this.clear();
            return;
        }
        int count = 0;
        double reachSq = REACH * REACH;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ThrownEnderpearl) && !(entity instanceof LargeFireball)) {
                continue;
            }
            if (!(entity instanceof ThrownEnderpearl) && !this.thrownFireballSetting.getValue()) {
                continue;
            }
            if (entity instanceof ThrownEnderpearl && !this.thrownPearlSetting.getValue()) {
                continue;
            }
            if (!entity.isAlive() || mc.player.distanceToSqr(entity) > reachSq) {
                continue;
            }
            if (count < MAX_TRACKED) {
                this.tracked[count++] = entity;
            }
        }
        this.trackedCount = count;
    }

    private int heldType() {
        if (mc.player == null) {
            return 0;
        }
        ItemStack stack = mc.player.getMainHandItem();
        if (stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();
        if (item == Items.BOW) {
            if (!this.bowSetting.getValue()) {
                return 0;
            }
            return this.unchargedSetting.getValue() && !mc.player.getUseItem().is(Items.BOW) ? 0 : 2;
        }
        if (item == Items.EGG) {
            return this.eggSetting.getValue() ? 1 : 0;
        }
        if (item == Items.ENDER_PEARL) {
            return this.pearlSetting.getValue() ? 1 : 0;
        }
        if (item == Items.SNOWBALL) {
            return this.snowballSetting.getValue() ? 1 : 0;
        }
        return 0;
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null) {
            this.clear();
            return;
        }
        float partial = event.partialTick();
        this.collectCandidates();
        this.pathsThisFrame = 0;

        Matrix4f matrix = event.poseStack().last().pose();
        Vec3 camera = WorldRenderUtil.camera();
        BufferBuilder buffer = WorldRenderUtil.begin(VertexFormat.Mode.DEBUG_LINES, true);
        RenderSystem.lineWidth(2.0f);

        int type = this.heldType();
        if (type != 0 && this.pathsThisFrame < MAX_PATHS) {
            this.computeHeld(type, partial);
            int color = type == 2 ? this.groundColor()
                    : WorldRenderUtil.rgba(this.pearlRedSetting.getValue().intValue(),
                            this.pearlGreenSetting.getValue().intValue(),
                            this.pearlBlueSetting.getValue().intValue(), 255);
            this.drawPath(buffer, matrix, camera, color, this.shortenSetting.getValue());
        }
        if (this.enemyFireballSetting.getValue()) {
            int color = this.fireballColor();
            for (int i = 0; i < this.candidateCount && this.pathsThisFrame < MAX_PATHS; i++) {
                LivingEntity living = this.candidates[i];
                if (!(living instanceof Player)) {
                    continue;
                }
                if (!((Player) living).getMainHandItem().is(Items.FIRE_CHARGE)) {
                    continue;
                }
                this.computeAim(living, partial);
                this.drawPath(buffer, matrix, camera, color, false);
            }
        }
        for (int i = 0; i < this.trackedCount && this.pathsThisFrame < MAX_PATHS; i++) {
            Entity entity = this.tracked[i];
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            if (entity instanceof ThrownEnderpearl) {
                Vec3 motion = entity.getDeltaMovement();
                this.simulate(entity.getX(), entity.getY(), entity.getZ(),
                        motion.x, motion.y, motion.z, 0.03, 0.99);
            } else {
                Vec3 motion = entity.getDeltaMovement();
                double speed = Math.sqrt(motion.x * motion.x + motion.y * motion.y + motion.z * motion.z);
                if (speed < 1.0E-4) {
                    continue;
                }
                double scale = 1.0 / speed;
                this.simulate(entity.getX(), entity.getY(), entity.getZ(),
                        motion.x * scale, motion.y * scale, motion.z * scale, 0.0, 1.0);
            }
            this.drawPath(buffer, matrix, camera,
                    entity instanceof ThrownEnderpearl ? this.pearlColor() : this.fireballColor(), false);
        }
        WorldRenderUtil.end();
        RenderSystem.lineWidth(1.0f);

        if (this.outlineSetting.getValue() && this.hasBlockHit && this.hitFace != null) {
            buffer = WorldRenderUtil.begin(VertexFormat.Mode.DEBUG_LINES, true);
            RenderSystem.lineWidth(2.0f);
            this.drawFace(buffer, matrix, camera, this.hitBlock, this.hitFace, this.groundColor());
            WorldRenderUtil.end();
            RenderSystem.lineWidth(1.0f);
        }
    }

    private void collectCandidates() {
        int count = 0;
        double reachSq = REACH * REACH;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity) || entity == mc.player || entity instanceof ArmorStand) {
                continue;
            }
            if (!entity.isAlive() || entity.isSpectator() || mc.player.distanceToSqr(entity) > reachSq) {
                continue;
            }
            if (count < MAX_CANDIDATES) {
                this.candidates[count++] = (LivingEntity) entity;
            }
        }
        this.candidateCount = count;
    }

    private int pearlColor() {
        return WorldRenderUtil.rgba(this.pearlRedSetting.getValue().intValue(),
                this.pearlGreenSetting.getValue().intValue(), this.pearlBlueSetting.getValue().intValue(), 255);
    }

    private int fireballColor() {
        return WorldRenderUtil.rgba(this.fireballRedSetting.getValue().intValue(),
                this.fireballGreenSetting.getValue().intValue(), this.fireballBlueSetting.getValue().intValue(), 255);
    }

    private int groundColor() {
        return WorldRenderUtil.rgba(this.groundRedSetting.getValue().intValue(),
                this.groundGreenSetting.getValue().intValue(), this.groundBlueSetting.getValue().intValue(), 255);
    }

    private void computeHeld(int type, float partial) {
        Player player = mc.player;
        double yaw = Math.toRadians(Mth.lerp(partial, player.yRotO, player.getYRot()));
        double pitch = Math.toRadians(Mth.lerp(partial, player.xRotO, player.getXRot()));
        double x = player.xo + (player.getX() - player.xo) * partial - Math.cos(yaw) * 0.16;
        double y = player.yo + (player.getY() - player.yo) * partial + player.getEyeHeight() - 0.1;
        double z = player.zo + (player.getZ() - player.zo) * partial - Math.sin(yaw) * 0.16;
        double speed = 1.5;
        double gravity = 0.03;
        if (type == 2) {
            float pull = (72000 - player.getUseItemRemainingTicks() + partial) / 20.0f;
            pull = (pull * pull + pull * 2.0f) / 3.0f;
            speed = 3.0 * Math.min(1.0f, pull);
            gravity = 0.05;
        }
        double cosPitch = Math.cos(pitch);
        this.simulate(x, y, z,
                -Math.sin(yaw) * cosPitch * speed,
                -Math.sin(pitch) * speed,
                Math.cos(yaw) * cosPitch * speed,
                gravity, 0.99);
    }

    private void computeAim(LivingEntity living, float partial) {
        double x = living.xo + (living.getX() - living.xo) * partial;
        double y = living.yo + (living.getY() - living.yo) * partial + living.getEyeHeight();
        double z = living.zo + (living.getZ() - living.zo) * partial;
        double yaw = Math.toRadians(Mth.rotLerp(partial, living.yHeadRotO, living.yHeadRot));
        double pitch = Math.toRadians(Mth.lerp(partial, living.xRotO, living.getXRot()));
        double cosPitch = Math.cos(pitch);
        this.simulate(x, y, z,
                -Math.sin(yaw) * cosPitch * REACH,
                -Math.sin(pitch) * REACH,
                Math.cos(yaw) * cosPitch * REACH,
                0.0, 1.0);
    }

    /** One cached path; MAX_STEPS allocation-free slab tests, no ClipContext or Vec3. */
    private void simulate(double x, double y, double z,
                          double vx, double vy, double vz, double gravity, double drag) {
        this.pathCount = 0;
        this.entityHit = false;
        this.hasBlockHit = false;
        this.hitFace = null;
        this.hitX = x;
        this.hitY = y;
        this.hitZ = z;
        for (int step = 0; step < MAX_STEPS && this.pathCount < MAX_STEPS; step++) {
            this.pathX[this.pathCount] = x;
            this.pathY[this.pathCount] = y;
            this.pathZ[this.pathCount] = z;
            this.pathCount++;

            double nx = x + vx;
            double ny = y + vy;
            double nz = z + vz;
            double bestT = 2.0;
            LivingEntity bestEntity = null;
            double midX = (x + nx) * 0.5, midY = (y + ny) * 0.5, midZ = (z + nz) * 0.5;
            for (int i = 0; i < this.candidateCount; i++) {
                LivingEntity living = this.candidates[i];
                if (Math.abs(living.getX() - midX) > 4.0 || Math.abs(living.getY() - midY) > 4.0
                        || Math.abs(living.getZ() - midZ) > 4.0) {
                    continue;
                }
                double t = this.boxHit(living.getBoundingBox().minX, living.getBoundingBox().minY,
                        living.getBoundingBox().minZ, living.getBoundingBox().maxX,
                        living.getBoundingBox().maxY, living.getBoundingBox().maxZ,
                        x, y, z, nx, ny, nz, 0.3);
                if (t < bestT) {
                    bestT = t;
                    bestEntity = living;
                }
            }

            this.scanPos.set(Mth.floor(nx), Mth.floor(ny), Mth.floor(nz));
            BlockState state = mc.level.getBlockState(this.scanPos);
            double blockT = 2.0;
            if (!state.isAir()) {
                VoxelShape shape = state.getCollisionShape(mc.level, this.scanPos);
                if (!shape.isEmpty()) {
                    double baseX = this.scanPos.getX(), baseY = this.scanPos.getY(), baseZ = this.scanPos.getZ();
                    blockT = this.boxHit(shape.bounds().minX + baseX, shape.bounds().minY + baseY,
                            shape.bounds().minZ + baseZ, shape.bounds().maxX + baseX,
                            shape.bounds().maxY + baseY, shape.bounds().maxZ + baseZ,
                            x, y, z, nx, ny, nz, 0.0);
                    if (blockT < bestT) {
                        this.hasBlockHit = true;
                        this.hitFace = this.boxFace;
                        this.hitBlock.set(this.scanPos.getX(), this.scanPos.getY(), this.scanPos.getZ());
                    }
                }
            }

            if (bestEntity != null && bestT <= blockT) {
                this.hitX = x + (nx - x) * bestT;
                this.hitY = y + (ny - y) * bestT;
                this.hitZ = z + (nz - z) * bestT;
                this.entityHit = true;
            } else if (this.hasBlockHit) {
                this.hitX = x + (nx - x) * blockT;
                this.hitY = y + (ny - y) * blockT;
                this.hitZ = z + (nz - z) * blockT;
            } else {
                x = nx;
                y = ny;
                z = nz;
                vx *= drag;
                vy = vy * drag - gravity;
                vz *= drag;
                if (y < -64.0) {
                    break;
                }
                continue;
            }
            this.pathX[this.pathCount] = this.hitX;
            this.pathY[this.pathCount] = this.hitY;
            this.pathZ[this.pathCount] = this.hitZ;
            this.pathCount++;
            break;
        }
        this.pathsThisFrame++;
    }

    /** Slab test against an AABB; returns the segment parameter in [0, 1] or 2.0 for a miss. */
    private double boxHit(double minX, double minY, double minZ,
                          double maxX, double maxY, double maxZ,
                          double x0, double y0, double z0, double x1, double y1, double z1,
                          double pad) {
        this.boxFace = Direction.UP;
        double near = 0.0;
        double far = 1.0;
        double d = x1 - x0;
        if (Math.abs(d) < 1.0E-8) {
            if (x0 < minX - pad || x0 > maxX + pad) {
                return 2.0;
            }
        } else {
            double t1 = (minX - pad - x0) / d;
            double t2 = (maxX + pad - x0) / d;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > near) {
                this.rememberFace(0, d);
            }
            near = Math.max(near, t1);
            far = Math.min(far, t2);
            if (near > far) {
                return 2.0;
            }
        }
        d = y1 - y0;
        if (Math.abs(d) < 1.0E-8) {
            if (y0 < minY - pad || y0 > maxY + pad) {
                return 2.0;
            }
        } else {
            double t1 = (minY - pad - y0) / d;
            double t2 = (maxY + pad - y0) / d;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > near) {
                this.rememberFace(1, d);
            }
            near = Math.max(near, t1);
            far = Math.min(far, t2);
            if (near > far) {
                return 2.0;
            }
        }
        d = z1 - z0;
        if (Math.abs(d) < 1.0E-8) {
            if (z0 < minZ - pad || z0 > maxZ + pad) {
                return 2.0;
            }
        } else {
            double t1 = (minZ - pad - z0) / d;
            double t2 = (maxZ + pad - z0) / d;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > near) {
                this.rememberFace(2, d);
            }
            near = Math.max(near, t1);
            far = Math.min(far, t2);
            if (near > far) {
                return 2.0;
            }
        }
        return near <= 1.0 ? near : 2.0;
    }

    /** Records the entry face of the last slab that moved the intersection point. */
    private void rememberFace(int axis, double delta) {
        if (axis == 0) {
            this.boxFace = delta > 0.0 ? Direction.WEST : Direction.EAST;
        } else if (axis == 1) {
            this.boxFace = delta > 0.0 ? Direction.DOWN : Direction.UP;
        } else {
            this.boxFace = delta > 0.0 ? Direction.NORTH : Direction.SOUTH;
        }
    }

    private void drawPath(BufferBuilder buffer, Matrix4f matrix, Vec3 camera, int color, boolean shorten) {
        if (this.pathCount < 2) {
            return;
        }
        int rgb = color;
        if (this.entityHit && this.highlightSetting.getValue()) {
            rgb = WorldRenderUtil.rgba(this.entityRedSetting.getValue().intValue(),
                    this.entityGreenSetting.getValue().intValue(), this.entityBlueSetting.getValue().intValue(), 255);
        }
        int first = shorten ? 1 : 0;
        for (int i = first; i < this.pathCount - 1; i++) {
            Trajectories.line(buffer, matrix,
                    this.pathX[i] - camera.x, this.pathY[i] - camera.y, this.pathZ[i] - camera.z,
                    this.pathX[i + 1] - camera.x, this.pathY[i + 1] - camera.y, this.pathZ[i + 1] - camera.z,
                    rgb);
        }
        float scale = 0.1f;
        if (this.autoScaleSetting.getValue()) {
            double distance = mc.player.distanceToSqr(this.hitX, this.hitY, this.hitZ);
            scale = (float) Math.min(0.1 * (1.0 + distance / 500.0), 0.5);
        }
        double cx = this.hitX - camera.x, cy = this.hitY - camera.y, cz = this.hitZ - camera.z;
        boolean vertical = this.hitFace == null || this.hitFace.getAxis() == Direction.Axis.Y;
        if (vertical) {
            Trajectories.line(buffer, matrix, cx - scale, cy, cz, cx + scale, cy, cz, rgb);
            Trajectories.line(buffer, matrix, cx, cy, cz - scale, cx, cy, cz + scale, rgb);
        } else if (this.hitFace.getAxis() == Direction.Axis.X) {
            Trajectories.line(buffer, matrix, cx, cy - scale, cz, cx, cy + scale, cz, rgb);
            Trajectories.line(buffer, matrix, cx, cy, cz - scale, cx, cy, cz + scale, rgb);
        } else {
            Trajectories.line(buffer, matrix, cx - scale, cy, cz, cx + scale, cy, cz, rgb);
            Trajectories.line(buffer, matrix, cx, cy - scale, cz, cx, cy + scale, cz, rgb);
        }
    }

    private void drawFace(BufferBuilder buffer, Matrix4f matrix, Vec3 camera,
                          BlockPos pos, Direction face, int color) {
        double minX = pos.getX() - camera.x, minY = pos.getY() - camera.y, minZ = pos.getZ() - camera.z;
        double maxX = minX + 1.0, maxY = minY + 1.0, maxZ = minZ + 1.0;
        if (face.getAxis() == Direction.Axis.X) {
            double x = face.getStepX() > 0 ? maxX : minX;
            Trajectories.line(buffer, matrix, x, minY, minZ, x, maxY, minZ, color);
            Trajectories.line(buffer, matrix, x, maxY, minZ, x, maxY, maxZ, color);
            Trajectories.line(buffer, matrix, x, maxY, maxZ, x, minY, maxZ, color);
            Trajectories.line(buffer, matrix, x, minY, maxZ, x, minY, minZ, color);
        } else if (face.getAxis() == Direction.Axis.Y) {
            double y = face.getStepY() > 0 ? maxY : minY;
            Trajectories.line(buffer, matrix, minX, y, minZ, maxX, y, minZ, color);
            Trajectories.line(buffer, matrix, maxX, y, minZ, maxX, y, maxZ, color);
            Trajectories.line(buffer, matrix, maxX, y, maxZ, minX, y, maxZ, color);
            Trajectories.line(buffer, matrix, minX, y, maxZ, minX, y, minZ, color);
        } else {
            double z = face.getStepZ() > 0 ? maxZ : minZ;
            Trajectories.line(buffer, matrix, minX, minY, z, maxX, minY, z, color);
            Trajectories.line(buffer, matrix, maxX, minY, z, maxX, maxY, z, color);
            Trajectories.line(buffer, matrix, maxX, maxY, z, minX, maxY, z, color);
            Trajectories.line(buffer, matrix, minX, maxY, z, minX, minY, z, color);
        }
    }

    private static void line(BufferBuilder buffer, Matrix4f matrix,
                             double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        WorldRenderUtil.vertex(buffer, matrix, x1, y1, z1, color, 0.85f);
        WorldRenderUtil.vertex(buffer, matrix, x2, y2, z2, color, 0.85f);
    }
}
