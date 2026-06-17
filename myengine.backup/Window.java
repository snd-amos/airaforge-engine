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

class Window {
    private final int width, height;
    private final String title;
    private long glfwWindow;

    private final boolean[] keys = new boolean[348];

    private static final double TARGET_FPS = 120.0;
    private static final double TIME_PER_FRAME = 1.0 / TARGET_FPS;

    public Window(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.title = title;
    }

    public void run() {
        init();
        Logger.info("Starting main game loop at " + TARGET_FPS + " FPS");

        GameStateManager.changeScene(new MainMenuScene(width, height, glfwWindow));

        double lastFrameTime = glfwGetTime();

        while (!glfwWindowShouldClose(glfwWindow)) {
            double currentTime = glfwGetTime();
            double deltaTime = currentTime - lastFrameTime;

            // Clamp dt so a slow frame (scene load, alt-tab, breakpoint) doesn't
            // produce a giant physics step that tunnels the player through
            // thin geometry like the floor.
            if (deltaTime > 0.1) deltaTime = 0.1;

            if (deltaTime >= TIME_PER_FRAME) {
                if (GameStateManager.currentScene != null) {
                    GameStateManager.currentScene.update(deltaTime, keys);
                }

                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

                if (GameStateManager.currentScene != null) {
                    GameStateManager.currentScene.render();
                }

                glfwSwapBuffers(glfwWindow);
                glfwPollEvents();

                lastFrameTime = currentTime;
            }

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Logger.error("Thread interrupted", e);
            }
        }

        cleanup();
    }

    private void init() {
        Logger.info("Initializing Window: " + width + "x" + height + " - " + title);

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwWindow = glfwCreateWindow(this.width, this.height, this.title, NULL, NULL);
        if (glfwWindow == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        glfwMakeContextCurrent(glfwWindow);
        glfwSwapInterval(1);
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);

        setupInputCallbacks();

        Logger.info("Window initialized successfully");
    }

    private void setupInputCallbacks() {
        glfwSetKeyCallback(glfwWindow, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                if (!(GameStateManager.currentScene instanceof MainMenuScene)) {
                    GameStateManager.changeScene(new MainMenuScene(width, height, glfwWindow));
                }
            }

            if (key >= 0 && key < keys.length) {
                keys[key] = (action != GLFW_RELEASE);
            }

            if (GameStateManager.currentScene != null) {
                GameStateManager.currentScene.onKeyPress(key, action);
            }
        });

        glfwSetCursorPosCallback(glfwWindow, (window, xpos, ypos) -> {
            if (GameStateManager.currentScene != null) {
                GameStateManager.currentScene.onMouseMove(xpos, ypos);
            }
        });

        glfwSetMouseButtonCallback(glfwWindow, (window, button, action, mods) -> {
            if (GameStateManager.currentScene != null) {
                double[] xpArr = new double[1], ypArr = new double[1];
                glfwGetCursorPos(glfwWindow, xpArr, ypArr);
                GameStateManager.currentScene.onMouseClick(button, action, xpArr[0], ypArr[0]);
            }
        });
    }

    private void cleanup() {
        Logger.info("Cleaning up Window");

        if (GameStateManager.currentScene != null) {
            GameStateManager.currentScene.destroy();
        }

        glfwDestroyWindow(glfwWindow);
        glfwTerminate();

        Logger.info("Window cleanup complete");
    }
}