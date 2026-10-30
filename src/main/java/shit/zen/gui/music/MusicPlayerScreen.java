package shit.zen.gui.music;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.Component;
import shit.zen.music.MusicManager;
import shit.zen.music.MusicUtil;
import shit.zen.music.Song;
import shit.zen.modules.impl.client.MusicPlayer;
import shit.zen.render.DrawContext;
import shit.zen.render.FontStore;
import shit.zen.render.ResourceLocationWrapper;
import shit.zen.render.RoundedRectShader;
import shit.zen.gui.newclickgui.Theme;
import shit.zen.utils.misc.CursorUtil;
import shit.zen.utils.render.ColorUtil;
import shit.zen.utils.render.RenderUtil;

/**
 * Rounded, native music browser. Playlist rows, search results, the search box
 * and the now-playing footer are all drawn with the client's rounded-rect
 * shader, so no HTML/JavaFX window is needed.
 */
public class MusicPlayerScreen extends Screen {
    private static final float MAX_PANEL_WIDTH = 460.0f;
    private static final float MAX_PANEL_HEIGHT = 548.0f;
    private static final float SEARCH_HEIGHT = 30.0f;
    private static final float ROW_HEIGHT = 46.0f;
    private static final float ROW_GAP = 4.0f;
    private static final float FOOTER_HEIGHT = 118.0f;
    private static final float COVER_SIZE = 52.0f;
    private static final int MAX_QUERY_LENGTH = 40;
    private static final long SEARCH_DEBOUNCE_MS = 450L;

    private float panelX;
    private float panelY;
    private float panelWidth;
    private float panelHeight;
    private float searchX;
    private float searchY;
    private float searchWidth;
    private float listX;
    private float listY;
    private float listWidth;
    private float listHeight;
    private float footerY;

    private String query = "";
    private String lastSearchQuery = "";
    private boolean searchFocused;
    private long lastTypedAt;
    private float scroll;
    private boolean draggingProgress;
    private float draggedProgress;
    private boolean draggingVolume;

    public MusicPlayerScreen() {
        super(Component.literal("Music Player"));
    }

    @Override
    protected void init() {
        super.init();
        this.panelWidth = Math.min(MAX_PANEL_WIDTH, this.width - 32.0f);
        this.panelHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(240.0f, this.height - 32.0f));
        this.panelX = (this.width - this.panelWidth) / 2.0f;
        this.panelY = (this.height - this.panelHeight) / 2.0f;

        this.searchX = this.panelX + 16.0f;
        this.searchY = this.panelY + 58.0f;
        this.searchWidth = this.panelWidth - 32.0f;

        this.footerY = this.panelY + this.panelHeight - FOOTER_HEIGHT;
        this.listX = this.panelX + 16.0f;
        this.listY = this.panelY + 106.0f;
        this.listWidth = this.panelWidth - 32.0f;
        this.listHeight = Math.max(0.0f, this.footerY - this.listY - 8.0f);

        MusicManager.getInstance().loadPlaylistIfEmpty();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        PoseStack poseStack = guiGraphics.pose();

        this.drawPanel(poseStack);
        this.drawHeader(poseStack, mouseX, mouseY);
        this.drawSearch(poseStack, mouseX, mouseY);
        this.drawList(poseStack, mouseX, mouseY);
        this.drawFooter(poseStack, mouseX, mouseY);
        this.tickSearchDebounce();
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics) {
        guiGraphics.fill(0, 0, this.width, this.height, 0x88000000);
    }

    private void drawPanel(PoseStack poseStack) {
        RenderUtil.drawRoundedRect(poseStack, this.panelX, this.panelY, this.panelWidth, this.panelHeight, 14.0f,
                new Color(255, 255, 255, 38).getRGB());
        RenderUtil.drawRoundedRect(poseStack, this.panelX + 1.0f, this.panelY + 1.0f,
                this.panelWidth - 2.0f, this.panelHeight - 2.0f, 13.0f,
                new Color(20, 22, 28, 246).getRGB());
        RenderUtil.drawRoundedRectCorners(poseStack, this.panelX + 1.0f, this.panelY + 1.0f,
                this.panelWidth - 2.0f, 54.0f, 13.0f, true, true, false, false,
                new Color(30, 34, 44, 230).getRGB());
    }

    private void drawHeader(PoseStack poseStack, int mouseX, int mouseY) {
        FontStore.AXIFORMA_BOLD_18.drawString(poseStack, "Music Player",
                this.panelX + 18.0f, this.panelY + 13.0f, 0xFFFFFFFF);
        FontStore.PINGFANG_16.drawString(poseStack, "大肥鱼 · 在线音乐",
                this.panelX + 18.0f, this.panelY + 33.0f, ColorUtil.withAlpha(Theme.accent(), 0.9f));

        float closeX = this.panelX + this.panelWidth - 36.0f;
        float closeY = this.panelY + 12.0f;
        boolean closeHovered = CursorUtil.isInBounds(mouseX, mouseY, closeX, closeY, 24.0f, 24.0f);
        RenderUtil.drawRoundedRect(poseStack, closeX, closeY, 24.0f, 24.0f, 7.0f,
                closeHovered ? 0x33FFFFFF : 0x14FFFFFF);
        FontStore.MATERIAL_20.drawStringCentered(poseStack, "\ue5cd",
                closeX + 12.0f, closeY + (24.0f - FontStore.MATERIAL_20.getFontHeight()) / 2.0f,
                closeHovered ? 0xFFFFFFFF : 0xAAFFFFFF);
    }

    private void drawSearch(PoseStack poseStack, int mouseX, int mouseY) {
        int outline = this.searchFocused ? ColorUtil.withAlpha(Theme.accent(), 0.85f) : 0x22FFFFFF;
        RenderUtil.drawRoundedRect(poseStack, this.searchX, this.searchY, this.searchWidth, SEARCH_HEIGHT, 8.0f, outline);
        RenderUtil.drawRoundedRect(poseStack, this.searchX + 1.0f, this.searchY + 1.0f,
                this.searchWidth - 2.0f, SEARCH_HEIGHT - 2.0f, 7.0f, 0xFF23262E);

        FontStore.MATERIAL_20.drawString(poseStack, "\ue8b6", this.searchX + 10.0f,
                this.searchY + (SEARCH_HEIGHT - FontStore.MATERIAL_20.getFontHeight()) / 2.0f,
                this.searchFocused ? ColorUtil.withAlpha(Theme.accent(), 1.0f) : 0x88FFFFFF);

        float textX = this.searchX + 32.0f;
        float textY = this.searchY + (SEARCH_HEIGHT - FontStore.AXIFORMA_REGULAR_16.getFontHeight()) / 2.0f;
        if (this.query.isEmpty() && !this.searchFocused) {
            FontStore.AXIFORMA_REGULAR_16.drawString(poseStack, "Search songs or artists...",
                    textX, textY, 0x66FFFFFF);
        } else {
            FontStore.AXIFORMA_REGULAR_16.drawString(poseStack, this.query, textX, textY, 0xE6FFFFFF);
            if (this.searchFocused && System.currentTimeMillis() % 1000L < 500L) {
                float cursorX = textX + FontStore.AXIFORMA_REGULAR_16.getStringWidth(this.query) + 1.0f;
                RenderUtil.drawFilledRect(poseStack, cursorX, textY, 1.0f,
                        FontStore.AXIFORMA_REGULAR_16.getFontHeight(), 0xFFFFFFFF);
            }
        }

        if (!this.query.isEmpty()) {
            float clearX = this.searchX + this.searchWidth - 24.0f;
            float clearY = this.searchY + (SEARCH_HEIGHT - 16.0f) / 2.0f;
            boolean hovered = CursorUtil.isInBounds(mouseX, mouseY, clearX, clearY, 16.0f, 16.0f);
            RenderUtil.drawRoundedRect(poseStack, clearX, clearY, 16.0f, 16.0f, 5.0f,
                    hovered ? 0x33FFFFFF : 0x14FFFFFF);
            FontStore.AXIFORMA_BOLD_13.drawStringCentered(poseStack, "x", clearX + 8.0f,
                    clearY + (16.0f - FontStore.AXIFORMA_BOLD_13.getFontHeight()) / 2.0f, 0xCCFFFFFF);
        }
    }

    private void drawList(PoseStack poseStack, int mouseX, int mouseY) {
        MusicManager manager = MusicManager.getInstance();
        List<Song> songs = this.displayedSongs();

        String label = this.query.trim().isEmpty() ? "PLAYLIST" : "SEARCH RESULTS";
        FontStore.AXIFORMA_BOLD_13.drawString(poseStack, label, this.listX + 2.0f, this.panelY + 90.0f,
                ColorUtil.withAlpha(Theme.accent(), 0.95f));
        String count = songs.size() + " tracks";
        float countWidth = FontStore.AXIFORMA_REGULAR_14.getStringWidth(count);
        FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, count,
                this.listX + this.listWidth - countWidth - 24.0f, this.panelY + 90.0f, 0x77FFFFFF);

        float refreshX = this.listX + this.listWidth - 16.0f;
        float refreshY = this.panelY + 88.0f;
        boolean refreshHovered = CursorUtil.isInBounds(mouseX, mouseY, refreshX, refreshY, 16.0f, 16.0f);
        FontStore.MATERIAL_20.drawStringCentered(poseStack, "\ue5d5", refreshX + 8.0f,
                refreshY + (16.0f - FontStore.MATERIAL_20.getFontHeight()) / 2.0f,
                refreshHovered ? 0xFFFFFFFF : 0x77FFFFFF);

        if (songs.isEmpty()) {
            String empty;
            if (manager.isLoadingPlaylist() || manager.isSearching()) {
                empty = "Loading...";
            } else if (this.query.trim().isEmpty()) {
                empty = "Playlist is empty";
            } else {
                empty = "No results";
            }
            FontStore.PINGFANG_16.drawStringCentered(poseStack, empty,
                    this.listX + this.listWidth / 2.0f, this.listY + this.listHeight / 2.0f - 8.0f, 0x77FFFFFF);
            if (!manager.getStatus().isEmpty()) {
                FontStore.AXIFORMA_REGULAR_14.drawStringCentered(poseStack, manager.getStatus(),
                        this.listX + this.listWidth / 2.0f, this.listY + this.listHeight / 2.0f + 12.0f,
                        0x55FFFFFF);
            }
            return;
        }

        float contentHeight = songs.size() * (ROW_HEIGHT + ROW_GAP);
        float maxScroll = Math.max(0.0f, contentHeight - this.listHeight);
        this.scroll = Math.max(0.0f, Math.min(this.scroll, maxScroll));

        RenderUtil.pushScissor((int) this.listX, (int) this.listY, (int) this.listWidth, (int) this.listHeight);
        Song current = manager.getCurrentSong();
        for (int i = 0; i < songs.size(); i++) {
            Song song = songs.get(i);
            float rowY = this.listY + i * (ROW_HEIGHT + ROW_GAP) - this.scroll;
            if (rowY + ROW_HEIGHT < this.listY || rowY > this.listY + this.listHeight) {
                continue;
            }
            boolean hovered = CursorUtil.isInBounds(mouseX, mouseY, this.listX, rowY, this.listWidth, ROW_HEIGHT)
                    && CursorUtil.isInBounds(mouseX, mouseY, this.listX, this.listY, this.listWidth, this.listHeight);
            boolean isCurrent = song == current;
            int background = isCurrent
                    ? ColorUtil.withAlpha(Theme.accent(), 0.18f)
                    : hovered ? 0x1FFFFFFF : 0x0CFFFFFF;
            RenderUtil.drawRoundedRect(poseStack, this.listX, rowY, this.listWidth, ROW_HEIGHT, 8.0f, background);
            if (isCurrent) {
                RenderUtil.drawRoundedRect(poseStack, this.listX + 3.0f, rowY + 11.0f, 2.0f,
                        ROW_HEIGHT - 22.0f, 1.0f, Theme.accent());
            }

            this.drawCover(poseStack, song, this.listX + 10.0f, rowY + 7.0f, 32.0f, 6.0f);

            float textX = this.listX + 52.0f;
            float maxTextWidth = this.listWidth - 52.0f - 60.0f;
            FontStore.PINGFANG_16.drawString(poseStack,
                    MusicUtil.trim(FontStore.PINGFANG_16, song.getTitle(), maxTextWidth),
                    textX, rowY + 7.0f, isCurrent ? 0xFFFFFFFF : 0xE6FFFFFF);
            FontStore.PINGFANG_12.drawString(poseStack,
                    MusicUtil.trim(FontStore.PINGFANG_12, song.getArtist(), maxTextWidth),
                    textX, rowY + 26.0f, isCurrent ? 0xB3FFFFFF : 0x77FFFFFF);

            String duration = song.getDurationMs() > 0L ? MusicUtil.formatTime(song.getDurationMs()) : "";
            if (!duration.isEmpty()) {
                float durationWidth = FontStore.AXIFORMA_REGULAR_14.getStringWidth(duration);
                FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, duration,
                        this.listX + this.listWidth - durationWidth - 12.0f, rowY + 16.0f,
                        isCurrent ? 0xAAFFFFFF : 0x66FFFFFF);
            }
        }
        RenderUtil.popScissor();

        if (!manager.getStatus().isEmpty()) {
            FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, manager.getStatus(),
                    this.listX + 2.0f, this.footerY - 14.0f, 0x66FFFFFF);
        }
    }

    private void drawFooter(PoseStack poseStack, int mouseX, int mouseY) {
        MusicManager manager = MusicManager.getInstance();
        RenderUtil.drawFilledRect(poseStack, this.panelX + 16.0f, this.footerY,
                this.panelWidth - 32.0f, 1.0f, 0x22FFFFFF);

        Song song = manager.getCurrentSong();
        float coverX = this.panelX + 16.0f;
        float coverY = this.footerY + 12.0f;
        if (song != null) {
            this.drawCover(poseStack, song, coverX, coverY, COVER_SIZE, 9.0f);
        } else {
            RenderUtil.drawRoundedRect(poseStack, coverX, coverY, COVER_SIZE, COVER_SIZE, 9.0f, 0xFF272A33);
            FontStore.MATERIAL_20.drawStringCentered(poseStack, "\ue405", coverX + COVER_SIZE / 2.0f,
                    coverY + (COVER_SIZE - FontStore.MATERIAL_20.getFontHeight()) / 2.0f, 0x55FFFFFF);
        }

        float textX = coverX + COVER_SIZE + 12.0f;
        float textWidth = this.panelX + this.panelWidth - 32.0f - textX;
        if (song != null) {
            FontStore.PINGFANG_16.drawString(poseStack,
                    MusicUtil.trim(FontStore.PINGFANG_16, song.getTitle(), textWidth),
                    textX, this.footerY + 13.0f, 0xFFFFFFFF);
            FontStore.PINGFANG_12.drawString(poseStack,
                    MusicUtil.trim(FontStore.PINGFANG_12, song.getArtist(), textWidth),
                    textX, this.footerY + 32.0f, 0x99FFFFFF);
        } else {
            FontStore.PINGFANG_16.drawString(poseStack, "Nothing playing", textX, this.footerY + 13.0f, 0xCCFFFFFF);
            FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, "Select a track from the list",
                    textX, this.footerY + 31.0f, 0x66FFFFFF);
        }

        float barX = textX;
        float barY = this.footerY + 47.0f;
        float barWidth = this.panelX + this.panelWidth - 32.0f - barX;
        float progress = this.draggingProgress ? this.draggedProgress : manager.getProgress();
        RenderUtil.drawRoundedRect(poseStack, barX, barY, barWidth, 4.0f, 2.0f, 0x33FFFFFF);
        if (progress > 0.0f) {
            RenderUtil.drawRoundedRect(poseStack, barX, barY, Math.max(2.0f, barWidth * progress), 4.0f, 2.0f,
                    Theme.accent());
        }
        if (progress > 0.0f) {
            float knobX = barX + barWidth * progress;
            RenderUtil.drawRoundedRect(poseStack, knobX - 2.0f, barY - 2.0f, 4.0f, 8.0f, 2.0f, 0xFFFFFFFF);
        }

        long duration = manager.getDurationMs();
        long position = this.draggingProgress && duration > 0L
                ? (long) (duration * this.draggedProgress) : manager.getPositionMs();
        String positionText = MusicUtil.formatTime(position);
        String durationText = duration > 0L ? MusicUtil.formatTime(duration) : "--:--";
        FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, positionText, barX, barY + 8.0f, 0x77FFFFFF);
        float durationWidth = FontStore.AXIFORMA_REGULAR_14.getStringWidth(durationText);
        FontStore.AXIFORMA_REGULAR_14.drawString(poseStack, durationText, barX + barWidth - durationWidth,
                barY + 8.0f, 0x77FFFFFF);

        float controlsY = this.footerY + 73.0f;
        boolean prevHovered = CursorUtil.isInBounds(mouseX, mouseY, textX + 2.0f, controlsY, 26.0f, 26.0f);
        boolean playHovered = CursorUtil.isInBounds(mouseX, mouseY, textX + 36.0f, controlsY - 4.0f, 34.0f, 34.0f);
        boolean nextHovered = CursorUtil.isInBounds(mouseX, mouseY, textX + 78.0f, controlsY, 26.0f, 26.0f);

        this.drawControl(poseStack, textX + 2.0f, controlsY, 26.0f, "\ue045", prevHovered, false);
        String playIcon = manager.isPlaying() ? "\ue034" : "\ue037";
        this.drawControl(poseStack, textX + 36.0f, controlsY - 4.0f, 34.0f, playIcon, playHovered, true);
        this.drawControl(poseStack, textX + 78.0f, controlsY, 26.0f, "\ue044", nextHovered, false);

        float volumeIconX = this.panelX + this.panelWidth - 104.0f;
        float volumeBarX = volumeIconX + 20.0f;
        float volumeBarY = controlsY + 11.0f;
        float volumeBarWidth = 64.0f;
        FontStore.MATERIAL_20.drawString(poseStack, "\ue050", volumeIconX, volumeBarY - 8.0f, 0x99FFFFFF);
        RenderUtil.drawRoundedRect(poseStack, volumeBarX, volumeBarY, volumeBarWidth, 4.0f, 2.0f, 0x33FFFFFF);
        float volume = manager.getVolume();
        RenderUtil.drawRoundedRect(poseStack, volumeBarX, volumeBarY,
                Math.max(2.0f, volumeBarWidth * volume), 4.0f, 2.0f, Theme.accent());
        RenderUtil.drawRoundedRect(poseStack, volumeBarX + volumeBarWidth * volume - 2.0f,
                volumeBarY - 2.0f, 4.0f, 8.0f, 2.0f, 0xFFFFFFFF);
    }

    private void drawControl(PoseStack poseStack, float x, float y, float size, String icon,
                             boolean hovered, boolean primary) {
        if (primary) {
            RenderUtil.drawRoundedRect(poseStack, x, y, size, size, size / 2.0f,
                    ColorUtil.withAlpha(Theme.accent(), hovered ? 1.0f : 0.88f));
            FontStore.MATERIAL_20.drawStringCentered(poseStack, icon, x + size / 2.0f,
                    y + (size - FontStore.MATERIAL_20.getFontHeight()) / 2.0f, 0xFF11141A);
            return;
        }
        if (hovered) {
            RenderUtil.drawRoundedRect(poseStack, x, y, size, size, 7.0f, 0x1FFFFFFF);
        }
        FontStore.MATERIAL_20.drawStringCentered(poseStack, icon, x + size / 2.0f,
                y + (size - FontStore.MATERIAL_20.getFontHeight()) / 2.0f, hovered ? 0xFFFFFFFF : 0xBBFFFFFF);
    }

    private void drawCover(PoseStack poseStack, Song song, float x, float y, float size, float radius) {
        ResourceLocationWrapper wrapper = MusicManager.getInstance().getCover(song);
        if (wrapper != null && this.minecraft != null) {
            AbstractTexture texture = this.minecraft.getTextureManager().getTexture(wrapper.get());
            if (texture != null) {
                RoundedRectShader shader = DrawContext.getRoundedRectShader();
                shader.drawTextured(poseStack.last().pose(), x, y, x + size, y + size,
                        radius, radius, radius, radius, 0xFFFFFFFF, texture.getId(), 0.0f, 0.0f, 1.0f, 1.0f);
                return;
            }
        }
        RenderUtil.drawRoundedRect(poseStack, x, y, size, size, radius, 0xFF2A2D36);
        FontStore.MATERIAL_20.drawStringCentered(poseStack, "\ue405", x + size / 2.0f,
                y + (size - FontStore.MATERIAL_20.getFontHeight()) / 2.0f, 0x55FFFFFF);
    }

    private List<Song> displayedSongs() {
        MusicManager manager = MusicManager.getInstance();
        return this.query.trim().isEmpty() ? manager.getPlaylist() : manager.getSearchResults();
    }

    private void tickSearchDebounce() {
        String trimmed = this.query.trim();
        if (trimmed.isEmpty()) {
            if (!this.lastSearchQuery.isEmpty()) {
                this.lastSearchQuery = "";
                MusicManager.getInstance().clearSearch();
                this.scroll = 0.0f;
            }
            return;
        }
        if (!trimmed.equals(this.lastSearchQuery)
                && System.currentTimeMillis() - this.lastTypedAt > SEARCH_DEBOUNCE_MS
                && !MusicManager.getInstance().isSearching()) {
            this.performSearch();
        }
    }

    private void performSearch() {
        String trimmed = this.query.trim();
        if (trimmed.isEmpty()) {
            this.lastSearchQuery = "";
            MusicManager.getInstance().clearSearch();
            return;
        }
        this.lastSearchQuery = trimmed;
        MusicManager.getInstance().search(trimmed);
        this.scroll = 0.0f;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        MusicManager manager = MusicManager.getInstance();

        float closeX = this.panelX + this.panelWidth - 36.0f;
        float closeY = this.panelY + 12.0f;
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, closeX, closeY, 24.0f, 24.0f)) {
            this.onClose();
            return true;
        }

        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, this.searchX, this.searchY,
                this.searchWidth, SEARCH_HEIGHT)) {
            if (!this.query.isEmpty()) {
                float clearX = this.searchX + this.searchWidth - 24.0f;
                float clearY = this.searchY + (SEARCH_HEIGHT - 16.0f) / 2.0f;
                if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, clearX, clearY, 16.0f, 16.0f)) {
                    this.query = "";
                    this.lastSearchQuery = "";
                    manager.clearSearch();
                    this.scroll = 0.0f;
                    this.searchFocused = true;
                    return true;
                }
            }
            this.searchFocused = true;
            return true;
        }
        this.searchFocused = false;

        float refreshX = this.listX + this.listWidth - 16.0f;
        float refreshY = this.panelY + 88.0f;
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, refreshX, refreshY, 16.0f, 16.0f)) {
            manager.loadPlaylist();
            return true;
        }

        Song song = manager.getCurrentSong();
        float textX = this.panelX + 16.0f + COVER_SIZE + 12.0f;
        float controlsY = this.footerY + 73.0f;
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, textX + 2.0f, controlsY, 26.0f, 26.0f)) {
            manager.previous();
            return true;
        }
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, textX + 36.0f, controlsY - 4.0f, 34.0f, 34.0f)) {
            manager.togglePlayPause();
            return true;
        }
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, textX + 78.0f, controlsY, 26.0f, 26.0f)) {
            manager.next();
            return true;
        }

        float barX = textX;
        float barY = this.footerY + 47.0f;
        float barWidth = this.panelX + this.panelWidth - 32.0f - barX;
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, barX, barY - 6.0f, barWidth, 16.0f)
                && manager.getDurationMs() > 0L) {
            this.draggingProgress = true;
            this.draggedProgress = this.progressFromMouse((float) mouseX, barX, barWidth);
            return true;
        }

        float volumeIconX = this.panelX + this.panelWidth - 104.0f;
        float volumeBarX = volumeIconX + 20.0f;
        float volumeBarY = controlsY + 11.0f;
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY, volumeBarX - 4.0f, volumeBarY - 7.0f,
                72.0f, 18.0f)) {
            this.draggingVolume = true;
            this.setVolumeFromMouse((float) mouseX, volumeBarX, 64.0f);
            return true;
        }

        List<Song> songs = this.displayedSongs();
        Song rowSong = this.rowAt(mouseX, mouseY, songs);
        if (rowSong != null) {
            manager.playSong(rowSong, songs);
            return true;
        }

        return CursorUtil.isInBounds((float) mouseX, (float) mouseY,
                this.panelX, this.panelY, this.panelWidth, this.panelHeight);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (this.draggingProgress) {
            this.draggingProgress = false;
            MusicManager.getInstance().seek(this.draggedProgress);
            return true;
        }
        if (this.draggingVolume) {
            this.draggingVolume = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != 0) {
            return false;
        }
        float textX = this.panelX + 16.0f + COVER_SIZE + 12.0f;
        if (this.draggingProgress) {
            float barX = textX;
            float barWidth = this.panelX + this.panelWidth - 32.0f - barX;
            this.draggedProgress = this.progressFromMouse((float) mouseX, barX, barWidth);
            return true;
        }
        if (this.draggingVolume) {
            float volumeIconX = this.panelX + this.panelWidth - 104.0f;
            this.setVolumeFromMouse((float) mouseX, volumeIconX + 20.0f, 64.0f);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        if (CursorUtil.isInBounds((float) mouseX, (float) mouseY,
                this.listX, this.listY, this.listWidth, this.listHeight)) {
            List<Song> songs = this.displayedSongs();
            float contentHeight = songs.size() * (ROW_HEIGHT + ROW_GAP);
            float maxScroll = Math.max(0.0f, contentHeight - this.listHeight);
            this.scroll = Math.max(0.0f, Math.min(maxScroll, this.scroll - (float) scrollDelta * 42.0f));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.searchFocused) {
            if (keyCode == 259) {
                if (!this.query.isEmpty()) {
                    this.query = this.query.substring(0, this.query.length() - 1);
                    this.lastTypedAt = System.currentTimeMillis();
                }
                return true;
            }
            if (keyCode == 257 || keyCode == 335) {
                this.performSearch();
                return true;
            }
            if (keyCode == 256) {
                if (!this.query.isEmpty()) {
                    this.query = "";
                    this.lastSearchQuery = "";
                    this.scroll = 0.0f;
                    MusicManager.getInstance().clearSearch();
                } else {
                    this.searchFocused = false;
                }
                return true;
            }
        }
        if (keyCode == 32 && !this.searchFocused) {
            MusicManager.getInstance().togglePlayPause();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.searchFocused || this.query.length() >= MAX_QUERY_LENGTH) {
            return false;
        }
        if (codePoint < 32 || codePoint == 127) {
            return false;
        }
        this.query += codePoint;
        this.lastTypedAt = System.currentTimeMillis();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.searchFocused = false;
        super.onClose();
    }

    private Song rowAt(double mouseX, double mouseY, List<Song> songs) {
        if (!CursorUtil.isInBounds((float) mouseX, (float) mouseY,
                this.listX, this.listY, this.listWidth, this.listHeight)) {
            return null;
        }
        int index = (int) (((float) mouseY - this.listY + this.scroll) / (ROW_HEIGHT + ROW_GAP));
        if (index < 0 || index >= songs.size()) {
            return null;
        }
        float rowY = this.listY + index * (ROW_HEIGHT + ROW_GAP) - this.scroll;
        if (mouseY < rowY || mouseY > rowY + ROW_HEIGHT) {
            return null;
        }
        return songs.get(index);
    }

    private float progressFromMouse(float mouseX, float barX, float barWidth) {
        return Math.max(0.0f, Math.min(1.0f, (mouseX - barX) / Math.max(1.0f, barWidth)));
    }

    private void setVolumeFromMouse(float mouseX, float barX, float barWidth) {
        float volume = Math.max(0.0f, Math.min(1.0f, (mouseX - barX) / Math.max(1.0f, barWidth)));
        MusicManager.getInstance().setVolume(volume);
        MusicPlayer module = MusicPlayer.INSTANCE;
        if (module != null) {
            module.setVolumeFromUi(volume);
        }
    }
}
