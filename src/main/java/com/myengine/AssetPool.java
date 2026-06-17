package com.myengine;

import java.util.*;

class AssetPool {
    private static Map<String, Mesh> meshes = new HashMap<>();
    private static Map<String, Integer> textures = new HashMap<>();
    private static Map<String, Integer> embeddedTextures = new HashMap<>();

    public static void cacheEmbeddedTexture(String modelPath, int texId) {
        embeddedTextures.put(modelPath, texId);
    }

    public static int getEmbeddedTexture(String modelPath) {
        return embeddedTextures.getOrDefault(modelPath, 0);
    }

    public static void registerMesh(String name, Mesh mesh) {
        meshes.put(name, mesh);
    }

    public static Mesh getMesh(String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) {
            Logger.warn("Attempted to load mesh with null or empty path");
            return null;
        }

        if (meshes.containsKey(modelPath)) {
            return meshes.get(modelPath);
        } else {
            // __-prefixed names are reserved for built-in / billboard / trigger
            // keys (see World.SPAWN_POINT_KEY, WIN_ZONE_KEY, etc.). They must
            // be registered up-front via registerMesh(); we never try to
            // resolve them as files on disk.
            if (modelPath.startsWith("__")) {
                return null;
            }
            Mesh mesh = ModelLoader.loadModel(modelPath);
            if (mesh != null) {
                meshes.put(modelPath, mesh);
                Logger.debug("Loaded and cached mesh: " + modelPath);
            } else {
                Logger.error("Failed to load mesh: " + modelPath);
            }
            return mesh;
        }
    }

    public static int getTexture(String texturePath) {
        if (texturePath == null || texturePath.isEmpty()) {
            Logger.warn("Attempted to load texture with null or empty path");
            return getFallbackTexture();
        }

        if (textures.containsKey(texturePath)) {
            return textures.get(texturePath);
        } else {
            int texId = Texture.loadTexture(texturePath);
            if (texId != -1) {
                textures.put(texturePath, texId);
                Logger.debug("Loaded and cached texture: " + texturePath);
                return texId;
            } else {
                Logger.error("Failed to load texture: " + texturePath + " — using fallback");
                // Cache the fallback ID under this path so we don't retry
                // the doomed lookup every frame.
                int fb = getFallbackTexture();
                textures.put(texturePath, fb);
                return fb;
            }
        }
    }

    /**
     * 1×1 bright-magenta texture used in place of textures that failed to
     * load. Two reasons it exists:
     *   1. Visual: missing textures render as obvious magenta blocks
     *      instead of solid black or invisible.
     *   2. Defensive: macOS's Core-profile GL driver stalls the GPU on
     *      glfwSwapBuffers if a shader has a sampler2D bound to a unit
     *      with no valid texture. Returning this fallback (instead of 0)
     *      keeps every sampler always-valid even when assets are missing.
     * Lazily generated on first use; lives for the GL context lifetime.
     */
    private static int fallbackTextureID = 0;
    private static int getFallbackTexture() {
        if (fallbackTextureID == 0) {
            fallbackTextureID = createMagenta1x1();
        }
        return fallbackTextureID;
    }

    private static int createMagenta1x1() {
        int id = org.lwjgl.opengl.GL11.glGenTextures();
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, id);
        org.lwjgl.opengl.GL11.glTexParameteri(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER,
                org.lwjgl.opengl.GL11.GL_NEAREST);
        org.lwjgl.opengl.GL11.glTexParameteri(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER,
                org.lwjgl.opengl.GL11.GL_NEAREST);
        java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(4);
        buf.put((byte) 0xFF).put((byte) 0x00).put((byte) 0xFF).put((byte) 0xFF).flip();
        org.lwjgl.opengl.GL11.glTexImage2D(
                org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                org.lwjgl.opengl.GL11.GL_RGBA, 1, 1, 0,
                org.lwjgl.opengl.GL11.GL_RGBA,
                org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, buf);
        Logger.info("Created 1×1 magenta fallback texture (id=" + id + ")");
        return id;
    }

    public static void destroy() {
        Logger.info("Destroying AssetPool - cleaning up " + meshes.size() + " meshes and "
                + textures.size() + " textures");

        for (Mesh mesh : meshes.values()) {
            mesh.destroy();
        }
        meshes.clear();

        // De-dupe before deletion: the fallback ID may be cached under many
        // failed paths. Set avoids double-delete.
        java.util.Set<Integer> uniqueIds = new java.util.HashSet<>(textures.values());
        for (int texId : uniqueIds) {
            Texture.deleteTexture(texId);
        }
        textures.clear();
        // The magenta fallback was generated against the now-destroyed GL
        // context (when scenes change). Force re-creation on next use.
        if (fallbackTextureID != 0 && !uniqueIds.contains(fallbackTextureID)) {
            Texture.deleteTexture(fallbackTextureID);
        }
        fallbackTextureID = 0;
    }

    public static void clearMeshes() {
        for (Mesh mesh : meshes.values()) {
            mesh.destroy();
        }
        meshes.clear();
    }

    public static void clearTextures() {
        for (int texId : textures.values()) {
            Texture.deleteTexture(texId);
        }
        textures.clear();
    }
}
