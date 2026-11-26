package shit.zen.utils.misc;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
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
                event = SoundEvents.UI_BUTTON_CLICK.value();
            }
            playEvent(event, volume, pitch);
        } catch (Throwable ignored) {
            // A missing or malformed sound id must never break gameplay.
        }
    }

    /** 统一播放 UI 音效，所有开关音都走这里，保证 1.20.1 一定有声音。 */
    private static void playEvent(SoundEvent event, float volume, float pitch) {
        if (event == null || mc == null || mc.getSoundManager() == null) {
            return;
        }
        try {
            // 1.20.1 的签名是 forUI(event, pitch, volume)，顺序不能写反。
            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
        } catch (Throwable ignored) {
            // Sound must never break a module toggle.
        }
    }

    public static void playUi(String id, float pitch) {
        play(id, 1.0f, pitch);
    }

    /** Plays the configured module-toggle click, unless suppressed during config load. */
    public static void toggle(boolean enabled) {
        Sounds.toggle(enabled, false);
    }

    /**
     * @param force bypass the master module check, used while toggling ToggleSounds itself
     */
    public static void toggle(boolean enabled, boolean force) {
        if (suppressed) {
            return;
        }
        ToggleSounds module = ToggleSounds.INSTANCE;
        if (module == null || (!force && !module.isEnabled()) || !module.soundSetting.getValue()) {
            return;
        }
        String style = module.styleSetting.getValue();
        // 旧版实现会尝试播放 zenfeiyu:toggle_* 自定义资源，但仓库里没有对应
        // sounds.json / ogg，导致 Tick/Smooth/Celestial/Nursultan 默认全部静音。
        // 这里改用 1.20.1 内置且一定注册的 SoundEvent，并用音高区分开关。
        float pitchJitter = random(-0.03f, 0.03f);
        if ("Lever".equals(style)) {
            playEvent(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f, (enabled ? 0.8f : 0.62f) + pitchJitter);
            return;
        }
        if ("Pling".equals(style)) {
            playEvent(SoundEvents.NOTE_BLOCK_PLING.value(), 0.65f, (enabled ? 1.85f : 1.45f) + pitchJitter);
            return;
        }
        if ("Smooth".equals(style)) {
            // EXPERIENCE_ORB_PICKUP 在 1.20.1 是 SoundEvent 而不是 Holder，不能再取 value()。
            playEvent(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, (enabled ? 1.35f : 1.0f) + pitchJitter);
            return;
        }
        if ("Celestial".equals(style)) {
            playEvent(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.7f, (enabled ? 1.65f : 1.15f) + pitchJitter);
            return;
        }
        if ("Nursultan".equals(style)) {
            playEvent(SoundEvents.NOTE_BLOCK_BELL.value(), 0.6f, (enabled ? 1.25f : 0.9f) + pitchJitter);
            return;
        }
        // Tick 是默认风格：轻微、短促，开启和关闭音高不同。
        playEvent(SoundEvents.UI_BUTTON_CLICK.value(), 0.85f, (enabled ? 1.22f : 0.92f) + pitchJitter);
    }

    public static void suppress(boolean value) {
        suppressed = value;
    }

    public static float random(float min, float max) {
        return (float) ((double) min + (double) (max - min) * Math.random());
    }
}
