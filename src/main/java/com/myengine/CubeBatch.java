package com.myengine;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

// Instanced batch renderer for procedural cubes. Each frame the scene
// collects all instanceable cubes (procedural, no special material) and
// flushes them with one glDrawElementsInstanced call per (texture +
// tiling-on/off + foliage-state) group. Replaces what used to be N
// glPushMatrix/glTranslatef/glDrawElements calls — for N=15000 cubes the
// driver-call overhead drops by ~3 orders of magnitude.
class CubeBatch {

    // Per-instance data layout:
    //   floats 0..15  — mat4 model
    //   floats 16..33 — 6 RGB face colours (+X, -X, +Y, -Y, +Z, -Z)
    // Uniform-coloured cubes set all 6 face colours equal, so a single
    // colour-tint workflow still works at no extra runtime cost.
    private static final int FLOATS_PER_INSTANCE = 16 + 18;
    private static final int STRIDE_BYTES = FLOATS_PER_INSTANCE * Float.BYTES;
    private static final int FACE_COLORS_OFFSET = 16;

    private int instanceVbo = 0;
    private FloatBuffer cpuBuf;
    private int cpuCapacity = 0;

    // Group cubes by (textureID, tiling, foliage, textureScale-rounded). Each
    // group renders as one instanced draw call.
    private final Map<GroupKey, List<float[]>> groups = new HashMap<>();

    private record GroupKey(int textureID, boolean tiling, boolean foliage, float scale) {}

    public void clear() {
        groups.clear();
    }

    public void submit(GameObject obj, MeshRenderer mr) {
        Material m = mr.material;
        boolean tiling = Boolean.TRUE.equals(m.useWorldSpaceTiling);
        // Round textureScale so tiny float drift doesn't create new groups.
        float scaleKey = Math.round(m.textureScale * 1000f) / 1000f;
        GroupKey key = new GroupKey(mr.textureID, tiling, mr.isFoliage, scaleKey);

        float[] data = new float[FLOATS_PER_INSTANCE];
        writeModelMatrix(data, 0, obj.transform);
        m.writeFaceColorsInto(data, FACE_COLORS_OFFSET);
        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(data);
    }

    public int instanceCount() {
        int total = 0;
        for (List<float[]> g : groups.values()) total += g.size();
        return total;
    }

    // Draw all collected groups using the given shader. Caller is responsible
    // for: shader.use(), setting view/proj/light uniforms, binding the cube
    // mesh VAO, calling glDrawElementsInstanced-compatible state. The cube
    // mesh VAO must already be bound when this is called.
    public void flush(Mesh cubeMesh, Shader shader, boolean writeColorUniforms) {
        if (groups.isEmpty()) return;

        glBindVertexArray(cubeMesh.vaoId());
        ensureInstanceVbo();

        for (Map.Entry<GroupKey, List<float[]>> e : groups.entrySet()) {
            GroupKey k = e.getKey();
            List<float[]> list = e.getValue();
            int n = list.size();
            if (n == 0) continue;

            ensureBuffer(n);
            cpuBuf.clear();
            for (float[] inst : list) cpuBuf.put(inst);
            cpuBuf.flip();

            glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
            glBufferData(GL_ARRAY_BUFFER, cpuBuf, GL_DYNAMIC_DRAW);

            // 4 vec4s for mat4 at locations 6..9, then 6 vec3 face colours
            // at locations 10..15 (+X, -X, +Y, -Y, +Z, -Z).
            for (int col = 0; col < 4; col++) {
                int loc = 6 + col;
                glEnableVertexAttribArray(loc);
                glVertexAttribPointer(loc, 4, GL_FLOAT, false, STRIDE_BYTES, (long)(col * 4 * Float.BYTES));
                glVertexAttribDivisor(loc, 1);
            }
            for (int f = 0; f < 6; f++) {
                int loc = 10 + f;
                glEnableVertexAttribArray(loc);
                glVertexAttribPointer(loc, 3, GL_FLOAT, false, STRIDE_BYTES,
                        (long)((FACE_COLORS_OFFSET + f * 3) * Float.BYTES));
                glVertexAttribDivisor(loc, 1);
            }

            if (writeColorUniforms) {
                shader.setInt("useTexture", k.textureID > 0 ? 1 : 0);
                shader.setInt("useWorldSpaceTiling", k.tiling ? 1 : 0);
                shader.setFloat("textureScale", k.scale);
                if (k.textureID > 0) {
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, k.textureID);
                    shader.setInt("texture_diffuse", 0);
                }
            } else {
                // Shadow pass: still needs the diffuse for foliage alpha-discard.
                if (k.foliage && k.textureID > 0) {
                    shader.setInt("useTexture", 1);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, k.textureID);
                    shader.setInt("texture_diffuse", 0);
                } else {
                    shader.setInt("useTexture", 0);
                }
            }

            glDrawElementsInstanced(GL_TRIANGLES, cubeMesh.vertexCount(), GL_UNSIGNED_INT, 0, n);
        }

        // Disable the divisor attributes so subsequent non-instanced draws on
        // the same VAO don't pick up stale per-instance state.
        for (int loc = 6; loc <= 15; loc++) {
            glVertexAttribDivisor(loc, 0);
            glDisableVertexAttribArray(loc);
        }
        glBindVertexArray(0);
    }

    private void ensureInstanceVbo() {
        if (instanceVbo == 0) instanceVbo = glGenBuffers();
    }

    private void ensureBuffer(int instanceCount) {
        int needed = instanceCount * FLOATS_PER_INSTANCE;
        if (cpuBuf == null || cpuCapacity < needed) {
            if (cpuBuf != null) MemoryUtil.memFree(cpuBuf);
            cpuCapacity = Math.max(needed, 1024);
            cpuBuf = MemoryUtil.memAllocFloat(cpuCapacity);
        }
    }

    // Writes (scale → Rz → Ry → Rx → translate) in column-major order, matching
    // the convention used by computeWorldPositions and the rest of the engine.
    private static void writeModelMatrix(float[] out, int off, Transform t) {
        float sx = t.scaleX, sy = t.scaleY, sz = t.scaleZ;
        double rx = Math.toRadians(t.rotX);
        double ry = Math.toRadians(t.rotY);
        double rz = Math.toRadians(t.rotZ);
        float crx = (float) Math.cos(rx), srx = (float) Math.sin(rx);
        float cry = (float) Math.cos(ry), sry = (float) Math.sin(ry);
        float crz = (float) Math.cos(rz), srz = (float) Math.sin(rz);

        // Build Rx * Ry * Rz (so the combined operation on a vector is
        // Rx(Ry(Rz(v)))), matching the per-vertex worldBounds() math.
        float r00 =  cry * crz;
        float r01 = -cry * srz;
        float r02 =  sry;
        float r10 =  srx * sry * crz + crx * srz;
        float r11 = -srx * sry * srz + crx * crz;
        float r12 = -srx * cry;
        float r20 = -crx * sry * crz + srx * srz;
        float r21 =  crx * sry * srz + srx * crz;
        float r22 =  crx * cry;

        // Multiply rotation by scale (scale first): result is R * S.
        float m00 = r00 * sx, m01 = r01 * sy, m02 = r02 * sz;
        float m10 = r10 * sx, m11 = r11 * sy, m12 = r12 * sz;
        float m20 = r20 * sx, m21 = r21 * sy, m22 = r22 * sz;

        // Column-major fill. Column 0:
        out[off + 0] = m00; out[off + 1] = m10; out[off + 2] = m20; out[off + 3] = 0f;
        // Column 1:
        out[off + 4] = m01; out[off + 5] = m11; out[off + 6] = m21; out[off + 7] = 0f;
        // Column 2:
        out[off + 8] = m02; out[off + 9] = m12; out[off + 10] = m22; out[off + 11] = 0f;
        // Column 3 = translation:
        out[off + 12] = t.x; out[off + 13] = t.y; out[off + 14] = t.z; out[off + 15] = 1f;
    }
}
