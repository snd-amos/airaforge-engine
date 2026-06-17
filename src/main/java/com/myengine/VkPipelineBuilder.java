package com.myengine;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static com.myengine.VkUtil.check;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Fluent builder for {@link VkPipeline}. Sets the fixed-function bits that
 * every pipeline in this engine shares — dynamic viewport+scissor, single
 * sample, no culling, triangle list, polygon-fill — so each pipeline call
 * site only has to spell out what's actually different about it
 * (shaders, vertex layout, push constants, depth state, blend, render pass).
 *
 * Typical use:
 * <pre>
 *   Built built = new VkPipelineBuilder(device, "model")
 *           .shaders(VERT_SRC, FRAG_SRC)
 *           .vertexLayout(8 * 4,
 *                   attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
 *                   attr(1, VK_FORMAT_R32G32B32_SFLOAT, 12),
 *                   attr(2, VK_FORMAT_R32G32_SFLOAT,    24))
 *           .descriptorSetLayout(setLayout)
 *           .pushConstants(208, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
 *           .depth(true, true, VK_COMPARE_OP_LESS)
 *           .colorBlendOpaque()
 *           .renderPass(rp, 0)
 *           .build();
 * </pre>
 */
final class VkPipelineBuilder {

    record VertexAttr(int location, int format, int offset) {}
    record Built(long layout, long pipeline) {}

    static VertexAttr attr(int location, int format, int offset) {
        return new VertexAttr(location, format, offset);
    }

    private final VkDevice device;
    private final String   label;

    private String vertSrc, fragSrc;
    private int    vertexStride = -1;
    private VertexAttr[] vertexAttrs;
    // Optional second binding for per-instance attributes (CubeBatch).
    // When set, the build() emits TWO binding descriptions: binding 0 =
    // per-vertex (rate VERTEX), binding 1 = per-instance (rate INSTANCE).
    private int    instanceStride = -1;
    private VertexAttr[] instanceAttrs;
    // When true the second binding is INSTANCE-rate (cube batch); when
    // false it's VERTEX-rate (skinned mesh's per-vertex bone IDs+weights).
    private boolean secondBindingIsInstance = true;
    private long   descriptorSetLayout = VK_NULL_HANDLE;
    private long[] descriptorSetLayouts = null;
    private int    pushConstantSize    = 0;
    private int    pushConstantStages  = 0;
    private boolean depthTest = true, depthWrite = true;
    private int    depthCompareOp = VK_COMPARE_OP_LESS;
    private boolean depthBiasEnable = false;
    private float  depthBiasConstant = 0f, depthBiasSlope = 0f;
    private boolean colorBlendEnabled = true;
    private boolean alphaBlend = false;
    private int     topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    // Stencil — see stencilMark / stencilTestNotEqual setters.
    private boolean stencilEnable    = false;
    private int     stencilCompareOp = VK_COMPARE_OP_ALWAYS;
    private int     stencilPassOp    = VK_STENCIL_OP_KEEP;
    private int     stencilFailOp    = VK_STENCIL_OP_KEEP;
    private int     stencilWriteMask = 0;
    private int     stencilCompareMask = 0xFF;
    private int     stencilRef       = 0;
    private boolean colorWriteMask   = true;
    private long   renderPass = VK_NULL_HANDLE;
    private int    subpass = 0;

    VkPipelineBuilder(VkDevice device, String label) {
        this.device = device;
        this.label  = label;
    }

    VkPipelineBuilder shaders(String vertGlsl, String fragGlsl) {
        this.vertSrc = vertGlsl; this.fragSrc = fragGlsl; return this;
    }
    VkPipelineBuilder vertexLayout(int stride, VertexAttr... attrs) {
        this.vertexStride = stride; this.vertexAttrs = attrs; return this;
    }
    /** Optional per-instance vertex layout. Attribute locations should
     *  not collide with {@link #vertexLayout}'s. Produces a binding
     *  with {@code inputRate = INSTANCE} so the data advances once per
     *  instance instead of once per vertex. */
    VkPipelineBuilder instanceLayout(int stride, VertexAttr... attrs) {
        this.instanceStride = stride; this.instanceAttrs = attrs;
        this.secondBindingIsInstance = true;
        return this;
    }
    /** Second VERTEX-rate binding — used by the skinned pipeline where
     *  binding 1 holds per-vertex bone IDs (ivec4) + weights (vec4). */
    VkPipelineBuilder vertexLayoutSecondary(int stride, VertexAttr... attrs) {
        this.instanceStride = stride; this.instanceAttrs = attrs;
        this.secondBindingIsInstance = false;
        return this;
    }
    VkPipelineBuilder descriptorSetLayout(long layout) {
        this.descriptorSetLayout = layout;
        this.descriptorSetLayouts = null;
        return this;
    }
    /** Multi-set variant — used by the skinned pipelines where set 0 is
     *  material (diffuse + shadow map) and set 1 is the bone-matrix UBO. */
    VkPipelineBuilder descriptorSetLayouts(long... layouts) {
        this.descriptorSetLayouts = layouts;
        this.descriptorSetLayout = VK_NULL_HANDLE;
        return this;
    }
    VkPipelineBuilder pushConstants(int sizeBytes, int stages) {
        this.pushConstantSize = sizeBytes; this.pushConstantStages = stages; return this;
    }
    VkPipelineBuilder depth(boolean test, boolean write, int compareOp) {
        this.depthTest = test; this.depthWrite = write; this.depthCompareOp = compareOp; return this;
    }
    /** Vulkan depth-bias args correspond to GL's glPolygonOffset(factor, units)
     *  in this order: depthBiasSlope = GL factor, depthBiasConstant = GL units. */
    VkPipelineBuilder depthBias(float constantFactor, float slopeFactor) {
        this.depthBiasEnable = true;
        this.depthBiasConstant = constantFactor; this.depthBiasSlope = slopeFactor; return this;
    }
    /** Opaque (no alpha blend) but writes RGBA. Default. */
    VkPipelineBuilder colorBlendOpaque() { this.colorBlendEnabled = true; this.alphaBlend = false; return this; }
    /** Standard srcAlpha / 1-srcAlpha alpha blending — used for UI / ImGui. */
    VkPipelineBuilder colorBlendAlpha()  { this.colorBlendEnabled = true; this.alphaBlend = true;  return this; }
    /** Use for depth-only passes that have no color attachment in their render pass. */
    VkPipelineBuilder noColorBlend() { this.colorBlendEnabled = false; return this; }
    VkPipelineBuilder renderPass(long rp, int subpass) {
        this.renderPass = rp; this.subpass = subpass; return this;
    }
    /** Primitive topology. Defaults to {@code TRIANGLE_LIST}; pass
     *  {@code VK_PRIMITIVE_TOPOLOGY_LINE_LIST} for the line renderer. */
    VkPipelineBuilder topology(int t) { this.topology = t; return this; }
    /** Stencil mark pass — writes {@code refValue} to stencil for every
     *  pixel passed by the depth test (or always, if depth is off).
     *  Used by the silhouette outline's first pass to fill stencil=1
     *  inside the selected mesh's footprint. */
    VkPipelineBuilder stencilMark(int refValue) {
        this.stencilEnable = true;
        this.stencilCompareOp = VK_COMPARE_OP_ALWAYS;
        this.stencilPassOp = VK_STENCIL_OP_REPLACE;
        this.stencilFailOp = VK_STENCIL_OP_KEEP;
        this.stencilWriteMask = 0xFF;
        this.stencilCompareMask = 0xFF;
        this.stencilRef = refValue;
        return this;
    }
    /** Stencil outline pass — passes ONLY where stencil != refValue
     *  (i.e., outside the previously-marked silhouette). Read-only. */
    VkPipelineBuilder stencilTestNotEqual(int refValue) {
        this.stencilEnable = true;
        this.stencilCompareOp = VK_COMPARE_OP_NOT_EQUAL;
        this.stencilPassOp = VK_STENCIL_OP_KEEP;
        this.stencilFailOp = VK_STENCIL_OP_KEEP;
        this.stencilWriteMask = 0x00;
        this.stencilCompareMask = 0xFF;
        this.stencilRef = refValue;
        return this;
    }
    /** Disable colour writes (RGBA mask = 0). Used by the silhouette
     *  mark pass — we only want stencil to be written, not the colour. */
    VkPipelineBuilder noColorWrite() { this.colorWriteMask = false; return this; }

    Built build() {
        if (vertSrc == null || fragSrc == null)
            throw new IllegalStateException(label + ": missing shaders");
        if (vertexStride < 0 || vertexAttrs == null)
            throw new IllegalStateException(label + ": missing vertex layout");
        if (renderPass == VK_NULL_HANDLE)
            throw new IllegalStateException(label + ": missing renderPass");

        long vertModule = compile(vertSrc, shaderc_glsl_vertex_shader, label + ".vert");
        long fragModule = compile(fragSrc, shaderc_glsl_fragment_shader, label + ".frag");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            // Vertex input — one binding (vertex) or two (vertex + instance).
            boolean hasInstance = instanceAttrs != null && instanceAttrs.length > 0;
            int bindingCount = hasInstance ? 2 : 1;
            VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(bindingCount, stack);
            bindings.get(0).binding(0).stride(vertexStride).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            if (hasInstance) {
                bindings.get(1).binding(1).stride(instanceStride)
                        .inputRate(secondBindingIsInstance
                                ? VK_VERTEX_INPUT_RATE_INSTANCE
                                : VK_VERTEX_INPUT_RATE_VERTEX);
            }
            int totalAttrs = vertexAttrs.length + (hasInstance ? instanceAttrs.length : 0);
            VkVertexInputAttributeDescription.Buffer attrs =
                    VkVertexInputAttributeDescription.calloc(totalAttrs, stack);
            for (int i = 0; i < vertexAttrs.length; i++) {
                attrs.get(i).binding(0)
                        .location(vertexAttrs[i].location())
                        .format(vertexAttrs[i].format())
                        .offset(vertexAttrs[i].offset());
            }
            if (hasInstance) {
                int base = vertexAttrs.length;
                for (int i = 0; i < instanceAttrs.length; i++) {
                    attrs.get(base + i).binding(1)
                            .location(instanceAttrs[i].location())
                            .format(instanceAttrs[i].format())
                            .offset(instanceAttrs[i].offset());
                }
            }
            VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(bindings)
                    .pVertexAttributeDescriptions(attrs);

            VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(topology);

            VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .lineWidth(1.0f)
                    .cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_CLOCKWISE)
                    .depthBiasEnable(depthBiasEnable)
                    .depthBiasConstantFactor(depthBiasConstant)
                    .depthBiasSlopeFactor(depthBiasSlope);

            VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(depthTest).depthWriteEnable(depthWrite)
                    .depthCompareOp(depthCompareOp)
                    .stencilTestEnable(stencilEnable);
            if (stencilEnable) {
                // Same state on both faces — we don't cull, so no need to
                // differentiate front vs back stencil ops.
                depth.front()
                        .compareOp(stencilCompareOp)
                        .passOp(stencilPassOp)
                        .failOp(stencilFailOp)
                        .depthFailOp(stencilPassOp)
                        .compareMask(stencilCompareMask)
                        .writeMask(stencilWriteMask)
                        .reference(stencilRef);
                depth.back()
                        .compareOp(stencilCompareOp)
                        .passOp(stencilPassOp)
                        .failOp(stencilFailOp)
                        .depthFailOp(stencilPassOp)
                        .compareMask(stencilCompareMask)
                        .writeMask(stencilWriteMask)
                        .reference(stencilRef);
            }

            // Color blend is OPTIONAL — depth-only passes (shadow) have no color attachments.
            VkPipelineColorBlendStateCreateInfo blend = null;
            if (colorBlendEnabled) {
                VkPipelineColorBlendAttachmentState.Buffer blendAtt =
                        VkPipelineColorBlendAttachmentState.calloc(1, stack);
                int mask = colorWriteMask
                        ? (VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                        : 0;
                blendAtt.get(0)
                        .colorWriteMask(mask)
                        .blendEnable(alphaBlend);
                if (alphaBlend) {
                    blendAtt.get(0)
                            .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                            .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                            .colorBlendOp(VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                            .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                            .alphaBlendOp(VK_BLEND_OP_ADD);
                }
                blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                        .pAttachments(blendAtt);
            }

            VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            // Pipeline layout.
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            if (descriptorSetLayouts != null && descriptorSetLayouts.length > 0) {
                LongBuffer pSetLayouts = stack.mallocLong(descriptorSetLayouts.length);
                for (long l : descriptorSetLayouts) pSetLayouts.put(l);
                pSetLayouts.flip();
                layoutInfo.pSetLayouts(pSetLayouts);
            } else if (descriptorSetLayout != VK_NULL_HANDLE) {
                layoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));
            }
            if (pushConstantSize > 0) {
                VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
                pcr.get(0).stageFlags(pushConstantStages).offset(0).size(pushConstantSize);
                layoutInfo.pPushConstantRanges(pcr);
            }
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout),
                    "vkCreatePipelineLayout(" + label + ")");
            long pipelineLayout = pLayout.get(0);

            VkGraphicsPipelineCreateInfo.Buffer pipeInfo =
                    VkGraphicsPipelineCreateInfo.calloc(1, stack);
            pipeInfo.get(0)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vi)
                    .pInputAssemblyState(ia)
                    .pViewportState(vp)
                    .pRasterizationState(rs)
                    .pMultisampleState(ms)
                    .pDepthStencilState(depth)
                    .pColorBlendState(blend)
                    .pDynamicState(dyn)
                    .layout(pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(subpass);

            LongBuffer pPipe = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipeInfo, null, pPipe),
                    "vkCreateGraphicsPipelines(" + label + ")");
            long pipeline = pPipe.get(0);

            vkDestroyShaderModule(device, vertModule, null);
            vkDestroyShaderModule(device, fragModule, null);
            Logger.info("[VK] Pipeline '" + label + "' built");
            return new Built(pipelineLayout, pipeline);
        }
    }

    /** GLSL → SPIR-V via shaderc, then wrapped in a VkShaderModule. */
    private long compile(String glsl, int shadercStage, String nameForLog) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == NULL) throw new RuntimeException("shaderc_compiler_initialize failed");
        try {
            long result = shaderc_compile_into_spv(compiler, glsl, shadercStage,
                    nameForLog, "main", NULL);
            if (result == NULL) throw new RuntimeException("shaderc_compile_into_spv returned null");
            try {
                int status = shaderc_result_get_compilation_status(result);
                if (status != shaderc_compilation_status_success) {
                    String err = shaderc_result_get_error_message(result);
                    throw new RuntimeException("Shader compile failed (" + nameForLog + "): " + err);
                }
                ByteBuffer spirv = shaderc_result_get_bytes(result);
                if (spirv == null) throw new RuntimeException("shaderc_result_get_bytes returned null");
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                            .pCode(spirv);
                    LongBuffer pModule = stack.mallocLong(1);
                    check(vkCreateShaderModule(device, info, null, pModule),
                            "vkCreateShaderModule(" + nameForLog + ")");
                    return pModule.get(0);
                }
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compiler_release(compiler);
        }
    }
}
