package com.myengine;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.myengine.VkUtil.check;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan port of {@link CubeBatch}. Each frame:
 *  1. {@link #clear} — reset groups.
 *  2. {@link #submit} per cube entity — append per-instance data to the
 *     right group (keyed by texture / tiling / foliage / texture scale).
 *  3. {@link #flush} — upload all per-instance data, issue one
 *     {@code vkCmdDrawIndexed(..., instanceCount, ...)} per group.
 *
 * <p>Per-instance layout (34 floats / 136 B):
 * <pre>
 *   0..15  mat4 model           — column-major
 *   16..33 6× vec3 face colour  — +X, -X, +Y, -Y, +Z, -Z
 * </pre>
 * The instanced vertex shader picks the right face colour from the
 * vertex's LOCAL normal (cube mesh has one normal per face).
 *
 * <p>Same dynamic-vertex-buffer discipline as VkImGuiRenderer: grow on
 * demand with vkDeviceWaitIdle, 2× factor so we don't bounce sizes.
 */
final class VkCubeBatch {

    private static final int FLOATS_PER_INSTANCE = 16 + 18;
    private static final int STRIDE_BYTES        = FLOATS_PER_INSTANCE * Float.BYTES;
    private static final int FACE_COLORS_OFFSET  = 16;

    /** Group key — instances sharing all four properties batch into the
     *  same draw call. (Texture set wraps the material's descriptor set;
     *  using its descriptor handle as a key avoids reconstructing it.) */
    private record GroupKey(long descriptorSet, boolean tiling, boolean foliage, float scale) {}

    /** Per-group state: collected per-instance data + a dedup'd
     *  descriptor set for binding before the draw. */
    private static final class Group {
        final long descriptorSet;
        final boolean tiling;
        final boolean foliage;
        final float scale;
        final List<float[]> instances = new ArrayList<>();
        Group(long ds, boolean tl, boolean fl, float sc) {
            this.descriptorSet = ds; this.tiling = tl; this.foliage = fl; this.scale = sc;
        }
    }

    private final VkContext ctx;
    private final Map<GroupKey, Group> groups = new HashMap<>();

    // Dynamic instance vertex buffer (host visible). Grows on demand.
    private long instanceBuffer;
    private long instanceMemory;
    private long instanceCapacityBytes;

    VkCubeBatch(VkContext ctx) {
        this.ctx = ctx;
    }

    void clear() { groups.clear(); }

    void submit(GameObject obj, MeshRenderer mr, long descriptorSet) {
        // Sun cube isn't a normal cube — it's a flat-yellow marker. The
        // OpenGL editor draws it via LineRenderer3D.drawMesh(cube, model,
        // 1, 1, 0) at line 544 with NO texture. The instanced cube
        // pipeline always samples a texture, so the Sun would come out
        // checkered+yellow instead of solid yellow. Caller (VulkanMain)
        // detects "Sun" and routes it through VkLineRenderer.drawMesh
        // instead — same flat-color path.
        if ("Sun".equals(obj.name)) return;

        Material m = mr.material;
        boolean tiling = Boolean.TRUE.equals(m.useWorldSpaceTiling);
        float scaleKey = Math.round(m.textureScale * 1000f) / 1000f;
        GroupKey key = new GroupKey(descriptorSet, tiling, mr.isFoliage, scaleKey);
        Group g = groups.computeIfAbsent(key,
                k -> new Group(k.descriptorSet, k.tiling, k.foliage, k.scale));

        float[] data = new float[FLOATS_PER_INSTANCE];
        writeModelMatrix(data, 0, obj.transform);
        m.writeFaceColorsInto(data, FACE_COLORS_OFFSET);
        g.instances.add(data);
    }

    int instanceCount() {
        int n = 0;
        for (Group g : groups.values()) n += g.instances.size();
        return n;
    }

    /** Flush all collected groups. Caller has already bound the cube mesh's
     *  vertex+index buffers at binding 0 AND pushed the per-frame matrices
     *  + sun direction via the cube pipeline's push constants. Per-group
     *  state (material descriptor set + tiling flag + texture scale +
     *  foliage flag) is pushed inside this method. */
    void flush(VkCommandBuffer cb, long pipelineLayout) {
        int total = instanceCount();
        if (total == 0) return;

        long needBytes = (long) total * STRIDE_BYTES;
        ensureInstanceBufferCapacity(needBytes);

        // Pack all instances into one contiguous buffer; remember each group's offset.
        int[] firstInstance = new int[groups.size()];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
            check(vkMapMemory(ctx.device(), instanceMemory, 0, needBytes, 0, pData),
                    "vkMapMemory(cubebatch)");
            ByteBuffer dst = pData.getByteBuffer(0, (int) needBytes);
            FloatBuffer fb = dst.asFloatBuffer();
            int written = 0, gi = 0;
            for (Group g : groups.values()) {
                firstInstance[gi++] = written;
                for (float[] inst : g.instances) fb.put(inst);
                written += g.instances.size();
            }
            vkUnmapMemory(ctx.device(), instanceMemory);

            // Bind instance buffer at binding 1; the cube mesh's buffers
            // are already at binding 0 from the caller.
            vkCmdBindVertexBuffers(cb, 1, stack.longs(instanceBuffer), stack.longs(0L));
        }

        // One indexed-instanced draw per group. Cube has 36 indices.
        int gi = 0;
        for (Group g : groups.values()) {
            int n = g.instances.size();
            // Per-group push constant: tiling/scale/foliage as a vec4 at
            // offset 0 (the cube pipeline's PC layout — see VulkanMain).
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer pcF = stack.mallocFloat(4);
                pcF.put(0, g.tiling ? 1f : 0f);
                pcF.put(1, g.scale);
                pcF.put(2, g.foliage ? 1f : 0f);
                pcF.put(3, Config.SHADOW_DEPTH_BIAS);   // material.w → live shadow depth bias
                vkCmdPushConstants(cb, pipelineLayout,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                        0, pcF);
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipelineLayout, 0, stack.longs(g.descriptorSet), null);
                vkCmdDrawIndexed(cb, 36, n, 0, 0, firstInstance[gi]);
            }
            gi++;
        }
    }

    private void ensureInstanceBufferCapacity(long needBytes) {
        if (needBytes <= instanceCapacityBytes) return;
        // Wait before destroying; in-flight frames may reference the old buffer.
        vkDeviceWaitIdle(ctx.device());
        if (instanceBuffer != 0) {
            vkDestroyBuffer(ctx.device(), instanceBuffer, null);
            vkFreeMemory(ctx.device(), instanceMemory, null);
        }
        long cap = Math.max(needBytes * 2, 64L * 1024);
        long[] buf = VkUtil.createBuffer(ctx.device(), ctx.physicalDevice(), cap,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        instanceBuffer = buf[0]; instanceMemory = buf[1]; instanceCapacityBytes = cap;
    }

    void destroy() {
        if (instanceBuffer != 0) vkDestroyBuffer(ctx.device(), instanceBuffer, null);
        if (instanceMemory != 0) vkFreeMemory(ctx.device(), instanceMemory, null);
    }

    // Same math as CubeBatch.writeModelMatrix — translate · Rx · Ry · Rz · scale,
    // column-major fill so the GPU reads it as `mat4(col0, col1, col2, col3)`.
    private static void writeModelMatrix(float[] out, int off, Transform t) {
        float sx = t.scaleX, sy = t.scaleY, sz = t.scaleZ;
        double rx = Math.toRadians(t.rotX);
        double ry = Math.toRadians(t.rotY);
        double rz = Math.toRadians(t.rotZ);
        float crx = (float) Math.cos(rx), srx = (float) Math.sin(rx);
        float cry = (float) Math.cos(ry), sry = (float) Math.sin(ry);
        float crz = (float) Math.cos(rz), srz = (float) Math.sin(rz);

        float r00 =  cry * crz;
        float r01 = -cry * srz;
        float r02 =  sry;
        float r10 =  srx * sry * crz + crx * srz;
        float r11 = -srx * sry * srz + crx * crz;
        float r12 = -srx * cry;
        float r20 = -crx * sry * crz + srx * srz;
        float r21 =  crx * sry * srz + srx * crz;
        float r22 =  crx * cry;

        float m00 = r00 * sx, m01 = r01 * sy, m02 = r02 * sz;
        float m10 = r10 * sx, m11 = r11 * sy, m12 = r12 * sz;
        float m20 = r20 * sx, m21 = r21 * sy, m22 = r22 * sz;

        out[off + 0] = m00; out[off + 1] = m10; out[off + 2] = m20; out[off + 3] = 0f;
        out[off + 4] = m01; out[off + 5] = m11; out[off + 6] = m21; out[off + 7] = 0f;
        out[off + 8] = m02; out[off + 9] = m12; out[off + 10] = m22; out[off + 11] = 0f;
        out[off + 12] = t.x; out[off + 13] = t.y; out[off + 14] = t.z; out[off + 15] = 1f;
    }
}
