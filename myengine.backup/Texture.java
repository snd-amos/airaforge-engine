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

class Texture {

    static int uploadImage(BufferedImage image) {
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

    public static boolean exists(String name) {
        InputStream s = Texture.class.getResourceAsStream(Config.TEXTURES_PATH + name);
        if (s == null) return false;
        try { s.close(); } catch (IOException ignored) {}
        return true;
    }

    public static int loadTexture(String path) {
        try {
            InputStream in = Texture.class.getResourceAsStream(Config.TEXTURES_PATH + path);
            if (in == null) {
                Logger.error("Texture not found: " + path);
                return -1;
            }

            BufferedImage image = ImageIO.read(in);
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

    public static int loadCubemap(String[] files) {
        int texID = glGenTextures();
        glBindTexture(GL_TEXTURE_CUBE_MAP, texID);

        for (int i = 0; i < files.length; i++) {
            try {
                InputStream in = Texture.class.getResourceAsStream(Config.TEXTURES_PATH + files[i]);
                if (in == null) {
                    Logger.warn("Cubemap face not found: " + files[i]);
                    continue;
                }

                BufferedImage image = ImageIO.read(in);
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

                glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            } catch (Exception e) {
                Logger.error("Failed to load cubemap face: " + files[i], e);
            }
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