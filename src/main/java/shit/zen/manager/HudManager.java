package shit.zen.manager;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.screens.ChatScreen;
import shit.zen.ClientBase;
import shit.zen.ZenClient;
import shit.zen.event.impl.GlRenderEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.gui.IntroAnimation;
import shit.zen.gui.newclickgui.Theme;
import shit.zen.hud.HudElement;
import shit.zen.hud.AttackTargetHud;
import shit.zen.hud.KeyBindsHud;
import shit.zen.hud.LieDetector;
import shit.zen.hud.ModuleListHud;
import shit.zen.hud.MusicHud;
import shit.zen.hud.PlayerListHud;
import shit.zen.hud.PotionEffectsHud;
import shit.zen.hud.TargetHud;
import shit.zen.event.EventTarget;
import shit.zen.render.CustomFont;
import shit.zen.render.FontStore;
import shit.zen.utils.misc.GuiI18n;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.RenderUtil;

public class HudManager {
    private final Map<String, HudElement> hudElements = new HashMap<>();

    public HudManager() {
        this.init();
    }

    public void init() {
        this.registerHudElement(new TargetHud());
        this.registerHudElement(new AttackTargetHud());
        this.registerHudElement(new MusicHud());
        this.registerHudElement(new KeyBindsHud());
        this.registerHudElement(new ModuleListHud());
        this.registerHudElement(new PlayerListHud());
        this.registerHudElement(new PotionEffectsHud());
        this.registerHudElement(new LieDetector());
    }

    private void registerHudElement(HudElement hudElement) {
        ZenClient.getInstance().getModuleManager().register(hudElement);
        this.hudElements.put(hudElement.getClass().getSimpleName(), hudElement);
    }

    public <T extends HudElement> T getHudElement(Class<T> clazz) {
        return clazz.cast(this.hudElements.get(clazz.getSimpleName()));
    }

    public HudElement getHudElementByName(String string) {
        return this.hudElements.values().stream().filter(hudElement -> hudElement.getName().equalsIgnoreCase(string)).findFirst().orElse(null);
    }

    public Collection<HudElement> getHudElements() {
        return this.hudElements.values();
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (ClientBase.mc.screen == null) {
            try {
                for (HudElement hudElement : ZenClient.getInstance().getHudManager().getHudElements()) {
                    hudElement.stopDragging();
                }
            } catch (Exception exception) {
                ClientBase.logger.error(exception);
                ClientBase.logger.error(exception.getMessage());
            }
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent render2DEvent) {
        if (IntroAnimation.isRunning()) {
            return;
        }
        for (HudElement hudElement : this.getHudElements()) {
            if (!hudElement.isEnabled()) continue;
            hudElement.onRender2D(render2DEvent, hudElement.getX(), hudElement.getY());
        }
        // 打开聊天栏时进入 HUD 编辑模式：所有可拖动 UI 都显示描边和名称，
        // 左键按住即可移动，松开后位置由 ValuesConfig 自动保存。
        if (ClientBase.mc.screen instanceof ChatScreen) {
            this.drawHudEditHints(render2DEvent);
        }
    }

    private void drawHudEditHints(Render2DEvent event) {
        // 顶部提示：让“打开聊天栏即可拖动”这个入口一眼可见。
        String hint = GuiI18n.isChinese() ? "HUD 编辑：拖动即可调整位置" : "HUD editor: drag to reposition";
        CustomFont hintFont = GuiI18n.isChinese() ? FontStore.PINGFANG_16 : FontStore.AXIFORMA_REGULAR_16;
        float hintWidth = hintFont.getStringWidth(hint) + 16.0f;
        float hintX = ((float) ClientBase.mc.getWindow().getGuiScaledWidth() - hintWidth) / 2.0f;
        RenderUtil.drawRoundedRect(event.poseStack(), hintX, 6.0f, hintWidth, 18.0f, 6.0f,
                ColorUtil.withAlpha(0xFF101014, 0.85f));
        hintFont.drawString(event.poseStack(), hint, hintX + 8.0f,
                6.0f + (18.0f - hintFont.getFontHeight()) / 2.0f, ColorUtil.withAlpha(-1, 0.85f));

        for (HudElement element : this.getHudElements()) {
            if (!element.isEnabled() || element.getWidth() <= 0.0f || element.getHeight() <= 0.0f) {
                continue;
            }
            float x = element.getX();
            float y = element.getY();
            float width = element.getWidth();
            float height = element.getHeight();
            int border = element.isDragging() ? Theme.accent() : ColorUtil.withAlpha(-1, 0.35f);
            RenderUtil.drawFilledRect(event.poseStack(), x, y, width, 1.0f, border);
            RenderUtil.drawFilledRect(event.poseStack(), x, y + height - 1.0f, width, 1.0f, border);
            RenderUtil.drawFilledRect(event.poseStack(), x, y, 1.0f, height, border);
            RenderUtil.drawFilledRect(event.poseStack(), x + width - 1.0f, y, 1.0f, height, border);

            String label = element.getName();
            float labelWidth = FontStore.AXIFORMA_BOLD_13.getStringWidth(label) + 8.0f;
            RenderUtil.drawRoundedRect(event.poseStack(), x, Math.max(0.0f, y - 13.0f), labelWidth, 12.0f,
                    4.0f, ColorUtil.withAlpha(0xFF101014, 0.9f));
            FontStore.AXIFORMA_BOLD_13.drawString(event.poseStack(), label, x + 4.0f,
                    Math.max(0.0f, y - 13.0f) + 1.0f, ColorUtil.withAlpha(-1, 0.9f));
        }
    }

    @EventTarget
    public void onGlRender(GlRenderEvent glRenderEvent) {
        if (IntroAnimation.isRunning()) {
            return;
        }
        for (HudElement hudElement : this.getHudElements()) {
            if (!hudElement.isEnabled()) continue;
            hudElement.onGlRender(glRenderEvent, hudElement.getX(), hudElement.getY());
        }
    }
}