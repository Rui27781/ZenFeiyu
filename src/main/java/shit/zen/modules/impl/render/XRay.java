package shit.zen.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import shit.zen.ZenClient;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.PacketEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import lombok.EqualsAndHashCode;
import shit.zen.utils.game.PlayerUtil;
import shit.zen.utils.game.RotationUtil;
import shit.zen.utils.misc.ChatUtil;
import shit.zen.utils.misc.PacketUtil;
import shit.zen.utils.rotation.Rotation;

/**
 * XRay — ore locator with three visual layers plus an optional built-in auto-miner:
 * <ul>
 *     <li><b>True see-through</b>: when this module is enabled, {@link shit.zen.patch.BlockPatch}
 *         hooks {@code Block.shouldRenderFace} and renders only the target blocks (the rest of the
 *         terrain becomes invisible). Toggling the module calls {@link
 *         net.minecraft.client.renderer.LevelRenderer#allChanged()} below to rebuild all chunk
 *         meshes so the effect applies/clears instantly.</li>
 *     <li><b>ESP overlay</b>: a background thread flood-fills nearby air pockets, finds adjacent
 *         ore veins, scores them to reject anti-cheat "fake" ores, and draws wireframe boxes plus
 *         on-screen tracers. A "blind scan" additionally tracks block-update packets so ores can be
 *         surfaced even outside the rendered chunk set.</li>
 *     <li><b>Protocol probe</b> (ported from TrueSight): anti-xray only disguises whole chunk
 *         packets, while single-block/section update packets always carry the real block. This layer
 *         sends cheap abort-break actions at reachable lattice points so the server broadcasts the
 *         real blocks around them, then renders <em>only</em> the ores the server itself confirmed.
 *         That makes the green probe boxes a fake-ore-free subset of everything else on screen.</li>
 *     <li><b>Auto-mine</b>: an optional state machine that picks the nearest/highest-value ore from the
 *         already-detected sets, smoothly turns toward it, swaps to the best hotbar tool, and drives
 *         {@code startDestroyBlock}/{@code continueDestroyBlock} until the block breaks or times out.</li>
 * </ul>
 */
public class XRay extends Module {
    public static XRay INSTANCE;

    private final ModeSetting xrayModeSetting = new ModeSetting("Block Type", "Ores", "Valuables").withDefault("Ores");
    private final NumberSetting scanRangeSetting = new NumberSetting("Range", 48, 16, 100, 4);
    private final NumberSetting scanIntervalSetting = new NumberSetting("Scan Delay", 500, 50, 3000, 100);
    private final BooleanSetting tracersSetting = new BooleanSetting("Tracers", true);
    private final NumberSetting maxBlocksSetting = new NumberSetting("MaxBlocks", 80000, 10000, 500000, 10000);
    private final BooleanSetting antiFakeOreSetting = new BooleanSetting("Anti-Fake Ore", true);
    private final NumberSetting minAuthScoreSetting = new NumberSetting("Min Auth Score", 1.0, 1.0, 15.0, 0.5, this.antiFakeOreSetting::getValue);
    private final BooleanSetting patternAnalysisSetting = new BooleanSetting("Pattern Analysis", false, this.antiFakeOreSetting::getValue);
    private final BooleanSetting strictHostRockSetting = new BooleanSetting("Strict Host Rock Check", true, this.antiFakeOreSetting::getValue);
    private final BooleanSetting altStartSetting = new BooleanSetting("Enable Alternative Start", true);
    private final NumberSetting minAirBlocksSetting = new NumberSetting("Min Air Blocks Required", 50, 10, 1000, 50);
    private final NumberSetting altSearchRadiusSetting = new NumberSetting("Alternative Search Radius", 5, 1, 10, 1);
    private final BooleanSetting blindScanSetting = new BooleanSetting("Blind Scan", true);
    private final NumberSetting blindRenderRangeSetting = new NumberSetting("Blind Render Range", 32, 8, 1024, 4, this.blindScanSetting::getValue);
    private final BooleanSetting probeSetting = new BooleanSetting("Protocol Probe", true);
    private final NumberSetting probeRadiusSetting = new NumberSetting("Probe Radius", 6, 1, 10, 1, this.probeSetting::getValue);
    private final NumberSetting probeBatchSetting = new NumberSetting("Probe Batch", 40, 5, 200, 5, this.probeSetting::getValue);
    private final NumberSetting probeIntervalSetting = new NumberSetting("Probe Interval", 250, 50, 2000, 50, this.probeSetting::getValue);
    private final NumberSetting minVeinSizeSetting = new NumberSetting("Minimum Vein Size", 1, 1, 10, 1, this.antiFakeOreSetting::getValue);
    private final BooleanSetting diamondSetting = new BooleanSetting("Diamond", true);
    private final BooleanSetting emeraldSetting = new BooleanSetting("Emerald", true);
    private final BooleanSetting redstoneSetting = new BooleanSetting("Redstone", true);
    private final BooleanSetting ironSetting = new BooleanSetting("Iron", true);
    private final BooleanSetting goldSetting = new BooleanSetting("Gold", true);
    private final BooleanSetting copperSetting = new BooleanSetting("Copper", true);
    private final BooleanSetting lapisSetting = new BooleanSetting("Lapis", true);
    private final BooleanSetting coalSetting = new BooleanSetting("Coal", true);
    private final BooleanSetting quartzSetting = new BooleanSetting("Quartz", true);
    private final BooleanSetting ancientDebrisSetting = new BooleanSetting("Ancient Debris", true);
    private final BooleanSetting spawnerSetting = new BooleanSetting("Spawner", true);
    private final BooleanSetting endPortalSetting = new BooleanSetting("EndPortalFrame", true);

    // ===== 自动挖矿设置（内置在 XRay 内，先探测矿物再自动挖掘） =====
    /** 自动挖矿总开关。 */
    private final BooleanSetting autoMineSetting = new BooleanSetting("Auto Mine", false);
    /** 自动挖矿距离：从眼睛到方块中心的距离，超过后不会选中。 */
    private final NumberSetting autoMineRangeSetting = new NumberSetting("Auto Mine Range", 4.5, 2.0, 6.0, 0.5, this.autoMineSetting::getValue);
    /** 每挖掉一个方块后的间隔（毫秒），用于降低被反作弊判定为瞬间挖掘的概率。 */
    private final NumberSetting autoMineDelaySetting = new NumberSetting("Auto Mine Delay", 150, 0, 2000, 50, this.autoMineSetting::getValue);
    /** 单个方块的最长挖掘时间（秒），超时后放弃并临时拉黑。 */
    private final NumberSetting autoMineTimeoutSetting = new NumberSetting("Auto Mine Timeout", 15, 1, 60, 1, this.autoMineSetting::getValue);
    /** 目标选择优先级：Nearest=最近优先，Valuable=价值优先。 */
    private final ModeSetting autoMinePrioritySetting = new ModeSetting("Auto Mine Priority", "Nearest", "Valuable")
            .withVisibility(this.autoMineSetting::getValue)
            .withDefault("Nearest");
    /** 是否自动切换到最适合当前方块的工具。 */
    private final BooleanSetting autoMineToolSetting = new BooleanSetting("Auto Mine Switch Tool", true, this.autoMineSetting::getValue);
    /** 是否只挖暴露在空气/液体中的矿物，避免隔着一整面石头空挖。 */
    private final BooleanSetting autoMineExposedSetting = new BooleanSetting("Auto Mine Exposed Only", false, this.autoMineSetting::getValue);
    /** 是否只挖协议探针确认过的真实矿物（需要开启 Protocol Probe）。 */
    private final BooleanSetting autoMineConfirmedSetting = new BooleanSetting("Auto Mine Confirmed Only", false,
            () -> this.autoMineSetting.getValue() && this.probeSetting.getValue());
    /** 是否给当前自动挖掘目标绘制金色高亮框。 */
    private final BooleanSetting autoMineHighlightSetting = new BooleanSetting("Auto Mine Highlight", true, this.autoMineSetting::getValue);

    private final Color diamondColor = new Color(0, 255, 255);
    private final Color emeraldColor = new Color(0, 255, 0);
    private final Color redstoneColor = new Color(255, 0, 0);
    private final Color ironColor = new Color(210, 210, 210);
    private final Color goldColor = new Color(255, 215, 0);
    private final Color copperColor = new Color(205, 127, 50);
    private final Color lapisColor = new Color(0, 0, 255);
    private final Color coalColor = new Color(50, 50, 50);
    private final Map<BlockPos, Block> foundBlocks = new ConcurrentHashMap<>();
    private volatile Map<BlockPos, Block> pendingBlocks = null;
    private long lastScanTime = 0L;
    private boolean scanInProgress = false;
    private final Map<BlockKey, BlockState> blindScanBlocks = new ConcurrentHashMap<>();
    private Level currentLevel;
    private static ThreadPoolExecutor threadPool;
    private volatile long generationId = 0L;
    private Matrix4f modelviewMatrix;
    private Matrix4f projectionMatrix;
    private final Map<String, ChunkState> chunkStateCache = new ConcurrentHashMap<>();
    private static final Direction[] DIRECTIONS = Direction.values();

    // ===== Protocol probe state (written by the packet thread, read by the client thread) =====
    /** Server-confirmed real target ores, keyed by position. This is the fake-ore-free display set. */
    private final Map<BlockPos, BlockState> probeOres = new ConcurrentHashMap<>();
    /** Positions the server has actually sent an update for (cumulative, invalidated on chunk reload). */
    private final Set<BlockPos> probeRevealed = ConcurrentHashMap.newKeySet();
    /** Per-position probe coverage counter; once it hits the cap the position is left alone. */
    private final Map<BlockPos, Integer> probeAttempts = new ConcurrentHashMap<>();
    /** Lattice points probed in the current round, used to classify reply distance. */
    private final Set<BlockPos> probeRoundTargets = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<BlockPos> probeQueue = new ArrayDeque<>();
    private boolean probeAwaitingReply = false;
    private int probeCooldownTicks = 0;
    /** Server update-radius guess: 2 (24 blocks) until proven to be 1 (6 face neighbours). */
    private int probeRevealRadius = 2;
    private volatile boolean probeRadiusConfirmed = false;
    private volatile int probeHitDist1 = 0;
    private volatile int probeHitDist2 = 0;
    private Level probeLevel = null;

    /** Eye-to-block-center hard cap the server accepts for interaction packets (4.5 + 1.0 buffer). */
    private static final double PROBE_REACH = 5.5;
    private static final int PROBE_MAX_ATTEMPTS = 2;
    private static final int PROBE_REPLY_WAIT_TICKS = 2;
    private static final long PROBE_BATCH_SLEEP_MS = 100L;
    private static final int PROBE_RADIUS1_EVIDENCE = 20;
    private static final Color PROBE_COLOR = new Color(120, 255, 0);
    /** Offsets revealed by a radius-2 server update (Manhattan distance 1..2). */
    private static final List<Vec3i> PROBE_BALL_2 = buildProbeBall(2);
    /** Offsets revealed by a radius-1 server update (the 6 face neighbours). */
    private static final List<Vec3i> PROBE_BALL_1 = buildProbeBall(1);
    private static final Set<Block> OVERWORLD_HOST_BLOCKS = Set.of(
            Blocks.STONE,
            Blocks.ANDESITE,
            Blocks.DIORITE,
            Blocks.GRANITE,
            Blocks.DEEPSLATE,
            Blocks.TUFF,
            Blocks.CALCITE,
            Blocks.DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.PODZOL,
            Blocks.MYCELIUM,
            Blocks.GRAVEL,
            Blocks.DRIPSTONE_BLOCK,
            Blocks.SAND,
            Blocks.SANDSTONE,
            Blocks.RED_SAND,
            Blocks.RED_SANDSTONE
    );
    private static final Set<Block> NETHER_HOST_BLOCKS = Set.of(
            Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.SOUL_SAND, Blocks.SOUL_SOIL
    );

    // ===== 自动挖矿运行状态（只在客户端线程读写，无需并发容器） =====
    /** 当前正在挖掘的矿物方块；为 null 表示正在寻找目标。 */
    private BlockPos autoMineTarget = null;
    /** 是否已经对当前目标调用过 startDestroyBlock。 */
    private boolean autoMineStarted = false;
    /** 当前目标已经持续的 tick 数，用于超时放弃。 */
    private int autoMineTicks = 0;
    /** 下一次允许挑选目标的系统时间（毫秒），用于控制挖矿间隔。 */
    private long autoMineNextTime = 0L;
    /** 兜底局部扫描的下一次允许时间（毫秒），避免空闲时每 tick 都扫一圈。 */
    private long autoMineLocalScanTime = 0L;
    /** 当前目标使用的挖掘面（发给服务端的方块面）。 */
    private Direction autoMineFace = Direction.UP;
    /** 临时黑名单：记录超时/失败的方块与解禁时间，避免反复卡在同一块上。 */
    private final Map<BlockPos, Long> autoMineBlacklist = new HashMap<>();
    /** 转向速度（度/tick）与判定“已经对准”的角度容差。 */
    private static final float AUTO_MINE_ROTATE_STEP = 35.0f;
    private static final float AUTO_MINE_AIM_TOLERANCE = 6.0f;
    /** 失败目标在黑名单中保留的时间（毫秒）与局部兜底扫描间隔（毫秒）。 */
    private static final long AUTO_MINE_BLACKLIST_MS = 4000L;
    private static final long AUTO_MINE_LOCAL_SCAN_INTERVAL_MS = 250L;
    /** 自动挖矿目标的高亮颜色与略微外扩的包围盒（复用静态实例，避免每帧分配）。 */
    private static final Color AUTO_MINE_COLOR = new Color(255, 196, 0);
    private static final AABB AUTO_MINE_BOX = new AABB(-0.02, -0.02, -0.02, 1.02, 1.02, 1.02);

    public XRay() {
        super("XRay", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.clearState();
        this.currentLevel = null;
        if (threadPool == null || threadPool.isShutdown()) {
            threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        }
        if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }

    @Override
    public void onDisable() {
        this.clearState();
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdownNow();
        }
        if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }

    private void clearState() {
        this.foundBlocks.clear();
        this.pendingBlocks = null;
        this.scanInProgress = false;
        this.blindScanBlocks.clear();
        this.modelviewMatrix = null;
        this.projectionMatrix = null;
        this.chunkStateCache.clear();
        this.probeLevel = null;
        this.clearProbeState();
        // 切换世界/关闭模块时，结束正在进行的自动挖掘并清空临时状态
        this.resetAutoMine();
        this.autoMineBlacklist.clear();
        this.generationId++;
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (!ZenClient.isReady()) {
            return;
        }
        if (mc.level != this.currentLevel) {
            this.resetForNewWorld();
        }
        if (this.pendingBlocks != null) {
            this.foundBlocks.clear();
            this.foundBlocks.putAll(this.pendingBlocks);
            this.pendingBlocks = null;
        }
        if (System.currentTimeMillis() - this.lastScanTime > this.scanIntervalSetting.getValue().longValue() && !this.scanInProgress) {
            this.scanInProgress = true;
            long generation = this.generationId;
            threadPool.execute(() -> {
                try {
                    Map<BlockPos, Block> result = new ConcurrentHashMap<>();
                    this.scanFromPos(mc.player.blockPosition(), result);
                    if (generation == this.generationId) {
                        this.pendingBlocks = result;
                    }
                } finally {
                    this.scanInProgress = false;
                    this.lastScanTime = System.currentTimeMillis();
                }
            });
        }
        // 先跑自动挖矿状态机，再跑协议探针：探针会检查 autoMineTarget，避免在挖矿时发 ABORT 包打断进度
        this.tickAutoMine();
        this.tickProbe();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!ZenClient.isReady()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdate) {
            this.foundBlocks.remove(blockUpdate.getPos());
            if (this.blindScanSetting.getValue()) {
                this.blindScanBlocks.put(new BlockKey(blockUpdate.getPos()), blockUpdate.getBlockState());
            }
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionUpdate) {
            sectionUpdate.runUpdates((pos, state) -> {
                this.foundBlocks.remove(pos);
                if (this.blindScanSetting.getValue()) {
                    this.blindScanBlocks.put(new BlockKey(pos), state);
                }
            });
        }
        this.handleProbePacket(packet);
    }

    private void resetForNewWorld() {
        this.clearState();
        this.currentLevel = mc.level;
        if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
        ChatUtil.print(ChatFormatting.GREEN + "Reset");
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.gameRenderer.getMainCamera().isInitialized()) {
            this.modelviewMatrix = new Matrix4f(event.poseStack().last().pose());
            this.projectionMatrix = new Matrix4f(RenderSystem.getProjectionMatrix());
        }
        if (!ZenClient.isReady()) {
            return;
        }
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
        PoseStack poseStack = event.poseStack();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        if (!this.foundBlocks.isEmpty()) {
            double rangeSq = Math.pow(this.scanRangeSetting.getValue().doubleValue(), 2.0);
            for (Map.Entry<BlockPos, Block> entry : this.foundBlocks.entrySet()) {
                if (this.probeSetting.getValue() && this.probeOres.containsKey(entry.getKey())) {
                    continue;
                }
                if (entry.getKey().distSqr(mc.player.blockPosition()) <= rangeSq) {
                    this.renderBlock(poseStack, entry.getKey(), entry.getValue().defaultBlockState(), cameraPos);
                }
            }
        }
        if (this.blindScanSetting.getValue() && !this.blindScanBlocks.isEmpty()) {
            double blindRangeSq = Math.pow(this.blindRenderRangeSetting.getValue().doubleValue(), 2.0);
            this.blindScanBlocks.forEach((key, state) -> {
                if (!this.isEnabledOreBlock(state.getBlock())) {
                    return;
                }
                BlockPos pos = key.toBlockPos();
                if (this.probeSetting.getValue() && this.probeOres.containsKey(pos)) {
                    return;
                }
                if (pos.distSqr(mc.player.blockPosition()) <= blindRangeSq) {
                    this.renderBlock(poseStack, pos, state, cameraPos);
                }
            });
        }
        if (this.probeSetting.getValue() && !this.probeOres.isEmpty()) {
            double probeRangeSq = Math.pow(this.scanRangeSetting.getValue().doubleValue(), 2.0);
            BlockPos playerPos = mc.player.blockPosition();
            for (Map.Entry<BlockPos, BlockState> entry : this.probeOres.entrySet()) {
                if (entry.getKey().distSqr(playerPos) <= probeRangeSq) {
                    this.renderProbeBlock(poseStack, entry.getKey(), cameraPos);
                }
            }
        }
        // 自动挖矿：给当前目标叠加一个金色高亮框，方便观察挖掘状态
        if (this.autoMineSetting.getValue() && this.autoMineHighlightSetting.getValue() && this.autoMineTarget != null) {
            poseStack.pushPose();
            poseStack.translate(
                    this.autoMineTarget.getX() - cameraPos.x,
                    this.autoMineTarget.getY() - cameraPos.y,
                    this.autoMineTarget.getZ() - cameraPos.z);
            this.drawWireBox(poseStack, AUTO_MINE_BOX, AUTO_MINE_COLOR);
            poseStack.popPose();
        }
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private void renderProbeBlock(PoseStack poseStack, BlockPos pos, Vec3 cameraPos) {
        poseStack.pushPose();
        poseStack.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
        this.drawWireBox(poseStack, new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), PROBE_COLOR);
        poseStack.popPose();
    }

    private void renderBlock(PoseStack poseStack, BlockPos pos, BlockState state, Vec3 cameraPos) {
        if (state == null || state.isAir()) {
            return;
        }
        Color color = this.getBlockColor(state.getBlock());
        if (color == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
        this.drawWireBox(poseStack, new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), color);
        poseStack.popPose();
    }

    private void scanRegion(int radius, Map<BlockPos, Block> out) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = new BlockPos(
                            (int) (mc.player.getX() + dx),
                            (int) (mc.player.getY() + dy),
                            (int) (mc.player.getZ() + dz));
                    Block block = PlayerUtil.getBlock(pos);
                    if (this.isSpecialBlock(block)) {
                        out.put(pos, block);
                    }
                }
            }
        }
    }

    private boolean isSpecialBlock(Block block) {
        return this.spawnerSetting.getValue() && block == Blocks.SPAWNER
                || this.quartzSetting.getValue() && block == Blocks.NETHER_QUARTZ_ORE
                || this.ancientDebrisSetting.getValue() && block == Blocks.ANCIENT_DEBRIS
                || this.endPortalSetting.getValue() && (block == Blocks.END_PORTAL_FRAME || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY);
    }

    private void scanFromPos(BlockPos origin, Map<BlockPos, Block> out) {
        if (mc.level == null) {
            return;
        }
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos airPos : this.floodFillVein(origin)) {
            for (Direction dir : DIRECTIONS) {
                BlockPos neighbor = airPos.relative(dir);
                if (!this.isAirOrFluid(neighbor)) {
                    Block block = PlayerUtil.getBlock(neighbor);
                    if (this.isEnabledOreBlock(block)) {
                        this.analyzeAndAddVein(neighbor, block, out, visited);
                    }
                }
            }
        }
        int range = this.scanRangeSetting.getValue().intValue();
        int extendedRange = range + 40;
        this.scanRegion(extendedRange, out);
    }

    private Color getBlockColor(Block block) {
        if (block == null) {
            return null;
        }
        if (this.isEnabledOreBlock(block) || this.isSpecialBlock(block)) {
            if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
                return this.diamondColor;
            }
            if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
                return this.emeraldColor;
            }
            if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
                return this.redstoneColor;
            }
            if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
                return this.ironColor;
            }
            if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE || block == Blocks.NETHER_GOLD_ORE) {
                return this.goldColor;
            }
            if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) {
                return this.copperColor;
            }
            if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
                return this.lapisColor;
            }
            if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) {
                return this.coalColor;
            }
            if (block == Blocks.NETHER_QUARTZ_ORE) {
                return new Color(255, 255, 255);
            }
            if (block == Blocks.ANCIENT_DEBRIS) {
                return new Color(255, 33, 0);
            }
            if (block == Blocks.SPAWNER) {
                return new Color(0, 85, 255);
            }
            if (block == Blocks.END_PORTAL_FRAME) {
                return new Color(253, 0, 250);
            }
        }
        return null;
    }

    private boolean isEnabledOreBlock(Block block) {
        if (block == null) {
            return false;
        }
        String mode = this.xrayModeSetting.getValue();
        if (mode.equalsIgnoreCase("Ores")) {
            return this.diamondSetting.getValue() && (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE)
                    || this.emeraldSetting.getValue() && (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE)
                    || this.redstoneSetting.getValue() && (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE)
                    || this.ironSetting.getValue() && (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE)
                    || this.goldSetting.getValue() && (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE || block == Blocks.NETHER_GOLD_ORE)
                    || this.copperSetting.getValue() && (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE)
                    || this.lapisSetting.getValue() && (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE)
                    || this.coalSetting.getValue() && (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE);
        } else if (mode.equalsIgnoreCase("Valuables")) {
            return block == Blocks.ANCIENT_DEBRIS
                    || block == Blocks.NETHER_GOLD_ORE
                    || block == Blocks.NETHER_QUARTZ_ORE
                    || block == Blocks.OBSIDIAN
                    || block == Blocks.AMETHYST_BLOCK
                    || block == Blocks.BUDDING_AMETHYST;
        } else {
            return false;
        }
    }

    /**
     * True iff {@code block} is currently a target block under the active mode and per-ore toggles.
     * Used by {@link shit.zen.patch.BlockPatch} to decide which faces survive the see-through pass,
     * so the terrain hook respects exactly the same settings as the ESP overlay.
     */
    public boolean isXrayVisible(Block block) {
        return block != null && (this.isEnabledOreBlock(block) || this.isSpecialBlock(block));
    }

    /** Target set for the protocol probe: the same blocks the ESP layer is allowed to draw. */
    private boolean isProbeTarget(Block block) {
        return this.isXrayVisible(block);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (ZenClient.isReady()
                && (!this.foundBlocks.isEmpty() || !this.blindScanBlocks.isEmpty() || !this.probeOres.isEmpty())
                && this.tracersSetting.getValue()
                && this.modelviewMatrix != null
                && this.projectionMatrix != null) {
            this.renderTracers(event.poseStack());
        }
    }

    private void drawWireBox(PoseStack poseStack, AABB box, Color color) {
        float r = color.getRed() / 255.0F;
        float g = color.getGreen() / 255.0F;
        float b = color.getBlue() / 255.0F;
        float a = 0.8F;
        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;
        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        buffer.vertex(matrix, minX, minY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, minY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, minY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, minY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, minY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, minY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, minY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, minY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, minY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, maxY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, minY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, maxY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, minY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, maxY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, minY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, maxY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, maxY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, maxY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, maxY, minZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, maxY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, maxX, maxY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, maxY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, maxY, maxZ).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, minX, maxY, minZ).color(r, g, b, a).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }

    private void renderTracers(PoseStack poseStack) {
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float centerX = screenWidth / 2.0F;
        float centerY = screenHeight / 2.0F;
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        double rangeSq = Math.pow(this.scanRangeSetting.getValue().doubleValue(), 2.0);
        Vec3 eyePos = mc.player.getEyePosition(1.0F);
        for (Map.Entry<BlockPos, Block> entry : this.foundBlocks.entrySet()) {
            if (this.probeSetting.getValue() && this.probeOres.containsKey(entry.getKey())) {
                continue;
            }
            this.renderSingleTracer(entry.getKey(), entry.getValue().defaultBlockState(), eyePos, rangeSq, cameraPos,
                    buffer, poseStack, centerX, centerY, screenWidth, screenHeight, null);
        }
        if (this.blindScanSetting.getValue()) {
            double blindRangeSq = Math.pow(this.blindRenderRangeSetting.getValue().doubleValue(), 2.0);
            for (Map.Entry<BlockKey, BlockState> entry : this.blindScanBlocks.entrySet()) {
                if (!this.isEnabledOreBlock(entry.getValue().getBlock())) {
                    continue;
                }
                BlockPos pos = entry.getKey().toBlockPos();
                if (this.probeSetting.getValue() && this.probeOres.containsKey(pos)) {
                    continue;
                }
                this.renderSingleTracer(pos, entry.getValue(), eyePos, blindRangeSq, cameraPos,
                        buffer, poseStack, centerX, centerY, screenWidth, screenHeight, null);
            }
        }
        if (this.probeSetting.getValue()) {
            for (Map.Entry<BlockPos, BlockState> entry : this.probeOres.entrySet()) {
                this.renderSingleTracer(entry.getKey(), entry.getValue(), eyePos, rangeSq, cameraPos,
                        buffer, poseStack, centerX, centerY, screenWidth, screenHeight, PROBE_COLOR);
            }
        }
        tesselator.end();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private void renderSingleTracer(BlockPos pos, BlockState state, Vec3 eyePos, double rangeSq, Vec3 cameraPos,
                                    BufferBuilder buffer, PoseStack poseStack, float centerX, float centerY,
                                    float screenWidth, float screenHeight, Color overrideColor) {
        Vec3 center = Vec3.atCenterOf(pos);
        if (center.subtract(eyePos).lengthSqr() > rangeSq) {
            return;
        }
        Vector3f projected = this.projectPosition(center, cameraPos);
        if (projected == null) {
            return;
        }
        float screenX = projected.x;
        float screenY = projected.y;
        boolean onScreen = projected.z > 0.0F;
        Color color = overrideColor != null ? overrideColor : this.getBlockColor(state.getBlock());
        if (color == null) {
            return;
        }
        float r = color.getRed() / 255.0F;
        float g = color.getGreen() / 255.0F;
        float b = color.getBlue() / 255.0F;
        float a = 0.8F;
        if (!onScreen) {
            Vector3f clamped = this.clampToScreen(centerX, centerY, screenX, screenY, screenWidth, screenHeight);
            screenX = clamped.x;
            screenY = clamped.y;
        }
        buffer.vertex(poseStack.last().pose(), centerX, centerY, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(poseStack.last().pose(), screenX, screenY, 0.0F).color(r, g, b, a).endVertex();
    }

    private Vector3f projectPosition(Vec3 worldPos, Vec3 cameraPos) {
        if (this.modelviewMatrix == null || this.projectionMatrix == null) {
            return null;
        }
        Vector4f vec = new Vector4f(
                (float) (worldPos.x - cameraPos.x),
                (float) (worldPos.y - cameraPos.y),
                (float) (worldPos.z - cameraPos.z),
                1.0F);
        vec.mul(this.modelviewMatrix);
        vec.mul(this.projectionMatrix);
        if (vec.w() <= 0.0F) {
            return null;
        }
        vec.mul(1.0F / vec.w());
        float width = mc.getWindow().getGuiScaledWidth();
        float height = mc.getWindow().getGuiScaledHeight();
        float screenX = (vec.x() * 0.5F + 0.5F) * width;
        float screenY = (-vec.y() * 0.5F + 0.5F) * height;
        boolean onScreen = screenX >= 0.0F && screenX <= width && screenY >= 0.0F && screenY <= height;
        return new Vector3f(screenX, screenY, onScreen ? 1.0F : -1.0F);
    }

    private Vector3f clampToScreen(float centerX, float centerY, float targetX, float targetY, float screenWidth, float screenHeight) {
        float dx = targetX - centerX;
        float dy = targetY - centerY;
        if (Math.abs(dx) < 1.0E-6 && Math.abs(dy) < 1.0E-6) {
            return new Vector3f(centerX, centerY, 0.0F);
        }
        float t = Float.MAX_VALUE;
        if (dx > 0.0F) {
            t = Math.min(t, (screenWidth - centerX) / dx);
        }
        if (dx < 0.0F) {
            t = Math.min(t, -centerX / dx);
        }
        if (dy > 0.0F) {
            t = Math.min(t, (screenHeight - centerY) / dy);
        }
        if (dy < 0.0F) {
            t = Math.min(t, -centerY / dy);
        }
        return new Vector3f(centerX + t * dx, centerY + t * dy, 0.0F);
    }

    // ===== 自动挖矿：从已探测的矿物集合中选目标、转向、切换工具并挖掘 =====

    /**
     * 每 tick 驱动自动挖矿状态机。
     *
     * <p>目标只从 {@link #foundBlocks}、{@link #blindScanBlocks}、{@link #probeOres}
     * 这些“已经探测到”的集合里挑选，因此流程始终是“先探测、后挖掘”，
     * 不会凭空去挖视野里不存在的方块。</p>
     */
    private void tickAutoMine() {
        if (!this.autoMineSetting.getValue()) {
            // 关闭开关后立即停止挖掘，避免残留的破坏状态
            if (this.autoMineTarget != null || this.autoMineStarted) {
                this.resetAutoMine();
            }
            return;
        }
        if (mc.player == null || mc.level == null || mc.gameMode == null
                || mc.screen != null || mc.player.isDeadOrDying() || mc.player.isSpectator()) {
            this.resetAutoMine();
            return;
        }

        // 已有目标：先确认方块仍然存在、仍然是矿物，并且仍在挖掘距离内
        if (this.autoMineTarget != null) {
            BlockState state = mc.level.getBlockState(this.autoMineTarget);
            if (!this.isAutoMineBlock(state.getBlock())) {
                // 方块已被挖掉或被替换：短暂拉黑，避免立即再次选中同一个位置
                this.finishAutoMineTarget(true);
                return;
            }
            if (!this.isWithinAutoMineRange(this.autoMineTarget)) {
                // 玩家走远了：直接放弃，不拉黑，走回来还能继续挖
                this.finishAutoMineTarget(false);
                return;
            }
            this.mineAutoMineTarget();
            return;
        }

        // 没有目标：等待挖矿间隔结束后，再从探测结果里挑一个
        if (System.currentTimeMillis() < this.autoMineNextTime) {
            return;
        }
        BlockPos target = this.selectAutoMineTarget();
        if (target == null) {
            // 探测集合里暂时没有目标时，低频扫描玩家周围一小圈，避免刚开启时一直站着不动；
            // “仅挖已确认矿物”模式下不能用客户端视角兜底，否则会把假矿当成目标。
            boolean confirmedOnly = this.autoMineConfirmedSetting.getValue() && this.probeSetting.getValue();
            if (!confirmedOnly) {
                long now = System.currentTimeMillis();
                if (now >= this.autoMineLocalScanTime) {
                    this.autoMineLocalScanTime = now + AUTO_MINE_LOCAL_SCAN_INTERVAL_MS;
                    target = this.scanNearbyAutoMineTarget();
                }
            }
            if (target == null) {
                return;
            }
        }
        this.autoMineTarget = target;
        this.autoMineStarted = false;
        this.autoMineTicks = 0;
        this.autoMineFace = this.faceTowardPlayer(target);
        if (this.autoMineToolSetting.getValue()) {
            this.switchToBestAutoMineTool(mc.level.getBlockState(target));
        }
        this.mineAutoMineTarget();
    }

    /**
     * 从三套探测结果中选出得分最高的矿物目标。
     * 距离筛选放在最前面，只有进入范围的少量候选才会创建临时对象。
     */
    private BlockPos selectAutoMineTarget() {
        // 黑名单过大时顺手清理过期项，避免长时间运行后无限增长
        if (this.autoMineBlacklist.size() >= 128) {
            long now = System.currentTimeMillis();
            this.autoMineBlacklist.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        Vec3 eye = mc.player.getEyePosition();
        double rangeSq = Math.pow(this.autoMineRangeSetting.getValue().doubleValue(), 2.0);
        boolean valuable = this.autoMinePrioritySetting.is("Valuable");
        boolean confirmedOnly = this.autoMineConfirmedSetting.getValue() && this.probeSetting.getValue();
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        // 普通扫描与盲区扫描的结果；确认模式下只看协议探针的结果
        if (!confirmedOnly) {
            for (Map.Entry<BlockPos, Block> entry : this.foundBlocks.entrySet()) {
                BlockPos pos = entry.getKey();
                double score = this.autoMineScore(pos.getX(), pos.getY(), pos.getZ(), entry.getValue(), eye, rangeSq, valuable);
                if (score > bestScore) {
                    bestScore = score;
                    best = pos;
                }
            }
            for (Map.Entry<BlockKey, BlockState> entry : this.blindScanBlocks.entrySet()) {
                BlockKey key = entry.getKey();
                double score = this.autoMineScore(key.x(), key.y(), key.z(), entry.getValue().getBlock(), eye, rangeSq, valuable);
                if (score > bestScore) {
                    bestScore = score;
                    best = key.toBlockPos();
                }
            }
        }
        // 协议探针确认过的真实矿物始终参与竞争（确认模式下则是唯一来源）
        for (Map.Entry<BlockPos, BlockState> entry : this.probeOres.entrySet()) {
            BlockPos pos = entry.getKey();
            double score = this.autoMineScore(pos.getX(), pos.getY(), pos.getZ(), entry.getValue().getBlock(), eye, rangeSq, valuable);
            if (score > bestScore) {
                bestScore = score;
                best = pos;
            }
        }
        return best;
    }

    /**
     * 兜底探测：直接扫描玩家周围一个立方体范围内的方块。
     * 只在主探测集合没有目标时低频调用，保证刚开启模块或换区域时也能立即开工。
     */
    private BlockPos scanNearbyAutoMineTarget() {
        Vec3 eye = mc.player.getEyePosition();
        double range = this.autoMineRangeSetting.getValue().doubleValue();
        double rangeSq = range * range;
        int radius = Mth.ceil(range);
        BlockPos origin = mc.player.blockPosition();
        boolean valuable = this.autoMinePrioritySetting.is("Valuable");
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.getX() - radius, origin.getY() - radius, origin.getZ() - radius,
                origin.getX() + radius, origin.getY() + radius, origin.getZ() + radius)) {
            Block block = mc.level.getBlockState(pos).getBlock();
            double score = this.autoMineScore(pos.getX(), pos.getY(), pos.getZ(), block, eye, rangeSq, valuable);
            if (score > bestScore) {
                bestScore = score;
                // betweenClosed 返回的是可变坐标，选中后必须复制一份
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 计算单个候选的得分；无效/超距/黑名单中的方块返回负无穷。 */
    private double autoMineScore(int x, int y, int z, Block block, Vec3 eye, double rangeSq, boolean valuable) {
        if (!this.isAutoMineBlock(block)) {
            return Double.NEGATIVE_INFINITY;
        }
        // 零分配的距离筛选：只有进入范围的候选才需要创建 BlockPos
        double dx = (double) x + 0.5 - eye.x;
        double dy = (double) y + 0.5 - eye.y;
        double dz = (double) z + 0.5 - eye.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > rangeSq) {
            return Double.NEGATIVE_INFINITY;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (this.isAutoMineBlacklisted(pos)) {
            return Double.NEGATIVE_INFINITY;
        }
        if (this.autoMineExposedSetting.getValue() && !this.isExposedToAir(pos)) {
            return Double.NEGATIVE_INFINITY;
        }
        // 最近优先直接用负距离；价值优先让价值差压过距离差（1 级价值 ≈ 1000 格²）
        return valuable ? this.autoMineValue(block) * 1000.0 - distSq : -distSq;
    }

    /** 自动挖矿只处理当前模式/矿物开关允许的矿物，刷怪笼、传送门等特殊方块不挖。 */
    private boolean isAutoMineBlock(Block block) {
        return this.isEnabledOreBlock(block);
    }

    /** 矿物价值等级，仅用于“价值优先”排序。 */
    private int autoMineValue(Block block) {
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
            return 100;
        }
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            return 95;
        }
        if (block == Blocks.ANCIENT_DEBRIS) {
            return 90;
        }
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE || block == Blocks.NETHER_GOLD_ORE) {
            return 70;
        }
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            return 60;
        }
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            return 55;
        }
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
            return 50;
        }
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) {
            return 40;
        }
        if (block == Blocks.NETHER_QUARTZ_ORE) {
            return 35;
        }
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) {
            return 30;
        }
        return 10;
    }

    /** 方块是否至少有一面暴露在空气/液体中。 */
    private boolean isExposedToAir(BlockPos pos) {
        for (Direction direction : DIRECTIONS) {
            if (this.isAirOrFluid(pos.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAutoMineBlacklisted(BlockPos pos) {
        Long expireAt = this.autoMineBlacklist.get(pos);
        return expireAt != null && expireAt > System.currentTimeMillis();
    }

    /** 眼睛到方块中心的距离是否仍在自动挖矿范围内。 */
    private boolean isWithinAutoMineRange(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = (double) pos.getX() + 0.5 - eye.x;
        double dy = (double) pos.getY() + 0.5 - eye.y;
        double dz = (double) pos.getZ() + 0.5 - eye.z;
        double range = this.autoMineRangeSetting.getValue().doubleValue();
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    /** 选择离玩家眼睛最近的那个方块面，作为挖掘包里的方向参数。 */
    private Direction faceTowardPlayer(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        return Direction.getNearest(
                eye.x - ((double) pos.getX() + 0.5),
                eye.y - ((double) pos.getY() + 0.5),
                eye.z - ((double) pos.getZ() + 0.5));
    }

    /**
     * 在快捷栏里挑选对目标方块破坏速度最快的工具。
     * 剑不适合挖矿，直接跳过；效率附魔按原版公式折算成额外速度。
     */
    private void switchToBestAutoMineTool(BlockState state) {
        if (state == null || state.isAir()) {
            return;
        }
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty() || stack.getItem() instanceof SwordItem) {
                continue;
            }
            float speed = stack.getItem().getDestroySpeed(stack, state);
            int efficiency = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, stack);
            if (efficiency > 0) {
                speed += (float) (efficiency * efficiency + 1);
            }
            if (speed > bestSpeed) {
                bestSlot = slot;
                bestSpeed = speed;
            }
        }
        if (bestSlot != -1 && mc.player.getInventory().selected != bestSlot) {
            mc.player.getInventory().selected = bestSlot;
            // 立即把主手物品同步给服务端，避免挖掘包与手持物品不一致
            PlayerUtil.sendCarriedItem();
        }
    }

    /** 平滑转向目标并推进挖掘。 */
    private void mineAutoMineTarget() {
        BlockPos pos = this.autoMineTarget;
        if (pos == null) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        Rotation rotation = RotationUtil.exactRotation(eye, Vec3.atCenterOf(pos));
        float yawDelta = Mth.wrapDegrees(rotation.getYaw() - mc.player.getYRot());
        float pitchDelta = rotation.getPitch() - mc.player.getXRot();

        // 每 tick 最多转动固定角度，避免瞬间甩头；朝向会在移动包里同步给服务端
        mc.player.setYRot(mc.player.getYRot() + Mth.clamp(yawDelta, -AUTO_MINE_ROTATE_STEP, AUTO_MINE_ROTATE_STEP));
        mc.player.setXRot(Mth.clamp(
                mc.player.getXRot() + Mth.clamp(pitchDelta, -AUTO_MINE_ROTATE_STEP, AUTO_MINE_ROTATE_STEP),
                -90.0f, 90.0f));

        boolean aimed = Math.abs(yawDelta) <= AUTO_MINE_AIM_TOLERANCE
                && Math.abs(pitchDelta) <= AUTO_MINE_AIM_TOLERANCE;
        if (!aimed) {
            // 还没对准：已经开挖的目标继续维持进度，否则只转向
            if (this.autoMineStarted) {
                mc.gameMode.continueDestroyBlock(pos, this.autoMineFace);
            }
            return;
        }

        if (!this.autoMineStarted) {
            this.autoMineFace = this.faceTowardPlayer(pos);
            this.autoMineStarted = mc.gameMode.startDestroyBlock(pos, this.autoMineFace);
            if (!this.autoMineStarted) {
                // 例如领地保护、冒险模式限制：拉黑一段时间，换下一个目标
                this.finishAutoMineTarget(true);
                return;
            }
        } else {
            mc.gameMode.continueDestroyBlock(pos, this.autoMineFace);
        }

        // 超时保护：被保护或无法破坏的方块不能永远占着目标
        int timeoutTicks = this.autoMineTimeoutSetting.getValue().intValue() * 20;
        if (++this.autoMineTicks > timeoutTicks) {
            this.finishAutoMineTarget(true);
        }
    }

    /** 结束当前目标；blacklist 为 true 时把方块放入临时黑名单。 */
    private void finishAutoMineTarget(boolean blacklist) {
        if (this.autoMineTarget != null && blacklist) {
            this.autoMineBlacklist.put(this.autoMineTarget.immutable(), System.currentTimeMillis() + AUTO_MINE_BLACKLIST_MS);
        }
        if (this.autoMineStarted && mc.gameMode != null && mc.gameMode.isDestroying()) {
            mc.gameMode.stopDestroyBlock();
        }
        this.autoMineTarget = null;
        this.autoMineStarted = false;
        this.autoMineTicks = 0;
        this.autoMineNextTime = System.currentTimeMillis() + this.autoMineDelaySetting.getValue().longValue();
    }

    /** 立即停止自动挖矿并清空目标（关闭模块、切换世界、打开界面时调用）。 */
    private void resetAutoMine() {
        if (this.autoMineStarted && mc.gameMode != null && mc.gameMode.isDestroying()) {
            mc.gameMode.stopDestroyBlock();
        }
        this.autoMineTarget = null;
        this.autoMineStarted = false;
        this.autoMineTicks = 0;
        this.autoMineNextTime = 0L;
        this.autoMineLocalScanTime = 0L;
    }

    // ===== Protocol probe: make the server reveal real blocks, then trust only those =====

    private static List<Vec3i> buildProbeBall(int radius) {
        List<Vec3i> ball = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(-radius, -radius, -radius, radius, radius, radius)) {
            int dist = Math.abs(pos.getX()) + Math.abs(pos.getY()) + Math.abs(pos.getZ());
            if (dist >= 1 && dist <= radius) {
                ball.add(pos.immutable());
            }
        }
        return List.copyOf(ball);
    }

    private static int probeTicks(long millis) {
        return (int) Math.max(1L, millis / 50L);
    }

    private List<Vec3i> probeBall() {
        return this.probeRevealRadius == 1 ? PROBE_BALL_1 : PROBE_BALL_2;
    }

    /**
     * Sparse lattice covering every block with a probed ball. Radius 2 uses
     * {@code x + 3y + 8z ≡ 0 (mod 16)}; radius 1 uses {@code x + 2y + 3z ≡ 0 (mod 7)}.
     */
    private boolean isProbeLatticePoint(BlockPos pos) {
        if (this.probeRevealRadius == 1) {
            return Math.floorMod(pos.getX() + 2 * pos.getY() + 3 * pos.getZ(), 7) == 0;
        }
        return Math.floorMod(pos.getX() + 3 * pos.getY() + 8 * pos.getZ(), 16) == 0;
    }

    /** Same reach test the server applies: eye to block centre within 4.5 + 1.0 blocks. */
    private boolean isProbeReachable(BlockPos pos) {
        return mc.player != null
                && mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= PROBE_REACH * PROBE_REACH;
    }

    private void tickProbe() {
        if (!this.probeSetting.getValue()) {
            if (this.probeLevel != null) {
                this.probeLevel = null;
                this.clearProbeState();
            }
            return;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (this.probeLevel != mc.level) {
            this.probeLevel = mc.level;
            this.clearProbeState();
        }
        if (this.probeCooldownTicks > 0) {
            this.probeCooldownTicks--;
            return;
        }
        if (!this.probeQueue.isEmpty()) {
            this.sendProbeBatch();
            return;
        }
        if (this.probeAwaitingReply) {
            this.probeAwaitingReply = false;
            this.probeCooldownTicks = probeTicks(this.probeIntervalSetting.getValue().longValue());
            return;
        }
        // Never fight real mining input, including the built-in auto-mine state machine.
        if (mc.options.keyAttack.isDown() || (this.autoMineSetting.getValue() && this.autoMineTarget != null)) {
            return;
        }
        this.detectProbeRadius();
        List<BlockPos> targets = this.planProbeTargets();
        this.probeQueue.addAll(targets);
        this.probeRoundTargets.clear();
        this.probeRoundTargets.addAll(targets);
        this.probeHitDist1 = 0;
        this.probeHitDist2 = 0;
        this.probeAwaitingReply = true;
    }

    private void sendProbeBatch() {
        int batch = this.probeBatchSetting.getValue().intValue();
        for (int i = 0; i < batch && !this.probeQueue.isEmpty(); i++) {
            PacketUtil.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, this.probeQueue.poll(), Direction.UP));
        }
        this.probeCooldownTicks = this.probeQueue.isEmpty()
                ? PROBE_REPLY_WAIT_TICKS
                : probeTicks(PROBE_BATCH_SLEEP_MS);
    }

    /** Enough replies in one round pin down whether the server updates 24 blocks or only 6. */
    private void detectProbeRadius() {
        if (this.probeRadiusConfirmed) {
            return;
        }
        if (this.probeHitDist2 > 0) {
            this.probeRadiusConfirmed = true;
            return;
        }
        if (this.probeHitDist1 < PROBE_RADIUS1_EVIDENCE) {
            return;
        }
        this.probeRadiusConfirmed = true;
        this.probeRevealRadius = 1;
    }

    /**
     * Reachable lattice points cover the whole area with one packet per 16 blocks; the greedy pass
     * then fills the rim where the lattice point itself is out of reach.
     */
    private List<BlockPos> planProbeTargets() {
        int radius = this.probeRadiusSetting.getValue().intValue();
        BlockPos origin = mc.player.blockPosition();
        List<BlockPos> reachable = new ArrayList<>();
        Set<BlockPos> uncovered = new HashSet<>();
        for (BlockPos cursor : BlockPos.betweenClosed(
                origin.offset(-radius, -radius, -radius), origin.offset(radius, radius, radius))) {
            if (!this.isProbeReachable(cursor)) {
                continue;
            }
            BlockPos pos = cursor.immutable();
            reachable.add(pos);
            if (mc.level.getBlockState(pos).isAir() || this.probeRevealed.contains(pos)) {
                continue;
            }
            if (this.probeAttempts.getOrDefault(pos, 0) < PROBE_MAX_ATTEMPTS) {
                uncovered.add(pos);
            }
        }
        if (uncovered.isEmpty()) {
            return List.of();
        }
        Set<BlockPos> reachableSet = new HashSet<>(reachable);
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : reachable) {
            if (!this.isProbeLatticePoint(pos) || this.probeRevealGain(pos, uncovered) == 0) {
                continue;
            }
            targets.add(pos);
            this.markProbeCovered(uncovered, pos);
        }
        for (BlockPos pos : new ArrayList<>(uncovered)) {
            if (!uncovered.contains(pos)) {
                continue;
            }
            BlockPos best = this.bestProbeSenderFor(pos, reachableSet, uncovered);
            if (best == null) {
                continue;
            }
            targets.add(best);
            this.markProbeCovered(uncovered, best);
        }
        return targets;
    }

    /** Pick the reachable sender whose ball uncovers the most still-uncovered blocks. */
    private BlockPos bestProbeSenderFor(BlockPos target, Set<BlockPos> reachableSet, Set<BlockPos> uncovered) {
        BlockPos best = null;
        int bestGain = 0;
        for (Vec3i offset : this.probeBall()) {
            BlockPos sender = target.offset(offset);
            if (!reachableSet.contains(sender)) {
                continue;
            }
            int gain = this.probeRevealGain(sender, uncovered);
            if (gain <= bestGain) {
                continue;
            }
            best = sender;
            bestGain = gain;
        }
        return best;
    }

    private int probeRevealGain(BlockPos sender, Set<BlockPos> uncovered) {
        int gain = 0;
        for (Vec3i offset : this.probeBall()) {
            if (uncovered.contains(sender.offset(offset))) {
                gain++;
            }
        }
        return gain;
    }

    private void markProbeCovered(Set<BlockPos> uncovered, BlockPos sender) {
        for (Vec3i offset : this.probeBall()) {
            BlockPos pos = sender.offset(offset);
            uncovered.remove(pos);
            this.probeAttempts.merge(pos, 1, Integer::sum);
        }
    }

    private void handleProbePacket(Packet<?> packet) {
        if (!this.probeSetting.getValue()) {
            return;
        }
        if (packet instanceof ClientboundBlockUpdatePacket blockUpdate) {
            this.onProbeServerBlock(blockUpdate.getPos(), blockUpdate.getBlockState());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionUpdate) {
            sectionUpdate.runUpdates(this::onProbeServerBlock);
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forgetChunk) {
            this.forgetProbeChunk(forgetChunk.getX(), forgetChunk.getZ());
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
            this.forgetProbeChunk(chunkPacket.getX(), chunkPacket.getZ());
        }
    }

    /**
     * Anti-xray only disguises chunk packets, so every single-block/section update is authoritative.
     * Positions the server never answered stay untrusted and are not displayed.
     */
    private void onProbeServerBlock(BlockPos rawPos, BlockState state) {
        BlockPos pos = rawPos.immutable();
        this.probeRevealed.add(pos);
        if (this.isProbeTarget(state.getBlock())) {
            this.probeOres.put(pos, state);
        } else {
            this.probeOres.remove(pos);
        }
        if (this.probeRadiusConfirmed || this.probeRoundTargets.isEmpty()) {
            return;
        }
        for (Vec3i offset : PROBE_BALL_2) {
            if (!this.probeRoundTargets.contains(pos.subtract(offset))) {
                continue;
            }
            int dist = Math.abs(offset.getX()) + Math.abs(offset.getY()) + Math.abs(offset.getZ());
            if (dist == 1) {
                this.probeHitDist1++;
            } else {
                this.probeHitDist2++;
            }
        }
    }

    /** A chunk was (re)sent or dropped: its blocks are disguised again, so forget what we learned. */
    private void forgetProbeChunk(int chunkX, int chunkZ) {
        this.probeOres.keySet().removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
        this.probeRevealed.removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
        this.probeAttempts.keySet().removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
        this.probeRoundTargets.removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
    }

    private void clearProbeState() {
        this.probeOres.clear();
        this.probeRevealed.clear();
        this.probeAttempts.clear();
        this.probeRoundTargets.clear();
        this.probeQueue.clear();
        this.probeAwaitingReply = false;
        this.probeCooldownTicks = 0;
        this.probeHitDist1 = 0;
        this.probeHitDist2 = 0;
    }

    private Set<BlockPos> floodFillVein(BlockPos origin) {
        Set<BlockPos> visited = new HashSet<>();
        LinkedList<BlockPos> queue = new LinkedList<>();
        int maxBlocks = this.maxBlocksSetting.getValue().intValue();
        if (this.isAirOrFluid(origin) && visited.add(origin)) {
            queue.add(origin);
        }
        BlockPos above = origin.above();
        if (this.isAirOrFluid(above) && visited.add(above)) {
            queue.add(above);
        }
        if (queue.isEmpty()) {
            for (Direction dir : DIRECTIONS) {
                BlockPos neighbor = origin.relative(dir);
                if (this.isAirOrFluid(neighbor) && visited.add(neighbor)) {
                    queue.add(neighbor);
                    break;
                }
            }
        }
        while (!queue.isEmpty() && visited.size() < maxBlocks) {
            BlockPos current = queue.poll();
            for (Direction dir : DIRECTIONS) {
                BlockPos neighbor = current.relative(dir);
                if (this.isAirOrFluid(neighbor) && visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        if (this.altStartSetting.getValue() && visited.size() < this.minAirBlocksSetting.getValue().intValue()) {
            BlockPos altStart = this.findAlternativeStart(origin);
            if (altStart != null) {
                return this.collectConnectedOre(altStart, maxBlocks);
            }
        }
        return visited;
    }

    private BlockPos findAlternativeStart(BlockPos origin) {
        int radius = this.altSearchRadiusSetting.getValue().intValue();
        int minAir = this.minAirBlocksSetting.getValue().intValue();
        BlockPos best = null;
        int bestCount = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!pos.equals(origin) && this.isAirOrFluid(pos)) {
                        int count = this.countConnectedOre(pos, minAir);
                        if (count > bestCount) {
                            bestCount = count;
                            best = pos;
                            if (count >= minAir) {
                                break;
                            }
                        }
                    }
                }
            }
        }
        return bestCount >= minAir ? best : null;
    }

    private int countConnectedOre(BlockPos origin, int limit) {
        Set<BlockPos> visited = new HashSet<>();
        LinkedList<BlockPos> queue = new LinkedList<>();
        if (this.isAirOrFluid(origin)) {
            queue.add(origin);
            visited.add(origin);
        }
        int count = 0;
        while (!queue.isEmpty() && count < limit) {
            BlockPos current = queue.poll();
            count++;
            for (Direction dir : DIRECTIONS) {
                BlockPos neighbor = current.relative(dir);
                if (!visited.contains(neighbor) && this.isAirOrFluid(neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return count;
    }

    private Set<BlockPos> collectConnectedOre(BlockPos origin, int limit) {
        Set<BlockPos> visited = new HashSet<>();
        LinkedList<BlockPos> queue = new LinkedList<>();
        if (this.isAirOrFluid(origin)) {
            queue.add(origin);
            visited.add(origin);
        }
        while (!queue.isEmpty() && visited.size() < limit) {
            BlockPos current = queue.poll();
            for (Direction dir : DIRECTIONS) {
                BlockPos neighbor = current.relative(dir);
                if (this.isAirOrFluid(neighbor) && visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return visited;
    }

    private void analyzeAndAddVein(BlockPos start, Block ore, Map<BlockPos, Block> out, Set<BlockPos> visited) {
        if (visited.contains(start)) {
            return;
        }
        List<BlockPos> vein = this.collectVeinBlocks(start, ore, visited);
        if (vein.isEmpty()) {
            return;
        }
        visited.addAll(vein);
        if (this.antiFakeOreSetting.getValue()) {
            OreScore score = this.calcOreScore(vein, ore);
            if (score.isAuthentic(this.minAuthScoreSetting.getValue().doubleValue())) {
                for (BlockPos pos : vein) {
                    out.put(pos, ore);
                }
            }
        } else {
            for (BlockPos pos : vein) {
                out.put(pos, ore);
            }
        }
    }

    private List<BlockPos> collectVeinBlocks(BlockPos start, Block ore, Set<BlockPos> globalVisited) {
        List<BlockPos> vein = new ArrayList<>();
        LinkedList<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> localVisited = new HashSet<>();
        if (globalVisited.contains(start)) {
            return vein;
        }
        queue.add(start);
        localVisited.add(start);
        int maxVeinSize = 64;
        while (!queue.isEmpty() && vein.size() < maxVeinSize) {
            BlockPos current = queue.poll();
            vein.add(current);
            for (Direction dir : DIRECTIONS) {
                BlockPos neighbor = current.relative(dir);
                if (!globalVisited.contains(neighbor) && !localVisited.contains(neighbor)
                        && mc.level.isLoaded(neighbor) && this.isSameOreFamily(PlayerUtil.getBlock(neighbor), ore)) {
                    localVisited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return vein;
    }

    private OreScore calcOreScore(List<BlockPos> vein, Block ore) {
        if (!ZenClient.isReady() || vein.isEmpty()) {
            return new OreScore();
        }
        OreScore score = new OreScore();
        BlockPos first = vein.get(0);
        int size = vein.size();
        score.veinSizeScore = this.calcVeinSizeScore(size);
        if (size < this.minVeinSizeSetting.getValue().intValue()) {
            score.veinSizeScore = -20.0;
            return score;
        }
        score.chunkStateScore = this.calcChunkStateScore(first, ore);
        score.depthScore = this.calcDepthScore(first, ore);
        if (score.depthScore <= -10.0) {
            return score;
        }
        if (this.strictHostRockSetting.getValue()) {
            double hostSum = 0.0;
            int airExposureCount = 0;
            for (BlockPos pos : vein) {
                hostSum += this.calcHostRockScore(pos, ore);
                for (Direction dir : DIRECTIONS) {
                    if (this.isAirOrFluid(pos.relative(dir))) {
                        airExposureCount++;
                    }
                }
            }
            score.hostRockScore = hostSum / size;
            double avgAirExposure = (double) airExposureCount / size;
            if (avgAirExposure >= 3.0) {
                score.airExposureScore = 3.0;
            } else if (avgAirExposure >= 1.0) {
                score.airExposureScore = 1.5;
            } else if (avgAirExposure > 0.0) {
                score.airExposureScore = 0.5;
            } else {
                score.airExposureScore = 0.0;
            }
        }
        if (this.patternAnalysisSetting.getValue()) {
            score.patternScore = this.calcPatternScore(first, 8);
        }
        return score;
    }

    private double calcHostRockScore(BlockPos pos, Block ore) {
        Set<Block> hostBlocks = this.getHostBlockSet(ore);
        if (hostBlocks.isEmpty()) {
            return 1.0;
        }
        int hostCount = 0;
        int foreignCount = 0;
        int airCount = 0;
        for (Direction dir : DIRECTIONS) {
            BlockPos neighbor = pos.relative(dir);
            Block block = PlayerUtil.getBlock(neighbor);
            if (hostBlocks.contains(block)) {
                hostCount++;
            } else if (this.isAirOrFluid(neighbor)) {
                airCount++;
            } else if (!this.isSameOreFamily(block, ore) && !this.isOreBlock(block) && !this.isWoodOrManMade(block)) {
                foreignCount++;
            }
        }
        if (hostCount == 0 && foreignCount >= 4 && airCount <= 1) {
            return -5.0;
        } else if (airCount < 2) {
            double s = 0.0;
            if (hostCount >= 4) {
                s += 3.0;
            } else if (hostCount >= 2) {
                s += 1.0;
            } else if (hostCount == 1) {
                s += 0.5;
            }
            return s;
        } else if (hostCount >= 1) {
            return 2.0;
        } else {
            return foreignCount <= 2 ? 0.5 : -1.0;
        }
    }

    private boolean isWoodOrManMade(Block block) {
        if (block == null) {
            return false;
        }
        String path = block.builtInRegistryHolder().key().location().getPath();
        return path.contains("_planks")
                || path.contains("_log")
                || path.contains("_fence")
                || path.contains("_stairs")
                || path.contains("rail")
                || path.equals("torch")
                || path.equals("wall_torch")
                || path.equals("cobweb")
                || path.equals("ladder")
                || path.equals("chest")
                || path.equals("trapped_chest");
    }

    private Set<Block> getHostBlockSet(Block ore) {
        String path = ore.builtInRegistryHolder().key().location().getPath();
        if (path.startsWith("nether_") || path.equals("ancient_debris")) {
            return NETHER_HOST_BLOCKS;
        }
        return path.contains("_ore") ? OVERWORLD_HOST_BLOCKS : Collections.emptySet();
    }

    private double calcVeinSizeScore(int size) {
        if (size == 1) {
            return 2.0;
        } else if (size >= 2 && size <= 4) {
            return 2.5;
        } else {
            return size >= 5 && size <= 10 ? 3.5 : 3.0;
        }
    }

    private boolean isAirOrFluid(BlockPos pos) {
        if (mc.level == null) {
            return false;
        }
        Block block = PlayerUtil.getBlock(pos);
        return block.defaultBlockState().isAir() || block == Blocks.WATER || block == Blocks.LAVA
                || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR;
    }

    private double calcChunkStateScore(BlockPos pos, Block ore) {
        if (!ZenClient.isReady()) {
            return 0.0;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        Set<Block> family = this.getOreFamilySet(ore);
        if (family.isEmpty()) {
            return 0.0;
        }
        Block familyKey = family.stream()
                .min(Comparator.comparing(candidate -> candidate.builtInRegistryHolder().key().location().toString()))
                .orElse(ore);
        String cacheKey = chunkX + ":" + chunkZ + ":" + familyKey.builtInRegistryHolder().key().location();
        ChunkState cached = this.chunkStateCache.get(cacheKey);
        if (cached == ChunkState.SUSPICIOUS) {
            return -5.0;
        } else if (cached == ChunkState.AUTHENTIC) {
            return 1.0;
        }
        BlockPos playerPos = mc.player.blockPosition();
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;
        int chunkDistance = Math.abs(playerChunkX - chunkX) + Math.abs(playerChunkZ - chunkZ);
        if (chunkDistance <= 3) {
            this.chunkStateCache.put(cacheKey, ChunkState.AUTHENTIC);
            return 1.0;
        }
        this.chunkStateCache.put(cacheKey, ChunkState.SCANNING);
        short threshold;
        if (family.contains(Blocks.DIAMOND_ORE) || family.contains(Blocks.EMERALD_ORE)) {
            threshold = 30;
        } else if (!family.contains(Blocks.GOLD_ORE) && !family.contains(Blocks.LAPIS_ORE)) {
            threshold = 200;
        } else {
            threshold = 50;
        }
        int oreCount = 0;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = mc.level.getMinBuildHeight(); y < mc.level.getMaxBuildHeight(); y++) {
                    if (this.isSameOreFamily(mc.level.getBlockState(new BlockPos(baseX + x, y, baseZ + z)).getBlock(), ore)) {
                        oreCount++;
                    }
                }
            }
        }
        if (oreCount > threshold) {
            this.chunkStateCache.put(cacheKey, ChunkState.SUSPICIOUS);
            return -5.0;
        } else {
            this.chunkStateCache.put(cacheKey, ChunkState.AUTHENTIC);
            return 0.0;
        }
    }

    private double calcPatternScore(BlockPos center, int radius) {
        Map<Block, List<BlockPos>> oresByFamily = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (mc.level.isLoaded(pos)) {
                        Block block = PlayerUtil.getBlock(pos);
                        if (this.isOreBlock(block)) {
                            this.getOreFamilySet(block).stream().findFirst().ifPresent(
                                    familyKey -> oresByFamily.computeIfAbsent(familyKey, k -> new ArrayList<>()).add(pos));
                        }
                    }
                }
            }
        }
        for (List<BlockPos> group : oresByFamily.values()) {
            if (group.size() >= 2 && !this.isVeinConnected(group) && this.isPatternSuspicious(group)) {
                return -10.0;
            }
        }
        return 0.0;
    }

    private boolean isVeinConnected(List<BlockPos> blocks) {
        if (blocks.size() <= 1) {
            return true;
        }
        Set<BlockPos> all = new HashSet<>(blocks);
        Set<BlockPos> visited = new HashSet<>();
        LinkedList<BlockPos> queue = new LinkedList<>();
        BlockPos start = blocks.get(0);
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (all.contains(neighbor) && !visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return visited.size() == blocks.size();
    }

    private double calcDepthScore(BlockPos pos, Block ore) {
        int y = pos.getY();
        Set<Block> family = this.getOreFamilySet(ore);
        if (family.contains(Blocks.DIAMOND_ORE)) {
            if (y >= -64 && y <= 16) {
                return 2.0;
            }
            return y > 16 && y <= 32 ? 0.5 : -10.0;
        } else if (family.contains(Blocks.EMERALD_ORE)) {
            return y >= -16 && y <= 320 ? 2.0 : -10.0;
        } else if (family.contains(Blocks.GOLD_ORE)) {
            if (y >= -64 && y <= 32) {
                return 2.0;
            }
            return y > 32 && y < 80 ? -1.5 : -5.0;
        } else if (!family.contains(Blocks.IRON_ORE)) {
            if (family.contains(Blocks.COAL_ORE)) {
                return y >= 0 && y <= 256 ? 1.0 : 0.0;
            } else if (family.contains(Blocks.COPPER_ORE)) {
                return y >= -16 && y <= 112 ? 1.5 : 0.5;
            } else if (family.contains(Blocks.LAPIS_ORE)) {
                return y >= -64 && y <= 64 ? 1.5 : 0.0;
            } else if (family.contains(Blocks.REDSTONE_ORE)) {
                return y >= -64 && y <= 16 ? 1.5 : 0.0;
            } else if (family.contains(Blocks.ANCIENT_DEBRIS)) {
                if (y >= 8 && y <= 22) {
                    return 2.0;
                }
                return y < 120 ? 1.0 : -15.0;
            } else {
                return 0.5;
            }
        } else {
            return (y < -24 || y > 72) && (y <= 80 || y > 320) ? 1.0 : 1.5;
        }
    }

    private boolean isOreBlock(Block block) {
        if (block == null) {
            return false;
        }
        return block.builtInRegistryHolder().key().location().getPath().contains("_ore");
    }

    private boolean isPatternSuspicious(List<BlockPos> blocks) {
        if (blocks.size() < 4) {
            return false;
        }
        Map<Integer, Integer> xGaps = new HashMap<>();
        Map<Integer, Integer> yGaps = new HashMap<>();
        Map<Integer, Integer> zGaps = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            for (int j = i + 1; j < blocks.size(); j++) {
                BlockPos a = blocks.get(i);
                BlockPos b = blocks.get(j);
                int dx = Math.abs(a.getX() - b.getX());
                int dy = Math.abs(a.getY() - b.getY());
                int dz = Math.abs(a.getZ() - b.getZ());
                if (dx > 1) {
                    xGaps.put(dx, xGaps.getOrDefault(dx, 0) + 1);
                }
                if (dy > 1) {
                    yGaps.put(dy, yGaps.getOrDefault(dy, 0) + 1);
                }
                if (dz > 1) {
                    zGaps.put(dz, zGaps.getOrDefault(dz, 0) + 1);
                }
            }
        }
        int limit = blocks.size() > 5 ? 3 : 2;
        return xGaps.values().stream().anyMatch(count -> count > limit)
                || yGaps.values().stream().anyMatch(count -> count > limit)
                || zGaps.values().stream().anyMatch(count -> count > limit);
    }

    private boolean isSameOreFamily(Block a, Block b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        Set<Block> familyA = this.getOreFamilySet(a);
        Set<Block> familyB = this.getOreFamilySet(b);
        return !familyA.isEmpty() && familyA.equals(familyB);
    }

    private Set<Block> getOreFamilySet(Block block) {
        if (block == null) {
            return Collections.emptySet();
        } else if (Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE).contains(block)) {
            return Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
        } else if (Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE).contains(block)) {
            return Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
        } else if (Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE).contains(block)) {
            return Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE);
        } else if (Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE).contains(block)) {
            return Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
        } else if (Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE).contains(block)) {
            return Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        } else if (Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE).contains(block)) {
            return Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
        } else if (Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE).contains(block)) {
            return Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        } else if (Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE).contains(block)) {
            return Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        } else if (block == Blocks.ANCIENT_DEBRIS) {
            return Set.of(Blocks.ANCIENT_DEBRIS);
        } else {
            return block == Blocks.NETHER_QUARTZ_ORE ? Set.of(Blocks.NETHER_QUARTZ_ORE) : Collections.emptySet();
        }
    }

    /** Aggregated authenticity score for a candidate ore vein (anti "fake ore" heuristic). */
    private static final class OreScore {
        double hostRockScore = 0.0;
        double chunkStateScore = 0.0;
        double veinSizeScore = 0.0;
        double patternScore = 0.0;
        double airExposureScore = 0.0;
        double depthScore = 0.0;
        double extraScore = 0.0;

        double getTotalScore() {
            return this.hostRockScore + this.chunkStateScore + this.veinSizeScore
                    + this.patternScore + this.airExposureScore + this.depthScore + this.extraScore;
        }

        boolean isAuthentic(double minScore) {
            return this.getTotalScore() >= minScore;
        }
    }

    /** Immutable integer block coordinate, used as a hash-map key for the packet-driven blind scan. */
    @EqualsAndHashCode
    private static final class BlockKey {
        private final int x;
        private final int y;
        private final int z;

        BlockKey(BlockPos pos) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
        }

        int x() {
            return this.x;
        }

        int y() {
            return this.y;
        }

        int z() {
            return this.z;
        }

        BlockPos toBlockPos() {
            return new BlockPos(this.x, this.y, this.z);
        }
    }

    /** Per-chunk authenticity cache entry for {@link #calcChunkStateScore}. */
    private enum ChunkState {
        AUTHENTIC,
        SUSPICIOUS,
        SCANNING
    }
}
