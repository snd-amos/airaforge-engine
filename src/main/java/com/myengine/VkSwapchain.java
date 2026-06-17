package com.myengine;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static com.myengine.VkUtil.check;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Everything that gets recreated together on window resize: the swapchain,
 * its image views, the depth attachment, the main color+depth render pass,
 * one framebuffer per swapchain image, and all per-frame / per-image sync
 * objects.
 *
 * Lifetime model for the semaphores (learned the hard way — see commentary
 * in drawFrame for the validation-layer story):
 *   - imageAvailableSemaphores[frameSlot]  — per CPU frame slot.
 *   - renderFinishedSemaphores[imageIndex] — per SWAPCHAIN IMAGE. The
 *     present op holds a reference until the image is re-acquired, so
 *     sharing one across frames eventually double-signals it.
 *   - inFlightFences[frameSlot]            — per CPU frame slot, blocks
 *     CPU when too many submissions are queued.
 */
final class VkSwapchain {

    private final VkContext ctx;
    private final int       maxFramesInFlight;

    private long   swapchain;
    private long[] images;
    private long[] imageViews;
    private long[] framebuffers;
    private int    imageFormat;
    private int    extentW, extentH;

    private long   depthImage;
    private long   depthImageMemory;
    private long   depthImageView;
    private int    depthFormat;

    private long   renderPass;

    private long[] imageAvailableSemaphores; // length = maxFramesInFlight
    private long[] renderFinishedSemaphores; // length = images.length
    private long[] inFlightFences;           // length = maxFramesInFlight

    VkSwapchain(VkContext ctx, long glfwWindow, int maxFramesInFlight) {
        this.ctx = ctx;
        this.maxFramesInFlight = maxFramesInFlight;
        createSwapchain(glfwWindow);
        createImageViews();
        createDepthResources();
        createRenderPass();
        createFramebuffers();
        createSyncObjects();
    }

    // ---- accessors ----
    long    handle()                  { return swapchain; }
    int     imageCount()              { return images.length; }
    int     extentW()                 { return extentW; }
    int     extentH()                 { return extentH; }
    int     imageFormat()             { return imageFormat; }
    int     depthFormat()             { return depthFormat; }
    long    renderPass()              { return renderPass; }
    long    framebuffer(int i)        { return framebuffers[i]; }
    long    imageAvailable(int frame) { return imageAvailableSemaphores[frame]; }
    long    renderFinished(int img)   { return renderFinishedSemaphores[img]; }
    long    inFlight(int frame)       { return inFlightFences[frame]; }

    // ============================================================
    //                          Setup
    // ============================================================

    private void createSwapchain(long glfwWindow) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(ctx.physicalDevice(), ctx.surface(), caps);

            // Prefer 8-bit BGRA UNORM — NOT sRGB. The OpenGL build wrote
            // shader outputs raw to the default framebuffer (no
            // GL_FRAMEBUFFER_SRGB), so all its lighting math happened in
            // gamma space and bytes hit the display untouched. An _SRGB
            // swapchain makes the hardware apply linear→sRGB encoding on
            // every write, brightening the whole frame (the "washed out
            // high-brightness hue" regression vs the GL build). UNORM
            // passes bytes through exactly like GL did.
            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice(), ctx.surface(), count, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice(), ctx.surface(), count, formats);
            VkSurfaceFormatKHR chosen = formats.get(0);
            for (int i = 0; i < formats.remaining(); i++) {
                VkSurfaceFormatKHR f = formats.get(i);
                if (f.format() == VK_FORMAT_B8G8R8A8_UNORM &&
                        f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) { chosen = f; break; }
            }
            imageFormat = chosen.format();

            // MAILBOX preferred (low-latency triple-buffer); FIFO is the spec guarantee.
            vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice(), ctx.surface(), count, null);
            IntBuffer modes = stack.mallocInt(count.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice(), ctx.surface(), count, modes);
            int presentMode = VK_PRESENT_MODE_FIFO_KHR;
            for (int i = 0; i < modes.remaining(); i++) {
                if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) { presentMode = VK_PRESENT_MODE_MAILBOX_KHR; break; }
            }

            if (caps.currentExtent().width() != 0xFFFFFFFF) {
                extentW = caps.currentExtent().width();
                extentH = caps.currentExtent().height();
            } else {
                int[] w = new int[1], h = new int[1];
                glfwGetFramebufferSize(glfwWindow, w, h);
                extentW = Math.max(caps.minImageExtent().width(),
                        Math.min(caps.maxImageExtent().width(),  w[0]));
                extentH = Math.max(caps.minImageExtent().height(),
                        Math.min(caps.maxImageExtent().height(), h[0]));
            }

            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) {
                imageCount = caps.maxImageCount();
            }

            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(ctx.surface())
                    .minImageCount(imageCount)
                    .imageFormat(chosen.format())
                    .imageColorSpace(chosen.colorSpace())
                    .imageExtent(e -> e.width(extentW).height(extentH))
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(VK_NULL_HANDLE);

            if (ctx.graphicsFamily() != ctx.presentFamily()) {
                info.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(stack.ints(ctx.graphicsFamily(), ctx.presentFamily()));
            } else {
                info.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(ctx.device(), info, null, p), "vkCreateSwapchainKHR");
            swapchain = p.get(0);

            vkGetSwapchainImagesKHR(ctx.device(), swapchain, count, null);
            LongBuffer pImages = stack.mallocLong(count.get(0));
            vkGetSwapchainImagesKHR(ctx.device(), swapchain, count, pImages);
            images = new long[count.get(0)];
            for (int i = 0; i < images.length; i++) images[i] = pImages.get(i);

            Logger.info("[VK] Swapchain: " + images.length + " images, " + extentW + "x" + extentH
                    + ", format=" + imageFormat
                    + ", presentMode=" + (presentMode == VK_PRESENT_MODE_MAILBOX_KHR ? "MAILBOX" : "FIFO"));
        }
    }

    private void createImageViews() {
        imageViews = new long[images.length];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < images.length; i++) {
                VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(images[i])
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(imageFormat);
                info.components()
                        .r(VK_COMPONENT_SWIZZLE_IDENTITY).g(VK_COMPONENT_SWIZZLE_IDENTITY)
                        .b(VK_COMPONENT_SWIZZLE_IDENTITY).a(VK_COMPONENT_SWIZZLE_IDENTITY);
                info.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(1);
                check(vkCreateImageView(ctx.device(), info, null, p), "vkCreateImageView[" + i + "]");
                imageViews[i] = p.get(0);
            }
        }
    }

    private void createDepthResources() {
        depthFormat = pickDepthFormat(ctx.physicalDevice());
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
            imgInfo.extent().set(extentW, extentH, 1);

            LongBuffer pImg = stack.mallocLong(1);
            check(vkCreateImage(ctx.device(), imgInfo, null, pImg), "vkCreateImage(depth)");
            depthImage = pImg.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(ctx.device(), depthImage, memReqs);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(VkUtil.findMemoryType(ctx.physicalDevice(),
                            memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));
            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(ctx.device(), alloc, null, pMem), "vkAllocateMemory(depth)");
            depthImageMemory = pMem.get(0);
            check(vkBindImageMemory(ctx.device(), depthImage, depthImageMemory, 0), "vkBindImageMemory(depth)");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(depthImage).viewType(VK_IMAGE_VIEW_TYPE_2D).format(depthFormat);
            // Depth-stencil attachment view needs BOTH aspects when the
            // format is D32S8 / D24S8 (Vulkan spec).
            viewInfo.subresourceRange()
                    .aspectMask(hasStencil(depthFormat)
                            ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT
                            : VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(ctx.device(), viewInfo, null, pView), "vkCreateImageView(depth)");
            depthImageView = pView.get(0);
        }
    }

    /** True if {@code format} contains a stencil aspect. */
    static boolean hasStencil(int format) {
        return format == VK_FORMAT_D32_SFLOAT_S8_UINT
            || format == VK_FORMAT_D24_UNORM_S8_UINT
            || format == VK_FORMAT_D16_UNORM_S8_UINT;
    }

    /** Picks the first supported depth+stencil format. We need stencil
     *  for the selection silhouette outline (sub-phase H), so D32+S8
     *  is preferred over plain D32. D24S8 is the legacy fallback for
     *  drivers without D32S8. */
    static int pickDepthFormat(VkPhysicalDevice physicalDevice) {
        int[] candidates = {
                VK_FORMAT_D32_SFLOAT_S8_UINT,
                VK_FORMAT_D24_UNORM_S8_UINT,
                VK_FORMAT_D32_SFLOAT
        };
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties props = VkFormatProperties.calloc(stack);
            for (int fmt : candidates) {
                vkGetPhysicalDeviceFormatProperties(physicalDevice, fmt, props);
                if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) return fmt;
            }
        }
        throw new RuntimeException("No supported depth format on this device");
    }

    private void createRenderPass() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer atts = VkAttachmentDescription.calloc(2, stack);
            atts.get(0)
                    .format(imageFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            atts.get(1)
                    .format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    // Stencil is now LOAD_OP_CLEAR (was DONT_CARE) because
                    // the selection outline relies on starting with a clean
                    // stencil buffer each frame — sub-phase H.
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
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

            // TWO external dependencies — byte-identical to
            // VkOffscreenTarget so pipelines created against either render
            // pass pass strict render-pass-compatibility checks under the
            // newer Vulkan validation layers (the layers compare dep
            // stage / access masks, not just counts).
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
            check(vkCreateRenderPass(ctx.device(), info, null, p), "vkCreateRenderPass");
            renderPass = p.get(0);
        }
    }

    private void createFramebuffers() {
        framebuffers = new long[imageViews.length];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer attachments = stack.mallocLong(2);
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .width(extentW).height(extentH).layers(1);
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < imageViews.length; i++) {
                attachments.put(0, imageViews[i]);
                attachments.put(1, depthImageView);
                info.pAttachments(attachments);
                check(vkCreateFramebuffer(ctx.device(), info, null, p), "vkCreateFramebuffer[" + i + "]");
                framebuffers[i] = p.get(0);
            }
        }
    }

    private void createSyncObjects() {
        imageAvailableSemaphores = new long[maxFramesInFlight];
        inFlightFences           = new long[maxFramesInFlight];
        renderFinishedSemaphores = new long[images.length];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo sem = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            VkFenceCreateInfo fence = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    // Pre-signaled so the very first vkWaitForFences doesn't deadlock.
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < maxFramesInFlight; i++) {
                check(vkCreateSemaphore(ctx.device(), sem, null, p), "imageAvailable semaphore");
                imageAvailableSemaphores[i] = p.get(0);
                check(vkCreateFence(ctx.device(), fence, null, p), "inFlight fence");
                inFlightFences[i] = p.get(0);
            }
            for (int i = 0; i < images.length; i++) {
                check(vkCreateSemaphore(ctx.device(), sem, null, p), "renderFinished semaphore");
                renderFinishedSemaphores[i] = p.get(0);
            }
        }
    }

    void destroy() {
        VkDevice device = ctx.device();
        if (imageAvailableSemaphores != null)
            for (long s : imageAvailableSemaphores) if (s != 0) vkDestroySemaphore(device, s, null);
        if (renderFinishedSemaphores != null)
            for (long s : renderFinishedSemaphores) if (s != 0) vkDestroySemaphore(device, s, null);
        if (inFlightFences != null)
            for (long f : inFlightFences) if (f != 0) vkDestroyFence(device, f, null);

        if (framebuffers != null)
            for (long fb : framebuffers) if (fb != 0) vkDestroyFramebuffer(device, fb, null);
        if (renderPass != 0) vkDestroyRenderPass(device, renderPass, null);

        if (depthImageView != 0)   vkDestroyImageView(device, depthImageView, null);
        if (depthImage != 0)       vkDestroyImage(device, depthImage, null);
        if (depthImageMemory != 0) vkFreeMemory(device, depthImageMemory, null);

        if (imageViews != null)
            for (long v : imageViews) if (v != 0) vkDestroyImageView(device, v, null);
        if (swapchain != 0) vkDestroySwapchainKHR(device, swapchain, null);
    }
}
