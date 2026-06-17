package com.myengine;

import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.glClearColor;

class MainMenuScene extends Scene {
    private final int screenWidth;
    private final int screenHeight;
    private final long glfwWindow;

    // ImGui (context + Glfw/GL3 impls) is owned by Window for the lifetime
    // of the app — see Window.java for why.

    private boolean startGameRequested = false;
    private boolean openEditorRequested = false;

    public MainMenuScene(int width, int height, long glfwWindow) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.glfwWindow = glfwWindow;
    }

    @Override
    public void init() {
        ImGui.getIO().setFontGlobalScale(2.2f);

        ImGuiStyle style = ImGui.getStyle();

        style.setWindowPadding(13, 13);

        style.setColor(ImGuiCol.Button,        0.2f, 0.5f, 1.0f, 1.0f);
        style.setColor(ImGuiCol.ButtonHovered, 0.3f, 0.6f, 1.0f, 1.0f);
        style.setColor(ImGuiCol.ButtonActive,  0.1f, 0.4f, 0.9f, 1.0f);
        style.setColor(ImGuiCol.WindowBg,      0.1f, 0.1f, 0.15f, 1.0f);
        style.setColor(ImGuiCol.Text,          1.0f, 1.0f, 1.0f, 1.0f);

        glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        glfwSetWindowTitle(glfwWindow, "JEngine Parkour - Main Menu");

        glClearColor(0.08f, 0.06f, 0.04f, 1.0f);
    }

    @Override
    public void update(double dt, boolean[] keys) {
        if (startGameRequested) {
            GameStateManager.changeScene(
                    new LevelEditorScene(
                            screenWidth,
                            screenHeight,
                            glfwWindow,
                            Config.GAME_LEVEL_PATH,
                            false
                    )
            );
        }

        if (openEditorRequested) {
            GameStateManager.changeScene(
                    new LevelEditorScene(
                            screenWidth,
                            screenHeight,
                            glfwWindow,
                            Config.GAME_LEVEL_PATH,
                            true
                    )
            );
        }
    }

    @Override
    public void render() {
        Window.imGuiGlfw.newFrame();
        // 1.89.0 lazy-uploads the font atlas the first time the GL backend's
        // newFrame() runs. Without this call, ImGui.newFrame() asserts:
        // "Font Atlas not built!"
        Window.imGuiGl3.newFrame();
        ImGui.newFrame();
        int w = 630, h = 630;
        // Use window size (logical points), NOT framebuffer pixels, for
        // ImGui positioning. ImGui's DisplaySize is in points. On a Retina
        // Mac the framebuffer is 2x the window — `screenWidth` (passed
        // from Window.java) is framebuffer pixels for the viewport, but
        // setNextWindowPos needs points.
        int[] wpx = new int[1], hpx = new int[1];
        glfwGetWindowSize(glfwWindow, wpx, hpx);
        ImGui.setNextWindowSize(w, h);
        ImGui.setNextWindowPos(
                wpx[0] / 2.0f - (float) w / 2,
                hpx[0] / 2.0f - (float) h / 2
        );

        ImGui.begin("Main Menu");

        ImGui.text("JEngine Parkour");
        ImGui.separator();
        int bw = 600, bh = 175;
        if (ImGui.button("Start Game", bw, bh)) {
            startGameRequested = true;
        }

        if (ImGui.button("Open Level Editor", bw, bh)) {
            openEditorRequested = true;
        }

        if (ImGui.button("Quit", bw, bh)) {
            glfwSetWindowShouldClose(glfwWindow, true);
        }

        ImGui.end();

        ImGui.render();
        Window.imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    @Override
    public void destroy() {
        // ImGui is app-lifetime now — nothing per-scene to tear down here.
    }

    @Override
    public void onMouseClick(int button, int action, double mouseX, double mouseY) {
    }

    @Override
    public void onMouseMove(double xpos, double ypos) {
    }

    @Override
    public void onKeyPress(int key, int action) {
    }
}
