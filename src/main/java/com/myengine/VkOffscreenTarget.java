package com.myengine;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static com.myengine.VkUtil.check;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Offscreen colour + depth render target used as the Scene Viewport's
 * texture source: the scene render pass targets this, then ImGui samples
 * the colour image and shows it inside the "Scene Viewport" panel (port
 * of {@code UIManager.renderViewport} at line 215).
 *
 * <p>Color format matches the swapchain so the model / skybox / shadow
 * pipelines (built against {@link VkSwapchain#renderPass()}) are
 * <b>render-pass-compatible</b> with this target's render pass and can be
 * used unchanged here. (Vulkan render-pass compatibility just requires
 * matching attachment formats + counts + sample counts.)
 *
 * <p>{@link #resize(int, int)} tears down and rebuilds the images +
 * framebuffer when the viewport panel resizes. Caller is responsible for
 * calling {@code vkDeviceWaitIdle} first.
 */
final class VkOffscreenTarget {

    private final VkContext ctx;
    private final int       colorFormat;
    private final int       depthFormat;

    private int width, height;

    private long colorImage;
    private long colorImageMemory;
    private long colorImageView;
    private long colorSampler;

    private long depthImage;
    private long depthImageMemory;
    private long depthImageView;

    private long renderPass;
    private long framebuffer;

    VkOffscreenTarget(VkContext ctx, int colorFormat, int depthFormat,
                      int initialWidth, int initialHeight) {
        this.ctx         = ctx;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
        createRenderPass();
        createSampler();
        resize(initialWidth, initialHeight);
    }

    // ---- accessors ----
    int     width()       { return width; }
    int     height()      { return height; }
    long    renderPass()  { return renderPass; }
    long    framebuffer() { return framebuffer; }
    long    colorView()   { return colorImageView; }
    long    sampler()     { return colorSampler; }

    /** Recreate color + depth + framebuffer at the new size. Caller must
     *  {@code vkDeviceWaitIdle} first because the GPU may still be reading
     *  the old colour image (it's an ImGui descriptor source).
     *  Cheap no-op if the size is already current. */
    void resize(int newW, int newH) {
        if (newW <= 0 || newH <= 0) return;
        if (newW == width && newH == height && colorImage != 0) return;
        destroyImages();
        width  = newW;
        height = newH;
        createColor();
        createDepth();
        createFramebuffer();
    }

    // ============================================================
    //                       Resource creation
    // ============================================================

    private void createRenderPass() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer atts = VkAttachmentDescription.calloc(2, stack);
            // Color attachment — finalLayout = SHADER_READ_ONLY so ImGui
            // can sample it immediately after the render pass ends.
            atts.get(0)
                    .format(colorFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            atts.get(1)
                    .format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    // Stencil CLEAR (was DONT_CARE) — selection outline writes
                    // here; needs to start clean each frame. (Sub-phase H.)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
            colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                    .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef)
                    .pDepthStencilAttachment(depthRef);

            // Two external dependencies — one before (wait for any previous
            // shader read of this colour image to finish before writing) and
            // one after (make the colour write visible to the upcoming ImGui
            // shader read).
            VkSubpassDependency.Buffer deps = VkSubpassDependency.calloc(2, stack);
            deps.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                            | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            deps.get(1)
                    .srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(atts).pSubpasses(subpass).pDependencies(deps);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateRenderPass(ctx.device(), info, null, p), "vkCreateRenderPass(offscreen)");
            renderPass = p.get(0);
        }
    }

    private void createSampler() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSampler(ctx.device(), info, null, p), "vkCreateSampler(offscreen)");
            colorSampler = p.get(0);
        }
    }

    private void createColor() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(colorFormat)
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imgInfo.extent().set(width, height, 1);
            LongBuffer pImg = stack.mallocLong(1);
            check(vkCreateImage(ctx.device(), imgInfo, null, pImg), "vkCreateImage(offscreen color)");
            colorImage = pImg.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(ctx.device(), colorImage, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(VkUtil.findMemoryType(ctx.physicalDevice(),
                            req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));
            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(ctx.device(), alloc, null, pMem), "vkAllocateMemory(offscreen color)");
            colorImageMemory = pMem.get(0);
            check(vkBindImageMemory(ctx.device(), colorImage, colorImageMemory, 0),
                    "vkBindImageMemory(offscreen color)");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(colorImage).viewType(VK_IMAGE_VIEW_TYPE_2D).format(colorFormat);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(ctx.device(), viewInfo, null, pView),
                    "vkCreateImageView(offscreen color)");
            colorImageView = pView.get(0);
        }
    }

    private void createDepth() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(depthFormat)
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imgInfo.extent().set(width, height, 1);
            LongBuffer pImg = stack.mallocLong(1);
            check(vkCreateImage(ctx.device(), imgInfo, null, pImg), "vkCreateImage(offscreen depth)");
            depthImage = pImg.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(ctx.device(), depthImage, req);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(VkUtil.findMemoryType(ctx.physicalDevice(),
                            req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));
            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(ctx.device(), alloc, null, pMem), "vkAllocateMemory(offscreen depth)");
            depthImageMemory = pMem.get(0);
            check(vkBindImageMemory(ctx.device(), depthImage, depthImageMemory, 0),
                    "vkBindImageMemory(offscreen depth)");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(depthImage).viewType(VK_IMAGE_VIEW_TYPE_2D).format(depthFormat);
            // D32S8 / D24S8 needs both aspects when used as depth-stencil attachment.
            viewInfo.subresourceRange()
                    .aspectMask(VkSwapchain.hasStencil(depthFormat)
                            ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT
                            : VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(ctx.device(), viewInfo, null, pView),
                    "vkCreateImageView(offscreen depth)");
            depthImageView = pView.get(0);
        }
    }

    private void createFramebuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(stack.longs(colorImageView, depthImageView))
                    .width(width).height(height).layers(1);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateFramebuffer(ctx.device(), info, null, p),
                    "vkCreateFramebuffer(offscreen)");
            framebuffer = p.get(0);
        }
    }

    private void destroyImages() {
        if (framebuffer      != 0) vkDestroyFramebuffer(ctx.device(), framebuffer, null);
        if (depthImageView   != 0) vkDestroyImageView(ctx.device(), depthImageView, null);
        if (depthImage       != 0) vkDestroyImage(ctx.device(), depthImage, null);
        if (depthImageMemory != 0) vkFreeMemory(ctx.device(), depthImageMemory, null);
        if (colorImageView   != 0) vkDestroyImageView(ctx.device(), colorImageView, null);
        if (colorImage       != 0) vkDestroyImage(ctx.device(), colorImage, null);
        if (colorImageMemory != 0) vkFreeMemory(ctx.device(), colorImageMemory, null);
        framebuffer = depthImageView = depthImage = depthImageMemory =
                colorImageView = colorImage = colorImageMemory = 0;
    }

    void destroy() {
        destroyImages();
        if (colorSampler != 0) vkDestroySampler(ctx.device(), colorSampler, null);
        if (renderPass   != 0) vkDestroyRenderPass(ctx.device(), renderPass, null);
        colorSampler = renderPass = 0;
    }
}
