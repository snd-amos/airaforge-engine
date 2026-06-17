package com.myengine;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Stencil-silhouette outline — port of OpenGL
 * {@link SelectionBoxRenderer#drawSilhouetteMark} +
 * {@link SelectionBoxRenderer#drawMeshOutline}.
 *
 * <p>Two passes on the selected entity:
 * <ol>
 *   <li><b>Mark</b> — render the mesh with stencil=1, depth+colour off.
 *       Fills the silhouette in the stencil buffer.</li>
 *   <li><b>Outline</b> — render the mesh again with the vertex
 *       shader pushing each vertex outward in NDC by a configurable
 *       thickness. Stencil test = NOT_EQUAL 1 so the inflated mesh
 *       only writes pixels OUTSIDE the silhouette — that's the rim.</li>
 * </ol>
 *
 * Same vertex layout as VkMesh (stride 8 floats: pos / normal / uv) so we
 * can reuse the entity's existing VkMesh. Both pipelines share the same
 * shader source — outlineThickness in push constants distinguishes them.
 */
final class VkOutlineRenderer {

    /** Push-constant block (88 B): mat4 model + mat4 viewProj + vec4
     *  (color rgb + outlineThickness in .w). */
    private static final int PC_FLOATS = 4 * 16 + 4;  // 68 floats, 272 B... wait
    // Actually mat4 = 16 floats; 2 mat4 = 32 floats; vec4 = 4 floats; total = 36 floats = 144 B.
    // 2 mat4 (32) + vec4 colorAndThickness (4) + vec4 flags (4). flags.x = isFoliage.
    private static final int OUTLINE_PC_FLOATS = 40;

    /** Outline vertex shader. Pushes each vertex along its OWN normal
     *  direction (projected into NDC) rather than the
     *  radial-from-projected-centre approach OpenGL's outline.vert uses.
     *  Radial-from-centre breaks for meshes whose origin isn't at the
     *  visual centre — table legs etc. get near-zero inflation.
     *  Normal-based inflation gives complete coverage as long as the
     *  mesh has reasonable per-vertex normals. */
    private static final String OUTLINE_VERT = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 viewProj;\n"
            + "    vec4 colorAndThickness;\n"
            + "    vec4 flags;\n"
            + "} pc;\n"
            + "layout(location = 0) in vec3 inPos;\n"
            + "layout(location = 1) in vec3 inNormal;\n"
            + "layout(location = 2) in vec2 inUV;\n"
            + "layout(location = 0) out vec2 vUV;\n"
            + "void main() {\n"
            + "    vUV = inUV;\n"
            + "    mat4 mvp = pc.viewProj * pc.model;\n"
            + "    gl_Position = mvp * vec4(inPos, 1.0);\n"
            + "    float thickness = pc.colorAndThickness.w;\n"
            + "    if (thickness > 0.0) {\n"
            + "        // Uniform screen-space silhouette inflation. Push each\n"
            + "        // vertex along its NORMAL projected into NDC:\n"
            + "        //  - normal matrix = inverse-transpose of the model 3x3, so\n"
            + "        //    heavy NON-UNIFORM scale (e.g. a cube scaled ~490x in\n"
            + "        //    one axis) doesn't skew the push toward the stretched\n"
            + "        //    axis. That skew was what made the radial-from-centre\n"
            + "        //    variant thick on the caps but paper-thin / gappy along\n"
            + "        //    the long edges (the rim ran tangent to the edge).\n"
            + "        //  - transform that world normal as a DIRECTION (w = 0): no\n"
            + "        //    subtraction of two large clip positions, so none of the\n"
            + "        //    catastrophic cancellation the local-epsilon push had.\n"
            + "        //  - offset in NDC (normalized) scaled by clip-w, so after\n"
            + "        //    the perspective divide the rim is a CONSTANT screen\n"
            + "        //    thickness at any distance or camera angle.\n"
            + "        // Vertices whose normal faces the camera project to ~0 here,\n"
            + "        // but they sit deep inside the stencil=1 silhouette and are\n"
            + "        // masked out, so their undefined direction never shows.\n"
            + "        mat3 nrmMat = transpose(inverse(mat3(pc.model)));\n"
            + "        vec4 clipN = pc.viewProj * vec4(nrmMat * inNormal, 0.0);\n"
            + "        float len = length(clipN.xy);\n"
            + "        if (len > 1e-6) {\n"
            + "            gl_Position.xy += (clipN.xy / len) * thickness * gl_Position.w;\n"
            + "        }\n"
            + "    }\n"
            + "}\n";

    /** Static outline fragment shader — solid colour, with foliage alpha
     *  discard (flags.x == 1) sampling the diffuse at set=0,binding=0.
     *  Matches OpenGL outline.frag: leaf-cutout silhouettes follow the leaf
     *  shape, not the rectangular leaf quad. */
    private static final String OUTLINE_FRAG = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 viewProj;\n"
            + "    vec4 colorAndThickness;\n"
            + "    vec4 flags;\n"
            + "} pc;\n"
            + "layout(set = 0, binding = 0) uniform sampler2D diffuse;\n"
            + "layout(location = 0) in vec2 vUV;\n"
            + "layout(location = 0) out vec4 outColor;\n"
            + "void main() {\n"
            + "    if (pc.flags.x > 0.5) {\n"
            + "        if (texture(diffuse, vUV).a < 0.1) discard;\n"
            + "    }\n"
            + "    outColor = vec4(pc.colorAndThickness.rgb, 1.0);\n"
            + "}\n";

    /** Skinned outline fragment shader — solid colour only. The skinned
     *  pipelines bind the bone UBO at set=0, so they can't also sample a
     *  texture there; characters are never foliage, so no alpha discard. */
    private static final String OUTLINE_FRAG_SOLID = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 viewProj;\n"
            + "    vec4 colorAndThickness;\n"
            + "    vec4 flags;\n"
            + "} pc;\n"
            + "layout(location = 0) in vec2 vUV;\n"
            + "layout(location = 0) out vec4 outColor;\n"
            + "void main() {\n"
            + "    outColor = vec4(pc.colorAndThickness.rgb, 1.0);\n"
            + "}\n";

    /** Skinned outline vertex shader (sub-phase G follow-up). Applies
     *  the bone-matrix skin transform BEFORE the normal-direction
     *  inflation so the silhouette follows the animation pose. */
    private static final String SKINNED_OUTLINE_VERT = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 viewProj;\n"
            + "    vec4 colorAndThickness;\n"
            + "    vec4 flags;\n"
            + "} pc;\n"
            + "layout(set = 0, binding = 0) uniform Bones {\n"
            + "    mat4 boneMatrices[100];\n"
            + "} bones;\n"
            + "layout(location = 0) in vec3  inPos;\n"
            + "layout(location = 1) in vec3  inNormal;\n"
            + "layout(location = 2) in vec2  inUV;\n"
            + "layout(location = 3) in ivec4 inBoneIds;\n"
            + "layout(location = 4) in vec4  inWeights;\n"
            + "layout(location = 0) out vec2 vUV;\n"
            + "void main() {\n"
            + "    vec4 totalPos = vec4(0.0);\n"
            + "    vec3 totalNrm = vec3(0.0);\n"
            + "    for (int i = 0; i < 4; ++i) {\n"
            + "        int bId = inBoneIds[i];\n"
            + "        if (bId < 0) continue;\n"
            + "        if (bId >= 100) { totalPos = vec4(inPos, 1.0); totalNrm = inNormal; break; }\n"
            + "        mat4 bm = bones.boneMatrices[bId];\n"
            + "        totalPos += (bm * vec4(inPos, 1.0)) * inWeights[i];\n"
            + "        totalNrm += (mat3(bm) * inNormal) * inWeights[i];\n"
            + "    }\n"
            + "    if (inWeights.x + inWeights.y + inWeights.z + inWeights.w < 0.0001) {\n"
            + "        totalPos = vec4(inPos, 1.0);\n"
            + "        totalNrm = inNormal;\n"
            + "    }\n"
            + "    vUV = inUV;\n"
            + "    mat4 mvp = pc.viewProj * pc.model;\n"
            + "    gl_Position = mvp * totalPos;\n"
            + "    float thickness = pc.colorAndThickness.w;\n"
            + "    if (thickness > 0.0) {\n"
            + "        // Uniform screen-space normal push (see OUTLINE_VERT note).\n"
            + "        // totalNrm is the skinned model-space normal; the model\n"
            + "        // normal matrix + viewProj direction transform give a\n"
            + "        // constant screen thickness without scale skew.\n"
            + "        mat3 nrmMat = transpose(inverse(mat3(pc.model)));\n"
            + "        vec4 clipN = pc.viewProj * vec4(nrmMat * totalNrm, 0.0);\n"
            + "        float len = length(clipN.xy);\n"
            + "        if (len > 1e-6) {\n"
            + "            gl_Position.xy += (clipN.xy / len) * thickness * gl_Position.w;\n"
            + "        }\n"
            + "    }\n"
            + "}\n";

    private final VkContext ctx;
    // Model descriptor-set layout (binding 0 = diffuse sampler) — the static
    // outline pipelines bind the entity's material set so the frag can
    // alpha-discard foliage. Same handle the model pipeline uses.
    private final long modelSetLayout;
    // Two pipelines sharing the same shaders, layout, and PC range —
    // they differ only in stencil state + colour-write mask.
    private long markPipelineLayout, markPipeline;
    private long outlinePipelineLayout, outlinePipeline;
    // Skinned variants — bone-aware silhouette for characters. Use the
    // skinning runtime's bone-set layout at set=0 (no material set for
    // outlines — they only need positions + bones).
    private long skinnedMarkPipelineLayout, skinnedMarkPipeline;
    private long skinnedOutlinePipelineLayout, skinnedOutlinePipeline;
    private VkSkinnedRuntime skinnedRuntime;   // set via setSkinnedRuntime()

    VkOutlineRenderer(VkContext ctx, long renderPass, long modelSetLayout) {
        this.ctx = ctx;
        this.modelSetLayout = modelSetLayout;
        createPipelines(renderPass);
    }

    /** Hook the skinned runtime in after both have been constructed
     *  (avoids a chicken-and-egg in VulkanMain's init order). Lazily
     *  builds the skinned pipelines on first call. */
    void setSkinnedRuntime(VkSkinnedRuntime runtime, long renderPass) {
        this.skinnedRuntime = runtime;
        createSkinnedPipelines(renderPass);
    }

    private void createPipelines(long renderPass) {
        // Mark pass — depth test ON (LESS_OR_EQUAL) so we only mark
        // stencil for fragments that are actually visible (in front of
        // any other geometry already in the depth buffer). Depth WRITE
        // is off so we don't disturb the scene's depth. Without the
        // depth test, parts of the mesh hidden behind walls would still
        // get marked, and the outline would then show through walls.
        VkPipelineBuilder.Built mark = new VkPipelineBuilder(ctx.device(), "outline-mark")
                .shaders(OUTLINE_VERT, OUTLINE_FRAG)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R32G32_SFLOAT,    6 * Float.BYTES))
                .descriptorSetLayouts(modelSetLayout)
                .pushConstants(OUTLINE_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                // Depth DISABLED — outline is visible through other geometry
                // (user's intended behaviour). The "back of the same model
                // shouldn't trace through" property comes from the stencil
                // mark filling the FULL silhouette (front + back project to
                // the same screen-space area, all marked stencil=1, so the
                // outline pass skips that whole region).
                .depth(false, false, VK_COMPARE_OP_ALWAYS)
                .stencilMark(1)
                .noColorWrite()
                .colorBlendOpaque()   // attachment present, just masked
                .renderPass(renderPass, 0)
                .build();
        markPipelineLayout = mark.layout();
        markPipeline       = mark.pipeline();

        // Outline pass — depth test ON for the same reason. The inflated
        // mesh's depth roughly matches the original mesh's silhouette
        // edge, so LESS_OR_EQUAL passes at the rim. Geometry IN FRONT of
        // the selected mesh now occludes the outline correctly.
        VkPipelineBuilder.Built outline = new VkPipelineBuilder(ctx.device(), "outline-draw")
                .shaders(OUTLINE_VERT, OUTLINE_FRAG)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R32G32_SFLOAT,    6 * Float.BYTES))
                .descriptorSetLayouts(modelSetLayout)
                .pushConstants(OUTLINE_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                // Depth DISABLED — outline is visible through other geometry
                // (user's intended behaviour). The "back of the same model
                // shouldn't trace through" property comes from the stencil
                // mark filling the FULL silhouette (front + back project to
                // the same screen-space area, all marked stencil=1, so the
                // outline pass skips that whole region).
                .depth(false, false, VK_COMPARE_OP_ALWAYS)
                .stencilTestNotEqual(1)
                .colorBlendOpaque()
                .renderPass(renderPass, 0)
                .build();
        outlinePipelineLayout = outline.layout();
        outlinePipeline       = outline.pipeline();
    }

    /** Build the two skinned-aware outline pipelines (mark + draw). Same
     *  stencil setup as the static path, just adds the per-vertex
     *  bone-IDs/weights binding and the bone-UBO descriptor set. */
    private void createSkinnedPipelines(long renderPass) {
        final int SK_STRIDE = 4 * Integer.BYTES + 4 * Float.BYTES;
        VkPipelineBuilder.Built mark = new VkPipelineBuilder(ctx.device(), "skinned-outline-mark")
                .shaders(SKINNED_OUTLINE_VERT, OUTLINE_FRAG_SOLID)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R32G32_SFLOAT,    6 * Float.BYTES))
                .vertexLayoutSecondary(SK_STRIDE,
                        VkPipelineBuilder.attr(3, VK_FORMAT_R32G32B32A32_SINT,    0),
                        VkPipelineBuilder.attr(4, VK_FORMAT_R32G32B32A32_SFLOAT, 16))
                .descriptorSetLayouts(skinnedRuntime.boneSetLayout())
                .pushConstants(OUTLINE_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(false, false, VK_COMPARE_OP_ALWAYS)
                .stencilMark(1)
                .noColorWrite()
                .colorBlendOpaque()
                .renderPass(renderPass, 0)
                .build();
        skinnedMarkPipelineLayout = mark.layout();
        skinnedMarkPipeline       = mark.pipeline();

        VkPipelineBuilder.Built outline = new VkPipelineBuilder(ctx.device(), "skinned-outline-draw")
                .shaders(SKINNED_OUTLINE_VERT, OUTLINE_FRAG_SOLID)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R32G32_SFLOAT,    6 * Float.BYTES))
                .vertexLayoutSecondary(SK_STRIDE,
                        VkPipelineBuilder.attr(3, VK_FORMAT_R32G32B32A32_SINT,    0),
                        VkPipelineBuilder.attr(4, VK_FORMAT_R32G32B32A32_SFLOAT, 16))
                .descriptorSetLayouts(skinnedRuntime.boneSetLayout())
                .pushConstants(OUTLINE_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(false, false, VK_COMPARE_OP_ALWAYS)
                .stencilTestNotEqual(1)
                .colorBlendOpaque()
                .renderPass(renderPass, 0)
                .build();
        skinnedOutlinePipelineLayout = outline.layout();
        skinnedOutlinePipeline       = outline.pipeline();
    }

    /** Two-pass silhouette outline for one entity. {@code thickness}
     *  in normalised clip-w units — 0.003 matches the OpenGL value
     *  (SelectionBoxRenderer.java:180). Dispatches to the skinned
     *  pipelines automatically when the mesh has bones and the runtime
     *  was provided via {@link #setSkinnedRuntime}. */
    void drawOutline(VkCommandBuffer cb, Matrix4f viewProj,
                     GameObject entity, VkMesh mesh,
                     float r, float g, float b, float thickness,
                     long textureSet, boolean isFoliage) {
        if (entity == null || mesh == null) return;

        Matrix4f model = new Matrix4f()
                .translate(entity.transform.x, entity.transform.y, entity.transform.z)
                .rotateX((float) Math.toRadians(entity.transform.rotX))
                .rotateY((float) Math.toRadians(entity.transform.rotY))
                .rotateZ((float) Math.toRadians(entity.transform.rotZ))
                .scale(entity.transform.scaleX, entity.transform.scaleY, entity.transform.scaleZ);

        boolean useSkinned = mesh.isSkinned() && skinnedRuntime != null && skinnedMarkPipeline != 0L;
        if (useSkinned) {
            // Resolve the per-Animator bone set (identity if none).
            Animator a = entity.getComponent(Animator.class);
            long boneSet = (a != null && a.finalBoneMatrices != null)
                    ? skinnedRuntime.updateAndGetSet(a)
                    : skinnedRuntime.updateAndGetSet(IDENTITY_ANIM);

            recordSkinnedPass(cb, skinnedMarkPipeline, skinnedMarkPipelineLayout,
                    mesh, model, viewProj, boneSet, r, g, b, 0f);
            recordSkinnedPass(cb, skinnedOutlinePipeline, skinnedOutlinePipelineLayout,
                    mesh, model, viewProj, boneSet, r, g, b, thickness);
        } else {
            float foliage = isFoliage ? 1.0f : 0.0f;
            // Pass A — mark stencil (alpha-discard so foliage stencil follows leaf shape)
            recordPass(cb, markPipeline, markPipelineLayout, mesh, model, viewProj, r, g, b, 0f, textureSet, foliage);
            // Pass B — outline (only where stencil != 1)
            recordPass(cb, outlinePipeline, outlinePipelineLayout, mesh, model, viewProj, r, g, b, thickness, textureSet, foliage);
        }
    }

    // Shared identity Animator used when a skinned mesh has no Animator
    // (newly-spawned Hazmat, etc.) — renders the outline in bind pose.
    private static final Animator IDENTITY_ANIM = makeIdentityAnimator();
    private static Animator makeIdentityAnimator() {
        Animator a = new Animator();
        a.start();   // init 100× Matrix4f identity
        return a;
    }

    private static void recordSkinnedPass(VkCommandBuffer cb, long pipeline, long layout,
                                           VkMesh mesh, Matrix4f model, Matrix4f viewProj,
                                           long boneSet,
                                           float r, float g, float b, float thickness) {
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    layout, 0, stack.longs(boneSet), null);
        }
        mesh.bindWithSkinning(cb);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pc = stack.malloc(OUTLINE_PC_FLOATS * Float.BYTES);
            FloatBuffer pcF = pc.asFloatBuffer();
            model.get(0, pcF);
            viewProj.get(16, pcF);
            pcF.put(32, r); pcF.put(33, g); pcF.put(34, b); pcF.put(35, thickness);
            pcF.put(36, 0f); pcF.put(37, 0f); pcF.put(38, 0f); pcF.put(39, 0f);
            vkCmdPushConstants(cb, layout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, pcF);
            vkCmdDrawIndexed(cb, mesh.indexCount(), 1, 0, 0, 0);
        }
    }

    private static void recordPass(VkCommandBuffer cb, long pipeline, long layout,
                                   VkMesh mesh, Matrix4f model, Matrix4f viewProj,
                                   float r, float g, float b, float thickness,
                                   long textureSet, float foliage) {
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        if (textureSet != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        layout, 0, stack.longs(textureSet), null);
            }
        }
        mesh.bind(cb);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pc = stack.malloc(OUTLINE_PC_FLOATS * Float.BYTES);
            FloatBuffer pcF = pc.asFloatBuffer();
            model.get(0, pcF);
            viewProj.get(16, pcF);
            pcF.put(32, r); pcF.put(33, g); pcF.put(34, b); pcF.put(35, thickness);
            pcF.put(36, foliage); pcF.put(37, 0f); pcF.put(38, 0f); pcF.put(39, 0f);
            vkCmdPushConstants(cb, layout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, pcF);
            vkCmdDrawIndexed(cb, mesh.indexCount(), 1, 0, 0, 0);
        }
    }

    void destroy() {
        if (skinnedOutlinePipeline       != 0) vkDestroyPipeline(ctx.device(), skinnedOutlinePipeline, null);
        if (skinnedOutlinePipelineLayout != 0) vkDestroyPipelineLayout(ctx.device(), skinnedOutlinePipelineLayout, null);
        if (skinnedMarkPipeline          != 0) vkDestroyPipeline(ctx.device(), skinnedMarkPipeline, null);
        if (skinnedMarkPipelineLayout    != 0) vkDestroyPipelineLayout(ctx.device(), skinnedMarkPipelineLayout, null);
        if (outlinePipeline       != 0) vkDestroyPipeline(ctx.device(), outlinePipeline, null);
        if (outlinePipelineLayout != 0) vkDestroyPipelineLayout(ctx.device(), outlinePipelineLayout, null);
        if (markPipeline          != 0) vkDestroyPipeline(ctx.device(), markPipeline, null);
        if (markPipelineLayout    != 0) vkDestroyPipelineLayout(ctx.device(), markPipelineLayout, null);
    }
}
