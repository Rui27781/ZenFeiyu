package shit.zen.modules.impl.client;

import shit.zen.gui.music.MusicPlayerScreen;
import shit.zen.music.MusicManager;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.settings.impl.StringSetting;

/**
 * Entry point for the built-in music player. Opening the module shows the
 * rounded music screen; playback itself lives in {@link MusicManager} so it
 * keeps running after the screen closes.
 */
public class MusicPlayer extends Module {
    public static MusicPlayer INSTANCE;

    public final StringSetting apiSetting = new StringSetting("API", MusicManager.DEFAULT_API, 160) {
        @Override
        public void onChanged(String oldValue, String newValue) {
            MusicManager.getInstance().setApiBase(newValue);
        }
    };

    public final StringSetting playlistSetting = new StringSetting("Playlist ID", MusicManager.DEFAULT_PLAYLIST, 40) {
        @Override
        public void onChanged(String oldValue, String newValue) {
            MusicManager.getInstance().setPlaylistId(newValue);
        }
    };

    public final ModeSetting serverSetting = new ModeSetting("Server", "netease", "tencent", "kugou", "baidu") {
        @Override
        public void onChanged(String oldValue, String newValue) {
            MusicManager.getInstance().setServer(newValue);
        }
    }.withDefault(MusicManager.DEFAULT_SERVER);

    public final NumberSetting volumeSetting = new NumberSetting("Volume", 70, 0, 100, 1) {
        @Override
        public void onChanged(Number oldValue, Number newValue) {
            MusicManager.getInstance().setVolume(newValue.floatValue() / 100.0f);
        }
    };

    public MusicPlayer() {
        super("musicplayer", Category.CLIENT);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        try {
            MusicManager manager = MusicManager.getInstance();
            manager.configure(this.apiSetting.getValue(), this.serverSetting.getValue(), this.playlistSetting.getValue());
            manager.setVolume(this.volumeSetting.getValue().floatValue() / 100.0f);
            manager.loadPlaylistIfEmpty();
            mc.setScreen(new MusicPlayerScreen());
        } finally {
            this.setEnabled(false);
        }
    }

    /** Called by the music screen so the volume slider is persisted in the config. */
    public void setVolumeFromUi(float volume) {
        int value = Math.max(0, Math.min(100, Math.round(volume * 100.0f)));
        this.volumeSetting.setValue(value);
    }
}
