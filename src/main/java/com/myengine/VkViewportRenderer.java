package com.myengine;

import imgui.ImGuiPlatformIO;
import imgui.ImGuiViewport;
import imgui.ImVec2;
import imgui.callback.ImPlatformFuncViewport;
import imgui.callback.ImPlatformFuncViewportImVec2;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.myengine.VkUtil.check;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Multi-viewport Vulkan backend — the renderer half of Dear ImGui's
 * platform/renderer split. <b>ISOLATED PER-VIEWPORT submit model:</b> each
 * detached window owns a complete, independent presentation stack (surface +
 * swapchain + framebuffers + per-image command buffers + its OWN single
 * {@code imageAvailable} semaphore, per-image {@code renderFinished} semaphores
 * and its OWN {@code inFlight} fence). {@code Renderer_RenderWindow} runs the
 * whole per-window cycle — wait its fence → acquire → reset its fence → record →
 * submit (own fence); {@code Renderer_SwapBuffers} presents it. Crucially the
 * sync objects are strictly per-viewport (never shared with the main frame or
 * with each other), so the engine's main submit and each viewport's submit are
 * independent vkQueueSubmit calls each protected by their OWN fence — the
 * model that previously validated cleanly. The earlier "unified submit"
 * (one vkQueueSubmit waiting on multiple swapchains' imageAvailable) is
 * abandoned: a single submission cannot safely wait on multiple async acquires.
 */
final class VkViewportRenderer {

    /** All Vulkan state owned by one detached ImGui window. */
    private static final class PerViewport {
        long   windowHandle;          // GLFWwindow* from the platform side
        long   surface;               // VkSurfaceKHR
        long   swapchain;             // VkSwapchainKHR
        int    extentW, extentH;
        long[] images;                // owned by the swapchain (not destroyed)
        long[] imageViews;
        long[] framebuffers;
        VkCommandBuffer[] commandBuffers;   // per swapchain image
        long   imageAvailable;        // single (this viewport's acquire)
        long[] renderFinished;        // per swapchain image (present)
        long   inFlight;              // this viewport's OWN fence (single in-flight)
        int    currentImage = -1;     // set by renderWindow, presented by swapBuffers
        boolean needsRecreate = false;
    }

    private final VkContext ctx;
    private final VkImGuiRenderer imgui;
    private final int colorFormat;

    // One colour-only render pass shared by every secondary window.
    private long sharedRenderPass;

    // Live windows, keyed by ImGuiViewport id.
    private final Map<Integer, PerViewport> viewports = new HashMap<>();

    VkViewportRenderer(VkContext ctx, VkImGuiRenderer imgui, int colorFormat) {
        this.ctx         = ctx;
        this.imgui       = imgui;
        this.colorFormat = colorFormat;
        createSharedRenderPass();
        imgui.setViewportRenderPass(sharedRenderPass);
    }

    /** Wire the renderer callbacks onto ImGui's platform IO. */
    void register(ImGuiPlatformIO platformIO) {
        platformIO.setRendererCreateWindow(new ImPlatformFuncViewport() {
            @Override public void accept(ImGuiViewport vp) { createWindow(vp); }
        });
        platformIO.setRendererDestroyWindow(new ImPlatformFuncViewport() {
            @Override public void accept(ImGuiViewport vp) { destroyWindow(vp); }
        });
        platformIO.setRendererSetWindowPos(new ImPlatformFuncViewportImVec2() {
            @Override public void accept(ImGuiViewport vp, ImVec2 size) { resizeWindow(vp); }
        });
        platformIO.setRendererRenderWindow(new ImPlatformFuncViewport() {
            @Override public void accept(ImGuiViewport vp) { renderWindow(vp); }
        });
        platformIO.setRendererSwapBuffers(new ImPlatformFuncViewport() {
            @Override public void accept(ImGuiViewport vp) { swapBuffers(vp); }
        });
    }

    // ============================================================
    //                    Renderer callbacks
    // ============================================================

    /** Renderer_CreateWindow: stand up a presentation stack for {@code vp}. */
    private void createWindow(ImGuiViewport vp) {
        PerViewport pv = new PerViewport();
        pv.windowHandle = vp.getPlatformHandle();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            int surfRes = GLFWVulkan.glfwCreateWindowSurface(ctx.instance(), pv.windowHandle, null, pSurface);
            if (surfRes != VK_SUCCESS) {
                Logger.warn("[VK] glfwCreateWindowSurface for a detached viewport failed (" + surfRes
                        + ") — skipping it; the main editor is unaffected.");
                return;
            }
            pv.surface = pSurface.get(0);

            IntBuffer canPresent = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceSupportKHR(ctx.physicalDevice(),
                    ctx.presentFamily(), pv.surface, canPresent);
            if (canPresent.get(0) != VK_TRUE) {
                Logger.warn("[VK] Viewport surface does not support the present queue family — skipping");
                vkDestroySurfaceKHR(ctx.instance(), pv.surface, null);
                return;
            }
        }

        createSwapchainResources(pv, (int) vp.getSizeX(), (int) vp.getSizeY());
        createSyncAndCommandBuffers(pv);
        viewports.put(vp.getID(), pv);
    }

    /** Renderer_SetWindowPos/Size: recreate the swapchain to the new size. */
    private void resizeWindow(ImGuiViewport vp) {
        PerViewport pv = viewports.get(vp.getID());
        if (pv == null) return;
        int w = (int) vp.getSizeX(), h = (int) vp.getSizeY();
        if (w <= 0 || h <= 0) return;
        if (w == pv.extentW && h == pv.extentH && !pv.needsRecreate) return;

        vkDeviceWaitIdle(ctx.device());
        destroySwapchainResources(pv);
        createSwapchainResources(pv, w, h);
        destroySyncAndCommandBuffers(pv);
        createSyncAndCommandBuffers(pv);
        pv.needsRecreate = false;
    }

    /** Renderer_RenderWindow: the full per-viewport cycle — wait THIS viewport's
     *  fence → acquire → reset THIS fence → record → submit with THIS fence.
     *  Strictly isolated: never touches the main frame's or other viewports'
     *  sync objects. */
    private void renderWindow(ImGuiViewport vp) {
        PerViewport pv = viewports.get(vp.getID());
        if (pv == null || pv.swapchain == 0L) return;
        if (pv.needsRecreate) { resizeWindow(vp); if (pv.swapchain == 0L) return; }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkWaitForFences(ctx.device(), pv.inFlight, true, Long.MAX_VALUE);

            IntBuffer pImageIndex = stack.mallocInt(1);
            int acq = vkAcquireNextImageKHR(ctx.device(), pv.swapchain, Long.MAX_VALUE,
                    pv.imageAvailable, VK_NULL_HANDLE, pImageIndex);
            if (acq == VK_ERROR_OUT_OF_DATE_KHR) { pv.needsRecreate = true; return; }
            if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR) {
                Logger.warn("[VK] viewport acquire failed: " + acq);
                return;
            }
            int imageIndex = pImageIndex.get(0);
            pv.currentImage = imageIndex;

            vkResetFences(ctx.device(), pv.inFlight);

            VkCommandBuffer cb = pv.commandBuffers[imageIndex];
            vkResetCommandBuffer(cb, 0);
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            check(vkBeginCommandBuffer(cb, begin), "vkBeginCommandBuffer(viewport)");

            VkExtent2D extent = VkExtent2D.calloc(stack).set(pv.extentW, pv.extentH);
            imgui.record(cb, vp.getDrawData(), pv.framebuffers[imageIndex], extent, sharedRenderPass);

            check(vkEndCommandBuffer(cb), "vkEndCommandBuffer(viewport)");

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(pv.imageAvailable))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(cb))
                    .pSignalSemaphores(stack.longs(pv.renderFinished[imageIndex]));
            check(vkQueueSubmit(ctx.graphicsQueue(), submit, pv.inFlight), "vkQueueSubmit(viewport)");
        }
    }

    /** Renderer_SwapBuffers: present the image rendered by renderWindow. */
    private void swapBuffers(ImGuiViewport vp) {
        PerViewport pv = viewports.get(vp.getID());
        if (pv == null || pv.swapchain == 0L || pv.currentImage < 0) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(pv.renderFinished[pv.currentImage]))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(pv.swapchain))
                    .pImageIndices(stack.ints(pv.currentImage));
            int res = vkQueuePresentKHR(ctx.presentQueue(), present);
            if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_SUBOPTIMAL_KHR) {
                pv.needsRecreate = true;
            } else if (res != VK_SUCCESS) {
                Logger.warn("[VK] viewport present failed: " + res);
            }
            pv.currentImage = -1;
        }
    }

    /** Renderer_DestroyWindow: tear the whole stack down. */
    private void destroyWindow(ImGuiViewport vp) {
        PerViewport pv = viewports.remove(vp.getID());
        if (pv == null) return;
        vkDeviceWaitIdle(ctx.device());
        destroySyncAndCommandBuffers(pv);
        destroySwapchainResources(pv);
        if (pv.surface != 0L) vkDestroySurfaceKHR(ctx.instance(), pv.surface, null);
    }

    // ============================================================
    //                    Swapchain lifecycle
    // ============================================================

    private void createSwapchainResources(PerViewport pv, int w, int h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(ctx.physicalDevice(), pv.surface, caps);

            int extentW, extentH;
            if (caps.currentExtent().width() != 0xFFFFFFFF) {
                extentW = caps.currentExtent().width();
                extentH = caps.currentExtent().height();
            } else {
                extentW = Math.max(caps.minImageExtent().width(),
                        Math.min(caps.maxImageExtent().width(), w));
                extentH = Math.max(caps.minImageExtent().height(),
                        Math.min(caps.maxImageExtent().height(), h));
            }
            pv.extentW = extentW;
            pv.extentH = extentH;

            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice(), pv.surface, count, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice(), pv.surface, count, formats);
            int chosenColorSpace = formats.get(0).colorSpace();
            for (int i = 0; i < formats.remaining(); i++) {
                if (formats.get(i).format() == colorFormat) {
                    chosenColorSpace = formats.get(i).colorSpace();
                    break;
                }
            }

            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) {
                imageCount = caps.maxImageCount();
            }

            // Avoid FIFO double-V-Sync contention: prefer MAILBOX, then IMMEDIATE.
            IntBuffer pmCount = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice(), pv.surface, pmCount, null);
            IntBuffer pModes = stack.mallocInt(pmCount.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice(), pv.surface, pmCount, pModes);
            boolean hasMailbox = false, hasImmediate = false;
            for (int i = 0; i < pModes.remaining(); i++) {
                if (pModes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR)   hasMailbox = true;
                if (pModes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR) hasImmediate = true;
            }
            int presentMode = hasMailbox ? VK_PRESENT_MODE_MAILBOX_KHR
                            : hasImmediate ? VK_PRESENT_MODE_IMMEDIATE_KHR
                            : VK_PRESENT_MODE_FIFO_KHR;

            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(pv.surface)
                    .minImageCount(imageCount)
                    .imageFormat(colorFormat)
                    .imageColorSpace(chosenColorSpace)
                    .imageExtent(e -> e.width(pv.extentW).height(pv.extentH))
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
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

            LongBuffer pSwap = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(ctx.device(), info, null, pSwap), "vkCreateSwapchainKHR(viewport)");
            pv.swapchain = pSwap.get(0);

            vkGetSwapchainImagesKHR(ctx.device(), pv.swapchain, count, null);
            LongBuffer pImages = stack.mallocLong(count.get(0));
            vkGetSwapchainImagesKHR(ctx.device(), pv.swapchain, count, pImages);
            pv.images = new long[count.get(0)];
            for (int i = 0; i < pv.images.length; i++) pv.images[i] = pImages.get(i);

            pv.imageViews = new long[pv.images.length];
            LongBuffer pView = stack.mallocLong(1);
            for (int i = 0; i < pv.images.length; i++) {
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(pv.images[i]).viewType(VK_IMAGE_VIEW_TYPE_2D).format(colorFormat);
                viewInfo.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                check(vkCreateImageView(ctx.device(), viewInfo, null, pView),
                        "vkCreateImageView(viewport[" + i + "])");
                pv.imageViews[i] = pView.get(0);
            }

            pv.framebuffers = new long[pv.images.length];
            LongBuffer pFb = stack.mallocLong(1);
            for (int i = 0; i < pv.images.length; i++) {
                VkFramebufferCreateInfo fbInfo = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(sharedRenderPass)
                        .pAttachments(stack.longs(pv.imageViews[i]))
                        .width(pv.extentW).height(pv.extentH).layers(1);
                check(vkCreateFramebuffer(ctx.device(), fbInfo, null, pFb),
                        "vkCreateFramebuffer(viewport[" + i + "])");
                pv.framebuffers[i] = pFb.get(0);
            }
        }
    }

    private void destroySwapchainResources(PerViewport pv) {
        if (pv.framebuffers != null)
            for (long fb : pv.framebuffers) if (fb != 0) vkDestroyFramebuffer(ctx.device(), fb, null);
        if (pv.imageViews != null)
            for (long v : pv.imageViews) if (v != 0) vkDestroyImageView(ctx.device(), v, null);
        if (pv.swapchain != 0) vkDestroySwapchainKHR(ctx.device(), pv.swapchain, null);
        pv.framebuffers = null;
        pv.imageViews = null;
        pv.swapchain = 0;
    }

    // ============================================================
    //                    Sync + command buffers
    // ============================================================

    private void createSyncAndCommandBuffers(PerViewport pv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo alloc = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(ctx.commandPool())
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(pv.images.length);
            PointerBuffer pCmd = stack.mallocPointer(pv.images.length);
            check(vkAllocateCommandBuffers(ctx.device(), alloc, pCmd), "vkAllocateCommandBuffers(viewport)");
            pv.commandBuffers = new VkCommandBuffer[pv.images.length];
            for (int i = 0; i < pv.images.length; i++) {
                pv.commandBuffers[i] = new VkCommandBuffer(pCmd.get(i), ctx.device());
            }

            VkSemaphoreCreateInfo sem = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            VkFenceCreateInfo fence = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);   // first wait must pass

            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSemaphore(ctx.device(), sem, null, p), "viewport imageAvailable");
            pv.imageAvailable = p.get(0);
            check(vkCreateFence(ctx.device(), fence, null, p), "viewport inFlight");
            pv.inFlight = p.get(0);

            pv.renderFinished = new long[pv.images.length];
            for (int i = 0; i < pv.images.length; i++) {
                check(vkCreateSemaphore(ctx.device(), sem, null, p), "viewport renderFinished");
                pv.renderFinished[i] = p.get(0);
            }
        }
    }

    private void destroySyncAndCommandBuffers(PerViewport pv) {
        if (pv.commandBuffers != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pCmd = stack.mallocPointer(pv.commandBuffers.length);
                for (VkCommandBuffer cb : pv.commandBuffers) pCmd.put(cb);
                pCmd.flip();
                vkFreeCommandBuffers(ctx.device(), ctx.commandPool(), pCmd);
            }
            pv.commandBuffers = null;
        }
        if (pv.imageAvailable != 0) vkDestroySemaphore(ctx.device(), pv.imageAvailable, null);
        if (pv.renderFinished != null)
            for (long s : pv.renderFinished) if (s != 0) vkDestroySemaphore(ctx.device(), s, null);
        if (pv.inFlight != 0) vkDestroyFence(ctx.device(), pv.inFlight, null);
        pv.imageAvailable = 0;
        pv.renderFinished = null;
        pv.inFlight = 0;
    }

    // ============================================================
    //                    Shared render pass
    // ============================================================

    private void createSharedRenderPass() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer atts = VkAttachmentDescription.calloc(1, stack);
            atts.get(0)
                    .format(colorFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
            colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, stack);
            dep.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(0)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(atts).pSubpasses(subpass).pDependencies(dep);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateRenderPass(ctx.device(), info, null, p), "vkCreateRenderPass(viewport-shared)");
            sharedRenderPass = p.get(0);
        }
    }

    // ============================================================
    //                         Teardown
    // ============================================================

    void destroy() {
        vkDeviceWaitIdle(ctx.device());
        for (PerViewport pv : viewports.values()) {
            destroySyncAndCommandBuffers(pv);
            destroySwapchainResources(pv);
            if (pv.surface != 0L) vkDestroySurfaceKHR(ctx.instance(), pv.surface, null);
        }
        viewports.clear();
        if (sharedRenderPass != 0) vkDestroyRenderPass(ctx.device(), sharedRenderPass, null);
        sharedRenderPass = 0;
    }
}
