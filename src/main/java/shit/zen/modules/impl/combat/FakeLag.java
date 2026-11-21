package shit.zen.modules.impl.combat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.PacketEvent;
import shit.zen.event.impl.ReceivePacketEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.misc.PacketUtil;

/**
 * FakeLag — 缓存出站位置数据包，模拟高延迟
 *
 * 核心机制: 拦截并延迟发送 ServerboundMovePlayerPacket，释放时瞬间发送所有缓存包
 *
 * Dynamic 模式: 仅在敌人进入范围时缓存
 * Constant 模式: 始终缓存
 *
 * 自动释放: 攻击、交互、受伤、击退、服务端传送
 */
public class FakeLag extends Module {

    public static FakeLag INSTANCE;

    private final NumberSetting range = new NumberSetting("Range", 4, 1, 10, 0.5);
    private final NumberSetting delay = new NumberSetting("Delay", 140, 50, 1000, 10);
    private final NumberSetting recoilTime = new NumberSetting("Recoil Time", 50, 0, 1000, 10);
    private final ModeSetting mode = new ModeSetting("Mode", "Dynamic", "Constant").withDefault("Dynamic");
    private final BooleanSetting flushOnAttack = new BooleanSetting("Flush On Attack", true);
    private final BooleanSetting flushOnInteract = new BooleanSetting("Flush On Interact", true);
    private final BooleanSetting flushOnDamage = new BooleanSetting("Flush On Damage", true);

    private final List<Packet<?>> packetQueue = new ArrayList<>();
    private long lastFlushTime = 0;
    private boolean isEnemyNearby = false;

    public FakeLag() {
        super("FakeLag", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        packetQueue.clear();
        lastFlushTime = 0;
        isEnemyNearby = false;
    }

    @Override
    public void onDisable() {
        flushAll();
    }

    // --- Tick: 检测敌人、超时释放 ---
    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        double r = range.getValue().doubleValue();
        isEnemyNearby = mc.level.players().stream()
                .filter(p -> p != mc.player)
                .filter(Player::isAlive)
                .anyMatch(p -> mc.player.distanceTo(p) <= r);

        if (!packetQueue.isEmpty()) {
            long elapsed = System.currentTimeMillis() - lastFlushTime;
            if (elapsed >= delay.getValue().longValue()) {
                flushAll();
            }
        }
    }

    // --- 拦截入站包: 检测击退/传送/受伤 → 触发释放 ---
    @EventTarget
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || packetQueue.isEmpty()) return;

        Packet<ClientGamePacketListener> packet = event.getPacket();

        if (packet instanceof ClientboundPlayerPositionPacket) {
            flushAll();
            return;
        }

        if (flushOnDamage.getValue()) {
            if (packet instanceof ClientboundSetEntityMotionPacket motion) {
                if (motion.getId() == mc.player.getId()
                        && (motion.getXa() != 0 || motion.getYa() != 0 || motion.getZa() != 0)) {
                    flushAll();
                    return;
                }
            }
            if (packet instanceof ClientboundSetHealthPacket health) {
                if (health.getHealth() < mc.player.getHealth()) {
                    flushAll();
                    return;
                }
            }
        }
    }

    // --- 拦截出站包: 缓存移动包，攻击/交互时释放 ---
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (event.isIncoming()) return;

        Packet<?> packet = event.getPacket();

        // 非移动包: 检查是否需要因攻击/交互而释放
        if (!(packet instanceof ServerboundMovePlayerPacket)) {
            if (flushOnAttack.getValue() && isAttackPacket(packet)) {
                flushAll();
                return;
            }
            if (flushOnInteract.getValue() && isInteractPacket(packet)) {
                flushAll();
                return;
            }
            return;
        }

        // Dynamic 模式: 没敌人就不缓存
        if ("Dynamic".equals(mode.getValue()) && !isEnemyNearby) {
            if (!packetQueue.isEmpty()) flushAll();
            return;
        }

        // 回冲冷却
        if (System.currentTimeMillis() - lastFlushTime < recoilTime.getValue().longValue()) {
            return;
        }

        // 缓存移动包
        event.setCancelled(true);
        packetQueue.add(packet);
    }

    // --- 释放所有缓存包 ---
    @SuppressWarnings("unchecked")
    private void flushAll() {
        if (packetQueue.isEmpty()) return;
        for (Packet<?> packet : packetQueue) {
            try {
                if (mc.getConnection() != null) {
                    PacketUtil.sendQueued((Packet<ServerGamePacketListener>) packet);
                }
            } catch (Exception ignored) {
            }
        }
        packetQueue.clear();
        lastFlushTime = System.currentTimeMillis();
    }

    private boolean isAttackPacket(Packet<?> packet) {
        return packet instanceof ServerboundInteractPacket
                || packet instanceof ServerboundSwingPacket;
    }

    private boolean isInteractPacket(Packet<?> packet) {
        return packet instanceof ServerboundUseItemOnPacket
                || packet instanceof ServerboundPlayerActionPacket;
    }

    /**
     * 从缓存的移动包中提取位置
     */
    public List<Vec3> getQueuedPositions() {
        List<Vec3> positions = new ArrayList<>();
        for (Packet<?> packet : packetQueue) {
            if (packet instanceof ServerboundMovePlayerPacket.PosRot p) {
                positions.add(new Vec3(p.getX(0), p.getY(0), p.getZ(0)));
            } else if (packet instanceof ServerboundMovePlayerPacket.Pos p) {
                positions.add(new Vec3(p.getX(0), p.getY(0), p.getZ(0)));
            }
        }
        // 追加玩家当前位置作为终点
        if (!positions.isEmpty() && mc.player != null) {
            positions.add(mc.player.position());
        }
        return positions;
    }

    public boolean isLagging() {
        return !packetQueue.isEmpty();
    }
}
