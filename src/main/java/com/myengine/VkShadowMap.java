package com.myengine;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static com.myengine.VkUtil.check;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan port of {@link ShadowMapFBO}: owns a depth-only render target plus
 * the render pass and framebuffer needed to render into it.
 *
 * One sun-shadow per frame — same as the OpenGL path. The depth image
 * ends each shadow pass in {@code SHADER_READ_ONLY_OPTIMAL} so the main
 * render pass can sample it directly without an explicit barrier (the
 * render-pass external dependency does the layout transition for us).
 *
 * Border colour = opaque white (depth = 1.0 = "farthest plane"), so any
 * fragment whose light-space coordinate falls outside the shadow map is
 * treated as fully lit. Matches the GL_CLAMP_TO_BORDER + white border
 * trick in ShadowMapFBO.
 */
final class VkShadowMap {

    private final int width, height, format;

    private long image;
    private long imageMemory;
    private long imageView;
    private long sampler;
    private long renderPass;
    private long framebuffer;

    VkShadowMap(VkDevice device, VkPhysicalDevice physicalDevice,
                int depthFormat, int width, int height) {
        this.width  = width;
        this.height = height;
        this.format = depthFormat;
        createImage(device, physicalDevice);
        createImageView(device);
        createSampler(device);
        createRenderPass(device);
        createFramebuffer(device);
    }

    // ---- accessors ----
    long image()       { return image; }
    long imageView()   { return imageView; }
    long sampler()     { return sampler; }
    long renderPass()  { return renderPass; }
    long framebuffer() { return framebuffer; }
    int  width()       { return width; }
    int  height()      { return height; }
    int  format()      { return format; }

    // ---- internals ----

    private void createImage(VkDevice device, VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    // DEPTH_STENCIL = write target during shadow pass.
                    // SAMPLED       = read target during main pass.
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                            | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);

            LongBuffer p = stack.mallocLong(1);
            check(vkCreateImage(device, info, null, p), "vkCreateImage(shadow)");
            image = p.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, memReqs);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(VkUtil.findMemoryType(physicalDevice,
                            memReqs.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));
            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(device, alloc, null, pMem), "vkAllocateMemory(shadow)");
            imageMemory = pMem.get(0);
            check(vkBindImageMemory(device, image, imageMemory, 0), "vkBindImageMemory(shadow)");
        }
    }

    private void createImageView(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            info.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateImageView(device, info, null, p), "vkCreateImageView(shadow)");
            imageView = p.get(0);
        }
    }

    private void createSampler(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    // Nearest matches GL's GL_NEAREST on the depth texture.
                    // The PCF tap loop in the shader does the smoothing.
                    .magFilter(VK_FILTER_NEAREST)
                    .minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
                    .compareEnable(false)
                    .anisotropyEnable(false)
                    .unnormalizedCoordinates(false);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSampler(device, info, null, p), "vkCreateSampler(shadow)");
            sampler = p.get(0);
        }
    }

    /** Depth-only render pass. One attachment, one subpass writing depth,
     *  external dependencies that transition the depth image to
     *  SHADER_READ_ONLY_OPTIMAL on exit so the main pass can sample it. */
    private void createRenderPass(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer atts = VkAttachmentDescription.calloc(1, stack);
            atts.get(0)
                    .format(format)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkAttachmentReference.Buffer depthRef = VkAttachmentReference.calloc(1, stack);
            depthRef.get(0)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    // No color attachments — depth-only pass.
                    .colorAttachmentCount(0)
                    .pDepthStencilAttachment(depthRef.get(0));

            // Two external dependencies — one before, one after — to make
            // the read-after-write between the shadow pass (depth write) and
            // the main pass (fragment shader read) safe without an explicit
            // pipeline barrier.
            VkSubpassDependency.Buffer deps = VkSubpassDependency.calloc(2, stack);
            deps.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            deps.get(1)
                    .srcSubpass(0)
                    .dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(atts)
                    .pSubpasses(subpass)
                    .pDependencies(deps);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateRenderPass(device, info, null, p), "vkCreateRenderPass(shadow)");
            renderPass = p.get(0);
        }
    }

    private void createFramebuffer(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(stack.longs(imageView))
                    .width(width)
                    .height(height)
                    .layers(1);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateFramebuffer(device, info, null, p), "vkCreateFramebuffer(shadow)");
            framebuffer = p.get(0);
        }
    }

    void destroy(VkDevice device) {
        if (framebuffer != 0) vkDestroyFramebuffer(device, framebuffer, null);
        if (renderPass  != 0) vkDestroyRenderPass(device, renderPass, null);
        if (sampler     != 0) vkDestroySampler(device, sampler, null);
        if (imageView   != 0) vkDestroyImageView(device, imageView, null);
        if (image       != 0) vkDestroyImage(device, image, null);
        if (imageMemory != 0) vkFreeMemory(device, imageMemory, null);
    }
}
