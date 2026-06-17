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

class Config {
    // ============ EDITOR CONFIG ============
    public static final float GIZMO_SENSITIVITY = 0.0014f;
    public static final float GIZMO_ROTATION_SPEED = 0.5f;
    public static final float GIZMO_SCALE_SPEED = 5.0f;
    public static final float GIZMO_SCALE_MIN = 0.01f;
    public static final float MOUSE_LOOK_SENSITIVITY = 0.1f;
    public static final long DOUBLE_TAP_DELAY = 250;

    // ============ PLAYER CONFIG ============
    public static final float PLAYER_SPEED = 0.05f;
    public static final float PLAYER_FLY_SPEED_MULTIPLIER = 2.0f;
    public static final float PLAYER_RADIUS = 0.3f;
    // Not final: avoids javac's compile-time inlining so tweaks here propagate
    // to existing class files without a full rebuild.
    public static float PLAYER_START_Y = 1.8f;

    // ============ PHYSICS CONFIG ============
    public static final float GRAVITY = 0.007f;
    public static final float JUMP_POWER = 0.15f;

    // ============ CAMERA CONFIG ============
    public static final float FOV = 70.0f;
    public static final float Z_NEAR = 0.1f;
    public static final float Z_FAR = 1000.0f;
    public static final float PITCH_CLAMP = 89.0f;

    // ============ WORLD CONFIG ============
    public static final float GRID_CELL_SIZE = 10.0f;
    public static final float FLOOR_GRID_SIZE = 20.0f;

    // ============ RAYCAST CONFIG ============
    public static final float RAYCAST_RANGE = 5.0f;
    public static final float RAYCAST_STEP = 0.1f;
    public static final float RAYCAST_PLACEMENT_OFFSET = 0.1f;
    public static final float RAYCAST_PLACEMENT_HEIGHT_OFFSET = 1.0f;

    // ============ GIZMO VISUALS CONFIG ============
    public static final float GIZMO_ARROW_LENGTH = 2.0f;
    public static final float GIZMO_ROTATION_RADIUS = 1.5f;
    public static final int GIZMO_CIRCLE_SEGMENTS = 64;
    public static final float GIZMO_SCALE_CUBE_SIZE = 0.3f;
    public static final float GIZMO_CENTER_CUBE_SIZE = 0.15f;

    // ============ VIEWPORT CONFIG ============
    public static final int MIN_VIEWPORT_WIDTH = 100;
    public static final int MIN_VIEWPORT_HEIGHT = 100;

    // ============ ASSET CONFIG ============
    public static final String MODELS_PATH = "src/main/resources/models/";
    public static final String ANIMATIONS_PATH = "src/main/resources/animations/";
    public static final String TEXTURES_PATH = "/textures/";
    public static final String SHADERS_PATH = "src/main/resources/shaders/";
    public static final String GAME_LEVEL_PATH = "level.json";

    // ============ SHADOW CONFIG ============
    public static final int SHADOW_MAP_WIDTH = 2048;
    public static final int SHADOW_MAP_HEIGHT = 2048;
}