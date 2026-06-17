package com.myengine;

import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

import static com.myengine.VkUtil.check;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU-side state for skinned-character rendering.
 *
 * <p>For every {@link Animator} that's rendered, this class owns:
 * <ul>
 *   <li>One uniform buffer ({@code MAX_BONES * sizeof(mat4)} = 6400 B,
 *       HOST_VISIBLE so we can upload per frame),</li>
 *   <li>One descriptor set with that UBO bound at {@code (set=1, binding=0)},
 *       matching the skinned vertex shader's
 *       {@code layout(set=1, binding=0) uniform Bones { mat4 boneMatrices[100]; };}.</li>
 * </ul>
 *
 * <p>One shared descriptor-set layout + pool serves all Animators; per-frame
 * the renderer calls {@link #updateAndGetSet} to upload the current
 * {@code Animator.finalBoneMatrices} and get the set handle to bind before
 * the draw call.
 *
 * <p>Sub-phase G port. Mirrors the OpenGL {@code default.vert}'s
 * {@code uniform mat4 finalBonesMatrices[100]} array — same shader contract,
 * different transport (UBO vs uniform array).
 */
final class VkSkinnedRuntime {

    static final int MAX_BONES   = 100;                              // matches Animator + shader
    static final int UBO_BYTES   = MAX_BONES * 16 * Float.BYTES;     // 6400 B
    private static final int MAX_INSTANCES = 32;                     // ≤32 skinned characters on screen

    private final VkContext ctx;
    private final long boneSetLayout;
    private final long descriptorPool;
    // IdentityHashMap so two Animators with the same hash/equals still get
    // separate GPU resources. Animator doesn't override equals so the
    // default identity works, but IdentityHashMap makes the intent explicit.
    private final Map<Animator, Instance> instances = new IdentityHashMap<>();

    private static final class Instance {
        long uboBuffer;
        long uboMemory;
        long descriptorSet;
    }

    VkSkinnedRuntime(VkContext ctx) {
        this.ctx = ctx;
        this.boneSetLayout = createBoneSetLayout();
        this.descriptorPool = createPool();
    }

    /** Layout for the skinned pipeline's SET 1 — single UBO binding at
     *  binding 0, visible to vertex stage only (we sample no bones from
     *  the fragment). */
    long boneSetLayout() { return boneSetLayout; }

    /** Per-frame: ensure the Animator has GPU resources, upload its bone
     *  matrices, return the descriptor set to bind at set 1 before the
     *  skinned draw. */
    long updateAndGetSet(Animator a) {
        Instance inst = instances.computeIfAbsent(a, k -> createInstance());
        uploadMatrices(inst, a.finalBoneMatrices);
        return inst.descriptorSet;
    }

    private long createBoneSetLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // single binding 0 = uniform buffer with all 100 bone matrices
            org.lwjgl.vulkan.VkDescriptorSetLayoutBinding.Buffer bind =
                    org.lwjgl.vulkan.VkDescriptorSetLayoutBinding.calloc(1, stack);
            bind.get(0).binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo info =
                    org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bind);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(ctx.device(), info, null, p),
                    "vkCreateDescriptorSetLayout(skinning)");
            return p.get(0);
        }
    }

    private long createPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkDescriptorPoolSize.Buffer sizes =
                    org.lwjgl.vulkan.VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(MAX_INSTANCES);
            org.lwjgl.vulkan.VkDescriptorPoolCreateInfo info =
                    org.lwjgl.vulkan.VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(sizes)
                    .maxSets(MAX_INSTANCES);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorPool(ctx.device(), info, null, p),
                    "vkCreateDescriptorPool(skinning)");
            return p.get(0);
        }
    }

    private Instance createInstance() {
        Instance inst = new Instance();
        // 1. Create the UBO.
        long[] buf = VkUtil.createBuffer(ctx.device(), ctx.physicalDevice(),
                UBO_BYTES,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        inst.uboBuffer = buf[0];
        inst.uboMemory = buf[1];

        // 2. Allocate the descriptor set + wire the UBO into binding 0.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkDescriptorSetAllocateInfo alloc =
                    org.lwjgl.vulkan.VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(boneSetLayout));
            LongBuffer p = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(ctx.device(), alloc, p),
                    "vkAllocateDescriptorSets(skinning)");
            inst.descriptorSet = p.get(0);

            org.lwjgl.vulkan.VkDescriptorBufferInfo.Buffer bufInfo =
                    org.lwjgl.vulkan.VkDescriptorBufferInfo.calloc(1, stack);
            bufInfo.get(0).buffer(inst.uboBuffer).offset(0).range(UBO_BYTES);
            org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer w =
                    org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(1, stack);
            w.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(inst.descriptorSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1).pBufferInfo(bufInfo);
            vkUpdateDescriptorSets(ctx.device(), w, null);
        }
        return inst;
    }

    private void uploadMatrices(Instance inst, Matrix4f[] mats) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            check(vkMapMemory(ctx.device(), inst.uboMemory, 0, UBO_BYTES, 0, pData),
                    "vkMapMemory(skinning UBO)");
            ByteBuffer dst = pData.getByteBuffer(0, UBO_BYTES);
            // Pack column-major (JOML's default get(int, FloatBuffer) writes that way).
            java.nio.FloatBuffer fb = dst.asFloatBuffer();
            int slots = Math.min(MAX_BONES, mats.length);
            for (int i = 0; i < slots; i++) {
                mats[i].get(i * 16, fb);
            }
            // Identity-fill any unused slots so an over-indexed bone ID
            // (shouldn't happen, but…) sees identity instead of garbage.
            if (slots < MAX_BONES) {
                Matrix4f id = new Matrix4f();
                for (int i = slots; i < MAX_BONES; i++) id.get(i * 16, fb);
            }
            vkUnmapMemory(ctx.device(), inst.uboMemory);
        }
    }

    void destroy() {
        for (Instance inst : instances.values()) {
            vkDestroyBuffer(ctx.device(), inst.uboBuffer, null);
            vkFreeMemory(ctx.device(), inst.uboMemory, null);
            // sets are freed implicitly when the pool is destroyed
        }
        instances.clear();
        vkDestroyDescriptorPool(ctx.device(), descriptorPool, null);
        vkDestroyDescriptorSetLayout(ctx.device(), boneSetLayout, null);
    }
}
