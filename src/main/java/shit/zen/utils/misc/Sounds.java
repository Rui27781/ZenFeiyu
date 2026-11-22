package shit.zen.utils.misc;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import shit.zen.ClientBase;
import shit.zen.modules.impl.client.ToggleSounds;

/**
 * Sound helpers ported from NeoRaven's {@code client.Sounds}.
 *
 * <p>1.20.1 no longer exposes the old positioned-sound constructor, so positioned queues fall back
 * to a UI sound instance at the same pitch. Missing sample ids fall back to {@code note.pling}, and
 * every call is wrapped so a missing sound can never break a module toggle. The toggle-click styles
 * are driven by the ZenFeiyu {@link ToggleSounds} module instead of the NeoRaven ClickGUI theme.</p>
 */
public final class Sounds extends ClientBase {
    private static String queuedName = "note.pling";
    private static final float[] QUEUED_ARGS = new float[2];
    private static boolean queued;
    private static boolean suppressed;

    private Sounds() {
    }

    public static void queue(String id, float volume, float pitch) {
        if (!ChatUtil.inWorld()) {
            return;
        }
        queuedName = id;
        QUEUED_ARGS[0] = volume;
        QUEUED_ARGS[1] = pitch;
        queued = true;
    }

    public static void flush() {
        if (!queued) {
            return;
        }
        queued = false;
        play(queuedName, QUEUED_ARGS[0], QUEUED_ARGS[1]);
    }

    public static void play(String id, float volume, float pitch) {
        if (mc == null || mc.getSoundManager() == null) {
            return;
        }
        try {
            ResourceLocation location = ResourceLocation.tryParse(id);
            SoundEvent event = location == null ? null : BuiltInRegistries.SOUND_EVENT.get(location);
            if (event == null) {
                event = BuiltInRegistries.SOUND_EVENT.get(new ResourceLocation("minecraft:note.pling"));
            }
            if (event == null) {
                event = SoundEvents.UI_BUTTON_CLICK;
            }
            // Volume is intentionally applied through pitch only for UI sounds: 1.20.1's UI bus
            // owns its gain, and forcing a per-instance volume made toggles far too loud.
            SoundInstance instance = SimpleSoundInstance.forUI(event, pitch);
            mc.getSoundManager().play(instance);
        } catch (Throwable ignored) {
            // A missing or malformed sound id must never break gameplay.
        }
    }

    public static void playUi(String id, float pitch) {
        play(id, 1.0f, pitch);
    }

    /** Plays the configured module-toggle click, unless suppressed during config load. */
    public static void toggle(boolean enabled) {
        Sounds.toggle(enabled, false);
    }

    /** @param force bypass the master module check, used while toggling ToggleSounds itself */
    public static void toggle(boolean enabled, boolean force) {
        if (suppressed) {
            return;
        }
        ToggleSounds module = ToggleSounds.INSTANCE;
        if (module == null || (!force && !module.isEnabled()) || !module.soundSetting.getValue()) {
            return;
        }
        String style = module.styleSetting.getValue();
        if ("Lever".equals(style)) {
            playUi("random.click", enabled ? 0.6f : 0.5f);
            return;
        }
        if ("Pling".equals(style)) {
            playUi("note.pling", enabled ? 1.85f : 1.45f);
            return;
        }
        String suffix = enabled ? "_on" : "_off";
        String set = "Smooth".equals(style) ? "smooth"
                : "Celestial".equals(style) ? "celestial" : "nursultan";
        if (!playSample("zenfeiyu:toggle_" + set + suffix)) {
            playSample("zenfeiyu:toggle" + suffix);
        }
    }

    private static boolean playSample(String id) {
        if (mc == null || mc.getSoundManager() == null) {
            return false;
        }
        try {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null || !BuiltInRegistries.SOUND_EVENT.containsKey(location)) {
                return false;
            }
            SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(location);
            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0f));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void suppress(boolean value) {
        suppressed = value;
    }

    public static float random(float min, float max) {
        return (float) ((double) min + (double) (max - min) * Math.random());
    }
}
