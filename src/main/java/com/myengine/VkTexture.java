package com.myengine;

import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU texture: loads a PNG / JPG from the filesystem via STB, uploads it
 * via a staging buffer, owns the {@code VkImage} / {@code VkImageView} /
 * {@code VkSampler} for the duration of the run.
 *
 * Vulkan's texture pipeline is verbose compared to OpenGL but each step
 * has a clear purpose:
 *   1. CPU decode (STB)   → raw RGBA bytes in CPU memory
 *   2. Staging buffer     → HOST_VISIBLE buffer holding the bytes
 *   3. Device image       → DEVICE_LOCAL VkImage with USAGE_SAMPLED |
 *                           USAGE_TRANSFER_DST
 *   4. Layout transition  → UNDEFINED → TRANSFER_DST_OPTIMAL
 *   5. vkCmdCopyBufferToImage → staging → device image
 *   6. Layout transition  → TRANSFER_DST_OPTIMAL → SHADER_READ_ONLY_OPTIMAL
 *   7. Image view         → how shaders sample it (R8G8B8A8_UNORM, mip 0)
 *   8. Sampler            → filtering / wrap mode
 *
 * In OpenGL all of this collapsed into {@code glTexImage2D} + driver
 * magic. Vulkan makes every step explicit so you can pipeline / batch
 * transfers — overkill for a single icon load but the same code scales
 * cleanly to streaming hundreds of textures.
 */
final class VkTexture {

    private final long image;
    private final long imageMemory;
    private final long imageView;
    private final long sampler;

    /** Load a texture from a filesystem path. Uses STB for decode (handles
     *  PNG / JPG / TGA / BMP). Synchronous — blocks until upload completes. */
    static VkTexture loadFromFile(VkDevice device, VkPhysicalDevice physicalDevice,
                                   long commandPool, VkQueue queue, String path) {
        ByteBuffer pixels;
        int width, height;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pW = stack.mallocInt(1);
            IntBuffer pH = stack.mallocInt(1);
            IntBuffer pC = stack.mallocInt(1);
            // 4 = force RGBA. SRGB color space is applied at sample time
            // via the image's VK_FORMAT_R8G8B8A8_UNORM format, not here.
            pixels = STBImage.stbi_load(path, pW, pH, pC, 4);
            if (pixels == null) {
                throw new RuntimeException("STBImage failed to load '" + path + "': "
                        + STBImage.stbi_failure_reason());
            }
            width = pW.get(0);
            height = pH.get(0);
        }
        try {
            return uploadRgba(device, physicalDevice, commandPool, queue, pixels, width, height);
        } finally {
            STBImage.stbi_image_free(pixels);
        }
    }

    /** Decode an in-memory PNG/JPEG/etc. byte stream and upload as a texture.
     *  Used for GLB-embedded textures (extracted by {@link VkMeshLoader}) —
     *  the bytes come from Assimp's {@code AITexture.pcData()} pointer
     *  rather than disk or classpath. */
    static VkTexture loadFromEncodedBytes(VkDevice device, VkPhysicalDevice physicalDevice,
                                           long commandPool, VkQueue queue, ByteBuffer encoded) {
        ByteBuffer pixels;
        int width, height;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pW = stack.mallocInt(1);
            IntBuffer pH = stack.mallocInt(1);
            IntBuffer pC = stack.mallocInt(1);
            pixels = STBImage.stbi_load_from_memory(encoded, pW, pH, pC, 4);
            if (pixels == null) {
                throw new RuntimeException("STB decode failed for embedded texture: "
                        + STBImage.stbi_failure_reason());
            }
            width = pW.get(0);
            height = pH.get(0);
        }
        try {
            return uploadRgba(device, physicalDevice, commandPool, queue, pixels, width, height);
        } finally {
            STBImage.stbi_image_free(pixels);
        }
    }

    /** Load a texture from a classpath resource — read into a ByteBuffer
     *  then hand off to STB's memory-load variant. Used for packaged JAR
     *  resources; the filesystem variant is fine during development. */
    static VkTexture loadFromClasspath(VkDevice device, VkPhysicalDevice physicalDevice,
                                        long commandPool, VkQueue queue, String resourcePath) {
        ByteBuffer encoded;
        try (InputStream in = VkTexture.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new RuntimeException("Texture not on classpath: " + resourcePath);
            byte[] bytes = in.readAllBytes();
            encoded = org.lwjgl.BufferUtils.createByteBuffer(bytes.length).put(bytes).flip();
        } catch (IOException e) {
            throw new RuntimeException("Failed reading texture resource " + resourcePath, e);
        }
        ByteBuffer pixels;
        int width, height;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pW = stack.mallocInt(1);
            IntBuffer pH = stack.mallocInt(1);
            IntBuffer pC = stack.mallocInt(1);
            pixels = STBImage.stbi_load_from_memory(encoded, pW, pH, pC, 4);
            if (pixels == null) {
                throw new RuntimeException("STB decode failed for " + resourcePath
                        + ": " + STBImage.stbi_failure_reason());
            }
            width = pW.get(0);
            height = pH.get(0);
        }
        try {
            return uploadRgba(device, physicalDevice, commandPool, queue, pixels, width, height);
        } finally {
            STBImage.stbi_image_free(pixels);
        }
    }

    /**
     * Load a cubemap from 6 face PNGs (in order: px, nx, py, ny, pz, nz —
     * same order as the OpenGL {@code Texture.loadCubemap}). Resolves each
     * face from the project's {@code src/main/resources/textures/} folder
     * via the filesystem (matches Phase-1 / Phase-2 path conventions for
     * dev runs). All faces must have the same dimensions and 4 channels.
     */
    static VkTexture loadCubemap(VkDevice device, VkPhysicalDevice physicalDevice,
                                  long commandPool, VkQueue queue, String[] faceFilenames) {
        if (faceFilenames.length != 6)
            throw new IllegalArgumentException("Cubemap needs 6 faces, got " + faceFilenames.length);

        // Decode all 6 faces first so we can validate sizes match.
        ByteBuffer[] facePixels = new ByteBuffer[6];
        int width = -1, height = -1;
        try {
            for (int i = 0; i < 6; i++) {
                String path = "src/main/resources/textures/" + faceFilenames[i];
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer pW = stack.mallocInt(1);
                    IntBuffer pH = stack.mallocInt(1);
                    IntBuffer pC = stack.mallocInt(1);
                    ByteBuffer pixels = STBImage.stbi_load(path, pW, pH, pC, 4);
                    if (pixels == null) {
                        throw new RuntimeException("Cubemap face " + faceFilenames[i] +
                                " failed: " + STBImage.stbi_failure_reason());
                    }
                    if (width < 0) {
                        width = pW.get(0);
                        height = pH.get(0);
                    } else if (pW.get(0) != width || pH.get(0) != height) {
                        STBImage.stbi_image_free(pixels);
                        throw new RuntimeException("Cubemap face " + faceFilenames[i]
                                + " size mismatch: expected " + width + "x" + height
                                + ", got " + pW.get(0) + "x" + pH.get(0));
                    }
                    facePixels[i] = pixels;
                }
            }
            return uploadCubemapFaces(device, physicalDevice, commandPool, queue,
                    facePixels, width, height);
        } finally {
            for (ByteBuffer b : facePixels) {
                if (b != null) STBImage.stbi_image_free(b);
            }
        }
    }

    private static VkTexture uploadCubemapFaces(VkDevice device, VkPhysicalDevice physicalDevice,
                                                 long commandPool, VkQueue queue,
                                                 ByteBuffer[] faces, int width, int height) {
        long faceBytes = (long) width * height * 4;
        long totalBytes = faceBytes * 6;

        // Single staging buffer holding all 6 faces back-to-back.
        long[] staging = VkUtil.createBuffer(device, physicalDevice,
                totalBytes,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        long stagingBuf = staging[0], stagingMem = staging[1];
        VkUtil.uploadToBuffer(device, stagingMem, totalBytes, dst -> {
            for (ByteBuffer face : faces) {
                dst.put(face.duplicate());   // duplicate so face position isn't advanced (see Phase 6 fix)
            }
        });

        // VkImage with arrayLayers=6 and CUBE_COMPATIBLE flag.
        long image, imageMemory;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .flags(VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(6)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imgInfo.extent().set(width, height, 1);

            LongBuffer pImg = stack.mallocLong(1);
            VkUtil.check(vkCreateImage(device, imgInfo, null, pImg), "vkCreateImage(cubemap)");
            image = pImg.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, req);

            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(VkUtil.findMemoryType(physicalDevice,
                            req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));
            LongBuffer pMem = stack.mallocLong(1);
            VkUtil.check(vkAllocateMemory(device, alloc, null, pMem), "vkAllocateMemory(cubemap)");
            imageMemory = pMem.get(0);
            VkUtil.check(vkBindImageMemory(device, image, imageMemory, 0), "vkBindImageMemory(cubemap)");
        }

        // Transition → copy → transition. The transitions cover ALL 6 array
        // layers (layerCount=6) in one barrier, and the copy lists 6 regions.
        final long finalImage = image;
        final int finalWidth = width, finalHeight = height;
        final long finalFaceBytes = faceBytes;
        VkUtil.singleTimeCommands(device, commandPool, queue, cb -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // UNDEFINED → TRANSFER_DST for all 6 layers.
                VkImageMemoryBarrier.Buffer b1 = VkImageMemoryBarrier.calloc(1, stack);
                b1.get(0)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(finalImage)
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                b1.get(0).subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(6);
                vkCmdPipelineBarrier(cb,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, null, null, b1);

                // Copy 6 regions in one command.
                VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(6, stack);
                for (int i = 0; i < 6; i++) {
                    regions.get(i)
                            .bufferOffset(i * finalFaceBytes)
                            .bufferRowLength(0)
                            .bufferImageHeight(0);
                    regions.get(i).imageSubresource()
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0)
                            .baseArrayLayer(i).layerCount(1);
                    regions.get(i).imageOffset().set(0, 0, 0);
                    regions.get(i).imageExtent().set(finalWidth, finalHeight, 1);
                }
                vkCmdCopyBufferToImage(cb, stagingBuf, finalImage,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);

                // TRANSFER_DST → SHADER_READ_ONLY for all 6 layers.
                VkImageMemoryBarrier.Buffer b2 = VkImageMemoryBarrier.calloc(1, stack);
                b2.get(0)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(finalImage)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                b2.get(0).subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(6);
                vkCmdPipelineBarrier(cb,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        0, null, null, b2);
            }
        });

        vkDestroyBuffer(device, stagingBuf, null);
        vkFreeMemory(device, stagingMem, null);

        // Cube view + sampler. Sampler uses CLAMP_TO_EDGE on all axes —
        // standard for skyboxes (otherwise seams appear at face borders).
        long view, samplerHandle;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo vInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_CUBE)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            vInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(6);
            LongBuffer pView = stack.mallocLong(1);
            VkUtil.check(vkCreateImageView(device, vInfo, null, pView), "vkCreateImageView(cubemap)");
            view = pView.get(0);

            VkSamplerCreateInfo sInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .anisotropyEnable(false)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS);
            LongBuffer pSampler = stack.mallocLong(1);
            VkUtil.check(vkCreateSampler(device, sInfo, null, pSampler), "vkCreateSampler(cubemap)");
            samplerHandle = pSampler.get(0);
        }

        Logger.info("[VK] Cubemap uploaded (6×" + width + "x" + height + ")");
        return new VkTexture(image, imageMemory, view, samplerHandle);
    }

    private static VkTexture uploadRgba(VkDevice device, VkPhysicalDevice physicalDevice,
                                         long commandPool, VkQueue queue,
                                         ByteBuffer pixels, int width, int height) {
        long imageSize = (long) width * height * 4;
        // Full mip chain — floor(log2(max(w,h))) + 1. Without this every
        // minified sample collapses to a single texel and you get classic
        // moire / "morphing-checker" aliasing at distance (the bug the user
        // saw on the stone walls).
        final int mipLevels = (int) (Math.floor(Math.log(Math.max(width, height)) / Math.log(2))) + 1;

        // 1. Staging buffer with the decoded RGBA bytes.
        long[] staging = VkUtil.createBuffer(device, physicalDevice,
                imageSize,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        long stagingBuf = staging[0], stagingMem = staging[1];
        VkUtil.uploadToBuffer(device, stagingMem, imageSize, dst -> {
            // Copy from a DUPLICATE of pixels so we don't advance the
            // original's position. LWJGL's stbi_image_free uses
            // memAddress(buffer) which returns base+position — if we put
            // pixels directly, position ends up at imageSize and the free
            // would call C free() on (base+imageSize), corrupting the heap.
            // (Spent half an hour learning this one with a 0xC0000374
            // process tear-down to show for it.)
            dst.put(pixels.duplicate());
        });

        // 2. Device-local VkImage. TRANSFER_SRC is required because mip
        //    generation blits FROM level N to level N+1 inside the same image.
        long image, imageMemory;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(mipLevels)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imgInfo.extent().set(width, height, 1);

            LongBuffer pImg = stack.mallocLong(1);
            VkUtil.check(vkCreateImage(device, imgInfo, null, pImg), "vkCreateImage(texture)");
            image = pImg.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, req);

            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(VkUtil.findMemoryType(physicalDevice,
                            req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));
            LongBuffer pMem = stack.mallocLong(1);
            VkUtil.check(vkAllocateMemory(device, alloc, null, pMem), "vkAllocateMemory(texture)");
            imageMemory = pMem.get(0);
            VkUtil.check(vkBindImageMemory(device, image, imageMemory, 0), "vkBindImageMemory(texture)");
        }

        // 3. Upload mip 0, then generate mips 1..N-1 via vkCmdBlitImage
        //    (linear filter — the GPU's box-filter equivalent of glGenerateMipmap).
        //    CRITICAL: ALL mipLevels start as UNDEFINED after vkCreateImage.
        //    Transitioning only level 0 leaves levels 1..N-1 in UNDEFINED,
        //    and blitting TO an UNDEFINED layout is UB (NVIDIA writes
        //    garbage into the upper mips → "morphing checker at distance"
        //    bug). Transition the WHOLE chain to TRANSFER_DST first.
        final long finalImage = image;
        VkUtil.singleTimeCommands(device, commandPool, queue, cb -> {
            transitionMipRange(cb, finalImage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, mipLevels, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            copyBufferToImage(cb, stagingBuf, finalImage, width, height);
            generateMipmaps(cb, finalImage, width, height, mipLevels);
        });

        // 4. Staging is no longer needed.
        vkDestroyBuffer(device, stagingBuf, null);
        vkFreeMemory(device, stagingMem, null);

        // 5. View + sampler. View exposes ALL mip levels; sampler picks
        //    between them via LINEAR mipmap mode (trilinear filtering).
        long view, samplerHandle;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo vInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            vInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(mipLevels)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            VkUtil.check(vkCreateImageView(device, vInfo, null, pView), "vkCreateImageView(texture)");
            view = pView.get(0);

            // Query device's max anisotropy (NVIDIA / AMD desktop: 16.0,
            // Intel iGPU usually 16.0, MoltenVK: typically 16.0).
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            float maxAniso = props.limits().maxSamplerAnisotropy();

            VkSamplerCreateInfo sInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    // LINEAR mipmap mode + maxLod = mipLevels turns on
                    // trilinear filtering. This is what kills the
                    // distance-aliasing/moire bug.
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .minLod(0f)
                    .maxLod((float) mipLevels)
                    .mipLodBias(0f)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    // Anisotropic filtering kills the "stretched/smeared
                    // strip" look on thin faces at glancing angles. The
                    // device feature was enabled in VkContext.createLogicalDevice.
                    .anisotropyEnable(true)
                    .maxAnisotropy(maxAniso)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false)
                    .compareEnable(false)
                    .compareOp(VK_COMPARE_OP_ALWAYS);
            LongBuffer pSampler = stack.mallocLong(1);
            VkUtil.check(vkCreateSampler(device, sInfo, null, pSampler), "vkCreateSampler");
            samplerHandle = pSampler.get(0);
        }

        Logger.info("[VK] Texture uploaded (" + width + "x" + height + ", " + mipLevels + " mips)");
        return new VkTexture(image, imageMemory, view, samplerHandle);
    }

    /** Generate mip chain in-place by blitting level i → level i+1 with
     *  a linear filter. Each level transitions through:
     *    TRANSFER_DST → TRANSFER_SRC (so it can be the blit source for i+1)
     *    → SHADER_READ_ONLY (final layout, sampleable).
     *  The last level (which is only ever a blit dest) goes
     *    TRANSFER_DST → SHADER_READ_ONLY directly. */
    private static void generateMipmaps(VkCommandBuffer cb, long image,
                                         int baseWidth, int baseHeight, int mipLevels) {
        int mipWidth = baseWidth, mipHeight = baseHeight;
        for (int i = 1; i < mipLevels; i++) {
            // Source level (i-1) was just written: TRANSFER_DST → TRANSFER_SRC.
            transitionMipRange(cb, image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    i - 1, 1, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

            int nextW = Math.max(mipWidth / 2, 1);
            int nextH = Math.max(mipHeight / 2, 1);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.get(0).srcSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i - 1)
                        .baseArrayLayer(0).layerCount(1);
                blit.get(0).srcOffsets(0).set(0, 0, 0);
                blit.get(0).srcOffsets(1).set(mipWidth, mipHeight, 1);
                blit.get(0).dstSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(i)
                        .baseArrayLayer(0).layerCount(1);
                blit.get(0).dstOffsets(0).set(0, 0, 0);
                blit.get(0).dstOffsets(1).set(nextW, nextH, 1);
                vkCmdBlitImage(cb,
                        image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        blit, VK_FILTER_LINEAR);
            }

            // Done reading level i-1 → flip it to SHADER_READ_ONLY.
            transitionMipRange(cb, image,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    i - 1, 1, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

            mipWidth = nextW;
            mipHeight = nextH;
        }

        // Last level was only ever a blit destination — still TRANSFER_DST.
        transitionMipRange(cb, image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                mipLevels - 1, 1, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    }

    /** Barrier for a sub-range of mip levels. Used by the mipmap pipeline
     *  where individual levels move between TRANSFER_DST / TRANSFER_SRC /
     *  SHADER_READ_ONLY at different times. */
    private static void transitionMipRange(VkCommandBuffer cb, long image,
                                            int oldLayout, int newLayout,
                                            int baseMip, int levelCount,
                                            int srcAccess, int dstAccess,
                                            int srcStage, int dstStage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
            b.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);
            b.get(0).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(baseMip).levelCount(levelCount)
                    .baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, b);
        }
    }

    private static void transitionLayout(VkCommandBuffer cb, long image,
                                          int oldLayout, int newLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int srcStage, dstStage, srcAccess, dstAccess;
            if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                    && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                srcAccess = 0;
                dstAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
                srcStage  = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                dstStage  = VK_PIPELINE_STAGE_TRANSFER_BIT;
            } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                    && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                srcAccess = VK_ACCESS_TRANSFER_WRITE_BIT;
                dstAccess = VK_ACCESS_SHADER_READ_BIT;
                srcStage  = VK_PIPELINE_STAGE_TRANSFER_BIT;
                dstStage  = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            } else {
                throw new IllegalArgumentException("Unhandled layout transition: "
                        + oldLayout + " → " + newLayout);
            }

            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
            b.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);
            b.get(0).subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, b);
        }
    }

    private static void copyBufferToImage(VkCommandBuffer cb, long buffer, long image,
                                           int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                    .bufferOffset(0)
                    .bufferRowLength(0)        // tightly packed
                    .bufferImageHeight(0);
            region.get(0).imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0).layerCount(1);
            region.get(0).imageOffset().set(0, 0, 0);
            region.get(0).imageExtent().set(width, height, 1);
            vkCmdCopyBufferToImage(cb, buffer, image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
    }

    private VkTexture(long image, long memory, long view, long sampler) {
        this.image = image;
        this.imageMemory = memory;
        this.imageView = view;
        this.sampler = sampler;
    }

    long imageView() { return imageView; }
    long sampler()   { return sampler; }

    void destroy(VkDevice device) {
        vkDestroySampler(device, sampler, null);
        vkDestroyImageView(device, imageView, null);
        vkDestroyImage(device, image, null);
        vkFreeMemory(device, imageMemory, null);
    }
}
