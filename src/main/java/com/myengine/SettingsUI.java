package com.myengine;

import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import org.lwjgl.glfw.GLFWVidMode;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Shared "Settings" modal, rendered from both the Main Menu and the editor
 * menu bar. Three dropdowns — Display Mode, Aspect Ratio, Resolution — that
 * actually drive the GLFW window via {@code glfwSetWindowMonitor} /
 * {@code glfwSetWindowAttrib} / {@code glfwSetWindowAspectRatio}.
 *
 * <p>Applying a change resizes the window / switches monitor mode, which
 * invalidates the Vulkan swapchain — the caller passes an {@code onApply}
 * callback ({@code () -> framebufferResized = true}) so VulkanMain recreates
 * the swapchain on the next present.
 */
final class SettingsUI {

    private static final String[] DISPLAY_MODES = { "Windowed", "Fullscreen Windowed (Borderless)", "Fullscreen" };
    private static final String[] ASPECTS       = { "4:3", "16:9", "16:10", "21:9" };
    private static final int[][]  ASPECT_WH     = { { 4, 3 }, { 16, 9 }, { 16, 10 }, { 21, 9 } };
    private static final String[] RESOLUTIONS   = { "2560x1440", "1920x1080", "1280x720", "720x480" };
    private static final int[][]  RES_WH        = { { 2560, 1440 }, { 1920, 1080 }, { 1280, 720 }, { 720, 480 } };

    // Dropdown state — seeded from the persisted settings.json on construction.
    private final ImInt displayMode = new ImInt(0);
    private final ImInt aspect      = new ImInt(1);
    private final ImInt resolution  = new ImInt(2);

    private boolean openRequested = false;

    // Fixed window box so the popup is byte-for-byte identical whether opened
    // from the Main Menu or the Editor.
    private static final float WIN_W = 520f, WIN_H = 300f;

    SettingsUI() {
        fromSettings(DisplaySettings.load());   // restore the user's last choice
    }

    /** Queue the modal to open on the next {@link #render} call. */
    void open() { openRequested = true; }

    /** Render the modal (call every frame). {@code window} is the main GLFW
     *  window; {@code onApply} runs after a GLFW size/monitor change so the
     *  caller can recreate the Vulkan swapchain. */
    void render(long window, Runnable onApply) {
        if (openRequested) { ImGui.openPopup("Settings"); openRequested = false; }

        // Enforce a fixed size + screen-centered position so the window can't
        // inherit different geometry from the Main Menu vs the Editor context.
        ImGuiViewport vp = ImGui.getMainViewport();
        ImGui.setNextWindowSize(WIN_W, WIN_H, ImGuiCond.Appearing);
        ImGui.setNextWindowPos(vp.getCenterX(), vp.getCenterY(), ImGuiCond.Appearing, 0.5f, 0.5f);

        // Explicit padding/spacing so style doesn't leak in from either scene.
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 18f, 18f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 12f, 12f);
        boolean open = ImGui.beginPopupModal("Settings", ImGuiWindowFlags.NoResize);
        if (open) {
            // Normalize against the global font scale (2.2x at the Main Menu,
            // 1.0x in the Editor) so text + widgets render the same absolute
            // size in both — the actual cause of the inconsistency.
            float gs = ImGui.getIO().getFontGlobalScale();
            ImGui.setWindowFontScale(1.0f / (gs > 0.01f ? gs : 1.0f));

            ImGui.pushItemWidth(WIN_W - 220f);
            ImGui.combo("Display Mode", displayMode, DISPLAY_MODES);
            ImGui.combo("Aspect Ratio", aspect, ASPECTS);
            ImGui.combo("Resolution", resolution, RESOLUTIONS);
            ImGui.popItemWidth();

            ImGui.separator();
            if (ImGui.button("Apply", 150, 0)) {
                apply(window);
                if (onApply != null) onApply.run();
            }
            ImGui.sameLine();
            if (ImGui.button("Close", 150, 0)) ImGui.closeCurrentPopup();
            ImGui.endPopup();
        }
        ImGui.popStyleVar(2);
    }

    private void apply(long window) {
        // Persist FIRST so the choice survives a restart (the resolution bug),
        // then drive the live GLFW window.
        DisplaySettings s = toSettings();
        s.save();
        applyToWindow(window, s);
        Logger.info("[VK] Settings applied + saved: " + DISPLAY_MODES[displayMode.get()]
                + ", " + RESOLUTIONS[resolution.get()] + ", " + ASPECTS[aspect.get()]);
    }

    /** Apply persisted display settings to an existing GLFW window. Shared by
     *  the Settings UI (on Apply) and {@code VulkanMain.initWindow} (on boot,
     *  for fullscreen / borderless modes). */
    static void applyToWindow(long window, DisplaySettings s) {
        int w = s.windowWidth, h = s.windowHeight;
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode mode = glfwGetVideoMode(monitor);
        int monW    = (mode != null) ? mode.width()       : w;
        int monH    = (mode != null) ? mode.height()      : h;
        int refresh = (mode != null) ? mode.refreshRate() : GLFW_DONT_CARE;

        switch (s.fullscreenMode) {
            case 1: // Fullscreen Windowed (Borderless) — undecorated, covers the monitor
                glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_FALSE);
                glfwSetWindowAspectRatio(window, GLFW_DONT_CARE, GLFW_DONT_CARE);
                glfwSetWindowMonitor(window, 0L, 0, 0, monW, monH, GLFW_DONT_CARE);
                break;
            case 2: // Exclusive fullscreen at the chosen resolution
                glfwSetWindowAspectRatio(window, GLFW_DONT_CARE, GLFW_DONT_CARE);
                glfwSetWindowMonitor(window, monitor, 0, 0, w, h, refresh);
                break;
            default: // 0 — Windowed at the chosen resolution
                // Stay BORDERLESS (the engine ships a custom ImGui title bar).
                // Re-enabling GLFW_DECORATED here brought the OS title bar back
                // on a fullscreen→windowed switch → "double bar" (Phase 1 fix).
                glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_FALSE);
                glfwSetWindowMonitor(window, 0L, 120, 120, w, h, GLFW_DONT_CARE);
                int[] ar = aspectWH(s.aspectRatio);
                glfwSetWindowAspectRatio(window, ar[0], ar[1]);
                break;
        }
    }

    private DisplaySettings toSettings() {
        DisplaySettings s = new DisplaySettings();
        int[] wh = RES_WH[resolution.get()];
        s.windowWidth   = wh[0];
        s.windowHeight  = wh[1];
        s.aspectRatio   = ASPECTS[aspect.get()];
        s.fullscreenMode = displayMode.get();
        return s;
    }

    private void fromSettings(DisplaySettings s) {
        displayMode.set(Math.max(0, Math.min(DISPLAY_MODES.length - 1, s.fullscreenMode)));
        aspect.set(aspectIndex(s.aspectRatio));
        resolution.set(resolutionIndex(s.windowWidth, s.windowHeight));
    }

    private static int resolutionIndex(int w, int h) {
        for (int i = 0; i < RES_WH.length; i++) {
            if (RES_WH[i][0] == w && RES_WH[i][1] == h) return i;
        }
        return 2;   // default 1280x720
    }

    private static int aspectIndex(String a) {
        for (int i = 0; i < ASPECTS.length; i++) {
            if (ASPECTS[i].equals(a)) return i;
        }
        return 1;   // default 16:9
    }

    private static int[] aspectWH(String a) {
        return ASPECT_WH[aspectIndex(a)];
    }
}
