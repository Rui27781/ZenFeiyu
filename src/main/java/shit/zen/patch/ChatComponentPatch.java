package shit.zen.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.WrapInvoke;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;
import shit.zen.ClientBase;
import shit.zen.asm.Invocation;
import shit.zen.utils.misc.ReflectionUtil;
import shit.zen.utils.render.RenderUtil;

/**
 * Replaces vanilla's per-line rectangular chat backgrounds with a single
 * frosted glass panel that follows the visible chat area.
 *
 * <p>Vanilla still draws a two-pixel coloured strip for tagged messages, so
 * that call is allowed through. Every other {@code GuiGraphics.fill} in
 * {@link ChatComponent#render} is skipped; the rounded blurred panel is drawn
 * once at the head of the method instead.</p>
 */
@Patch(ChatComponent.class)
public class ChatComponentPatch
extends ClientBase {
    private static final float PANEL_RADIUS = 6.0f;
    private static final float BLUR_RADIUS = 14.0f;

    @Inject(method = "render", desc = "(Lnet/minecraft/client/gui/GuiGraphics;III)V", at = @At(At.Type.HEAD))
    public static void onRenderHead(ChatComponent component, GuiGraphics graphics,
                                    int tickCount, int mouseX, int mouseY, CallbackInfo callbackInfo) {
        try {
            if (mc == null || mc.options == null) {
                return;
            }
            if (mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
                return;
            }
            List<?> messages = getTrimmedMessages(component);
            if (messages == null || messages.isEmpty()) {
                return;
            }

            double scaleValue = component.getScale();
            float scale = (float) Math.max(0.01, scaleValue);
            int widthUnits = Mth.ceil((float) component.getWidth() / scale);
            int bottomUnits = Mth.floor((float) (graphics.guiHeight() - 40) / scale);
            int lineHeight = (int) (9.0 * (mc.options.chatLineSpacing().get() + 1.0));
            int lines = Math.min(component.getLinesPerPage(), messages.size());
            if (lines <= 0) {
                return;
            }
            long queued = mc.getChatListener().queueSize();

            float bottom = (bottomUnits + (queued > 0L ? 9 : 0)) * scale;
            float top = (bottomUnits - lines * lineHeight) * scale;
            // Vanilla draws the message background from local x=-4 to
            // widthUnits+8 inside a pose translated by 4, so the screen-space
            // edges are (local + 4) * scale.
            float x1 = -2.0f;
            float x2 = (widthUnits + 12.0f) * scale + 2.0f;
            top -= 2.0f;
            bottom += 2.0f;
            float width = x2 - x1;
            float height = bottom - top;
            if (width <= 0.0f || height <= 0.0f) {
                return;
            }

            float fade = 1.0f;
            if (!(mc.screen instanceof ChatScreen)) {
                fade = (float) (mc.options.chatOpacity().get() * 0.9 + 0.1);
                if (messages.get(0) instanceof GuiMessage.Line line) {
                    double factor = (double) (tickCount - line.addedTime()) / 200.0;
                    factor = 1.0 - factor;
                    factor *= 10.0;
                    factor = Mth.clamp(factor, 0.0, 1.0);
                    fade *= (float) (factor * factor);
                }
            }
            if (fade <= 0.01f) {
                return;
            }

            float radius = Math.min(PANEL_RADIUS, height * 0.5f);
            PoseStack poseStack = graphics.pose();
            poseStack.pushPose();
            RenderUtil.drawBlurredRect(poseStack, x1, top, width, height, radius,
                    BLUR_RADIUS, 0.9f * fade, 0);
            poseStack.popPose();

            int rimColor = new Color(255, 255, 255, (int) (34.0f * fade)).getRGB();
            int bodyColor = new Color(10, 12, 18, (int) (145.0f * fade)).getRGB();
            RenderUtil.drawRoundedRect(poseStack, x1, top, width, height, radius, rimColor);
            RenderUtil.drawRoundedRect(poseStack, x1 + 1.0f, top + 1.0f, width - 2.0f, height - 2.0f,
                    Math.max(0.0f, radius - 1.0f), bodyColor);
        } catch (Throwable ignored) {
            // Never let a cosmetic chat patch break chat rendering.
        }
    }

    @WrapInvoke(method = "render", desc = "(Lnet/minecraft/client/gui/GuiGraphics;III)V",
            target = "net/minecraft/client/gui/GuiGraphics/fill", targetDesc = "(IIIII)V")
    public static void wrapBackgroundFill(ChatComponent component, GuiGraphics graphics,
                                          int tickCount, int mouseX, int mouseY,
                                          Invocation<GuiGraphics, Void> invocation) throws Exception {
        List<Object> args = invocation.args();
        int x1 = ((Number) args.get(0)).intValue();
        int x2 = ((Number) args.get(2)).intValue();
        // The tag indicator is only two pixels wide; keep it. All full-width
        // message/queue backgrounds are replaced by the rounded blurred panel.
        if (x2 - x1 <= 2) {
            invocation.call();
        }
    }

    private static List<?> getTrimmedMessages(ChatComponent component) {
        try {
            Object value = ReflectionUtil.getStaticField(component, "trimmedMessages",
                    ChatComponent.class.getName());
            return value instanceof List<?> list ? list : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
