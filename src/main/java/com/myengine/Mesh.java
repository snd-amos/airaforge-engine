package com.myengine;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

class Mesh {
    private int vaoId;
    private int vboId;
    private int textCoordsVboId;
    private int normalsVboId;
    private int smoothNormalsVboId = 0;
    private int boneIdsVboId = 0;   // 0 = not skinned
    private int weightsVboId = 0;
    private int idxVboId;
    private int vertexCount;

    public float minX = -0.5f, minY = -0.5f, minZ = -0.5f;
    public float maxX = 0.5f, maxY = 0.5f, maxZ = 0.5f;

    public boolean isSkinned = false;
    public Skeleton skeleton = null;  // null for static meshes

    // CPU-side copies retained for mesh-accurate raycasting and collision.
    public final float[] positions;
    public final int[] indices;
    public int[] boneIds = null;
    public float[] weights = null;

    public Mesh(float[] positions, float[] textCoords, float[] normals, int[] indices) {
        vertexCount = indices.length;
        this.positions = positions;
        this.indices = indices;

        vaoId = glGenVertexArrays();
        glBindVertexArray(vaoId);

        vboId = glGenBuffers();
        FloatBuffer posBuffer = MemoryUtil.memAllocFloat(positions.length);
        posBuffer.put(positions).flip();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, posBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);

        textCoordsVboId = glGenBuffers();
        FloatBuffer textCoordsBuffer = MemoryUtil.memAllocFloat(textCoords.length);
        textCoordsBuffer.put(textCoords).flip();
        glBindBuffer(GL_ARRAY_BUFFER, textCoordsVboId);
        glBufferData(GL_ARRAY_BUFFER, textCoordsBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(1);

        normalsVboId = glGenBuffers();
        FloatBuffer normalsBuffer = MemoryUtil.memAllocFloat(normals.length);
        normalsBuffer.put(normals).flip();
        glBindBuffer(GL_ARRAY_BUFFER, normalsVboId);
        glBufferData(GL_ARRAY_BUFFER, normalsBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(2);

        // Smooth normals: same position = same normal (averaged across all
        // vertices sharing that XYZ). Used ONLY by the outline pass so the
        // inflated hull stays connected at sharp corners with split normals.
        float[] smoothNormals = computeSmoothNormals(positions, normals);
        smoothNormalsVboId = glGenBuffers();
        FloatBuffer smoothBuffer = MemoryUtil.memAllocFloat(smoothNormals.length);
        smoothBuffer.put(smoothNormals).flip();
        glBindBuffer(GL_ARRAY_BUFFER, smoothNormalsVboId);
        glBufferData(GL_ARRAY_BUFFER, smoothBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(5, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(5);
        MemoryUtil.memFree(smoothBuffer);

        idxVboId = glGenBuffers();
        IntBuffer indicesBuffer = MemoryUtil.memAllocInt(indices.length);
        indicesBuffer.put(indices).flip();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, idxVboId);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indicesBuffer, GL_STATIC_DRAW);

        glBindVertexArray(0);

        MemoryUtil.memFree(posBuffer);
        MemoryUtil.memFree(textCoordsBuffer);
        MemoryUtil.memFree(normalsBuffer);
        MemoryUtil.memFree(indicesBuffer);
    }

    private static float[] computeSmoothNormals(float[] positions, float[] normals) {
        int vertCount = positions.length / 3;
        float[] result = new float[normals.length];

        // Bucket vertices by quantized position. Quantization handles the
        // case where the same logical corner has tiny float drift between
        // duplicates (common with split-normal exports).
        java.util.HashMap<Long, float[]> buckets = new java.util.HashMap<>();
        long[] keys = new long[vertCount];
        for (int i = 0; i < vertCount; i++) {
            int qx = Math.round(positions[i * 3]     * 10000f);
            int qy = Math.round(positions[i * 3 + 1] * 10000f);
            int qz = Math.round(positions[i * 3 + 2] * 10000f);
            long key = (((long) qx) << 42) ^ (((long) qy) << 21) ^ (long) qz;
            keys[i] = key;
            float[] acc = buckets.get(key);
            if (acc == null) {
                acc = new float[3];
                buckets.put(key, acc);
            }
            acc[0] += normals[i * 3];
            acc[1] += normals[i * 3 + 1];
            acc[2] += normals[i * 3 + 2];
        }

        for (int i = 0; i < vertCount; i++) {
            float[] acc = buckets.get(keys[i]);
            float nx = acc[0], ny = acc[1], nz = acc[2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) {
                result[i * 3]     = normals[i * 3];
                result[i * 3 + 1] = normals[i * 3 + 1];
                result[i * 3 + 2] = normals[i * 3 + 2];
            } else {
                result[i * 3]     = nx / len;
                result[i * 3 + 1] = ny / len;
                result[i * 3 + 2] = nz / len;
            }
        }
        return result;
    }

    public void addBoneData(int[] boneIds, float[] weights) {
        this.boneIds = boneIds;
        this.weights = weights;
        glBindVertexArray(vaoId);

        boneIdsVboId = glGenBuffers();
        IntBuffer idsBuffer = MemoryUtil.memAllocInt(boneIds.length);
        idsBuffer.put(boneIds).flip();
        glBindBuffer(GL_ARRAY_BUFFER, boneIdsVboId);
        glBufferData(GL_ARRAY_BUFFER, idsBuffer, GL_STATIC_DRAW);
        glVertexAttribIPointer(3, 4, GL_INT, 0, 0);
        glEnableVertexAttribArray(3);

        weightsVboId = glGenBuffers();
        FloatBuffer wBuffer = MemoryUtil.memAllocFloat(weights.length);
        wBuffer.put(weights).flip();
        glBindBuffer(GL_ARRAY_BUFFER, weightsVboId);
        glBufferData(GL_ARRAY_BUFFER, wBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(4, 4, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(4);

        glBindVertexArray(0);
        MemoryUtil.memFree(idsBuffer);
        MemoryUtil.memFree(wBuffer);

        isSkinned = true;
    }

    public void render() {
        glBindVertexArray(vaoId);
        glDrawElements(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    public int vaoId() { return vaoId; }
    public int vertexCount() { return vertexCount; }

    public void destroy() {
        Logger.debug("Destroying Mesh - freeing VAO and VBOs");
        glDeleteBuffers(vboId);
        glDeleteBuffers(textCoordsVboId);
        glDeleteBuffers(normalsVboId);
        if (smoothNormalsVboId != 0) glDeleteBuffers(smoothNormalsVboId);
        if (boneIdsVboId != 0) glDeleteBuffers(boneIdsVboId);
        if (weightsVboId != 0) glDeleteBuffers(weightsVboId);
        glDeleteBuffers(idxVboId);
        glDeleteVertexArrays(vaoId);
    }
}
