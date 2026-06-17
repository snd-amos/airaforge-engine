package com.myengine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

// Per-animation-file trim defaults. When an animation is loaded onto an Animator,
// apply() copies the stored trim values onto the Animator so the same alignment is
// reused across every model using that animation. saveDefault() persists the
// current Animator trim back to disk.
class AnimationDefaults {
    private static final String FILE_PATH = "animation_trims.json";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, TrimValues> defaults = null;

    static class TrimValues {
        float offsetX, offsetY, offsetZ;
        float tiltX, tiltY, tiltZ;
    }

    private static void ensureLoaded() {
        if (defaults != null) return;
        defaults = new HashMap<>();
        try (Reader r = new FileReader(FILE_PATH)) {
            Type t = new TypeToken<HashMap<String, TrimValues>>() {}.getType();
            Map<String, TrimValues> loaded = gson.fromJson(r, t);
            if (loaded != null) defaults = loaded;
        } catch (Exception ignored) {
            // file doesn't exist yet — fine, start empty
        }
    }

    public static void apply(String animationFile, Animator a) {
        ensureLoaded();
        TrimValues v = defaults.get(animationFile);
        if (v == null) {
            Logger.info("No trim defaults found for animation: " + animationFile
                    + " (known: " + defaults.keySet() + ")");
            return;
        }
        a.trimOffsetX = v.offsetX;
        a.trimOffsetY = v.offsetY;
        a.trimOffsetZ = v.offsetZ;
        a.trimTiltX = v.tiltX;
        a.trimTiltY = v.tiltY;
        a.trimTiltZ = v.tiltZ;
        Logger.info("Applied trim defaults for " + animationFile
                + ": offset=(" + v.offsetX + "," + v.offsetY + "," + v.offsetZ + ")"
                + " tilt=(" + v.tiltX + "," + v.tiltY + "," + v.tiltZ + ")");
    }

    public static void saveDefault(String animationFile, Animator a) {
        ensureLoaded();
        TrimValues v = new TrimValues();
        v.offsetX = a.trimOffsetX;
        v.offsetY = a.trimOffsetY;
        v.offsetZ = a.trimOffsetZ;
        v.tiltX = a.trimTiltX;
        v.tiltY = a.trimTiltY;
        v.tiltZ = a.trimTiltZ;
        defaults.put(animationFile, v);

        try (Writer w = new FileWriter(FILE_PATH)) {
            gson.toJson(defaults, w);
            Logger.info("Saved trim defaults for: " + animationFile);
        } catch (Exception e) {
            Logger.error("Failed to save animation trim defaults", e);
        }
    }
}
