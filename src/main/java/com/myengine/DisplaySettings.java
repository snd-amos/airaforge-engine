package com.myengine;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;

/**
 * Persisted display / window settings (written to {@code settings.json} at the
 * project root). The Settings UI writes this on Apply; {@code VulkanMain.initWindow}
 * reads it so the engine boots at the user's last chosen resolution / display
 * mode instead of a hardcoded 1280×720.
 *
 * <p>{@code fullscreenMode}: 0 = windowed, 1 = borderless windowed,
 * 2 = exclusive fullscreen (matches the Settings UI dropdown order).
 */
class DisplaySettings {

    int    windowWidth   = 1280;
    int    windowHeight  = 720;
    String aspectRatio   = "16:9";
    int    fullscreenMode = 0;

    private static final String PATH = "settings.json";

    static DisplaySettings load() {
        File f = new File(PATH);
        if (!f.exists()) return new DisplaySettings();
        try (Reader r = new FileReader(f)) {
            DisplaySettings s = new Gson().fromJson(r, DisplaySettings.class);
            return s != null ? s : new DisplaySettings();
        } catch (Exception e) {
            Logger.warn("[VK] Could not load settings.json: " + e.getMessage());
            return new DisplaySettings();
        }
    }

    void save() {
        try (Writer w = new FileWriter(PATH)) {
            new Gson().toJson(this, w);
        } catch (Exception e) {
            Logger.warn("[VK] Could not save settings.json: " + e.getMessage());
        }
    }
}
