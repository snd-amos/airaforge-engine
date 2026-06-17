package com.myengine;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

// Per-pass camera matrices. LevelEditorScene populates these once at the
// start of each render pass (shadow / main); every other renderer that
// needs to know where the camera is — selection outline, gizmo, line
// renderer, skybox, debug viz — reads from here instead of the GL matrix
// stack. Required for the Core-profile port: glPushMatrix and friends
// don't exist there, so the matrix stack can't be the source of truth.
class RenderState {

    // The view matrix is the inverse-of-camera transform that takes world
    // coords into eye coords. The projection matrix turns eye coords into
    // clip space. Both are stored both as JOML matrices (for further math)
    // and as FloatBuffers (for shader uniform uploads — JOML writes
    // column-major into a FloatBuffer, matching OpenGL).
    public static final Matrix4f viewMat = new Matrix4f();
    public static final Matrix4f projMat = new Matrix4f();
    public static final FloatBuffer viewBuf = BufferUtils.createFloatBuffer(16);
    public static final FloatBuffer projBuf = BufferUtils.createFloatBuffer(16);

    // Scratch — borrowed by callers building a model matrix or an MVP.
    // Not thread-safe (the engine is single-threaded); reset to identity
    // before use.
    public static final Matrix4f scratch = new Matrix4f();
    public static final Matrix4f scratch2 = new Matrix4f();
    public static final FloatBuffer scratchBuf = BufferUtils.createFloatBuffer(16);

    // Push the current view & projection into both the JOML and FloatBuffer
    // mirrors. Called by LevelEditorScene at the start of each pass.
    public static void setViewProj(Matrix4f view, Matrix4f proj) {
        viewMat.set(view);
        projMat.set(proj);
        viewBuf.clear();
        view.get(viewBuf);
        projBuf.clear();
        proj.get(projBuf);
    }

    // Build a TRS model matrix from a Transform (scale -> Rz -> Ry -> Rx ->
    // translate — same convention as CubeBatch.writeModelMatrix and
    // GameObject.worldBounds). Writes into `out`, returning it for chaining.
    public static Matrix4f modelMatrix(Transform t, Matrix4f out) {
        return out.identity()
                .translate(t.x, t.y, t.z)
                .rotateX((float) Math.toRadians(t.rotX))
                .rotateY((float) Math.toRadians(t.rotY))
                .rotateZ((float) Math.toRadians(t.rotZ))
                .scale(t.scaleX, t.scaleY, t.scaleZ);
    }

    // Compose MVP = projection * view * model. Writes into `out`.
    public static Matrix4f mvp(Matrix4f model, Matrix4f out) {
        return out.set(projMat).mul(viewMat).mul(model);
    }
}
