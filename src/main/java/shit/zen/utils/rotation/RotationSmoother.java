package shit.zen.utils.rotation;

import java.util.Random;
import net.minecraft.util.Mth;
import shit.zen.ClientBase;

/**
 * 旋转平滑器：惯性 + 波动，并把出站增量对齐到鼠标灵敏度格点。
 *
 * <p>由 Scaffold 使用。{@link #tick(Rotation, Rotation, float)} 的 speed 语义与旧的
 * {@code clamp(diff, ±speed)} 相同：单位为度/tick，speed 以内一步到位；超过时按带随机
 * 波动的角速度逼近目标，避免每 tick 输出完全相同的增量（Grim 的 DuplicateRotPlace 正是
 * 抓这个）。
 *
 * <p>{@link #patchConstantRotation(Rotation, Rotation)} 把增量量化到灵敏度步长，
 * {@link #breakDuplicateDelta(Rotation, Rotation)} 在相邻两次放置增量完全相同时沿格点
 * 挪动一格。
 */
public final class RotationSmoother {
    private final Random random;
    private float lastYawDelta;
    private float lastPitchDelta;
    private int ticks;

    public RotationSmoother(Random random) {
        this.random = random != null ? random : new Random();
    }

    public void reset() {
        this.lastYawDelta = 0.0f;
        this.lastPitchDelta = 0.0f;
        this.ticks = 0;
    }

    /**
     * 向目标旋转逼近。
     *
     * @param current  当前旋转（通常是上一次的目标旋转）
     * @param desired  期望旋转
     * @param maxSpeed 每 tick 最大角速度（度）
     * @return 平滑后的旋转；不会越过目标
     */
    public Rotation tick(Rotation current, Rotation desired, float maxSpeed) {
        if (desired == null) {
            return current != null ? current.clone() : new Rotation();
        }
        if (current == null) {
            return desired.clone();
        }
        if (maxSpeed >= 360.0f) {
            return desired.clone();
        }

        float yawDelta = Mth.wrapDegrees(desired.getYaw() - current.getYaw());
        float pitchDelta = desired.getPitch() - current.getPitch();
        float distance = Mth.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);
        if (distance <= maxSpeed || distance <= 1.0E-4f) {
            return desired.clone();
        }

        // 随机化步长，避免匀速转头产生完全相同的相邻增量。
        float step = maxSpeed * (0.82f + this.random.nextFloat() * 0.18f);
        step = Math.min(step, distance);
        if (step <= 1.0E-4f) {
            return current.clone();
        }

        float dirYaw = yawDelta / distance;
        float dirPitch = pitchDelta / distance;
        // 亚感知横向抖动：幅度随步长缩放，不会破坏“不越过目标”的上限。
        float wobble = (this.random.nextFloat() - 0.5f) * Math.min(1.2f, step * 0.12f);

        float newYaw = current.getYaw() + dirYaw * step - dirPitch * wobble;
        float newPitch = current.getPitch() + dirPitch * step + dirYaw * wobble;
        newPitch = Mth.clamp(newPitch, -90.0f, 90.0f);
        this.ticks++;
        return new Rotation(newYaw, newPitch);
    }

    /**
     * 把 rotation 相对 anchor 的增量量化到灵敏度步长。anchor 应为上一次真正下发的旋转，
     * 这样服务端看到的相邻增量始终落在同一个 GCD 网格上。
     */
    public static Rotation patchConstantRotation(Rotation rotation, Rotation anchor) {
        if (rotation == null) {
            return null;
        }
        if (anchor == null) {
            return rotation;
        }
        double step = sensitivityStep();
        if (step <= 1.0E-7) {
            return rotation;
        }
        float yaw = quantize(rotation.getYaw(), anchor.getYaw(), step);
        float pitch = quantize(rotation.getPitch(), anchor.getPitch(), step);
        return new Rotation(yaw, Mth.clamp(pitch, -90.0f, 90.0f));
    }

    /**
     * 若本次增量与上一次完全相同（且幅度足够被服务端注意），沿格点挪动一格，保持增量幅度
     * 近似不变但数值不同。返回的旋转仍在灵敏度格点上。
     */
    public Rotation breakDuplicateDelta(Rotation rotation, Rotation anchor) {
        if (rotation == null || anchor == null) {
            return rotation;
        }
        double step = sensitivityStep();
        float yawDelta = Mth.wrapDegrees(rotation.getYaw() - anchor.getYaw());
        float pitchDelta = rotation.getPitch() - anchor.getPitch();
        float yaw = rotation.getYaw();
        float pitch = rotation.getPitch();

        if (Math.abs(yawDelta) > 2.0f && Math.abs(yawDelta - this.lastYawDelta) < 1.0E-4f) {
            yaw = (float) (anchor.getYaw() + yawDelta + (yawDelta >= 0.0f ? step : -step));
        }
        if (Math.abs(pitchDelta) > 2.0f && Math.abs(pitchDelta - this.lastPitchDelta) < 1.0E-4f) {
            pitch = (float) (anchor.getPitch() + pitchDelta + (pitchDelta >= 0.0f ? step : -step));
            pitch = Mth.clamp(pitch, -90.0f, 90.0f);
        }

        this.lastYawDelta = Mth.wrapDegrees(yaw - anchor.getYaw());
        this.lastPitchDelta = pitch - anchor.getPitch();
        return new Rotation(yaw, pitch);
    }

    /**
     * 当前鼠标灵敏度对应的旋转格点（度）。与 Minecraft 的
     * {@code f = sens * 0.6 + 0.2; gcd = f^3 * 8 * 0.15} 一致。
     */
    public static double sensitivityStep() {
        try {
            if (ClientBase.mc != null && ClientBase.mc.options != null) {
                float sensitivity = ClientBase.mc.options.sensitivity().get().floatValue();
                // MC 在 0.5 时实际生效的灵敏度略有偏差。
                if (Math.abs(sensitivity - 0.5f) < 1.0E-6f) {
                    sensitivity = 0.47887325f;
                }
                double scaled = sensitivity * 0.6 + 0.2;
                return scaled * scaled * scaled * 1.2;
            }
        } catch (Throwable ignored) {
            // 客户端尚未初始化时回落到默认格点。
        }
        return 0.15;
    }

    private static float quantize(float value, float anchor, double step) {
        double delta = Mth.wrapDegrees(value - anchor);
        long units = Math.round(delta / step);
        return (float) (anchor + units * step);
    }
}
