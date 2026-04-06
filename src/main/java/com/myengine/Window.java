package com.myengine;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Main window and event loop management.
 * Handles GLFW initialization, input callbacks, and frame timing.
 */
public class Window {
    private final int width, height;
    private final String title;
    private long glfwWindow;

    private final boolean[] keys = new boolean[348];

    private static final double TARGET_FPS = 120.0;
    private static final double TIME_PER_FRAME = 1.0 / TARGET_FPS;

    /**
     * Create a new window.
     */
    public Window(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.title = title;
    }

    /**
     * Run the main game loop.
     */
    public void run() {
        init();
        Logger.info("Starting main game loop at " + TARGET_FPS + " FPS");

        GameStateManager.changeScene(new LevelEditorScene(width, height, glfwWindow));

        double lastFrameTime = glfwGetTime();

        while (!glfwWindowShouldClose(glfwWindow)) {
            double currentTime = glfwGetTime();
            double deltaTime = currentTime - lastFrameTime;

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

    /**
     * Initialize OpenGL and GLFW.
     */
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

    /**
     * Set up GLFW input callbacks.
     */
    private void setupInputCallbacks() {
        glfwSetKeyCallback(glfwWindow, (window, key, scancode, action, mods) -> {
            // Global escape key to close
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                glfwSetWindowShouldClose(glfwWindow, true);
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

    /**
     * Clean up and destroy the window.
     */
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