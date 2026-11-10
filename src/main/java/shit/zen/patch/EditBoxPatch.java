package shit.zen.patch;

import asm.patchify.annotation.Patch;
import asm.patchify.annotation.WrapInvoke;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import shit.zen.ClientBase;
import shit.zen.asm.Invocation;

/**
 * Hides the vanilla rectangular edit-box background while the chat screen is
 * open. {@link ChatScreenPatch} draws a rounded frosted panel in its place;
 * the text, selection and cursor are left untouched.
 */
@Patch(EditBox.class)
public class EditBoxPatch
extends ClientBase {
    @WrapInvoke(method = "renderWidget", desc = "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            target = "net/minecraft/client/gui/GuiGraphics/fill", targetDesc = "(IIIII)V")
    public static void wrapFill(EditBox editBox, GuiGraphics graphics, int mouseX, int mouseY,
                                float partialTick, Invocation<GuiGraphics, Void> invocation) throws Exception {
        if (mc != null && mc.screen instanceof ChatScreen) {
            List<Object> args = invocation.args();
            int x1 = ((Number) args.get(0)).intValue();
            int x2 = ((Number) args.get(2)).intValue();
            // Only drop the border/background that spans the whole widget.
            if (x1 <= editBox.getX() && x2 >= editBox.getX() + editBox.getWidth()) {
                return;
            }
        }
        invocation.call();
    }
}
