package shit.zen.modules.impl.render;

import shit.zen.event.impl.GlRenderEvent;
import shit.zen.event.impl.Render2DEvent;
import shit.zen.hud.DynamicIsland;
import shit.zen.hud.NeverloseWatermark;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.settings.impl.StringSetting;
import shit.zen.event.EventTarget;

public class Watermark extends Module {
    public static Watermark INSTANCE;
    final ModeSetting styleSetting = new ModeSetting("Style", "Neverlose", "DynamicIsland").withDefault("DynamicIsland");
    private final StringSetting idSetting = new StringSetting("ID", "cn78", 16).withDefault("cn78");
    private final NumberSetting islandBlur = new NumberSetting("Island Blur", 16.0, 0.0, 40.0, 1.0,
            () -> this.styleSetting.is("DynamicIsland"));
    private final DynamicIsland dynamicIsland = new DynamicIsland();
    private final NeverloseWatermark neverloseWatermark = new NeverloseWatermark();

    public Watermark() {
        super("Watermark", Category.RENDER);
        INSTANCE = this;
    }

    public String getId() {
        String id = this.idSetting.getValue();
        return id == null || id.isBlank() ? "cn78" : id.trim();
    }

    @EventTarget
    public void onRender2D(Render2DEvent render2DEvent) {
        if (!this.isEnabled()) {
            return;
        }
        switch (this.styleSetting.getValue()) {
            case "Neverlose":
                this.neverloseWatermark.onRender2D(render2DEvent);
                break;
            case "DynamicIsland":
                this.dynamicIsland.onRender2D(render2DEvent, this.islandBlur.getValue().floatValue());
                break;
        }
    }

    @EventTarget
    public void onGlRender(GlRenderEvent glRenderEvent) {
        if (!this.isEnabled()) {
            return;
        }
        if ("Neverlose".equals(this.styleSetting.getValue())) {
            this.neverloseWatermark.onGlRender(glRenderEvent);
        }
    }
}
