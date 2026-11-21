package shit.zen.music;

/**
 * One track returned by the Meting API. The cover/audio/lyric URLs are plain
 * HTTP(S) endpoints, so they can be fetched asynchronously without any extra
 * metadata requests.
 */
public final class Song {
    private final String id;
    private final String title;
    private final String artist;
    private final String coverUrl;
    private final String audioUrl;
    private final String lyricUrl;
    private volatile long durationMs;

    public Song(String id, String title, String artist, String coverUrl, String audioUrl, String lyricUrl, long durationMs) {
        this.id = id == null ? "" : id;
        this.title = title == null || title.isEmpty() ? "Unknown" : title;
        this.artist = artist == null || artist.isEmpty() ? "Unknown artist" : artist;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.audioUrl = audioUrl == null ? "" : audioUrl;
        this.lyricUrl = lyricUrl == null ? "" : lyricUrl;
        this.durationMs = Math.max(0L, durationMs);
    }

    public String getId() {
        return this.id;
    }

    public String getTitle() {
        return this.title;
    }

    public String getArtist() {
        return this.artist;
    }

    public String getCoverUrl() {
        return this.coverUrl;
    }

    public String getAudioUrl() {
        return this.audioUrl;
    }

    public String getLyricUrl() {
        return this.lyricUrl;
    }

    public long getDurationMs() {
        return this.durationMs;
    }

    public void setDurationMs(long durationMs) {
        if (durationMs > 0L) {
            this.durationMs = durationMs;
        }
    }

    /** Stable cache key for the downloaded MP3 and the cover texture. */
    public String getCacheKey() {
        if (!this.id.isEmpty()) {
            return this.id;
        }
        return Integer.toHexString(this.audioUrl.hashCode());
    }
}
