package com.myengine;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

// Batched 3D line drawing — single static `drawLines(verts, r, g, b)` entry
// point. Replaces every `glBegin(GL_LINES) / glVertex3f / glEnd` pattern in
// the engine (wireframe AABBs, capsule outlines, skeleton debug viz, gizmo
// rings) with a VAO/VBO/shader path. Required for the macOS Core-profile
// port, since the legacy immediate-mode functions are removed there.
//
// Single shared VAO + VBO + line shader; the VBO is reallocated on demand
// when a caller passes more vertices than fit. Each call is one
// glDrawArrays(GL_LINES) — caller-side batching (one drawLines per logical
// shape) is good enough for now.
class LineRenderer3D {

    private static int vao = 0;
    private static int vbo = 0;
    private static Shader shader = null;
    private static FloatBuffer cpuBuf = null;
    private static int cpuCapacity = 0;
    private static final FloatBuffer mvpBuf = BufferUtils.createFloatBuffer(16);
    private static final Matrix4f tmpModel = new Matrix4f();
    private static final Matrix4f tmpMvp = new Matrix4f();

    private static void ensureInit() {
        if (shader != null) return;
        shader = new Shader("line.vert", "line.frag");
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        // Attribute 0 = vec3 position (only attribute; line colour is a
        // uniform set per drawLines call).
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glBindVertexArray(0);
    }

    private static void ensureCpuBuf(int floatCount) {
        if (cpuBuf == null || cpuCapacity < floatCount) {
            if (cpuBuf != null) MemoryUtil.memFree(cpuBuf);
            cpuCapacity = Math.max(floatCount, 256);
            cpuBuf = MemoryUtil.memAllocFloat(cpuCapacity);
        }
    }

    // Draws `verts.length / 6` line segments. Each segment is 6 floats:
    // (x0, y0, z0, x1, y1, z1). Colour is uniform across the call.
    //
    // The current GL_MODELVIEW + GL_PROJECTION matrices (set by the caller's
    // glPushMatrix / glTranslatef / glRotatef / glScalef calls) are read by
    // the vertex shader via `gl_ModelViewProjectionMatrix`. That's a Compat-
    // profile feature; the final Core-profile port (Phase 4) will replace
    // it with an explicit `uniform mat4 mvp` and pass it from the caller.
    // Draws lines in the world. `model` is the local-to-world transform
    // (identity if vertices are already in world space); RenderState
    // supplies the camera's current view * projection. MVP = P * V * M.
    public static void drawLines(float[] verts, Matrix4f model, float r, float g, float b) {
        ensureInit();
        ensureCpuBuf(verts.length);

        cpuBuf.clear();
        cpuBuf.put(verts).flip();

        RenderState.mvp(model, tmpMvp);
        mvpBuf.clear();
        tmpMvp.get(mvpBuf);

        shader.use();
        shader.setMat4("mvp", mvpBuf);
        shader.setVec3("lineColor", r, g, b);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, cpuBuf, GL_DYNAMIC_DRAW);
        glDrawArrays(GL_LINES, 0, verts.length / 3);
        glBindVertexArray(0);

        shader.detach();
    }

    // Convenience: vertices already in world space (identity model).
    public static void drawLinesWorld(float[] verts, float r, float g, float b) {
        drawLines(verts, tmpModel.identity(), r, g, b);
    }

    // Renders an existing Mesh's triangles with the flat line shader. Used
    // by the gizmo's solid scale cube and the sun cube. Caller supplies
    // the model matrix; the projection × view comes from RenderState.
    public static void drawMesh(Mesh mesh, Matrix4f model, float r, float g, float b) {
        ensureInit();
        RenderState.mvp(model, tmpMvp);
        mvpBuf.clear();
        tmpMvp.get(mvpBuf);

        shader.use();
        shader.setMat4("mvp", mvpBuf);
        shader.setVec3("lineColor", r, g, b);
        mesh.render();
        shader.detach();
    }
}
