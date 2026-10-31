package shit.zen.music;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerUtils;
import javazoom.jl.decoder.SampleBuffer;
import shit.zen.utils.misc.Assets;

/**
 * Minimal MP3 player built on JLayer. JLayer decodes frames to 16-bit PCM and
 * this class writes the PCM to a {@link SourceDataLine}, which gives us pause,
 * resume, seek, volume and position without depending on JavaFX.
 */
public final class Mp3Player {
    public interface Listener {
        void onFinished();

        void onError(String message);
    }

    private static final int WRITE_CHUNK = 4096;

    /*
     * JLayer loads sfd.ser / l3reorder.ser from its own classpath. In the DLL
     * injection path our jar is not on the game ClassLoader's URL path, so
     * those lookups return null and SynthesisFilter fails with
     * ExceptionInInitializerError. JavaLayerUtils exposes a hook for exactly
     * this case; Assets.open falls back to the extracted openzen.resources
     * directory where the .ser files already live.
     */
    static {
        try {
            JavaLayerUtils.setHook(name -> Assets.open("/javazoom/jl/decoder/" + name));
        } catch (Throwable ignored) {
        }
    }

    private final Object pauseLock = new Object();
    private volatile SourceDataLine line;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean stopped = true;
    private volatile float volume = 0.7f;
    private volatile long positionMs;
    private volatile long durationMs;
    private volatile File currentFile;
    private volatile String error;
    private volatile int generation;
    private volatile Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return this.running;
    }

    public boolean isPaused() {
        return this.paused;
    }

    public long getPositionMs() {
        return this.positionMs;
    }

    public long getDurationMs() {
        return this.durationMs;
    }

    public float getVolume() {
        return this.volume;
    }

    public File getCurrentFile() {
        return this.currentFile;
    }

    public String getError() {
        return this.error;
    }

    public void play(File file, long startMs) {
        if (file == null || !file.isFile()) {
            this.error = "Audio file is missing";
            return;
        }
        int gen = ++this.generation;
        this.stopped = false;
        this.paused = false;
        this.running = true;
        this.positionMs = Math.max(0L, startMs);
        this.durationMs = 0L;
        this.currentFile = file;
        this.error = null;

        SourceDataLine oldLine = this.line;
        this.line = null;
        if (oldLine != null) {
            closeLine(oldLine);
        }

        Thread thread = new Thread(() -> this.run(file, Math.max(0L, startMs), gen), "OpenZen-MusicPlayer");
        thread.setDaemon(true);
        thread.start();
    }

    public void pause() {
        if (this.running) {
            this.paused = true;
        }
    }

    public void resume() {
        this.paused = false;
        synchronized (this.pauseLock) {
            this.pauseLock.notifyAll();
        }
    }

    public void togglePause() {
        if (this.paused) {
            this.resume();
        } else {
            this.pause();
        }
    }

    public void stop() {
        this.stopped = true;
        this.paused = false;
        this.running = false;
        this.positionMs = 0L;
        this.durationMs = 0L;
        ++this.generation;
        SourceDataLine current = this.line;
        this.line = null;
        if (current != null) {
            closeLine(current);
        }
        synchronized (this.pauseLock) {
            this.pauseLock.notifyAll();
        }
    }

    public void seek(long positionMs) {
        File file = this.currentFile;
        if (file == null) {
            return;
        }
        boolean wasPaused = this.paused;
        this.play(file, Math.max(0L, positionMs));
        if (wasPaused) {
            this.pause();
        }
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        SourceDataLine current = this.line;
        if (current != null) {
            applyVolume(current, this.volume);
        }
    }

    private void run(File file, long startMs, int gen) {
        SourceDataLine localLine = null;
        Bitstream bitstream = null;
        long frameTime = 0L;
        try {
            long total = probeDuration(file);
            if (gen == this.generation && total > 0L) {
                this.durationMs = total;
            }

            bitstream = new Bitstream(new BufferedInputStream(new FileInputStream(file), 64 * 1024));
            Decoder decoder = new Decoder();
            Header header;
            while (!this.stopped && gen == this.generation && (header = bitstream.readFrame()) != null) {
                long frameMs = (long) header.ms_per_frame();

                // Seeking is implemented by discarding complete frames.
                if (frameTime + frameMs <= startMs) {
                    frameTime += frameMs;
                    bitstream.closeFrame();
                    continue;
                }

                this.waitIfPaused(gen);
                if (this.stopped || gen != this.generation) {
                    bitstream.closeFrame();
                    break;
                }

                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                short[] samples = output.getBuffer();
                int sampleCount = output.getBufferLength();

                if (localLine == null) {
                    AudioFormat format = new AudioFormat(
                            output.getSampleFrequency(), 16, output.getChannelCount(), true, false);
                    localLine = AudioSystem.getSourceDataLine(format);
                    localLine.open(format, WRITE_CHUNK * 4);
                    applyVolume(localLine, this.volume);
                    localLine.start();
                    this.line = localLine;
                }

                byte[] pcm = toPcm(samples, sampleCount);
                int offset = 0;
                while (offset < pcm.length && !this.stopped && gen == this.generation) {
                    this.waitIfPaused(gen);
                    if (this.stopped || gen != this.generation) {
                        break;
                    }
                    int written = localLine.write(pcm, offset, Math.min(WRITE_CHUNK, pcm.length - offset));
                    offset += written;
                }

                frameTime += frameMs;
                if (this.stopped || gen != this.generation) {
                    bitstream.closeFrame();
                    break;
                }
                this.positionMs = frameTime;
                bitstream.closeFrame();
            }

            if (localLine != null) {
                localLine.drain();
                localLine.stop();
                localLine.close();
                localLine = null;
            }
            this.line = null;

            if (!this.stopped && gen == this.generation) {
                this.running = false;
                this.paused = false;
                this.positionMs = this.durationMs > 0L ? this.durationMs : frameTime;
                Listener callback = this.listener;
                if (callback != null) {
                    callback.onFinished();
                }
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            if (gen == this.generation) {
                this.running = false;
                this.paused = false;
                StringBuilder message = new StringBuilder(throwable.getClass().getSimpleName());
                Throwable cause = throwable.getCause();
                if (cause != null) {
                    message.append(": ").append(cause.getClass().getSimpleName());
                    if (cause.getMessage() != null) {
                        message.append(" (").append(cause.getMessage()).append(')');
                    }
                } else if (throwable.getMessage() != null) {
                    message.append(": ").append(throwable.getMessage());
                }
                this.error = message.toString();
                Listener callback = this.listener;
                if (callback != null) {
                    callback.onError(this.error);
                }
            }
        } finally {
            if (bitstream != null) {
                try {
                    bitstream.close();
                } catch (Throwable ignored) {
                }
            }
            if (localLine != null) {
                closeLine(localLine);
            }
            if (gen == this.generation && this.line == localLine) {
                this.line = null;
            }
        }
    }

    private void waitIfPaused(int gen) {
        while (this.paused && !this.stopped && gen == this.generation) {
            synchronized (this.pauseLock) {
                try {
                    this.pauseLock.wait(40L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static byte[] toPcm(short[] samples, int sampleCount) {
        byte[] bytes = new byte[sampleCount * 2];
        for (int i = 0; i < sampleCount; i++) {
            short sample = samples[i];
            bytes[i * 2] = (byte) (sample & 0xFF);
            bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return bytes;
    }

    private static void applyVolume(SourceDataLine line, float volume) {
        try {
            if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                return;
            }
            FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float decibels = volume <= 0.0001f ? gain.getMinimum() : (float) (20.0 * Math.log10(volume));
            decibels = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels));
            gain.setValue(decibels);
        } catch (Throwable ignored) {
        }
    }

    private static void closeLine(SourceDataLine line) {
        try {
            line.stop();
        } catch (Throwable ignored) {
        }
        try {
            line.flush();
        } catch (Throwable ignored) {
        }
        try {
            line.close();
        } catch (Throwable ignored) {
        }
    }

    private static long probeDuration(File file) {
        Bitstream bitstream = null;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file), 16 * 1024)) {
            bitstream = new Bitstream(input);
            Header header = bitstream.readFrame();
            if (header == null) {
                return 0L;
            }
            long length = Math.min(Integer.MAX_VALUE, file.length());
            long duration = (long) header.total_ms((int) length);
            bitstream.closeFrame();
            return Math.max(0L, duration);
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            if (bitstream != null) {
                try {
                    bitstream.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
