package com.myengine;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

// Screen-space 2D drawing — filled quads, line outlines, points. Inputs are
// in NDC (-1..1 on both axes); a caller wanting pixel coords does a quick
// conversion (helpers below). Replaces the ortho-projection + immediate-
// mode glBegin(GL_QUADS) overlay patterns (crosshair, stamina bar,
// box-select rectangle) — required for the macOS Core-profile port.
class Renderer2D {

    private static int vao = 0;
    private static int vbo = 0;
    private static Shader shader = null;
    private static FloatBuffer cpuBuf = null;
    private static int cpuCapacity = 0;

    private static void ensureInit() {
        if (shader != null) return;
        shader = new Shader("renderer2d.vert", "renderer2d.frag");
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        // Attribute 0 = vec2 position in NDC. Other locations (texcoord,
        // normal, ...) are unused; the line shader's location-0 binding from
        // Shader.java covers aPos for this shader too.
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glBindVertexArray(0);
    }

    private static void ensureCpuBuf(int floatCount) {
        if (cpuBuf == null || cpuCapacity < floatCount) {
            if (cpuBuf != null) MemoryUtil.memFree(cpuBuf);
            cpuCapacity = Math.max(floatCount, 64);
            cpuBuf = MemoryUtil.memAllocFloat(cpuCapacity);
        }
    }

    private static void draw(float[] xy, int primitive,
                             float r, float g, float b, float a) {
        ensureInit();
        ensureCpuBuf(xy.length);
        cpuBuf.clear();
        cpuBuf.put(xy).flip();

        shader.use();
        shader.setVec4("color", r, g, b, a);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, cpuBuf, GL_DYNAMIC_DRAW);
        glDrawArrays(primitive, 0, xy.length / 2);
        glBindVertexArray(0);
        shader.detach();
    }

    // Filled axis-aligned rectangle in NDC. Two triangles per quad.
    public static void fillRect(float x0, float y0, float x1, float y1,
                                float r, float g, float b, float a) {
        float[] v = {
                x0, y0,  x1, y0,  x1, y1,
                x0, y0,  x1, y1,  x0, y1
        };
        draw(v, GL_TRIANGLES, r, g, b, a);
    }

    // Outline of an axis-aligned rectangle in NDC (4 separate lines).
    public static void outlineRect(float x0, float y0, float x1, float y1,
                                   float r, float g, float b, float a) {
        float[] v = {
                x0, y0,  x1, y0,
                x1, y0,  x1, y1,
                x1, y1,  x0, y1,
                x0, y1,  x0, y0
        };
        draw(v, GL_LINES, r, g, b, a);
    }

    public static void drawPoint(float x, float y, float r, float g, float b, float a) {
        draw(new float[]{ x, y }, GL_POINTS, r, g, b, a);
    }

    // Helpers — convert pixel coordinates with top-left origin to NDC, the
    // way drawBoxSelectOverlay used to pose its quads via glOrtho(0, w, h, 0).
    public static float pxX(float pixelX, int viewportW) {
        return (pixelX / (float) viewportW) * 2f - 1f;
    }
    public static float pxY(float pixelY, int viewportH) {
        return 1f - (pixelY / (float) viewportH) * 2f;
    }
}
