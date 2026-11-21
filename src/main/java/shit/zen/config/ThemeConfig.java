package shit.zen.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import shit.zen.gui.newclickgui.Theme;

/**
 * Persists the NewClickGui accent/rainbow theme in {@code theme.cfg}.
 */
public class ThemeConfig
extends Config {
    public ThemeConfig() {
        super("theme.cfg");
    }

    @Override
    public void read(BufferedReader bufferedReader) throws IOException {
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            String[] parts = line.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            try {
                switch (parts[0]) {
                    case "rainbow" -> Theme.rainbow = Boolean.parseBoolean(parts[1]);
                    case "hue" -> Theme.hue = Float.parseFloat(parts[1]);
                    case "saturation" -> Theme.saturation = Float.parseFloat(parts[1]);
                    case "brightness" -> Theme.brightness = Float.parseFloat(parts[1]);
                    case "speed" -> Theme.rainbowSpeed = Float.parseFloat(parts[1]);
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed values and keep the defaults.
            }
        }
    }

    @Override
    public void save(BufferedWriter bufferedWriter) throws IOException {
        bufferedWriter.write("rainbow:" + Theme.rainbow + "\n");
        bufferedWriter.write("hue:" + Theme.hue + "\n");
        bufferedWriter.write("saturation:" + Theme.saturation + "\n");
        bufferedWriter.write("brightness:" + Theme.brightness + "\n");
        bufferedWriter.write("speed:" + Theme.rainbowSpeed + "\n");
    }
}
