package com.myengine;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static com.myengine.VkUtil.check;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan port of {@link LineRenderer3D}. Two flat-colour entry points:
 * <ul>
 *   <li>{@link #drawLines}  — dynamic vertex buffer, {@code LINE_LIST}
 *       topology, vec3 position only. Used for wireframe AABBs, capsule
 *       outlines, rotation rings, etc.</li>
 *   <li>{@link #drawMesh}   — sources an existing {@link VkMesh} (the
 *       shared 8-floats-per-vert layout), {@code TRIANGLE_LIST} topology,
 *       reads only the position attribute. Used by {@link VkGizmo} to
 *       draw stretched-cube arrow shafts / box tips / rotation chord
 *       segments without allocating per-frame line geometry.</li>
 * </ul>
 *
 * <p>Both paths share the same vertex + fragment shaders (flat colour) and
 * push-constant layout: {@code mat4 mvp + vec4 color = 80 bytes}.
 * Depth-test off so the gizmo and outlines stay visible through scene
 * geometry — matches {@code Gizmo.render}'s {@code glDisable(GL_DEPTH_TEST)}.
 *
 * <p>The dynamic line vertex buffer grows on demand; initial capacity is
 * 8 KB (≈680 line segments), enough for the rotation gizmo's 192 segments
 * + a couple of wireframe AABBs without reallocations.
 */
final class VkLineRenderer {

    private static final int FLAT_PC_FLOATS = 20;     // mat4 + vec4

    private static final String FLAT_VERT = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 mvp;\n"
            + "    vec4 color;\n"
            + "} pc;\n"
            + "layout(location = 0) in vec3 inPos;\n"
            + "void main() { gl_Position = pc.mvp * vec4(inPos, 1.0); }\n";

    private static final String FLAT_FRAG = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 mvp;\n"
            + "    vec4 color;\n"
            + "} pc;\n"
            + "layout(location = 0) out vec4 outColor;\n"
            + "void main() { outColor = pc.color; }\n";

    private final VkContext ctx;

    private long pipelineLayout;
    private long linePipeline;
    private long meshPipeline;

    private long vertexBuffer;
    private long vertexBufferMemory;
    private long vertexBufferCapacity;

    VkLineRenderer(VkContext ctx, long renderPass) {
        this.ctx = ctx;
        createPipelines(renderPass);
        ensureVertexCapacity(8 * 1024);
    }

    private void createPipelines(long renderPass) {
        // Line pipeline — LINE_LIST, vec3 pos stride 12.
        VkPipelineBuilder.Built linePipe = new VkPipelineBuilder(ctx.device(), "line")
                .shaders(FLAT_VERT, FLAT_FRAG)
                .vertexLayout(3 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0))
                .pushConstants(FLAT_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(false, false, VK_COMPARE_OP_ALWAYS)
                .colorBlendOpaque()
                .topology(VK_PRIMITIVE_TOPOLOGY_LINE_LIST)
                .renderPass(renderPass, 0)
                .build();
        // Mesh pipeline — TRIANGLE_LIST, same 8-floats-per-vert stride as
        // every other VkMesh; only reads location 0 (position).
        VkPipelineBuilder.Built meshPipe = new VkPipelineBuilder(ctx.device(), "line-mesh")
                .shaders(FLAT_VERT, FLAT_FRAG)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0))
                .pushConstants(FLAT_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(false, false, VK_COMPARE_OP_ALWAYS)
                .colorBlendOpaque()
                .renderPass(renderPass, 0)
                .build();

        // Both pipelines share the same layout (same PC range, no descriptors).
        // Built creates one layout per pipeline — that's wasteful, but harmless;
        // we keep linePipe's layout as the canonical one for push calls and
        // throw away meshPipe's by destroying it via its own pipeline destroy.
        // Pipeline layouts can be shared by Vulkan when fully-compatible.
        pipelineLayout = linePipe.layout();
        linePipeline   = linePipe.pipeline();
        meshPipeline   = meshPipe.pipeline();
        // Throw away the extra layout from meshPipe.
        vkDestroyPipelineLayout(ctx.device(), meshPipe.layout(), null);
    }

    // Per-frame ring-buffer cursor. Without this, multiple drawLines
    // calls in the same frame would all write to offset 0 of the same
    // buffer — by the time the GPU executes the queued draws, only the
    // LAST call's data is in the buffer, so every line draw renders the
    // same vertices (with different MVPs). Caused selection wireframes
    // to "swap" with hover wireframes and showed a phantom rectangle.
    // beginFrame() resets the cursor; each draw appends + bumps.
    private int writeCursor;
    private static final int ATTR_ALIGN = 12;   // vec3 vertex stride

    /** Reset the ring-buffer cursor — call once per frame BEFORE any
     *  line draws. drawFrame's vkWaitForFences has already drained the
     *  previous frame's command buffer so it's safe to reuse bytes. */
    void beginFrame() { writeCursor = 0; }

    private void ensureVertexCapacity(long needBytes) {
        if (vertexBufferCapacity >= needBytes) return;
        // Growing mid-frame would orphan in-flight references; wait
        // before swapping. Safe because we're between command-buffer
        // submissions in the typical path.
        vkDeviceWaitIdle(ctx.device());
        if (vertexBuffer != 0) {
            vkDestroyBuffer(ctx.device(), vertexBuffer, null);
            vkFreeMemory(ctx.device(), vertexBufferMemory, null);
        }
        long cap = Math.max(needBytes, vertexBufferCapacity * 2);
        if (cap < 8 * 1024) cap = 8 * 1024;
        long[] buf = VkUtil.createBuffer(ctx.device(), ctx.physicalDevice(), cap,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        vertexBuffer = buf[0];
        vertexBufferMemory = buf[1];
        vertexBufferCapacity = cap;
    }

    /** Draws {@code verts.length / 6} line segments — each 6 floats:
     *  {@code (x0 y0 z0 x1 y1 z1)}. Colour uniform across the call.
     *  Records into {@code cb} inside an already-begun render pass. */
    void drawLines(VkCommandBuffer cb, float[] verts, Matrix4f mvp,
                   float r, float g, float b) {
        if (verts.length == 0) return;
        int bytes = verts.length * Float.BYTES;
        ensureVertexCapacity(writeCursor + bytes);

        final int offset = writeCursor;
        // Upload to the ring-buffer slot (HOST_COHERENT — no flush).
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
            org.lwjgl.vulkan.VK10.vkMapMemory(ctx.device(), vertexBufferMemory,
                    offset, bytes, 0, pData);
            FloatBuffer fb = pData.getByteBuffer(0, bytes).asFloatBuffer();
            fb.put(verts);
            org.lwjgl.vulkan.VK10.vkUnmapMemory(ctx.device(), vertexBufferMemory);

            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, linePipeline);
            vkCmdBindVertexBuffers(cb, 0, stack.longs(vertexBuffer), stack.longs(offset));
            ByteBuffer pc = stack.malloc(FLAT_PC_FLOATS * Float.BYTES);
            FloatBuffer pcF = pc.asFloatBuffer();
            mvp.get(0, pcF);
            pcF.put(16, r); pcF.put(17, g); pcF.put(18, b); pcF.put(19, 1f);
            vkCmdPushConstants(cb, pipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, pcF);
            vkCmdDraw(cb, verts.length / 3, 1, 0, 0);
        }
        // Bump cursor to next aligned slot for the next call.
        int bumped = bytes + (ATTR_ALIGN - 1);
        writeCursor = offset + (bumped - (bumped % ATTR_ALIGN));
    }

    /** Draws every triangle of {@code mesh} in flat colour {@code (r,g,b)}.
     *  Used by {@link VkGizmo} for the solid stretched-cube primitives
     *  (arrow shafts, box tips, rotation chord segments). Same render
     *  pass as the caller. */
    void drawMesh(VkCommandBuffer cb, VkMesh mesh, Matrix4f mvp,
                  float r, float g, float b) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, meshPipeline);
            mesh.bind(cb);
            FloatBuffer pcF = stack.mallocFloat(FLAT_PC_FLOATS);
            mvp.get(0, pcF);
            pcF.put(16, r); pcF.put(17, g); pcF.put(18, b); pcF.put(19, 1f);
            vkCmdPushConstants(cb, pipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, pcF);
            vkCmdDrawIndexed(cb, mesh.indexCount(), 1, 0, 0, 0);
        }
    }

    void destroy() {
        if (meshPipeline       != 0) vkDestroyPipeline(ctx.device(), meshPipeline, null);
        if (linePipeline       != 0) vkDestroyPipeline(ctx.device(), linePipeline, null);
        if (pipelineLayout     != 0) vkDestroyPipelineLayout(ctx.device(), pipelineLayout, null);
        if (vertexBuffer       != 0) vkDestroyBuffer(ctx.device(), vertexBuffer, null);
        if (vertexBufferMemory != 0) vkFreeMemory(ctx.device(), vertexBufferMemory, null);
    }
}
