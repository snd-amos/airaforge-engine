package com.myengine;

import imgui.ImGui;
import imgui.ImGuiPlatformIO;
import imgui.ImGuiViewport;
import imgui.ImVec2;
import imgui.callback.ImPlatformFuncViewport;
import imgui.callback.ImPlatformFuncViewportImVec2;
import imgui.callback.ImPlatformFuncViewportString;
import imgui.callback.ImPlatformFuncViewportSuppBoolean;
import imgui.callback.ImPlatformFuncViewportSuppImVec2;
import imgui.flag.ImGuiViewportFlags;
import imgui.glfw.ImGuiImplGlfw;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Hand-rolled GLFW <b>platform</b> backend for Dear ImGui multi-viewport,
 * replacing SpaiR's {@code ImGuiImplGlfw} platform interface for the detached-
 * window case. The bundled {@code ImGuiImplGlfw} initialises in OpenGL mode and
 * its {@code Platform_CreateWindow} makes GL-context child windows; against our
 * Vulkan {@code GLFW_NO_API} main window {@code glfwCreateWindow} returns NULL
 * and {@code glfwShowWindow} NPEs. This class creates every child window with
 * {@code GLFW_CLIENT_API = GLFW_NO_API} so a Vulkan surface can be made for it
 * by {@link VkViewportRenderer} (the renderer half).
 *
 * <p>We keep {@code ImGuiImplGlfw} for main-window input + monitor enumeration;
 * we only take over the platform <i>window-management</i> callbacks. The GLFW
 * window handle is stored on the viewport via {@link ImGuiViewport#setPlatformHandle}
 * (so the renderer's {@code glfwCreateWindowSurface} can read it) and mirrored
 * in an id-keyed map for the getter callbacks.
 *
 * <p>Scope: window create / destroy / show / move / resize / focus / minimise /
 * title — enough for panels to detach, render and be driven by ImGui's own
 * title bars. Per-child-window input <i>event</i> callbacks (button/key/scroll)
 * are a later refinement; mouse-position routing already works because ImGui's
 * GLFW input path reads each viewport's {@code PlatformHandle}, which we set.
 */
final class VkGlfwPlatformIO {

    private final long mainWindow;
    private final int  mainViewportId;
    // Kept so child windows can reuse ImGuiImplGlfw's existing input-callback
    // handlers (button/key/scroll/char/focus/cursor/close) — same functions
    // it installs on the main window, just attached to each detached window.
    private final ImGuiImplGlfw imGuiGlfw;

    // viewport id -> GLFWwindow*. Seeded with the main viewport so getter
    // callbacks invoked on it resolve to the real main window.
    private final Map<Integer, Long> windows = new HashMap<>();

    VkGlfwPlatformIO(long mainWindow, ImGuiImplGlfw imGuiGlfw) {
        this.mainWindow = mainWindow;
        this.imGuiGlfw  = imGuiGlfw;
        this.mainViewportId = ImGui.getMainViewport().getID();
        windows.put(mainViewportId, mainWindow);
    }

    /** Resolve the GLFW window for a viewport (0 if unknown). */
    private long handle(ImGuiViewport vp) {
        Long h = windows.get(vp.getID());
        return h == null ? 0L : h;
    }

    /** Wire every platform window-management callback onto ImGui's platform IO. */
    void register(ImGuiPlatformIO io) {
        io.setPlatformCreateWindow(new ImPlatformFuncViewport() {
            @Override public void accept(ImGuiViewport vp) { createWindow(vp); }
        });
        io.setPlatformDestroyWindow(new ImPlatformFuncViewport() {
            @Override public void accept(ImGuiViewport vp) { destroyWindow(vp); }
        });
        io.setPlatformShowWindow(new ImPlatformFuncViewport() {
            @Override public void accept(ImGuiViewport vp) {
                long h = handle(vp);
                if (h != 0L) glfwShowWindow(h);
            }
        });
        io.setPlatformSetWindowPos(new ImPlatformFuncViewportImVec2() {
            @Override public void accept(ImGuiViewport vp, ImVec2 pos) {
                long h = handle(vp);
                if (h != 0L) glfwSetWindowPos(h, (int) pos.x, (int) pos.y);
            }
        });
        io.setPlatformGetWindowPos(new ImPlatformFuncViewportSuppImVec2() {
            @Override public void get(ImGuiViewport vp, ImVec2 dst) {
                long h = handle(vp);
                if (h == 0L) { dst.set(0, 0); return; }
                int[] x = new int[1], y = new int[1];
                glfwGetWindowPos(h, x, y);
                dst.set(x[0], y[0]);
            }
        });
        io.setPlatformSetWindowSize(new ImPlatformFuncViewportImVec2() {
            @Override public void accept(ImGuiViewport vp, ImVec2 size) {
                long h = handle(vp);
                if (h != 0L) glfwSetWindowSize(h, (int) size.x, (int) size.y);
            }
        });
        io.setPlatformGetWindowSize(new ImPlatformFuncViewportSuppImVec2() {
            @Override public void get(ImGuiViewport vp, ImVec2 dst) {
                long h = handle(vp);
                if (h == 0L) { dst.set(0, 0); return; }
                int[] w = new int[1], hh = new int[1];
                glfwGetWindowSize(h, w, hh);
                dst.set(w[0], hh[0]);
            }
        });
        io.setPlatformSetWindowFocus(new ImPlatformFuncViewport() {
            @Override public void accept(ImGuiViewport vp) {
                long h = handle(vp);
                if (h != 0L) glfwFocusWindow(h);
            }
        });
        io.setPlatformGetWindowFocus(new ImPlatformFuncViewportSuppBoolean() {
            @Override public boolean get(ImGuiViewport vp) {
                long h = handle(vp);
                return h != 0L && glfwGetWindowAttrib(h, GLFW_FOCUSED) != 0;
            }
        });
        io.setPlatformGetWindowMinimized(new ImPlatformFuncViewportSuppBoolean() {
            @Override public boolean get(ImGuiViewport vp) {
                long h = handle(vp);
                return h != 0L && glfwGetWindowAttrib(h, GLFW_ICONIFIED) != 0;
            }
        });
        io.setPlatformSetWindowTitle(new ImPlatformFuncViewportString() {
            @Override public void accept(ImGuiViewport vp, String title) {
                long h = handle(vp);
                if (h != 0L) glfwSetWindowTitle(h, title);
            }
        });
    }

    // ============================================================
    //                    Create / destroy
    // ============================================================

    private void createWindow(ImGuiViewport vp) {
        int flags = vp.getFlags();
        // STABILITY: every child window is GLFW_NO_API so a Vulkan surface can
        // be created for it (the whole reason this class exists). Hidden until
        // ImGui's ShowWindow; ImGui draws its own title bar, so undecorated.
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
        // Issue 1: force an OPAQUE framebuffer so the OS compositor can't make
        // the detached window's background transparent (desktop bleed).
        glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED,
                (flags & ImGuiViewportFlags.NoDecoration) != 0 ? GLFW_FALSE : GLFW_TRUE);
        glfwWindowHint(GLFW_FLOATING,
                (flags & ImGuiViewportFlags.TopMost) != 0 ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

        int w = Math.max(1, (int) vp.getSizeX());
        int h = Math.max(1, (int) vp.getSizeY());
        long win = glfwCreateWindow(w, h, "Viewport", 0L, 0L);
        if (win == 0L) {
            Logger.warn("[VK] glfwCreateWindow(NO_API) failed for detached viewport " + vp.getID());
            return;
        }
        glfwSetWindowPos(win, (int) vp.getPosX(), (int) vp.getPosY());

        // Route this child window's input through ImGuiImplGlfw's existing
        // handlers (they resolve the owning viewport via PlatformHandle, which
        // we set below) — without this, an undecorated detached panel couldn't
        // even be clicked or dragged. Mirrors Dear ImGui's own CreateWindow.
        glfwSetMouseButtonCallback(win, imGuiGlfw::mouseButtonCallback);
        glfwSetScrollCallback(win, imGuiGlfw::scrollCallback);
        glfwSetKeyCallback(win, imGuiGlfw::keyCallback);
        glfwSetCharCallback(win, imGuiGlfw::charCallback);
        glfwSetCursorPosCallback(win, imGuiGlfw::cursorPosCallback);
        glfwSetCursorEnterCallback(win, imGuiGlfw::cursorEnterCallback);
        glfwSetWindowFocusCallback(win, imGuiGlfw::windowFocusCallback);
        // (windowCloseCallback is private in ImGuiImplGlfw; our child windows
        //  are undecorated, so ImGui handles panel close itself — not needed.)

        windows.put(vp.getID(), win);
        // The renderer half reads this to build the Vulkan surface, and
        // ImGuiImplGlfw's callbacks resolve the viewport by this handle.
        vp.setPlatformHandle(win);
    }

    private void destroyWindow(ImGuiViewport vp) {
        Long h = windows.remove(vp.getID());
        // Never destroy the main window here — it's owned by the engine.
        if (h != null && h != mainWindow) {
            org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(h);   // release the input closures
            glfwDestroyWindow(h);
        }
        vp.setPlatformHandle(0L);
    }

    // ============================================================
    //                         Teardown
    // ============================================================

    /** Destroy any child windows still standing (ImGui normally destroys them
     *  via the DestroyWindow callback, but this guards against leaks on an
     *  abrupt shutdown). Never touches the main window. */
    void destroy() {
        for (Map.Entry<Integer, Long> e : windows.entrySet()) {
            long h = e.getValue();
            if (h != 0L && h != mainWindow) {
                org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(h);
                glfwDestroyWindow(h);
            }
        }
        windows.clear();
    }
}
