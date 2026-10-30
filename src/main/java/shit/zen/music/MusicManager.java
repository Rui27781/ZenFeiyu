package shit.zen.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.BufferUtils;
import shit.zen.ClientBase;
import shit.zen.ZenClient;
import shit.zen.render.ResourceLocationWrapper;

/**
 * Music data + playback layer. All network work happens on daemon worker
 * threads; the game thread only receives completed lists and textures.
 *
 * <p>Data comes from a Meting-compatible endpoint (playlist / search / cover
 * URLs). Audio is decoded by {@link Mp3Player} (JLayer + SourceDataLine).</p>
 */
public final class MusicManager extends ClientBase {
    public static final String DEFAULT_API = "https://met.liiiu.cn/meting/api";
    public static final String DEFAULT_SERVER = "netease";
    public static final String DEFAULT_PLAYLIST = "3778678";

    private static final MusicManager INSTANCE = new MusicManager();
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final Pattern ID_PATTERN = Pattern.compile("[?&]id=([^&]+)");
    private static final long MAX_AUDIO_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_IMAGE_BYTES = 16L * 1024L * 1024L;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "OpenZen-Music-Worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Mp3Player player = new Mp3Player();
    private final Map<String, ResourceLocationWrapper> covers = new ConcurrentHashMap<>();
    private final Set<String> pendingCovers = ConcurrentHashMap.newKeySet();

    private volatile String apiBase = DEFAULT_API;
    private volatile String server = DEFAULT_SERVER;
    private volatile String playlistId = DEFAULT_PLAYLIST;

    private volatile List<Song> playlist = Collections.emptyList();
    private volatile List<Song> searchResults = Collections.emptyList();
    private volatile List<Song> queue = Collections.emptyList();
    private volatile Song currentSong;
    private volatile int currentIndex = -1;

    private volatile boolean loadingPlaylist;
    private volatile boolean searching;
    private volatile boolean loadingTrack;
    private volatile String status = "";

    private volatile int playlistToken;
    private volatile int searchToken;
    private volatile int trackToken;

    private MusicManager() {
        this.player.setListener(new Mp3Player.Listener() {
            @Override
            public void onFinished() {
                MusicManager.this.handleFinished();
            }

            @Override
            public void onError(String message) {
                MusicManager.this.handlePlayerError(message);
            }
        });
    }

    public static MusicManager getInstance() {
        return INSTANCE;
    }

    public String getApiBase() {
        return this.apiBase;
    }

    public void setApiBase(String apiBase) {
        if (apiBase != null && !apiBase.trim().isEmpty()) {
            this.apiBase = apiBase.trim();
        }
    }

    public String getServer() {
        return this.server;
    }

    public void setServer(String server) {
        if (server != null && !server.trim().isEmpty()) {
            this.server = server.trim();
        }
    }

    public String getPlaylistId() {
        return this.playlistId;
    }

    public void setPlaylistId(String playlistId) {
        if (playlistId != null && !playlistId.trim().isEmpty()) {
            this.playlistId = playlistId.trim();
        }
    }

    public void configure(String apiBase, String server, String playlistId) {
        String previousApi = this.apiBase;
        String previousServer = this.server;
        String previousPlaylist = this.playlistId;
        this.setApiBase(apiBase);
        this.setServer(server);
        this.setPlaylistId(playlistId);
        if (!previousApi.equals(this.apiBase)
                || !previousServer.equals(this.server)
                || !previousPlaylist.equals(this.playlistId)) {
            // Drop the cached list so the next load uses the new endpoint.
            ++this.playlistToken;
            this.loadingPlaylist = false;
            this.playlist = Collections.emptyList();
            this.status = "";
        }
    }

    public List<Song> getPlaylist() {
        return this.playlist;
    }

    public List<Song> getSearchResults() {
        return this.searchResults;
    }

    public List<Song> getQueue() {
        return this.queue;
    }

    public Song getCurrentSong() {
        return this.currentSong;
    }

    public int getCurrentIndex() {
        return this.currentIndex;
    }

    public boolean isLoadingPlaylist() {
        return this.loadingPlaylist;
    }

    public boolean isSearching() {
        return this.searching;
    }

    public boolean isLoadingTrack() {
        return this.loadingTrack;
    }

    public String getStatus() {
        return this.status;
    }

    public boolean isPlaying() {
        return this.player.isRunning() && !this.player.isPaused();
    }

    public boolean isPaused() {
        return this.player.isRunning() && this.player.isPaused();
    }

    public boolean isActive() {
        return this.isPlaying() || this.isPaused() || this.loadingTrack;
    }

    public long getPositionMs() {
        return this.player.isRunning() ? this.player.getPositionMs() : 0L;
    }

    public long getDurationMs() {
        long duration = this.player.getDurationMs();
        if (duration > 0L) {
            Song song = this.currentSong;
            if (song != null) {
                song.setDurationMs(duration);
            }
            return duration;
        }
        Song song = this.currentSong;
        return song == null ? 0L : song.getDurationMs();
    }

    public float getProgress() {
        long duration = this.getDurationMs();
        if (duration <= 0L) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, (float) this.getPositionMs() / (float) duration));
    }

    public float getVolume() {
        return this.player.getVolume();
    }

    public void setVolume(float volume) {
        this.player.setVolume(volume);
    }

    public void loadPlaylistIfEmpty() {
        if (this.playlist.isEmpty() && !this.loadingPlaylist) {
            this.loadPlaylist();
        }
    }

    public void loadPlaylist() {
        if (this.loadingPlaylist) {
            return;
        }
        int token = ++this.playlistToken;
        this.loadingPlaylist = true;
        this.status = "Loading playlist...";
        String url = this.endpoint("server=" + this.encode(this.server)
                + "&type=playlist&id=" + this.encode(this.playlistId));

        this.executor.execute(() -> {
            String body = null;
            String error = null;
            try {
                body = this.get(url, MAX_AUDIO_BYTES);
            } catch (Throwable throwable) {
                error = throwable.getMessage();
            }
            String response = body;
            String failure = error;
            if (mc == null) {
                return;
            }
            mc.execute(() -> {
                if (token != this.playlistToken) {
                    return;
                }
                this.loadingPlaylist = false;
                if (response == null) {
                    this.status = "Playlist failed" + (failure == null ? "" : ": " + failure);
                    return;
                }
                List<Song> parsed = this.parseSongs(response);
                this.playlist = List.copyOf(parsed);
                this.status = parsed.isEmpty() ? "Playlist is empty" : "";
            });
        });
    }

    public void search(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            this.searchResults = Collections.emptyList();
            this.searching = false;
            return;
        }
        int token = ++this.searchToken;
        this.searching = true;
        this.status = "Searching...";
        String url = this.endpoint("server=" + this.encode(this.server)
                + "&type=search&id=" + this.encode(normalized) + "&limit=30");

        this.executor.execute(() -> {
            String body = null;
            String error = null;
            try {
                body = this.get(url, MAX_AUDIO_BYTES);
            } catch (Throwable throwable) {
                error = throwable.getMessage();
            }
            String response = body;
            String failure = error;
            if (mc == null) {
                return;
            }
            mc.execute(() -> {
                if (token != this.searchToken) {
                    return;
                }
                this.searching = false;
                if (response == null) {
                    this.status = "Search failed" + (failure == null ? "" : ": " + failure);
                    return;
                }
                List<Song> parsed = this.parseSongs(response);
                this.searchResults = List.copyOf(parsed);
                this.status = parsed.isEmpty() ? "No results" : "";
            });
        });
    }

    public void clearSearch() {
        ++this.searchToken;
        this.searching = false;
        this.searchResults = Collections.emptyList();
    }

    public void playSong(Song song, List<Song> source) {
        if (song == null) {
            return;
        }
        List<Song> newQueue = source == null || source.isEmpty() ? this.playlist : new ArrayList<>(source);
        int index = newQueue.indexOf(song);
        if (index < 0) {
            newQueue = new ArrayList<>(this.playlist);
            index = newQueue.indexOf(song);
        }
        if (index < 0) {
            newQueue = List.of(song);
            index = 0;
        }

        this.queue = List.copyOf(newQueue);
        this.currentSong = song;
        this.currentIndex = index;
        this.loadingTrack = true;
        this.status = "Loading track...";

        int token = ++this.trackToken;
        this.player.stop();

        this.executor.execute(() -> {
            File file = null;
            String error = null;
            try {
                file = this.downloadAudio(song);
            } catch (Throwable throwable) {
                error = throwable.getMessage();
            }
            File downloaded = file;
            String failure = error;
            if (mc == null) {
                return;
            }
            mc.execute(() -> {
                if (token != this.trackToken) {
                    return;
                }
                this.loadingTrack = false;
                if (downloaded == null) {
                    this.status = "Playback failed" + (failure == null ? "" : ": " + failure);
                    return;
                }
                this.status = "";
                this.player.play(downloaded, 0L);
            });
        });
    }

    public void togglePlayPause() {
        if (this.player.isRunning()) {
            this.player.togglePause();
            return;
        }
        Song song = this.currentSong;
        if (song != null) {
            this.playSong(song, this.queue);
            return;
        }
        List<Song> list = this.playlist;
        if (!list.isEmpty()) {
            this.playSong(list.get(0), list);
        }
    }

    public void next() {
        List<Song> list = this.queue;
        if (list.isEmpty()) {
            return;
        }
        int index = this.currentIndex + 1;
        if (index < 0 || index >= list.size()) {
            index = 0;
        }
        this.playSong(list.get(index), list);
    }

    public void previous() {
        List<Song> list = this.queue;
        if (list.isEmpty()) {
            return;
        }
        int index = this.currentIndex - 1;
        if (index < 0) {
            index = list.size() - 1;
        }
        this.playSong(list.get(index), list);
    }

    public void seek(float progress) {
        long duration = this.getDurationMs();
        if (duration <= 0L) {
            return;
        }
        boolean wasPaused = this.player.isPaused();
        this.player.seek((long) (Math.max(0.0f, Math.min(1.0f, progress)) * duration));
        if (wasPaused) {
            this.player.pause();
        }
    }

    public void stopPlayback() {
        ++this.trackToken;
        this.player.stop();
        this.loadingTrack = false;
        this.currentSong = null;
        this.currentIndex = -1;
        this.queue = Collections.emptyList();
        this.status = "";
    }

    /**
     * Returns a registered cover texture, or null while the image is still
     * downloading. Only visible rows call this, so a 200-track playlist does
     * not trigger 200 image requests at once.
     */
    public ResourceLocationWrapper getCover(Song song) {
        if (song == null) {
            return null;
        }
        String url = song.getCoverUrl();
        if (url.isEmpty()) {
            return null;
        }
        ResourceLocationWrapper existing = this.covers.get(url);
        if (existing != null) {
            return existing;
        }
        if (this.pendingCovers.add(url)) {
            this.executor.execute(() -> this.downloadCover(url));
        }
        return null;
    }

    private void downloadCover(String url) {
        BufferedImage image = null;
        try {
            byte[] bytes = this.getBytes(url, MAX_IMAGE_BYTES);
            if (bytes != null && bytes.length > 0) {
                image = ImageIO.read(new ByteArrayInputStream(bytes));
            }
        } catch (Throwable ignored) {
        }
        if (image == null) {
            this.pendingCovers.remove(url);
            return;
        }
        BufferedImage downloaded = image;
        if (mc == null) {
            this.pendingCovers.remove(url);
            return;
        }
        mc.execute(() -> {
            try {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(downloaded, "png", png);
                byte[] bytes = png.toByteArray();
                ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
                buffer.put(bytes);
                buffer.flip();
                NativeImage nativeImage = NativeImage.read(buffer);
                ResourceLocationWrapper wrapper = new ResourceLocationWrapper(
                        "music/cover_" + Integer.toHexString(url.hashCode()));
                DynamicTexture texture = new DynamicTexture(nativeImage);
                mc.getTextureManager().register(wrapper.get(), texture);
                this.covers.put(url, wrapper);
            } catch (Throwable ignored) {
            } finally {
                this.pendingCovers.remove(url);
            }
        });
    }

    private void handleFinished() {
        List<Song> list = this.queue;
        if (list.isEmpty()) {
            return;
        }
        int index = this.currentIndex + 1;
        if (index < 0 || index >= list.size()) {
            index = 0;
        }
        this.playSong(list.get(index), list);
    }

    private void handlePlayerError(String message) {
        this.loadingTrack = false;
        this.status = "Playback error" + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private List<Song> parseSongs(String json) {
        List<Song> songs = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return songs;
            }
            JsonArray array = root.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String title = this.stringValue(object, "title", "name");
                String artist = this.stringValue(object, "author", "artist");
                String audioUrl = this.stringValue(object, "url");
                String coverUrl = this.stringValue(object, "pic");
                String lyricUrl = this.stringValue(object, "lrc");
                String id = this.stringValue(object, "id");
                if (id.isEmpty() && !audioUrl.isEmpty()) {
                    Matcher matcher = ID_PATTERN.matcher(audioUrl);
                    if (matcher.find()) {
                        id = matcher.group(1);
                    }
                }
                if (audioUrl.isEmpty() && !id.isEmpty()) {
                    audioUrl = this.endpoint("server=" + this.encode(this.server)
                            + "&type=url&id=" + this.encode(id));
                }
                long durationMs = this.durationValue(object);
                songs.add(new Song(id, title, artist, coverUrl, audioUrl, lyricUrl, durationMs));
            }
        } catch (Throwable ignored) {
        }
        return songs;
    }

    private String stringValue(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                try {
                    return object.get(key).getAsString();
                } catch (Throwable ignored) {
                }
            }
        }
        return "";
    }

    private long durationValue(JsonObject object) {
        for (String key : new String[]{"duration", "interval", "length", "time"}) {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                continue;
            }
            try {
                double value = object.get(key).getAsDouble();
                if (value > 10000.0) {
                    value /= 1000.0;
                }
                if (value > 0.0 && value < 7200.0) {
                    return (long) (value * 1000.0);
                }
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private String endpoint(String query) {
        String base = this.apiBase;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + (base.contains("?") ? "&" : "?") + query;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String get(String url, long limit) throws IOException {
        byte[] bytes = this.getBytes(url, limit);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] getBytes(String url, long limit) throws IOException {
        HttpURLConnection connection = this.openConnection(url);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    total += read;
                    if (total > limit) {
                        throw new IOException("Response is too large");
                    }
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private File downloadAudio(Song song) throws IOException {
        String url = song.getAudioUrl();
        if (url.isEmpty()) {
            throw new IOException("No audio URL");
        }
        File directory = new File(ZenClient.configDir, "music_cache");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create music cache directory");
        }
        File target = new File(directory, this.safeFileName(song.getCacheKey()) + ".mp3");
        if (target.isFile() && target.length() > 4096L && looksLikeMp3(target)) {
            return target;
        }

        File temporary = new File(directory, target.getName() + ".part");
        HttpURLConnection connection = this.openConnection(url);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    total += read;
                    if (total > MAX_AUDIO_BYTES) {
                        throw new IOException("Audio file is too large");
                    }
                }
            }
        } finally {
            connection.disconnect();
        }

        if (!looksLikeMp3(temporary)) {
            temporary.delete();
            throw new IOException("Server did not return an MP3");
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable throwable) {
            temporary.delete();
            throw new IOException("Could not save audio file", throwable);
        }
        return target;
    }

    private HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", "netease".equals(this.server)
                ? "https://music.163.com/" : "https://y.qq.com/");
        return connection;
    }

    private static boolean looksLikeMp3(File file) {
        try (InputStream input = new FileInputStream(file)) {
            byte[] header = input.readNBytes(3);
            if (header.length < 3) {
                return false;
            }
            if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                return true;
            }
            int first = header[0] & 0xFF;
            int second = header[1] & 0xFF;
            return first == 0xFF && (second & 0xE0) == 0xE0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String safeFileName(String value) {
        String sanitized = value == null ? "track" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        if (sanitized.isEmpty()) {
            sanitized = "track";
        }
        return sanitized.length() > 80 ? sanitized.substring(0, 80) : sanitized;
    }
}
