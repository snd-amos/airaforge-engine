package com.myengine;

import imgui.ImDrawData;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec4;
import imgui.type.ImInt;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.myengine.VkUtil.check;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan rendering backend for Dear ImGui — the Java equivalent of
 * Dear ImGui's official {@code imgui_impl_vulkan.cpp}. SpaiR's
 * imgui-java-lwjgl3 only ships GL3 + GLFW, so we hand-roll the Vulkan
 * piece. Input/eventing is still handled by {@code ImGuiImplGlfw} from
 * the SpaiR package — that backend is API-agnostic.
 *
 * <p>What this class does each frame:
 * <ol>
 *   <li>{@link #renderDrawData} reads the {@link ImDrawData} ImGui just
 *       built, copies its vertex + index data into resident
 *       {@code HOST_VISIBLE} buffers (resizing them on demand), then
 *       emits one {@code vkCmdDrawIndexed} per ImGui draw command.</li>
 *   <li>Push constants carry the 2D ortho transform
 *       ({@code scale}, {@code translate}).</li>
 *   <li>Scissor is set per command from {@code ImDrawCmd.ClipRect}.</li>
 * </ol>
 *
 * <p>For now we only ever bind the font texture — the renderer ignores
 * per-command {@code TexID}. When we add {@code ImGui.image(shadowMap)}
 * or similar in later phases, this is the place to expand: keep a
 * descriptor set per registered texture and switch on TexID.
 *
 * <p>Vertex layout is fixed by ImGui:
 * <pre>
 *   stride = sizeof(ImDrawVert) = 20 bytes
 *   pos   vec2  R32G32_SFLOAT   offset 0
 *   uv    vec2  R32G32_SFLOAT   offset 8
 *   col   uint8x4 R8G8B8A8_UNORM offset 16
 * </pre>
 * Index type is {@code VK_INDEX_TYPE_UINT16} on the typical build of
 * Dear ImGui (the SpaiR natives use the default config), so we hardcode
 * that here. {@code ImDrawData.sizeOfImDrawIdx()} is the runtime check.
 */
final class VkImGuiRenderer {

    private static final String VERT_SRC = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    vec2 scale;\n"
            + "    vec2 translate;\n"
            + "} pc;\n"
            + "layout(location = 0) in vec2 inPos;\n"
            + "layout(location = 1) in vec2 inUV;\n"
            + "layout(location = 2) in vec4 inColor;\n"
            + "layout(location = 0) out vec2 vUV;\n"
            + "layout(location = 1) out vec4 vColor;\n"
            + "void main() {\n"
            + "    vUV    = inUV;\n"
            + "    vColor = inColor;\n"
            + "    gl_Position = vec4(inPos * pc.scale + pc.translate, 0.0, 1.0);\n"
            + "}\n";

    private static final String FRAG_SRC = ""
            + "#version 450\n"
            + "layout(set = 0, binding = 0) uniform sampler2D fontTex;\n"
            + "layout(location = 0) in  vec2 vUV;\n"
            + "layout(location = 1) in  vec4 vColor;\n"
            + "layout(location = 0) out vec4 outColor;\n"
            + "void main() { outColor = vColor * texture(fontTex, vUV); }\n";

    private final VkContext ctx;

    // Pipeline.
    private long pipelineLayout;
    private long pipeline;
    // Secondary pipeline built against the multi-viewport render pass
    // (VkViewportRenderer). 0 until setViewportRenderPass() is called. The
    // main-window path never touches it. Its push-constant layout is
    // compatible with pipelineLayout, so record() can push through the
    // canonical layout regardless of which pipeline is bound (same trick
    // VkLineRenderer uses).
    private long viewportPipeline;
    private long descriptorSetLayout;
    private long descriptorPool;

    // Multi-texture support. ImGui draw commands carry a TexID (a long the
    // application picks); we map that to a descriptor set that views one
    // texture at binding 0. The font, the Scene Viewport's offscreen
    // colour, and the Shadow Map preview all get registered separately.
    // ImGui passes the TexID we registered through to renderDrawData via
    // ImDrawData.getCmdListCmdBufferTextureId().
    private final Map<Long, Long> texIdToSet = new HashMap<>();
    private long fontTexId = 0L;
    private long nextTexId = 1L;   // 0 reserved as a sentinel

    // Font atlas.
    private long fontImage;
    private long fontImageMemory;
    private long fontImageView;
    private long fontSampler;

    // Dynamic vertex + index buffers, grown on demand.
    private long vertexBuffer;
    private long vertexBufferMemory;
    private long indexBuffer;
    private long indexBufferMemory;
    private long vertexBufferCapacityBytes;
    private long indexBufferCapacityBytes;

    private final int idxSize;   // sizeof(ImDrawIdx) — 2 (uint16) on standard build

    VkImGuiRenderer(VkContext ctx, long renderPass) {
        this.ctx     = ctx;
        this.idxSize = ImDrawData.sizeOfImDrawIdx();
        if (idxSize != 2 && idxSize != 4) {
            throw new RuntimeException("Unexpected ImDrawIdx size: " + idxSize);
        }
        createDescriptorPoolAndLayout();
        createFontTexture();
        // Register the font under its own TexID and tell ImGui to use it.
        fontTexId = registerTexture(fontImageView, fontSampler);
        ImGui.getIO().getFonts().setTexID(fontTexId);
        createPipeline(renderPass);
        Logger.info("[VK] ImGui Vulkan backend ready");
    }

    // ============================================================
    //                    Resource creation
    // ============================================================

    private void createDescriptorPoolAndLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1, stack);
            b.get(0).binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo lInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(b);
            LongBuffer pL = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(ctx.device(), lInfo, null, pL),
                    "vkCreateDescriptorSetLayout(imgui)");
            descriptorSetLayout = pL.get(0);

            // Sized for: font + Scene Viewport + Shadow Map + headroom for
            // future debug textures the editor might want to display.
            final int MAX_TEXTURES = 16;
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(MAX_TEXTURES);
            VkDescriptorPoolCreateInfo pInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(sizes).maxSets(MAX_TEXTURES);
            LongBuffer pP = stack.mallocLong(1);
            check(vkCreateDescriptorPool(ctx.device(), pInfo, null, pP),
                    "vkCreateDescriptorPool(imgui)");
            descriptorPool = pP.get(0);
        }
    }

    private void createFontTexture() {
        // Ask ImGui for the rasterised font pixels (RGBA32).
        ImGuiIO io = ImGui.getIO();
        ImInt w = new ImInt(), h = new ImInt();
        ByteBuffer pixels = io.getFonts().getTexDataAsRGBA32(w, h);
        int width = w.get(), height = h.get();
        long sizeBytes = (long) width * height * 4;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Create the image.
            VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imgInfo.extent().set(width, height, 1);
            LongBuffer pImg = stack.mallocLong(1);
            check(vkCreateImage(ctx.device(), imgInfo, null, pImg), "vkCreateImage(imgui font)");
            fontImage = pImg.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(ctx.device(), fontImage, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(VkUtil.findMemoryType(ctx.physicalDevice(),
                            req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));
            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(ctx.device(), alloc, null, pMem), "vkAllocateMemory(imgui font)");
            fontImageMemory = pMem.get(0);
            check(vkBindImageMemory(ctx.device(), fontImage, fontImageMemory, 0), "vkBindImageMemory(imgui font)");

            // 2. Staging buffer with the pixel bytes.
            long[] stagingBuf = VkUtil.createBuffer(ctx.device(), ctx.physicalDevice(),
                    sizeBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            try {
                VkUtil.uploadToBuffer(ctx.device(), stagingBuf[1], sizeBytes, dst -> {
                    pixels.rewind();
                    dst.put(pixels);
                });

                // 3. Transition + copy + transition.
                VkUtil.singleTimeCommands(ctx.device(), ctx.commandPool(), ctx.graphicsQueue(), cb -> {
                    transitionLayout(cb, fontImage,
                            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            0, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                    try (MemoryStack s = MemoryStack.stackPush()) {
                        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, s);
                        region.get(0).bufferOffset(0)
                                .bufferRowLength(0).bufferImageHeight(0);
                        region.get(0).imageSubresource()
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(0).baseArrayLayer(0).layerCount(1);
                        region.get(0).imageOffset().set(0, 0, 0);
                        region.get(0).imageExtent().set(width, height, 1);
                        vkCmdCopyBufferToImage(cb, stagingBuf[0], fontImage,
                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                    }
                    transitionLayout(cb, fontImage,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                });
            } finally {
                vkDestroyBuffer(ctx.device(), stagingBuf[0], null);
                vkFreeMemory(ctx.device(), stagingBuf[1], null);
            }

            // 4. View + sampler.
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(fontImage).viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(ctx.device(), viewInfo, null, pView), "vkCreateImageView(imgui font)");
            fontImageView = pView.get(0);

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);
            LongBuffer pSampler = stack.mallocLong(1);
            check(vkCreateSampler(ctx.device(), samplerInfo, null, pSampler), "vkCreateSampler(imgui)");
            fontSampler = pSampler.get(0);
        }

        // Free the CPU-side font pixels — they're on the GPU now. The
        // real TexID is assigned after registerTexture in the constructor.
        io.getFonts().clearTexData();
    }

    /** Plain pipeline-barrier helper for layout transitions during one-shot
     *  upload commands. Generic enough that VkTexture could share it later. */
    private static void transitionLayout(VkCommandBuffer cb, long image,
                                         int oldLayout, int newLayout,
                                         int srcAccess, int dstAccess,
                                         int srcStage,  int dstStage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
            b.get(0).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            b.get(0).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, b);
        }
    }

    /** Allocate a new descriptor set viewing {@code (imageView, sampler)}
     *  at binding 0, register it under a fresh ImGui TexID, and return
     *  that TexID. Caller passes the returned long to {@code ImGui.image()}
     *  or stores it in {@code ImFontAtlas.setTexID()}. */
    long registerTexture(long imageView, long sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo a = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer p = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(ctx.device(), a, p),
                    "vkAllocateDescriptorSets(imgui texture)");
            long set = p.get(0);

            VkDescriptorImageInfo.Buffer img = VkDescriptorImageInfo.calloc(1, stack);
            img.get(0).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(imageView).sampler(sampler);
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
            w.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(img);
            vkUpdateDescriptorSets(ctx.device(), w, null);

            long texId = nextTexId++;
            texIdToSet.put(texId, set);
            return texId;
        }
    }

    /** Update an EXISTING registered TexID to point at a new image view +
     *  sampler. Used when the Scene Viewport's offscreen target resizes
     *  (the image view changes but ImGui already holds the TexID). Caller
     *  is responsible for {@code vkDeviceWaitIdle} before the old view is
     *  destroyed. */
    void rebindTexture(long texId, long imageView, long sampler) {
        Long set = texIdToSet.get(texId);
        if (set == null) throw new IllegalStateException("Unknown ImGui TexID " + texId);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer img = VkDescriptorImageInfo.calloc(1, stack);
            img.get(0).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(imageView).sampler(sampler);
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
            w.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(img);
            vkUpdateDescriptorSets(ctx.device(), w, null);
        }
    }

    private void createPipeline(long renderPass) {
        int stride = ImDrawData.sizeOfImDrawVert();   // typically 20
        VkPipelineBuilder.Built built = new VkPipelineBuilder(ctx.device(), "imgui")
                .shaders(VERT_SRC, FRAG_SRC)
                .vertexLayout(stride,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32_SFLOAT,   0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32_SFLOAT,   8),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R8G8B8A8_UNORM, 16))
                .descriptorSetLayout(descriptorSetLayout)
                .pushConstants(4 * Float.BYTES, VK_SHADER_STAGE_VERTEX_BIT)
                // ImGui draws on top of the scene — no depth test, no depth write.
                .depth(false, false, VK_COMPARE_OP_ALWAYS)
                .colorBlendAlpha()
                .renderPass(renderPass, 0)
                .build();
        pipelineLayout = built.layout();
        pipeline       = built.pipeline();
    }

    // ============================================================
    //                       Per-frame draw
    // ============================================================

    /** Records ImGui draws into {@code cb} for the MAIN window. Call this
     *  AFTER {@link ImGui#render()} and INSIDE the main render pass — the
     *  caller owns the surrounding render-pass begin/end. Thin wrapper over
     *  {@link #record} with no framebuffer of its own. */
    void renderDrawData(VkCommandBuffer cb) {
        record(cb, ImGui.getDrawData(), 0L, null, 0L);
    }

    /**
     * Generalized ImGui draw recorder shared by the main window and every
     * secondary multi-viewport window.
     *
     * <ul>
     *   <li><b>Main viewport</b> — pass {@code framebuffer == 0}. The caller
     *       has already begun a render pass compatible with the ImGui
     *       pipeline, so this only records draw commands into the current
     *       pass (no begin/end, {@code extent}/{@code renderPass} unused).</li>
     *   <li><b>Secondary viewport</b> ({@link VkViewportRenderer}) — pass a
     *       real {@code framebuffer} + {@code extent} + {@code renderPass}.
     *       This begins that pass (clearing the colour attachment), records,
     *       and ends it. The pass must be render-pass-compatible with the
     *       pipeline built by {@link #setViewportRenderPass}.</li>
     * </ul>
     *
     * Safe to call with empty/zero draw data: a secondary viewport still gets
     * its colour attachment cleared; the main path becomes a no-op.
     */
    void record(VkCommandBuffer cb, ImDrawData drawData,
                long framebuffer, VkExtent2D extent, long renderPass) {
        boolean ownPass = framebuffer != 0L;

        boolean haveGeom = drawData != null && drawData.getValid()
                && drawData.getTotalVtxCount() > 0 && drawData.getTotalIdxCount() > 0;

        int fbWidth = 0, fbHeight = 0;
        if (haveGeom) {
            fbWidth  = (int) (drawData.getDisplaySizeX() * drawData.getFramebufferScaleX());
            fbHeight = (int) (drawData.getDisplaySizeY() * drawData.getFramebufferScaleY());
            if (fbWidth <= 0 || fbHeight <= 0) haveGeom = false;
        }

        // Upload BEFORE beginning any render pass — ensureBufferCapacity may
        // vkDeviceWaitIdle on a grow, which must not happen mid-render-pass.
        if (haveGeom) uploadDrawData(drawData);

        if (ownPass) beginViewportPass(cb, renderPass, framebuffer, extent);

        if (haveGeom) {
            // Main path binds the canonical pipeline; a secondary viewport
            // binds the render-pass-compatible one (falls back to the main
            // pipeline if the viewport pipeline was never built).
            long usePipeline = (ownPass && viewportPipeline != 0L) ? viewportPipeline : pipeline;
            recordDrawCommands(cb, drawData, usePipeline, fbWidth, fbHeight);
        }

        if (ownPass) vkCmdEndRenderPass(cb);
    }

    /** Copy ImGui's per-list vertex + index data into the resident
     *  HOST_VISIBLE buffers, growing them on demand. */
    private void uploadDrawData(ImDrawData drawData) {
        int totalVtx = drawData.getTotalVtxCount();
        int totalIdx = drawData.getTotalIdxCount();
        int vtxStride = ImDrawData.sizeOfImDrawVert();
        long vtxBytes = (long) totalVtx * vtxStride;
        long idxBytes = (long) totalIdx * idxSize;

        ensureBufferCapacity(vtxBytes, idxBytes);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pVtx = stack.mallocPointer(1);
            org.lwjgl.PointerBuffer pIdx = stack.mallocPointer(1);
            check(vkMapMemory(ctx.device(), vertexBufferMemory, 0, vtxBytes, 0, pVtx), "map(imgui vtx)");
            check(vkMapMemory(ctx.device(), indexBufferMemory,  0, idxBytes, 0, pIdx), "map(imgui idx)");
            ByteBuffer vtxDst = pVtx.getByteBuffer(0, (int) vtxBytes);
            ByteBuffer idxDst = pIdx.getByteBuffer(0, (int) idxBytes);

            int vtxOff = 0, idxOff = 0;
            int listCount = drawData.getCmdListsCount();
            for (int li = 0; li < listCount; li++) {
                // NOTE: getCmdListVtxBufferData / getCmdListIdxBufferData share
                // a single direct buffer in imgui-java — copy each one BEFORE
                // calling the other to avoid clobber.
                ByteBuffer vSrc = drawData.getCmdListVtxBufferData(li);
                int vBytes = vSrc.remaining();
                vtxDst.position(vtxOff);
                vtxDst.put(vSrc);
                vtxOff += vBytes;

                ByteBuffer iSrc = drawData.getCmdListIdxBufferData(li);
                int iBytes = iSrc.remaining();
                idxDst.position(idxOff);
                idxDst.put(iSrc);
                idxOff += iBytes;
            }
            vkUnmapMemory(ctx.device(), indexBufferMemory);
            vkUnmapMemory(ctx.device(), vertexBufferMemory);
        }
    }

    /** Emit the bind / viewport / push-constant / per-command scissor + draw
     *  sequence for {@code drawData} using {@code usePipeline}. Assumes a
     *  render pass is already active and the geometry is already uploaded. */
    private void recordDrawCommands(VkCommandBuffer cb, ImDrawData drawData,
                                    long usePipeline, int fbWidth, int fbHeight) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, usePipeline);

            // Bind vertex + index buffers (vertex offset 0; index offset 0).
            vkCmdBindVertexBuffers(cb, 0, stack.longs(vertexBuffer), stack.longs(0L));
            vkCmdBindIndexBuffer(cb, indexBuffer, 0,
                    (idxSize == 2) ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32);

            // Full-window viewport.
            VkViewport.Buffer vp = VkViewport.calloc(1, stack);
            vp.get(0).x(0).y(0).width(fbWidth).height(fbHeight).minDepth(0f).maxDepth(1f);
            vkCmdSetViewport(cb, 0, vp);

            // Push constants — 2D ortho mapping ImGui's display space onto NDC.
            float displayPosX = drawData.getDisplayPosX();
            float displayPosY = drawData.getDisplayPosY();
            float displaySzX  = drawData.getDisplaySizeX();
            float displaySzY  = drawData.getDisplaySizeY();
            FloatBuffer pc = stack.mallocFloat(4);
            pc.put(0,  2f / displaySzX);                  // scaleX
            pc.put(1,  2f / displaySzY);                  // scaleY
            pc.put(2, -1f - displayPosX * (2f / displaySzX));  // translateX
            pc.put(3, -1f - displayPosY * (2f / displaySzY));  // translateY
            // Push through the canonical layout — compatible with both pipelines.
            vkCmdPushConstants(cb, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pc);

            // Iterate command lists / commands, applying scissor per command.
            float fbScaleX = drawData.getFramebufferScaleX();
            float fbScaleY = drawData.getFramebufferScaleY();
            int globalVtxOffset = 0, globalIdxOffset = 0;
            long boundSet = 0;
            int listCount = drawData.getCmdListsCount();
            for (int li = 0; li < listCount; li++) {
                int cmdCount = drawData.getCmdListCmdBufferSize(li);
                for (int ci = 0; ci < cmdCount; ci++) {
                    // Switch descriptor set when the texture changes between
                    // commands (font ↔ Scene Viewport ↔ Shadow Map etc.).
                    long cmdTexId = drawData.getCmdListCmdBufferTextureId(li, ci);
                    Long setForTex = texIdToSet.get(cmdTexId);
                    long set = (setForTex != null) ? setForTex : texIdToSet.get(fontTexId);
                    if (set != boundSet) {
                        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                pipelineLayout, 0, stack.longs(set), null);
                        boundSet = set;
                    }

                    ImVec4 clip = drawData.getCmdListCmdBufferClipRect(li, ci);
                    // Convert to framebuffer-space scissor.
                    float clipX = (clip.x - displayPosX) * fbScaleX;
                    float clipY = (clip.y - displayPosY) * fbScaleY;
                    float clipW = (clip.z - displayPosX) * fbScaleX;
                    float clipH = (clip.w - displayPosY) * fbScaleY;
                    if (clipX < 0)      clipX = 0;
                    if (clipY < 0)      clipY = 0;
                    if (clipW > fbWidth) clipW = fbWidth;
                    if (clipH > fbHeight) clipH = fbHeight;
                    if (clipW <= clipX || clipH <= clipY) continue;

                    VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
                    sc.get(0).offset().set((int) clipX, (int) clipY);
                    sc.get(0).extent().set((int) (clipW - clipX), (int) (clipH - clipY));
                    vkCmdSetScissor(cb, 0, sc);

                    int elemCount = drawData.getCmdListCmdBufferElemCount(li, ci);
                    int vtxOffset = drawData.getCmdListCmdBufferVtxOffset(li, ci) + globalVtxOffset;
                    int idxOffset = drawData.getCmdListCmdBufferIdxOffset(li, ci) + globalIdxOffset;
                    vkCmdDrawIndexed(cb, elemCount, 1, idxOffset, vtxOffset, 0);
                }
                globalVtxOffset += drawData.getCmdListVtxBufferSize(li);
                globalIdxOffset += drawData.getCmdListIdxBufferSize(li);
            }
        }
    }

    /** Begin a secondary-viewport render pass, clearing the colour
     *  attachment to opaque black. The viewport's own ImGui draw commands
     *  paint the window background on top. */
    private static void beginViewportPass(VkCommandBuffer cb, long renderPass,
                                          long framebuffer, VkExtent2D extent) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
            clear.get(0).color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f);
            VkRenderPassBeginInfo bi = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass).framebuffer(framebuffer)
                    .pClearValues(clear);
            bi.renderArea().offset().set(0, 0);
            bi.renderArea().extent().set(extent.width(), extent.height());
            vkCmdBeginRenderPass(cb, bi, VK_SUBPASS_CONTENTS_INLINE);
        }
    }

    /** Build (once) the ImGui pipeline variant compatible with the
     *  multi-viewport render pass. Called by {@link VkViewportRenderer} after
     *  it creates its shared colour-only render pass. Reuses the canonical
     *  pipeline layout for push compatibility and throws away the duplicate
     *  layout the builder makes (same approach as VkLineRenderer). */
    void setViewportRenderPass(long renderPass) {
        if (viewportPipeline != 0L) return;   // already built
        int stride = ImDrawData.sizeOfImDrawVert();
        VkPipelineBuilder.Built built = new VkPipelineBuilder(ctx.device(), "imgui-viewport")
                .shaders(VERT_SRC, FRAG_SRC)
                .vertexLayout(stride,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32_SFLOAT,   0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32_SFLOAT,   8),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R8G8B8A8_UNORM, 16))
                .descriptorSetLayout(descriptorSetLayout)
                .pushConstants(4 * Float.BYTES, VK_SHADER_STAGE_VERTEX_BIT)
                .depth(false, false, VK_COMPARE_OP_ALWAYS)
                .colorBlendAlpha()
                .renderPass(renderPass, 0)
                .build();
        viewportPipeline = built.pipeline();
        vkDestroyPipelineLayout(ctx.device(), built.layout(), null);
    }

    private void ensureBufferCapacity(long vtxBytes, long idxBytes) {
        boolean needGrow = (vtxBytes > vertexBufferCapacityBytes) || (idxBytes > indexBufferCapacityBytes);
        if (!needGrow) return;

        // The OLD vertex/index buffers may still be referenced by a
        // command buffer from a previous frame that hasn't been retired
        // yet (Vulkan validation flagged this with VUID-vkDestroyBuffer-
        // buffer-00922). Wait for ALL in-flight work before destroying.
        // Buffer grows are rare — once per UI complexity tier (panel
        // dock, stress-test spawn) — so the stall here is negligible.
        // Grow with a 2× factor so we don't bounce between sizes.
        vkDeviceWaitIdle(ctx.device());

        if (vtxBytes > vertexBufferCapacityBytes) {
            destroyVertexBuffer();
            long cap = Math.max(vtxBytes * 2, 64L * 1024);
            long[] buf = VkUtil.createBuffer(ctx.device(), ctx.physicalDevice(), cap,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            vertexBuffer = buf[0]; vertexBufferMemory = buf[1]; vertexBufferCapacityBytes = cap;
        }
        if (idxBytes > indexBufferCapacityBytes) {
            destroyIndexBuffer();
            long cap = Math.max(idxBytes * 2, 16L * 1024);
            long[] buf = VkUtil.createBuffer(ctx.device(), ctx.physicalDevice(), cap,
                    VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            indexBuffer = buf[0]; indexBufferMemory = buf[1]; indexBufferCapacityBytes = cap;
        }
    }

    private void destroyVertexBuffer() {
        if (vertexBuffer       != 0) vkDestroyBuffer(ctx.device(), vertexBuffer, null);
        if (vertexBufferMemory != 0) vkFreeMemory(ctx.device(), vertexBufferMemory, null);
        vertexBuffer = 0; vertexBufferMemory = 0; vertexBufferCapacityBytes = 0;
    }

    private void destroyIndexBuffer() {
        if (indexBuffer       != 0) vkDestroyBuffer(ctx.device(), indexBuffer, null);
        if (indexBufferMemory != 0) vkFreeMemory(ctx.device(), indexBufferMemory, null);
        indexBuffer = 0; indexBufferMemory = 0; indexBufferCapacityBytes = 0;
    }

    void destroy() {
        destroyVertexBuffer();
        destroyIndexBuffer();
        if (viewportPipeline    != 0) vkDestroyPipeline(ctx.device(), viewportPipeline, null);
        if (pipeline            != 0) vkDestroyPipeline(ctx.device(), pipeline, null);
        if (pipelineLayout      != 0) vkDestroyPipelineLayout(ctx.device(), pipelineLayout, null);
        if (descriptorPool      != 0) vkDestroyDescriptorPool(ctx.device(), descriptorPool, null);
        if (descriptorSetLayout != 0) vkDestroyDescriptorSetLayout(ctx.device(), descriptorSetLayout, null);
        if (fontSampler         != 0) vkDestroySampler(ctx.device(), fontSampler, null);
        if (fontImageView       != 0) vkDestroyImageView(ctx.device(), fontImageView, null);
        if (fontImage           != 0) vkDestroyImage(ctx.device(), fontImage, null);
        if (fontImageMemory     != 0) vkFreeMemory(ctx.device(), fontImageMemory, null);
    }
}
