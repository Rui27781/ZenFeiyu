package shit.zen.modules.impl.combat.antikb;

import java.util.HashMap;
import java.util.Optional;
import shit.zen.ClientBase;
import shit.zen.event.impl.DisconnectEvent;
import shit.zen.event.impl.EntityHurtEvent;
import shit.zen.event.impl.EntityRemoveEvent;
import shit.zen.event.impl.GameTickEvent;
import shit.zen.event.impl.MotionEvent;
import shit.zen.event.impl.PreMotionEvent;
import shit.zen.event.impl.ReceivePacketEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.event.impl.RotationEvent;
import shit.zen.event.impl.SprintEvent;
import shit.zen.event.impl.StrafeEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.impl.combat.antikb.JumpResetMode;
import shit.zen.modules.impl.combat.antikb.MixMode;
import shit.zen.modules.impl.combat.antikb.NoXZMode;

public abstract class AntiKBMode
extends ClientBase {
    protected final String name;
    private static final HashMap<Class<? extends AntiKBMode>, AntiKBMode> modes = new HashMap<>();

    public AntiKBMode(String string) {
        this.name = string;
    }

    public static void initModes() {
        modes.put(JumpResetMode.class, new JumpResetMode());
        modes.put(MixMode.class, new MixMode());
        modes.put(NoXZMode.class, new NoXZMode());
    }

    public abstract void onEnable();

    public abstract void onDisable();

    public abstract String getName();

    public static Optional<AntiKBMode> findMode(String string) {
        return modes.values().stream().filter(antiKBMode -> antiKBMode.name.equals(string)).findFirst();
    }

    public abstract void onRotation(RotationEvent var1);

    public abstract void onReceivePacket(ReceivePacketEvent var1);

    public abstract void onDisconnect(DisconnectEvent var1);

    public abstract void onPreMotion(PreMotionEvent var1);

    public abstract void onGameTick(GameTickEvent var1);

    public abstract void onSprint(SprintEvent var1);

    public abstract void onTick(TickEvent var1);

    public abstract void onStrafe(StrafeEvent var1);

    public abstract void onMotion(MotionEvent var1);

    public void onRender(RenderEvent renderEvent) {
    }

    public void onRender2D(Render2DEvent render2DEvent) {
    }

    /** 攻击事件（EntityRemoveEvent 的 pre-attack 阶段）。 */
    public void onAttack(EntityRemoveEvent entityRemoveEvent) {
    }

    /** 玩家受伤事件，供模式记录攻击者。 */
    public void onEntityHurt(EntityHurtEvent entityHurtEvent) {
    }

    public boolean isActive() {
        return false;
    }
}