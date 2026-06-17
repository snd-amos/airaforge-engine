package com.myengine;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU-side geometry for a single mesh. Owns one interleaved vertex buffer
 * and one index buffer; the caller is responsible for the pipeline that
 * interprets the vertex layout (binding/attribute descriptions).
 *
 * Phase-4 design notes:
 *   - Vertices are stored interleaved in a single buffer. Same layout the
 *     OpenGL {@link Mesh} uses, but combined into one VkBuffer instead of
 *     three VBOs. One binding + multiple attribute offsets in the pipeline
 *     reads each field; this is the standard Vulkan idiom.
 *   - Memory is HOST_VISIBLE | HOST_COHERENT (mapped + CPU-writable). Fine
 *     for the editor's modest mesh count; Phase 5+ will switch hot meshes
 *     to DEVICE_LOCAL with a staging copy when we start streaming.
 *   - 32-bit indices (UINT32). 16-bit would halve memory but caps at 65k
 *     verts per mesh — fine for game assets but breaks for stress tests
 *     (the user's existing 15k-cube benchmark already crosses that
 *     boundary if we ever flatten meshes).
 *
 * The class is final and has no setters — meshes are immutable once built.
 * To change geometry, destroy and rebuild.
 */
final class VkMesh {

    private final long vertexBuffer;
    private final long vertexBufferMemory;
    private final long indexBuffer;
    private final long indexBufferMemory;
    private final int  indexCount;
    private final int  vertCount;

    // Optional skinning data (sub-phase G). Built lazily via addSkinning().
    // Layout: per vertex 32 bytes = ivec4 boneIds (16) + vec4 weights (16).
    // Bound at vertex-input binding 1 by the skinned pipeline.
    private long skinningBuffer       = 0L;
    private long skinningBufferMemory = 0L;
    private Skeleton skeleton         = null;
    // CPU-side copies retained for save/reload + Animator debug. Mirrors
    // Mesh.boneIds/weights on the OpenGL side.
    private int[]   boneIds = null;
    private float[] weights = null;
    // Local-space axis-aligned bounds — computed at construction time by
    // scanning vertex positions (first 3 floats of every stride-8 vert).
    // Mirrors Mesh.minX..maxZ on the OpenGL side. Used by click-to-select,
    // selection wireframe, and the MeshCollider port.
    private final float minX, minY, minZ;
    private final float maxX, maxY, maxZ;
    // CPU-side position + index arrays kept for MeshCollider triangle
    // tests. Position is x,y,z per vertex (3 floats), de-interleaved from
    // the stride-8 GPU layout. Indices are the original triangle list.
    // Cost ~12 bytes/vert + 4 bytes/index — small for typical level models.
    private final float[] localPositions;
    private final int[]   localIndices;

    /**
     * Build a mesh from interleaved float vertex data + integer indices.
     * The {@code vertices} layout (how many floats per vertex, what each
     * field means) is entirely up to the caller — it must match the
     * pipeline's vertex input description used to render this mesh.
     */
    VkMesh(VkDevice device, VkPhysicalDevice physicalDevice,
           float[] vertices, int[] indices) {
        if (vertices == null || vertices.length == 0)
            throw new IllegalArgumentException("vertices empty");
        if (indices == null || indices.length == 0)
            throw new IllegalArgumentException("indices empty");

        long vertexBytes = (long) vertices.length * Float.BYTES;
        long indexBytes  = (long) indices.length  * Integer.BYTES;
        this.indexCount  = indices.length;

        // Compute local AABB and de-interleave positions from the stride-8
        // vertex layout into a contiguous positions array for MeshCollider.
        // (Collision reads x,y,z only; normals + UVs would just waste cache.)
        this.vertCount = vertices.length / 8;
        this.localPositions = new float[vertCount * 3];
        float mnX = Float.POSITIVE_INFINITY, mnY = Float.POSITIVE_INFINITY, mnZ = Float.POSITIVE_INFINITY;
        float mxX = Float.NEGATIVE_INFINITY, mxY = Float.NEGATIVE_INFINITY, mxZ = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < vertCount; v++) {
            float x = vertices[v * 8], y = vertices[v * 8 + 1], z = vertices[v * 8 + 2];
            localPositions[v * 3]     = x;
            localPositions[v * 3 + 1] = y;
            localPositions[v * 3 + 2] = z;
            if (x < mnX) mnX = x; if (x > mxX) mxX = x;
            if (y < mnY) mnY = y; if (y > mxY) mxY = y;
            if (z < mnZ) mnZ = z; if (z > mxZ) mxZ = z;
        }
        // Pad degenerate axes so paper-thin meshes (alpha-cutout leaf quads,
        // billboards) produce a non-zero AABB for the selection box + picking.
        // Mirrors the ModelLoader pad on the OpenGL side.
        final float EPSILON = 0.01f;
        final float PAD = 0.05f;
        if (mxX - mnX < EPSILON) { mnX -= PAD; mxX += PAD; }
        if (mxY - mnY < EPSILON) { mnY -= PAD; mxY += PAD; }
        if (mxZ - mnZ < EPSILON) { mnZ -= PAD; mxZ += PAD; }
        this.minX = mnX; this.minY = mnY; this.minZ = mnZ;
        this.maxX = mxX; this.maxY = mxY; this.maxZ = mxZ;
        this.localIndices = indices.clone();

        long[] vbo = VkUtil.createBuffer(device, physicalDevice,
                vertexBytes,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        this.vertexBuffer       = vbo[0];
        this.vertexBufferMemory = vbo[1];

        long[] ibo = VkUtil.createBuffer(device, physicalDevice,
                indexBytes,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        this.indexBuffer       = ibo[0];
        this.indexBufferMemory = ibo[1];

        // Upload — single mapping window per buffer is fine for this size.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);

            VkUtil.check(vkMapMemory(device, vertexBufferMemory, 0, vertexBytes, 0, pData),
                    "vkMapMemory(vertex)");
            FloatBuffer vDst = pData.getByteBuffer(0, (int) vertexBytes).asFloatBuffer();
            vDst.put(vertices);
            vkUnmapMemory(device, vertexBufferMemory);

            VkUtil.check(vkMapMemory(device, indexBufferMemory, 0, indexBytes, 0, pData),
                    "vkMapMemory(index)");
            IntBuffer iDst = pData.getByteBuffer(0, (int) indexBytes).asIntBuffer();
            iDst.put(indices);
            vkUnmapMemory(device, indexBufferMemory);
        }
    }

    /**
     * Bind this mesh's vertex+index buffers on the given command buffer.
     * Caller still needs to {@code vkCmdDrawIndexed(cb, indexCount(), ...)}.
     */
    void bind(VkCommandBuffer cb) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindVertexBuffers(cb, 0,
                    stack.longs(vertexBuffer),
                    stack.longs(0L));
            vkCmdBindIndexBuffer(cb, indexBuffer, 0, VK_INDEX_TYPE_UINT32);
        }
    }

    /** Bind both the static mesh buffer (binding 0) AND the skinning buffer
     *  (binding 1). Caller must have already called {@link #addSkinning} and
     *  must be using the skinned pipeline layout. */
    void bindWithSkinning(VkCommandBuffer cb) {
        if (skinningBuffer == 0L) throw new IllegalStateException(
                "bindWithSkinning called on non-skinned mesh");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindVertexBuffers(cb, 0,
                    stack.longs(vertexBuffer, skinningBuffer),
                    stack.longs(0L, 0L));
            vkCmdBindIndexBuffer(cb, indexBuffer, 0, VK_INDEX_TYPE_UINT32);
        }
    }

    /**
     * Attach per-vertex bone IDs (ivec4, -1 = no bone) and weights (vec4),
     * plus the bone hierarchy / inverse-bind map. Called once by
     * {@link VkMeshLoader} after the static buffers are built. Allocates a
     * SECOND vertex buffer at binding 1 that the skinned pipeline reads as
     * `ivec4 boneIds; vec4 weights;` per vertex (32 B stride). Mirrors
     * {@link Mesh#addBoneData}.
     */
    void addSkinning(VkDevice device, VkPhysicalDevice physicalDevice,
                      int[] boneIds, float[] weights, Skeleton skeleton) {
        if (boneIds.length != vertCount * 4 || weights.length != vertCount * 4)
            throw new IllegalArgumentException("Bone data length mismatch (need "
                    + (vertCount * 4) + ", got bones=" + boneIds.length
                    + ", weights=" + weights.length + ")");
        this.boneIds = boneIds;
        this.weights = weights;
        this.skeleton = skeleton;

        // Pack interleaved: per vertex (int×4, float×4) = 32 B. Vulkan's
        // VK_FORMAT_R32G32B32A32_SINT reads the bone IDs natively.
        long stride = 4 * Integer.BYTES + 4 * Float.BYTES;   // 32
        long totalBytes = (long) vertCount * stride;
        long[] sk = VkUtil.createBuffer(device, physicalDevice, totalBytes,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        skinningBuffer       = sk[0];
        skinningBufferMemory = sk[1];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            VkUtil.check(vkMapMemory(device, skinningBufferMemory, 0, totalBytes, 0, pData),
                    "vkMapMemory(skinning)");
            ByteBuffer dst = pData.getByteBuffer(0, (int) totalBytes);
            for (int v = 0; v < vertCount; v++) {
                int base = v * 4;
                dst.putInt(boneIds[base]).putInt(boneIds[base + 1])
                   .putInt(boneIds[base + 2]).putInt(boneIds[base + 3]);
                dst.putFloat(weights[base]).putFloat(weights[base + 1])
                   .putFloat(weights[base + 2]).putFloat(weights[base + 3]);
            }
            vkUnmapMemory(device, skinningBufferMemory);
        }
    }

    int indexCount() { return indexCount; }
    boolean isSkinned() { return skinningBuffer != 0L; }
    Skeleton skeleton() { return skeleton; }
    int[]   skinningBoneIds() { return boneIds; }
    float[] skinningWeights() { return weights; }

    // Local-space AABB accessors.
    float minX() { return minX; } float minY() { return minY; } float minZ() { return minZ; }
    float maxX() { return maxX; } float maxY() { return maxY; } float maxZ() { return maxZ; }
    // CPU-side data — handed off to MeshRenderer.vkLocal* for MeshCollider.
    float[] localPositions() { return localPositions; }
    int[]   localIndices()   { return localIndices; }

    /**
     * Free both buffers and their backing memory. Safe to call multiple
     * times only if the caller stops using the handles after the first
     * call — we don't null-out for diagnostic clarity in validation logs.
     */
    void destroy(VkDevice device) {
        if (skinningBuffer != 0L) {
            vkDestroyBuffer(device, skinningBuffer, null);
            vkFreeMemory(device, skinningBufferMemory, null);
        }
        vkDestroyBuffer(device, indexBuffer, null);
        vkFreeMemory(device, indexBufferMemory, null);
        vkDestroyBuffer(device, vertexBuffer, null);
        vkFreeMemory(device, vertexBufferMemory, null);
    }
}
