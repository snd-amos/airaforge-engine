package com.myengine;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT32;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

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