package shit.zen;

import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.HitResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ClientBase {
    public static Minecraft mc;
    public static Logger logger = LogManager.getLogger("Client");
    public static float yaw;
    public static boolean isLoading;
    public static final ConcurrentLinkedQueue<Runnable> delayPackets = new ConcurrentLinkedQueue<>();

    public static BlockPos getTargetBlockPos() {
        if (mc == null || mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return ((net.minecraft.world.phys.BlockHitResult) mc.hitResult).getBlockPos();
    }
}
