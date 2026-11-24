package shit.zen.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import shit.zen.modules.impl.render.NoBackground;

/**
 * Hooks the single vanilla background entry point used by most screens.
 *
 * <p>{@code Screen#renderBackground(GuiGraphics)} paints the grey translucent
 * gradient (or the dirt texture in menus). Cancelling it here lets the
 * {@link NoBackground} module make inventories and the ClickGUI fully
 * transparent while leaving every screen's widgets untouched.</p>
 */
@Patch(Screen.class)
public class ScreenPatch {
    @Inject(method = "renderBackground", desc = "(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At(At.Type.HEAD))
    public static void onRenderBackground(Screen screen, GuiGraphics graphics, CallbackInfo callbackInfo) {
        if (NoBackground.shouldHide(screen)) {
            callbackInfo.cancel();
        }
    }
}
