package com.myengine;

import java.util.HashMap;
import java.util.Map;

/**
 * Asset pooling system for efficient mesh and texture management.
 * Caches loaded assets to avoid duplicate loading and provides cleanup.
 */
public class AssetPool {
    private static Map<String, Mesh> meshes = new HashMap<>();
    private static Map<String, Integer> textures = new HashMap<>();

    /**
     * Get or load a mesh from the pool.
     */
    public static Mesh getMesh(String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) {
            Logger.warn("Attempted to load mesh with null or empty path");
            return null;
        }

        if (meshes.containsKey(modelPath)) {
            return meshes.get(modelPath);
        } else {
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

    /**
     * Get or load a texture from the pool.
     */
    public static int getTexture(String texturePath) {
        if (texturePath == null || texturePath.isEmpty()) {
            Logger.warn("Attempted to load texture with null or empty path");
            return 0;
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
                Logger.error("Failed to load texture: " + texturePath);
            }
            return 0;
        }
    }

    /**
     * Destroy all cached assets and free GPU memory.
     * Call this when destroying the scene or closing the application.
     */
    public static void destroy() {
        Logger.info("Destroying AssetPool - cleaning up " + meshes.size() + " meshes and "
                + textures.size() + " textures");

        for (Mesh mesh : meshes.values()) {
            mesh.destroy();
        }
        meshes.clear();

        for (int texId : textures.values()) {
            Texture.deleteTexture(texId);
        }
        textures.clear();
    }

    /**
     * Clear only meshes from the pool.
     */
    public static void clearMeshes() {
        for (Mesh mesh : meshes.values()) {
            mesh.destroy();
        }
        meshes.clear();
    }

    /**
     * Clear only textures from the pool.
     */
    public static void clearTextures() {
        for (int texId : textures.values()) {
            Texture.deleteTexture(texId);
        }
        textures.clear();
    }
}