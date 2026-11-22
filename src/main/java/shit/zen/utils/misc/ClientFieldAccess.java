package shit.zen.utils.misc;

import java.lang.reflect.Field;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.projectile.AbstractArrow;

/**
 * Reads a handful of 1.20.1 client fields that had no public getter when the NeoRaven visual
 * modules were ported. Reflection is cached and never throws into the render/tick loop: on failure
 * the callers fall back to a coarse public value.
 */
public final class ClientFieldAccess {
    private static Field destroyProgress;
    private static boolean destroyProgressResolved;
    private static Field arrowInGround;
    private static boolean arrowInGroundResolved;

    private ClientFieldAccess() {
    }

    /** Smooth block-break progress in {@code [0,1]}, with the destroy stage as a fallback. */
    public static float destroyProgress(MultiPlayerGameMode mode) {
        if (mode == null) {
            return 0.0f;
        }
        if (!destroyProgressResolved) {
            destroyProgressResolved = true;
            try {
                destroyProgress = MultiPlayerGameMode.class.getDeclaredField("destroyProgress");
                destroyProgress.setAccessible(true);
            } catch (Throwable ignored) {
                destroyProgress = null;
            }
        }
        if (destroyProgress != null) {
            try {
                return destroyProgress.getFloat(mode);
            } catch (Throwable ignored) {
                // Fall through to the coarse stage.
            }
        }
        return mode.isDestroying() ? (mode.getDestroyStage() + 1) / 9.0f : 0.0f;
    }

    /** Whether an arrow is resting in the ground; falls back to the generic on-ground flag. */
    public static boolean arrowInGround(AbstractArrow arrow) {
        if (arrow == null) {
            return false;
        }
        if (!arrowInGroundResolved) {
            arrowInGroundResolved = true;
            try {
                arrowInGround = AbstractArrow.class.getDeclaredField("inGround");
                arrowInGround.setAccessible(true);
            } catch (Throwable ignored) {
                arrowInGround = null;
            }
        }
        if (arrowInGround != null) {
            try {
                return arrowInGround.getBoolean(arrow);
            } catch (Throwable ignored) {
                // Fall through to the generic flag.
            }
        }
        return arrow.onGround;
    }
}
