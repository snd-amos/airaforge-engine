package com.myengine;

import imgui.ImGui;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

class Window {
    // ImGui lives for the whole app, NOT per scene. imgui-java 1.89.0's
    // shutdown() leaves dead callbacks chained on the GLFW window, so
    // re-init from a second scene corrupts the chain (NPE in keyCallback
    // accessing a null `data` field on the previous shutdown'd instance,
    // see CLAUDE.md). One context + one Glfw/GL3 impl pair for the
    // lifetime of the app sidesteps the entire mess.
    static ImGuiImplGlfw imGuiGlfw;
    static ImGuiImplGl3  imGuiGl3;
    // Not final: set to the monitor resolution in init() for windowed
    // fullscreen. The requested values are just a fallback.
    private int width, height;
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
                Profiler.frameStart();
                if (GameStateManager.currentScene != null) {
                    try (var z = Profiler.zone("Update")) {
                        GameStateManager.currentScene.update(deltaTime, keys);
                    }
                }

                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

                if (GameStateManager.currentScene != null) {
                    try (var z = Profiler.zone("Render")) {
                        GameStateManager.currentScene.render();
                    }
                }

                try (var z = Profiler.zone("Swap + poll")) {
                    glfwSwapBuffers(glfwWindow);
                    glfwPollEvents();
                }
                Profiler.frameEnd();

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

        // Normal decorated window (keeps the title bar, minimize and X
        // buttons), started maximized so it fills the screen on launch.
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_MAXIMIZED, GLFW_TRUE);

        // Request an OpenGL 3.3 Core profile context. Required for macOS,
        // which has no Compat profile above 2.1 — our shaders (#version
        // 150+) won't compile there without this. The forward-compat flag
        // is mandatory on macOS for any 3.2+ context; harmless on Windows
        // and Linux. We're past every fixed-function GL call now, so Core
        // is the right home everywhere.
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        glfwWindow = glfwCreateWindow(this.width, this.height, this.title, NULL, NULL);
        if (glfwWindow == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        // Read the actual FRAMEBUFFER size (in pixels), not the window size
        // (in logical points). On a Retina Mac the framebuffer is 2× the
        // window size — using window size for glViewport leaves the top
        // and right halves of the framebuffer unrendered. On Windows /
        // Linux without HiDPI scaling, framebuffer == window so this
        // matches the previous behaviour.
        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(glfwWindow, w, h);
        this.width = w[0];
        this.height = h[0];

        glfwMakeContextCurrent(glfwWindow);
        glfwSwapInterval(1);
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);

        setupInputCallbacks();

        // ImGui init AFTER our own GLFW callbacks are installed — ImGui
        // chains, so it needs Window's callbacks to be in place first.
        // installCallbacks=true: ImGui replaces the GLFW callbacks with
        // its own and chains to ours, so UI clicks/keys are seen by ImGui
        // AND still fire our handlers. installCallbacks=false used to
        // work in 1.86 (it polled directly) but 1.89 needs the chain.
        ImGui.createContext();
        ImGui.getIO().addConfigFlags(imgui.flag.ImGuiConfigFlags.DockingEnable);
        imGuiGlfw = new ImGuiImplGlfw();
        imGuiGl3  = new ImGuiImplGl3();
        imGuiGlfw.init(glfwWindow, true);
        imGuiGl3.init("#version 150");

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

        if (imGuiGl3 != null)  imGuiGl3.shutdown();
        if (imGuiGlfw != null) imGuiGlfw.shutdown();
        ImGui.destroyContext();

        glfwDestroyWindow(glfwWindow);
        glfwTerminate();

        Logger.info("Window cleanup complete");
    }
}
