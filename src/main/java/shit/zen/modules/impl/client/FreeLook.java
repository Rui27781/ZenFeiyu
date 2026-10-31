package shit.zen.modules.impl.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.lwjgl.glfw.GLFW;
import shit.zen.ClientBase;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.PacketEvent;
import shit.zen.event.impl.PreMotionEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.render.FontStore;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.misc.ReflectionUtil;

/**
 * FreeLook - camera decoupled from the body. Hold (or toggle) the bound key to look
 * around freely while your body yaw/pitch and the rotation sent to the server stay
 * frozen at the body rotation. Movement stays body-relative when Lock Movement is on.
 */
public class FreeLook extends Module {
    public static FreeLook INSTANCE;

    private static final String[] KEY_LABELS = {
        "Mouse 4 (M4)", "Mouse 5 (M5)", "Left Alt", "Right Alt", "Left Shift", "Right Shift",
        "Caps Lock", "Tab", "V", "X", "C", "Z"
    };
    private static final int[] KEY_CODES = {
        3, 4, 342, 346, 340, 344, 280, 258, 86, 88, 67, 90
    };

    private final ModeSetting keySetting = new ModeSetting("Free Look Key", KEY_LABELS)
        .withDefault("Mouse 4 (M4)");
    private final NumberSetting sensitivity = new NumberSetting("Sensitivity", 1.0, 0.2, 3.0, 0.05);
    private final NumberSetting viewRange = new NumberSetting("FOV", 180.0, 40.0, 180.0, 5.0);
    private final BooleanSetting toggleMode = new BooleanSetting("Toggle Mode", false);
    private final BooleanSetting smoothReturn = new BooleanSetting("Smooth Return", true);
    private final BooleanSetting lockMovement = new BooleanSetting("Lock Movement", true);
    private final BooleanSetting showHud = new BooleanSetting("Show HUD", true);

    private boolean freeLook = false;
    private boolean returning = false;
    private boolean spoofing = false;
    private boolean keyHeld = false;
    private float bodyYaw = 0.0f;
    private float bodyPitch = 0.0f;
    private float camYaw = 0.0f;
    private float camPitch = 0.0f;

    public FreeLook() {
        super("FreeLook", Category.CLIENT);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        resetState(false);
    }

    @Override
    protected void onDisable() {
        if (spoofing && ClientBase.mc.player != null) {
            ClientBase.mc.player.setYRot(bodyYaw);
            ClientBase.mc.player.setXRot(bodyPitch);
        }
        resetState(false);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (ClientBase.mc.player == null || ClientBase.mc.player.isDeadOrDying()) {
            resetState(false);
            return;
        }

        if (ClientBase.mc.screen != null) {
            if (freeLook || returning) release(true);
            keyHeld = false;
            return;
        }

        boolean down = isKeyDown();

        if (toggleMode.getValue()) {
            if (down && !keyHeld) {
                if (freeLook) release(false);
                else begin();
            }
        } else {
            if (down && !freeLook) begin();
            else if (!down && freeLook) release(false);
        }
        keyHeld = down;

        if (freeLook) applyCamera();
        else if (returning) animateReturn();
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        if (!spoofing || ClientBase.mc.player == null) return;
        ClientBase.mc.player.setYRot(bodyYaw);
        ClientBase.mc.player.setXRot(bodyPitch);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!spoofing || event.isIncoming()) return;
        Object packet = event.getPacket();
        if (packet instanceof ServerboundMovePlayerPacket movePacket) {
            ReflectionUtil.setYRot(movePacket, bodyYaw);
            ReflectionUtil.setXRot(movePacket, bodyPitch);
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!showHud.getValue()) return;
        if (!freeLook && !returning) return;

        var stack = event.poseStack();
        float x = 4.0f;
        float y = 4.0f;
        int accent = freeLook ? 0xFF4FD1FF : 0xFF999999;
        int white = 0xFFFFFFFF;
        float lineHeight = FontStore.AXIFORMA_BOLD_13.getFontHeight() + 2;

        FontStore.AXIFORMA_BOLD_13.drawString(stack, "FREE LOOK", x, y, accent);

        float line = y + lineHeight;
        FontStore.AXIFORMA_BOLD_13.drawString(stack,
            "yaw " + Math.round(wrap(camYaw - bodyYaw)) + " / " + Math.round(viewRange.getValue().floatValue()),
            x, line, white);

        line = line + lineHeight;
        FontStore.AXIFORMA_BOLD_13.drawString(stack,
            "sens " + Math.round(sensitivity.getValue().floatValue() * 100.0) / 100.0,
            x, line, white);
    }

    @EventTarget
    public void onRotation(shit.zen.event.impl.RotationEvent event) {
        if (freeLook && lockMovement.getValue() && ClientBase.mc.player != null) {
            event.setYaw(bodyYaw);
        }
    }

    private void begin() {
        bodyYaw = wrap(ClientBase.mc.player.getYRot());
        bodyPitch = clampPitch(ClientBase.mc.player.getXRot());
        camYaw = bodyYaw;
        camPitch = bodyPitch;
        freeLook = true;
        returning = false;
        spoofing = true;
    }

    private void release(boolean instant) {
        if (!freeLook) {
            returning = false;
            if (instant) snapBack();
            return;
        }
        freeLook = false;
        if (smoothReturn.getValue() && !instant) {
            returning = true;
            camYaw = wrap(ClientBase.mc.player.getYRot());
            camPitch = clampPitch(ClientBase.mc.player.getXRot());
        } else {
            snapBack();
        }
    }

    private void snapBack() {
        returning = false;
        spoofing = false;
        camYaw = bodyYaw;
        camPitch = bodyPitch;
        if (ClientBase.mc.player != null) {
            ClientBase.mc.player.setYRot(bodyYaw);
            ClientBase.mc.player.setXRot(bodyPitch);
        }
    }

    private void applyCamera() {
        float rawYaw = ClientBase.mc.player.getYRot();
        float rawPitch = ClientBase.mc.player.getXRot();

        float dYaw = wrap(rawYaw - camYaw);
        float dPitch = rawPitch - camPitch;

        if (dYaw != 0.0f || dPitch != 0.0f) {
            if (Math.abs(dYaw) > 45.0f || Math.abs(dPitch) > 45.0f) {
                camYaw = wrap(rawYaw);
                camPitch = clampPitch(rawPitch);
            } else {
                camYaw = wrap(camYaw + dYaw * sensitivity.getValue().floatValue());
                camPitch = clampPitch(camPitch + dPitch * sensitivity.getValue().floatValue());
            }
        }

        float range = viewRange.getValue().floatValue();
        if (range < 179.5f) {
            float relative = wrap(camYaw - bodyYaw);
            if (relative > range) relative = range;
            else if (relative < -range) relative = -range;
            camYaw = wrap(bodyYaw + relative);
        }

        if (ClientBase.mc.player != null) {
            ClientBase.mc.player.setYRot(camYaw);
            ClientBase.mc.player.setXRot(camPitch);
        }
    }

    private void animateReturn() {
        float dYaw = wrap(bodyYaw - camYaw);
        float dPitch = bodyPitch - camPitch;

        if (Math.abs(dYaw) < 0.30f && Math.abs(dPitch) < 0.30f) {
            camYaw = bodyYaw;
            camPitch = bodyPitch;
            if (ClientBase.mc.player != null) {
                ClientBase.mc.player.setYRot(camYaw);
                ClientBase.mc.player.setXRot(camPitch);
            }
            returning = false;
            spoofing = false;
            return;
        }

        camYaw = wrap(camYaw + dYaw * 0.45f);
        camPitch = clampPitch(camPitch + dPitch * 0.45f);

        if (ClientBase.mc.player != null) {
            ClientBase.mc.player.setYRot(camYaw);
            ClientBase.mc.player.setXRot(camPitch);
        }
    }

    private void resetState(boolean restoreRotation) {
        if (restoreRotation && ClientBase.mc.player != null && spoofing) {
            ClientBase.mc.player.setYRot(bodyYaw);
            ClientBase.mc.player.setXRot(bodyPitch);
        }
        freeLook = false;
        returning = false;
        spoofing = false;
        keyHeld = false;
    }

    private boolean isKeyDown() {
        String label = keySetting.getValue();
        int idx = -1;
        for (int i = 0; i < KEY_LABELS.length; i++) {
            if (label.equals(KEY_LABELS[i])) { idx = i; break; }
        }
        if (idx < 0) idx = 0;
        int code = KEY_CODES[idx];
        long window = ClientBase.mc.getWindow().getWindow();
        if (code >= 0 && code <= 900) {
            return InputConstants.isKeyDown(window, code);
        }
        if (code < 0) {
            return GLFW.glfwGetMouseButton(window, -code - 1) == GLFW.GLFW_PRESS;
        }
        return false;
    }

    private float wrap(float angle) {
        float v = angle % 360.0f;
        if (v > 180.0f) v -= 360.0f;
        else if (v < -180.0f) v += 360.0f;
        return v;
    }

    private float clampPitch(float pitch) {
        if (pitch > 90.0f) return 90.0f;
        if (pitch < -90.0f) return -90.0f;
        return pitch;
    }
}
