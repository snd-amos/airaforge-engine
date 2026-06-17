package com.myengine;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.myengine.VkUtil.check;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan port of {@link Renderer2D}. Filled / outline rectangles in NDC,
 * plus the same {@code pxX/pxY} viewport-pixel → NDC helpers.
 *
 * <p>Layout: position-only (vec2). Push constant = vec4 colour (16 B).
 * Alpha blending is permanently on (over-blend). Depth + stencil are
 * fully disabled — the 2D pass overlays everything in the scene.
 *
 * <p>One pipeline is compiled lazily per render pass (the editor's
 * offscreen target and the swapchain main pass need separate pipelines
 * — Vulkan 1.2 ties a pipeline to a specific render-pass handle).
 *
 * <p>Geometry lives in ONE host-visible vertex buffer that the renderer
 * bumps an offset into per call. Critical: previously each call mapped +
 * wrote to offset 0, so a second call before the GPU drained the queue
 * would clobber the first call's data — making the stamina bar's "fill"
 * rect overwrite its background. {@link #beginFrame} now resets the
 * offset, every draw maps + writes at the current offset, then bumps.
 *
 * <p>Sub-phase H — stamina bar (play mode) + box-select overlay (editor).
 */
final class VkRenderer2D {

    private static final String VERT = ""
            + "#version 450\n"
            + "layout(location = 0) in vec2 inPos;\n"
            + "void main() {\n"
            + "    gl_Position = vec4(inPos, 0.0, 1.0);\n"
            + "}\n";
    private static final String FRAG = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC { vec4 color; } pc;\n"
            + "layout(location = 0) out vec4 outColor;\n"
            + "void main() { outColor = pc.color; }\n";

    // Round buffer offset up to this when bumping so each draw's data
    // starts on a vec2-aligned address (Vulkan vertex-attribute reads
    // require alignment matching the attribute format — vec2 → 8 B).
    private static final int ATTR_ALIGN = 8;

    private final VkContext ctx;
    private final Map<Long, Pipeline> pipelinesByRP = new HashMap<>();

    private static final class Pipeline {
        long handle;        // triangle topology
        long handleLines;   // line topology — shares the layout via PC compatibility
        long layoutHandle;
    }

    // Single dynamic vertex buffer. Per draw: write at writeCursor, then
    // bump by the bytes written (rounded up to ATTR_ALIGN). beginFrame()
    // resets writeCursor back to 0 — overwriting last frame's data is
    // safe because vkWaitForFences in drawFrame has already drained the
    // previous frame's command buffer.
    private long vbuffer;
    private long vmemory;
    private long vcapacity;
    private int  writeCursor;

    VkRenderer2D(VkContext ctx) {
        this.ctx = ctx;
    }

    /** Called from VulkanMain.drawFrame BEFORE any fillRect / outlineRect
     *  for this frame. Resets the ring-buffer cursor. */
    void beginFrame() {
        writeCursor = 0;
    }

    /** Fill an axis-aligned rectangle in NDC. */
    void fillRect(VkCommandBuffer cb, long renderPass, int viewportW, int viewportH,
                  float x0, float y0, float x1, float y1,
                  float r, float g, float b, float a) {
        float[] tri = {
                x0, y0,  x1, y0,  x1, y1,
                x0, y0,  x1, y1,  x0, y1
        };
        draw(cb, renderPass, tri, true, r, g, b, a);
    }

    /** Outline an axis-aligned rectangle in NDC (4 line segments). */
    void outlineRect(VkCommandBuffer cb, long renderPass, int viewportW, int viewportH,
                     float x0, float y0, float x1, float y1,
                     float r, float g, float b, float a) {
        float[] lines = {
                x0, y0,  x1, y0,
                x1, y0,  x1, y1,
                x1, y1,  x0, y1,
                x0, y1,  x0, y0
        };
        draw(cb, renderPass, lines, false, r, g, b, a);
    }

    public static float pxX(float pixelX, int viewportW) {
        return (pixelX / (float) viewportW) * 2f - 1f;
    }
    public static float pxY(float pixelY, int viewportH) {
        return 1f - (pixelY / (float) viewportH) * 2f;
    }

    private void draw(VkCommandBuffer cb, long renderPass,
                       float[] xy, boolean filled,
                       float r, float g, float b, float a) {
        Pipeline pipe = pipelinesByRP.computeIfAbsent(renderPass, this::buildPipelines);

        int bytes = xy.length * Float.BYTES;
        // Bump-allocate a region in the per-frame buffer for THIS draw's
        // vertices. Each draw needs its own bytes so the GPU sees the
        // right data when it executes (CPU writes them all in sequence
        // before any draw runs). Grow on demand 2× to keep up.
        ensureBufferCapacity(writeCursor + bytes);

        int offset = writeCursor;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            check(vkMapMemory(ctx.device(), vmemory, offset, bytes, 0, pData), "vkMapMemory(2D)");
            FloatBuffer dst = pData.getByteBuffer(0, bytes).asFloatBuffer();
            dst.put(xy);
            vkUnmapMemory(ctx.device(), vmemory);

            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    filled ? pipe.handle : pipe.handleLines);
            // Bind with this draw's offset so vertex 0 reads from `offset`.
            vkCmdBindVertexBuffers(cb, 0, stack.longs(vbuffer), stack.longs(offset));

            ByteBuffer pc = stack.malloc(16);
            pc.putFloat(0, r).putFloat(4, g).putFloat(8, b).putFloat(12, a);
            vkCmdPushConstants(cb, pipe.layoutHandle,
                    VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc);
            vkCmdDraw(cb, xy.length / 2, 1, 0, 0);
        }

        // Bump to next aligned slot.
        int bumped = bytes + (ATTR_ALIGN - 1);
        writeCursor = offset + (bumped - (bumped % ATTR_ALIGN));
    }

    private Pipeline buildPipelines(long renderPass) {
        Pipeline p = new Pipeline();
        VkPipelineBuilder.Built tri = new VkPipelineBuilder(ctx.device(), "2d-fill")
                .shaders(VERT, FRAG)
                .vertexLayout(2 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32_SFLOAT, 0))
                .pushConstants(16, VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(false, false, VK_COMPARE_OP_ALWAYS)
                .colorBlendAlpha()
                .renderPass(renderPass, 0)
                .build();
        p.handle       = tri.pipeline();
        p.layoutHandle = tri.layout();

        VkPipelineBuilder.Built lines = new VkPipelineBuilder(ctx.device(), "2d-line")
                .shaders(VERT, FRAG)
                .vertexLayout(2 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32_SFLOAT, 0))
                .pushConstants(16, VK_SHADER_STAGE_FRAGMENT_BIT)
                .topology(VK_PRIMITIVE_TOPOLOGY_LINE_LIST)
                .depth(false, false, VK_COMPARE_OP_ALWAYS)
                .colorBlendAlpha()
                .renderPass(renderPass, 0)
                .build();
        p.handleLines = lines.pipeline();
        // The line pipeline got its OWN layout from the builder, but it's
        // identical to tri's (same 16B FRAGMENT push, no descriptors) so we
        // push through tri's `p.layoutHandle` for both. Destroy the redundant
        // line layout now to avoid leaking it (mirrors VkLineRenderer).
        vkDestroyPipelineLayout(ctx.device(), lines.layout(), null);
        return p;
    }

    private void ensureBufferCapacity(long needBytes) {
        if (needBytes <= vcapacity) return;
        vkDeviceWaitIdle(ctx.device());
        if (vbuffer != 0) {
            vkDestroyBuffer(ctx.device(), vbuffer, null);
            vkFreeMemory(ctx.device(), vmemory, null);
        }
        long cap = Math.max(needBytes * 2, 4 * 1024);
        long[] buf = VkUtil.createBuffer(ctx.device(), ctx.physicalDevice(), cap,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        vbuffer = buf[0]; vmemory = buf[1]; vcapacity = cap;
    }

    void destroy() {
        for (Pipeline p : pipelinesByRP.values()) {
            if (p.handle       != 0) vkDestroyPipeline(ctx.device(), p.handle, null);
            if (p.handleLines  != 0) vkDestroyPipeline(ctx.device(), p.handleLines, null);
            if (p.layoutHandle != 0) vkDestroyPipelineLayout(ctx.device(), p.layoutHandle, null);
        }
        pipelinesByRP.clear();
        if (vbuffer != 0) {
            vkDestroyBuffer(ctx.device(), vbuffer, null);
            vkFreeMemory(ctx.device(), vmemory, null);
        }
    }
}
