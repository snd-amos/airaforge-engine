package com.myengine;

import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.glClearColor;

class MainMenuScene extends Scene {
    private final int screenWidth;
    private final int screenHeight;
    private final long glfwWindow;

    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();

    private boolean startGameRequested = false;
    private boolean openEditorRequested = false;

    public MainMenuScene(int width, int height, long glfwWindow) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.glfwWindow = glfwWindow;
    }

    @Override
    public void init() {
        ImGui.createContext();

        ImGui.getIO().getFonts().addFontFromFileTTF("arial.ttf", 26.0f);

        ImGuiStyle style = ImGui.getStyle();

        style.setWindowPadding(13, 13);

        style.setColor(ImGuiCol.Button,        0.2f, 0.5f, 1.0f, 1.0f);
        style.setColor(ImGuiCol.ButtonHovered, 0.3f, 0.6f, 1.0f, 1.0f);
        style.setColor(ImGuiCol.ButtonActive,  0.1f, 0.4f, 0.9f, 1.0f);
        style.setColor(ImGuiCol.WindowBg,      0.1f, 0.1f, 0.15f, 1.0f);
        style.setColor(ImGuiCol.Text,          1.0f, 1.0f, 1.0f, 1.0f);
        ImGui.getIO().addConfigFlags(imgui.flag.ImGuiConfigFlags.DockingEnable);

        imGuiGlfw.init(glfwWindow, false);
        imGuiGl3.init("#version 130");

        glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        glfwSetWindowTitle(glfwWindow, "SNGLTY Parkour - Main Menu");

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
        imGuiGlfw.newFrame();
        ImGui.newFrame();
        int w = 630, h = 630;
        ImGui.setNextWindowSize(w, h);
        ImGui.setNextWindowPos(
                screenWidth / 2.0f - (float) w / 2,
                screenHeight / 2.0f - (float) h / 2
        );

        ImGui.begin("Main Menu");

        ImGui.text("SNGLTY Parkour");
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
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    @Override
    public void destroy() {
        imGuiGl3.dispose();
        imGuiGlfw.dispose();
        ImGui.destroyContext();
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
