package com.myengine;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Stateless Vulkan helper functions. Lives outside {@link VulkanMain} so
 * mesh / texture / pipeline modules can call into the same routines
 * without circular dependencies. Everything here takes its required
 * device handles as parameters — no shared mutable state.
 */
final class VkUtil {

    private VkUtil() {}

    /**
     * Throws a {@link RuntimeException} if the Vulkan result code is not
     * {@code VK_SUCCESS}. Used after every fallible Vulkan call.
     */
    static void check(int result, String label) {
        if (result != VK_SUCCESS) {
            throw new RuntimeException(label + " failed: VkResult=" + result);
        }
    }

    /**
     * Pick a memory type satisfying (a) the resource's typeFilter mask
     * (from {@code VkMemoryRequirements.memoryTypeBits}) and (b) the
     * requested property flags (e.g. HOST_VISIBLE | HOST_COHERENT for
     * easy CPU upload, DEVICE_LOCAL for GPU-only buffers). Standard
     * Vulkan idiom — repeated in every textbook walkthrough.
     */
    static int findMemoryType(VkPhysicalDevice physicalDevice, int typeFilter,
                              int requiredProps, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0
                    && (memProps.memoryTypes(i).propertyFlags() & requiredProps) == requiredProps) {
                return i;
            }
        }
        throw new RuntimeException("No suitable memory type for filter=0x"
                + Integer.toHexString(typeFilter) + " props=0x" + Integer.toHexString(requiredProps));
    }

    /**
     * Generic Vulkan buffer factory. Returns {@code [bufferHandle, memoryHandle]}.
     *
     * For Phase 3/4 we allocate HOST_VISIBLE | HOST_COHERENT memory so we
     * can {@code vkMapMemory} and {@code memcpy} from Java without manual
     * cache flushes. That's slower for hot GPU reads than DEVICE_LOCAL
     * memory with a staging transfer, but the difference is invisible
     * until we're streaming hundreds of MB/s. Worth optimising later.
     */
    static long[] createBuffer(VkDevice device, VkPhysicalDevice physicalDevice,
                                long sizeBytes, int usage, int memProperties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bi = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(sizeBytes)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            check(vkCreateBuffer(device, bi, null, pBuf), "vkCreateBuffer");

            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, pBuf.get(0), req);

            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(findMemoryType(physicalDevice, req.memoryTypeBits(),
                            memProperties, stack));
            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(device, alloc, null, pMem), "vkAllocateMemory");

            check(vkBindBufferMemory(device, pBuf.get(0), pMem.get(0), 0),
                    "vkBindBufferMemory");
            return new long[]{ pBuf.get(0), pMem.get(0) };
        }
    }

    /** Copy a region of CPU memory into a HOST_VISIBLE buffer via map/unmap. */
    static void uploadToBuffer(VkDevice device, long bufferMemory, long sizeBytes,
                                java.util.function.Consumer<java.nio.ByteBuffer> writer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            check(vkMapMemory(device, bufferMemory, 0, sizeBytes, 0, pData),
                    "vkMapMemory");
            writer.accept(pData.getByteBuffer(0, (int) sizeBytes));
            vkUnmapMemory(device, bufferMemory);
        }
    }

    /**
     * Allocate a one-shot primary command buffer from {@code commandPool},
     * record into it via the caller's lambda, submit to {@code queue},
     * wait for completion, then free. Used for transfer / layout-transition
     * operations (texture upload, etc.) that happen outside the render loop.
     *
     * Blocks until the GPU finishes — fine for init-time work; don't use
     * this in the hot path.
     */
    static void singleTimeCommands(VkDevice device, long commandPool, VkQueue queue,
                                    java.util.function.Consumer<VkCommandBuffer> recorder) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo alloc = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCb = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, alloc, pCb), "alloc one-shot cb");
            VkCommandBuffer cb = new VkCommandBuffer(pCb.get(0), device);

            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cb, bi), "begin one-shot cb");
            recorder.accept(cb);
            check(vkEndCommandBuffer(cb), "end one-shot cb");

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cb.address()));
            check(vkQueueSubmit(queue, submit, VK10.VK_NULL_HANDLE), "submit one-shot");
            check(vkQueueWaitIdle(queue), "wait one-shot");

            vkFreeCommandBuffers(device, commandPool, cb);
        }
    }
}
