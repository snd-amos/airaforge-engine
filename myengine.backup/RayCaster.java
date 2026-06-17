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

class RayCaster {

    public static float rayAABB(float ox, float oy, float oz,
                                float dx, float dy, float dz,
                                float minX, float minY, float minZ,
                                float maxX, float maxY, float maxZ) {
        float dirFracX = 1.0f / (dx != 0 ? dx : 0.000001f);
        float dirFracY = 1.0f / (dy != 0 ? dy : 0.000001f);
        float dirFracZ = 1.0f / (dz != 0 ? dz : 0.000001f);

        float t1 = (minX - ox) * dirFracX;
        float t2 = (maxX - ox) * dirFracX;
        float t3 = (minY - oy) * dirFracY;
        float t4 = (maxY - oy) * dirFracY;
        float t5 = (minZ - oz) * dirFracZ;
        float t6 = (maxZ - oz) * dirFracZ;

        //review later
        float tmin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
        float tmax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));

        if (tmax < 0) return -1.0f;
        if (tmin > tmax) return -1.0f;
        if (tmin < 0) return tmax;
        return tmin;
    }

    public static float[] getMouseRay(double mouseX, double mouseY,
                                      int screenWidth, int screenHeight,
                                      float fov, float pitch, float yaw) {
        float nx = (float) (2.0f * mouseX) / screenWidth - 1.0f;
        float ny = 1.0f - (float) (2.0f * mouseY) / screenHeight;
        float fovRad = (float) Math.toRadians(fov / 2.0);
        float aspect = (float) screenWidth / screenHeight;

        float vx = nx * aspect * (float) Math.tan(fovRad);
        float vy = ny * (float) Math.tan(fovRad);
        float vz = -1.0f;

        float length = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        vx /= length; vy /= length; vz /= length;

        float pitchRad = (float) Math.toRadians(-pitch);
        float yawRad = (float) Math.toRadians(-yaw);

        float px = vx;
        float py = vy * (float) Math.cos(pitchRad) - vz * (float) Math.sin(pitchRad);
        float pz = vy * (float) Math.sin(pitchRad) + vz * (float) Math.cos(pitchRad);

        float rx = px * (float) Math.cos(yawRad) + pz * (float) Math.sin(yawRad);
        float ry = py;
        float rz = -px * (float) Math.sin(yawRad) + pz * (float) Math.cos(yawRad);

        return new float[]{rx, ry, rz};
    }
}