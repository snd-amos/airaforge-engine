package com.myengine;

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;

/**
 * Texture loading and management utilities. Handles 2D textures and cubemaps.
 *
 * Resource lookup tries the classpath first (for IDE runs with
 * src/main/resources marked as a Resources Root, and for packaged JARs)
 * and then falls back to the source-tree filesystem path. This matches
 * how model loading works (Assimp.aiImportFile uses a filesystem path)
 * and keeps the engine running regardless of how IntelliJ's
 * Resources-Root config got set on a given machine.
 *
 * Marker textures (spawn.png / win.png) are explicitly capped at 256×256
 * because the full 1024 cap triggers a macOS Core-profile swap stall —
 * see CLAUDE.md "Open bugs" for the bisection trail.
 */
class Texture {

    // Detect macOS at startup. The Apple Core-profile GL driver
    // reliably stalls on swap whenever this engine uploads a non-trivial
    // set of 2D / cubemap textures alongside the editor's render load.
    // Many hours of bisection (CLAUDE.md "Open bugs") narrowed it down
    // but did not isolate a single offender. As a pragmatic stopgap on
    // Mac we route all FILE texture loads through the magenta-fallback
    // path (which is proven to keep the editor running). Embedded GLB
    // textures (via uploadImage) are NOT blocked — smaller and have
    // never been observed to cause the hang.
    private static final boolean MACOS_DISABLE_FILE_TEXTURES =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    static {
        if (MACOS_DISABLE_FILE_TEXTURES) {
            Logger.warn("macOS detected — file-based texture loads disabled to avoid GL swap stall. " +
                    "Missing textures will render as magenta. Toggle MACOS_DISABLE_FILE_TEXTURES " +
                    "in Texture.java to re-enable when the underlying driver bug is fixed.");
        }
    }

    /**
     * Opens a texture by name from either the classpath OR the filesystem.
     * Caller must close the returned stream. Returns null if not found.
     * On macOS this always returns null (see comment on
     * MACOS_DISABLE_FILE_TEXTURES) so callers fall back to magenta.
     */
    private static InputStream openTexture(String name) {
        if (MACOS_DISABLE_FILE_TEXTURES) return null;
        InputStream cp = Texture.class.getResourceAsStream(Config.TEXTURES_PATH + name);
        if (cp != null) return cp;
        File f = new File("src/main/resources/textures/" + name);
        if (f.isFile()) {
            try { return new FileInputStream(f); }
            catch (FileNotFoundException ignored) { /* fall through */ }
        }
        return null;
    }

    /**
     * Downscale oversize textures so we don't push too much data at the
     * driver. Marker billboards (spawn.png / win.png) get an explicit
     * smaller cap because larger sizes hang the macOS GL driver on swap.
     */
    private static BufferedImage capSize(BufferedImage src, String pathForLog) {
        int w = src.getWidth(), h = src.getHeight();
        // Marker billboards (spawn.png / win.png) get an explicit smaller
        // cap. The 4725×4725 source PNGs hung macOS at 1024 — but on macOS
        // they're now blocked entirely by MACOS_DISABLE_FILE_TEXTURES so
        // this only affects Windows / Linux. Everything else, including
        // GLB-embedded textures (path="<embedded>"), keeps the project's
        // default cap. Tried "256 for everything" globally and it ended
        // up downscaling embedded textures whose native upload was known
        // to work on Mac (e.g. old_computer.glb at 1024×1024); the
        // downscale path itself somehow trips Apple's driver. Lesson:
        // when something works at a given size, don't shrink it.
        int cap = ("spawn.png".equals(pathForLog) || "win.png".equals(pathForLog))
                ? 256
                : Config.MAX_TEXTURE_SIZE;
        if (w <= cap && h <= cap) return src;
        float scale = Math.min((float) cap / w, (float) cap / h);
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        Logger.debug("Downscaled texture " + pathForLog + " from " + w + "x" + h + " to " + nw + "x" + nh);
        return scaled;
    }

    /**
     * Upload an already-decoded BufferedImage as a 2D texture. Used for
     * GLB-embedded textures extracted by Assimp.
     */
    static int uploadImage(BufferedImage image) {
        image = capSize(image, "<embedded>");
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = new int[w * h];
        image.getRGB(0, 0, w, h, pixels, 0, w);
        ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = pixels[y * w + x];
                buffer.put((byte) ((p >> 16) & 0xFF));
                buffer.put((byte) ((p >> 8)  & 0xFF));
                buffer.put((byte) ( p        & 0xFF));
                buffer.put((byte) ((p >> 24) & 0xFF));
            }
        }
        buffer.flip();
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        return id;
    }

    /** True if the named texture is resolvable via classpath OR filesystem. */
    public static boolean exists(String name) {
        InputStream s = openTexture(name);
        if (s == null) return false;
        try { s.close(); } catch (IOException ignored) {}
        return true;
    }

    /**
     * Load a 2D texture by filename. Returns the GL texture ID, or -1 if
     * loading failed (caller is expected to use the AssetPool fallback).
     */
    public static int loadTexture(String path) {
        try {
            InputStream in = openTexture(path);
            if (in == null) {
                Logger.error("Texture not found: " + path);
                return -1;
            }

            BufferedImage image = capSize(ImageIO.read(in), path);
            int w = image.getWidth();
            int h = image.getHeight();
            int[] pixels = new int[w * h];
            image.getRGB(0, 0, w, h, pixels, 0, w);

            ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pixel = pixels[y * w + x];
                    buffer.put((byte) ((pixel >> 16) & 0xFF));
                    buffer.put((byte) ((pixel >> 8) & 0xFF));
                    buffer.put((byte) (pixel & 0xFF));
                    buffer.put((byte) ((pixel >> 24) & 0xFF));
                }
            }
            buffer.flip();

            int id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

            Logger.debug("Loaded texture: " + path + " (" + w + "x" + h + ")");
            return id;
        } catch (Exception e) {
            Logger.error("Failed to load texture: " + path, e);
            return -1;
        }
    }

    /**
     * Load a cubemap from 6 image files (px, nx, py, ny, pz, nz). Any face
     * that fails to load gets backfilled with a 1×1 sky-blue pixel so the
     * cubemap is always complete (incomplete cubemaps stall macOS on swap).
     * Each face is also capped at 512×512 — 6 huge faces at 2048² each can
     * push 96 MB through the driver in one upload, which is exactly the
     * kind of transaction Apple's Core-profile bridge stalls on.
     */
    public static int loadCubemap(String[] files) {
        final int CUBEMAP_FACE_CAP = 512;
        int texID = glGenTextures();
        glBindTexture(GL_TEXTURE_CUBE_MAP, texID);

        boolean[] faceLoaded = new boolean[files.length];

        for (int i = 0; i < files.length; i++) {
            try {
                InputStream in = openTexture(files[i]);
                if (in == null) {
                    Logger.warn("Cubemap face not found: " + files[i]);
                    continue;
                }

                BufferedImage image = ImageIO.read(in);
                // Cap face size like 2D textures do — large skybox faces
                // can put macOS into the swap-stall state.
                int srcW = image.getWidth(), srcH = image.getHeight();
                if (srcW > CUBEMAP_FACE_CAP || srcH > CUBEMAP_FACE_CAP) {
                    float scale = Math.min((float) CUBEMAP_FACE_CAP / srcW, (float) CUBEMAP_FACE_CAP / srcH);
                    int nw = Math.max(1, Math.round(srcW * scale));
                    int nh = Math.max(1, Math.round(srcH * scale));
                    BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = scaled.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(image, 0, 0, nw, nh, null);
                    g.dispose();
                    Logger.debug("Downscaled cubemap face " + files[i] + " from " + srcW + "x" + srcH + " to " + nw + "x" + nh);
                    image = scaled;
                }
                int w = image.getWidth();
                int h = image.getHeight();
                int[] pixels = new int[w * h];
                image.getRGB(0, 0, w, h, pixels, 0, w);

                ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int pixel = pixels[y * w + x];
                        buffer.put((byte) ((pixel >> 16) & 0xFF));
                        buffer.put((byte) ((pixel >> 8) & 0xFF));
                        buffer.put((byte) (pixel & 0xFF));
                        buffer.put((byte) ((pixel >> 24) & 0xFF));
                    }
                }
                buffer.flip();

                glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, 0, GL_RGBA, w, h, 0,
                        GL_RGBA, GL_UNSIGNED_BYTE, buffer);
                faceLoaded[i] = true;
            } catch (Exception e) {
                Logger.error("Failed to load cubemap face: " + files[i], e);
            }
        }

        // Backfill any missing faces with a 1×1 sky-blue pixel. Any unloaded
        // face leaves the cubemap incomplete = macOS swap stall.
        ByteBuffer fallbackPixel = null;
        for (int i = 0; i < files.length; i++) {
            if (faceLoaded[i]) continue;
            if (fallbackPixel == null) {
                fallbackPixel = BufferUtils.createByteBuffer(4);
                fallbackPixel.put((byte) 0x40).put((byte) 0x60).put((byte) 0x80).put((byte) 0xFF);
                fallbackPixel.flip();
            } else {
                fallbackPixel.rewind();
            }
            glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, 0, GL_RGBA, 1, 1, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, fallbackPixel);
            Logger.warn("Cubemap face " + files[i] + " filled with 1×1 fallback");
        }

        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

        Logger.debug("Loaded cubemap texture");
        return texID;
    }

    public static void deleteTexture(int textureID) {
        if (textureID > 0) {
            glDeleteTextures(textureID);
            Logger.debug("Deleted texture ID: " + textureID);
        }
    }
}
