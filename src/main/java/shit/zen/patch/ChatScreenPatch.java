package shit.zen.patch;

import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.WrapInvoke;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;
import shit.zen.ClientBase;
import shit.zen.ZenClient;
import shit.zen.asm.Invocation;
import shit.zen.hud.HudElement;
import shit.zen.modules.Module;
import shit.zen.utils.misc.ReflectionUtil;
import shit.zen.utils.render.RenderUtil;

@Patch(ChatScreen.class)
public class ChatScreenPatch {
    @Inject(method = "render", desc = "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
    public static void onRender(ChatScreen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callbackInfo) {
        try {
            for (HudElement element : ZenClient.getInstance().getHudManager().getHudElements().stream().filter(Module::isEnabled).toList()) {
                if (!element.isDragging()) continue;
                element.mouseDragged(mouseX, mouseY);
                boolean leftDown = GLFW.glfwGetMouseButton(ClientBase.mc.getWindow().getWindow(), 0) == 1;
                if (!leftDown) {
                    element.setDragging(false);
                }
            }
        } catch (Exception exception) {
            ClientBase.logger.error(exception);
            ClientBase.logger.error(exception.getMessage());
        }
        drawInputBackground(screen, graphics);
    }

    private static void drawInputBackground(ChatScreen screen, GuiGraphics graphics) {
        try {
            Object value = ReflectionUtil.getStaticField(screen, "input", ChatScreen.class.getName());
            if (!(value instanceof EditBox input)) {
                return;
            }
            float x = input.getX() - 3.0f;
            float y = input.getY() - 3.0f;
            float width = input.getWidth() + 6.0f;
            float height = input.getHeight() + 6.0f;
            PoseStack poseStack = graphics.pose();
            poseStack.pushPose();
            RenderUtil.drawBlurredRect(poseStack, x, y, width, height, 6.0f, 14.0f, 0.9f, 0);
            poseStack.popPose();
            RenderUtil.drawRoundedRect(poseStack, x, y, width, height, 6.0f,
                    new Color(255, 255, 255, 38).getRGB());
            RenderUtil.drawRoundedRect(poseStack, x + 1.0f, y + 1.0f, width - 2.0f, height - 2.0f, 5.0f,
                    new Color(10, 12, 18, 170).getRGB());
        } catch (Throwable ignored) {
            // Cosmetic only; never break chat input.
        }
    }

    @WrapInvoke(method = "render", desc = "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            target = "net/minecraft/client/gui/GuiGraphics/fill", targetDesc = "(IIIII)V")
    public static void wrapBackgroundFill(ChatScreen screen, GuiGraphics graphics, int mouseX, int mouseY,
                                          float partialTick, Invocation<GuiGraphics, Void> invocation) throws Exception {
        // Vanilla paints a full-width translucent strip behind the input.
        // It is replaced by the rounded blurred panel in drawInputBackground.
    }

    @Inject(method = "mouseClicked", desc = "(DDI)Z")
    public static void onMouseClicked(ChatScreen screen, double mouseX, double mouseY, int button, CallbackInfo callbackInfo) {
        try {
            for (HudElement element : ZenClient.getInstance().getHudManager().getHudElements().stream().filter(Module::isEnabled).toList()) {
                if (element.mousePressed((int) mouseX, (int) mouseY, button)) {
                    break;
                }
            }
        } catch (Exception exception) {
            ClientBase.logger.error(exception);
            ClientBase.logger.error(exception.getMessage());
        }
    }
}
