package shit.zen.modules.impl.render;

import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.NumberSetting;

/**
 * Smooth hold-style zoom, ported from NeoRaven's {@code ZoomModule}.
 *
 * <p>Upbound used a separate held key because 1.8.9's module system had no hold binds. ZenFeiyu's
 * binds toggle, so the module's own enabled state is the zoom state: bind it to a convenient key and
 * use {@code setKey(...)}/the ClickGUI to zoom. The fade keeps running while disabled, which means
 * releasing the bind eases the FOV back out instead of snapping.</p>
 *
 * <p>The FOV value is fed into {@code GameRenderer#getProjectionMatrix} from
 * {@code GameRendererPatch}. No extra mixin or patch class is needed.</p>
 */
public class Zoom extends Module {
    public static Zoom INSTANCE;

    private static final float EPSILON = 0.0005f;

    private final NumberSetting distanceSetting = new NumberSetting("Distance", 4.0, 1.5, 12.0, 0.5);
    private final NumberSetting animationSetting = new NumberSetting("Animation", 180, 0, 500, 10);

    /** Eased fade: 0 = normal FOV, 1 = fully zoomed. */
    private float current;
    private long lastFrame;

    public Zoom() {
        super("Zoom", Category.RENDER);
        INSTANCE = this;
    }

    /** Advances the fade once per rendered frame. */
    public static void tickStatic() {
        Zoom module = INSTANCE;
        if (module != null) {
            module.tick();
        }
    }

    /** Applies the current fade to a vanilla FOV value. */
    public static double applyStatic(double fov) {
        Zoom module = INSTANCE;
        return module == null ? fov : module.apply(fov);
    }

    private void tick() {
        long now = System.nanoTime();
        float delta = this.lastFrame == 0L ? 16.0f : Math.min(100.0f, (now - this.lastFrame) / 1000000.0f);
        this.lastFrame = now;

        float target = this.isEnabled() ? 1.0f : 0.0f;
        float duration = this.animationSetting.getValue().floatValue();
        if (duration <= 0.0f) {
            this.current = target;
            return;
        }
        float step = Math.min(1.0f, Math.max(0.0f, delta) / duration);
        float eased = 1.0f - (1.0f - step) * (1.0f - step) * (1.0f - step);
        this.current += (target - this.current) * eased;
        if (Math.abs(target - this.current) < EPSILON) {
            this.current = target;
        }
    }

    private double apply(double fov) {
        if (this.current <= EPSILON) {
            return fov;
        }
        float eased = this.current * this.current * (3.0f - 2.0f * this.current);
        double zoomed = fov / Math.max(1.0, this.distanceSetting.getValue().doubleValue());
        return fov + (zoomed - fov) * eased;
    }

    @Override
    protected void onEnable() {
        // A fresh activation must not jump from a stale timestamp after a long pause.
        this.lastFrame = 0L;
    }
}
