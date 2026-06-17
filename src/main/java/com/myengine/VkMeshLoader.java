package com.myengine;

import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.assimp.Assimp.*;

/**
 * Loads a model file (.glb / .gltf / .obj / .fbx) from disk via Assimp and
 * returns both a merged {@link VkMesh} (all submeshes flattened into one
 * vertex/index buffer pair) and — when the source file embeds one — the
 * model's diffuse {@link VkTexture}.
 *
 * Vertex layout: 8 floats per vertex
 *   - position  (x, y, z)
 *   - normal    (nx, ny, nz)
 *   - texcoord  (u, v)
 *
 * Phase-7 changes from Phase 5/6:
 *   - Iterates every {@code aiMesh} in the scene and concatenates them
 *     into one combined vertex/index buffer with index offsets adjusted
 *     per submesh. Matches what the OpenGL {@link ModelLoader} does.
 *   - Pulls the first embedded texture out of the GLB (Assimp exposes it
 *     via {@code scene.mTextures()}) and decodes it with STB. This is
 *     why old_computer.glb, leaf_tree.glb, etc. now show their proper
 *     authored albedo instead of whatever happened to be in
 *     {@code oldcomputer.png}. The OpenGL path does the same trick.
 *
 * Still single-material (we don't yet handle per-submesh different
 * textures — that's Phase 8+). Good enough for the test models.
 */
final class VkMeshLoader {

    /** Holder for what {@link #load} produces. The texture is {@code null}
     *  when the file has no embedded image — caller decides whether to
     *  fall back to a sidecar PNG / use a magenta default / etc. */
    record LoadResult(VkMesh mesh, VkTexture embeddedTexture) {}

    private VkMeshLoader() {}

    static LoadResult load(VkDevice device, VkPhysicalDevice physicalDevice,
                            long commandPool, VkQueue queue, String path) {
        // aiProcess_LimitBoneWeights caps influences at 4 per vertex — required
        // by the (ivec4 boneIds, vec4 weights) shader contract used everywhere
        // in this engine (matches ModelLoader / AnimationLoader on the OpenGL side).
        int flags = aiProcess_Triangulate | aiProcess_GenSmoothNormals
                  | aiProcess_FlipUVs | aiProcess_LimitBoneWeights;

        AIScene scene = aiImportFile(path, flags);
        if (scene == null || scene.mNumMeshes() == 0) {
            String err = aiGetErrorString();
            throw new RuntimeException("Assimp failed to load '" + path + "': "
                    + (err != null ? err : "(no detail)"));
        }
        try {
            VkMesh mergedMesh = buildMergedMesh(scene, device, physicalDevice, path);
            VkTexture embeddedTexture = tryExtractEmbeddedTexture(scene,
                    device, physicalDevice, commandPool, queue, path);
            return new LoadResult(mergedMesh, embeddedTexture);
        } finally {
            aiReleaseImport(scene);
        }
    }

    /** Concatenate every {@code aiMesh} into one big interleaved vertex
     *  buffer + one big index buffer. Per-submesh indices are offset by
     *  the running vertex count so they keep pointing at the right verts
     *  in the merged buffer.
     *
     *  <p>If submesh 0 has bones, the model is treated as SKINNED and only
     *  submesh 0's vertices/indices/bones are used (other submeshes have
     *  separate bone indices that would need remapping — matches
     *  ModelLoader.java:175 behaviour). The static merge path runs only
     *  when no bones are present anywhere. */
    private static VkMesh buildMergedMesh(AIScene scene, VkDevice device,
                                           VkPhysicalDevice physicalDevice, String pathForLog) {
        int meshCount = scene.mNumMeshes();
        // Check if submesh 0 is skinned — that's our trigger to skip the
        // merge and walk the single-submesh skinning path.
        AIMesh first = AIMesh.create(scene.mMeshes().get(0));
        if (first.mNumBones() > 0) {
            return buildSkinnedMesh(scene, first, device, physicalDevice, pathForLog);
        }
        // First pass: tally totals so we can allocate exact-size arrays
        // (avoids List<Float> boxing + ArrayList grows).
        int totalVerts = 0;
        int totalIndices = 0;
        AIMesh[] meshes = new AIMesh[meshCount];
        for (int m = 0; m < meshCount; m++) {
            AIMesh ai = AIMesh.create(scene.mMeshes().get(m));
            meshes[m] = ai;
            totalVerts += ai.mNumVertices();
            AIFace.Buffer faces = ai.mFaces();
            for (int f = 0; f < ai.mNumFaces(); f++) {
                totalIndices += faces.get(f).mNumIndices();
            }
        }

        float[] vertices = new float[totalVerts * 8];
        int[]   indices  = new int[totalIndices];

        int vertOffset  = 0;      // running vertex base in the merged array
        int vWriteCursor = 0;     // current write position in vertices[]
        int iWriteCursor = 0;     // current write position in indices[]

        for (int m = 0; m < meshCount; m++) {
            AIMesh ai = meshes[m];
            int n = ai.mNumVertices();
            AIVector3D.Buffer aiPos     = ai.mVertices();
            AIVector3D.Buffer aiNormals = ai.mNormals();
            AIVector3D.Buffer aiUV0     = ai.mTextureCoords(0);
            if (aiNormals == null) {
                throw new RuntimeException("Submesh " + m + " of '" + pathForLog
                        + "' has no normals after GenSmoothNormals — should be impossible");
            }

            for (int i = 0; i < n; i++) {
                AIVector3D p = aiPos.get(i);
                AIVector3D nrm = aiNormals.get(i);
                vertices[vWriteCursor++] = p.x();
                vertices[vWriteCursor++] = p.y();
                vertices[vWriteCursor++] = p.z();
                vertices[vWriteCursor++] = nrm.x();
                vertices[vWriteCursor++] = nrm.y();
                vertices[vWriteCursor++] = nrm.z();
                if (aiUV0 != null) {
                    AIVector3D uv = aiUV0.get(i);
                    vertices[vWriteCursor++] = uv.x();
                    vertices[vWriteCursor++] = uv.y();
                } else {
                    vertices[vWriteCursor++] = 0f;
                    vertices[vWriteCursor++] = 0f;
                }
            }

            AIFace.Buffer faces = ai.mFaces();
            int faceCount = ai.mNumFaces();
            for (int f = 0; f < faceCount; f++) {
                IntBuffer fi = faces.get(f).mIndices();
                while (fi.hasRemaining()) {
                    indices[iWriteCursor++] = fi.get() + vertOffset;
                }
            }

            vertOffset += n;
        }

        Logger.info("[VK] Loaded model '" + pathForLog + "': "
                + meshCount + " submesh(es), "
                + totalVerts + " verts / " + (totalIndices / 3) + " tris");
        return new VkMesh(device, physicalDevice, vertices, indices);
    }

    /** Single-submesh skinned path. Extracts per-vertex bone IDs/weights +
     *  the {@link Skeleton} (BoneInfo map + node hierarchy + globalInverse).
     *  Verbatim port of {@link ModelLoader} lines 128-169 + 286-296. */
    private static VkMesh buildSkinnedMesh(AIScene scene, AIMesh ai,
                                           VkDevice device, VkPhysicalDevice physicalDevice,
                                           String pathForLog) {
        int n = ai.mNumVertices();
        AIVector3D.Buffer aiPos     = ai.mVertices();
        AIVector3D.Buffer aiNormals = ai.mNormals();
        AIVector3D.Buffer aiUV0     = ai.mTextureCoords(0);
        if (aiNormals == null) {
            throw new RuntimeException("Skinned submesh of '" + pathForLog
                    + "' has no normals after GenSmoothNormals — should be impossible");
        }

        // Static interleaved vertex layout (pos / normal / uv = 8 floats).
        float[] vertices = new float[n * 8];
        for (int i = 0; i < n; i++) {
            AIVector3D p = aiPos.get(i);
            AIVector3D nrm = aiNormals.get(i);
            int base = i * 8;
            vertices[base    ] = p.x();
            vertices[base + 1] = p.y();
            vertices[base + 2] = p.z();
            vertices[base + 3] = nrm.x();
            vertices[base + 4] = nrm.y();
            vertices[base + 5] = nrm.z();
            if (aiUV0 != null) {
                AIVector3D uv = aiUV0.get(i);
                vertices[base + 6] = uv.x();
                vertices[base + 7] = uv.y();
            }
        }

        AIFace.Buffer faces = ai.mFaces();
        int faceCount = ai.mNumFaces();
        int totalIndices = 0;
        for (int f = 0; f < faceCount; f++) totalIndices += faces.get(f).mNumIndices();
        int[] indices = new int[totalIndices];
        int w = 0;
        for (int f = 0; f < faceCount; f++) {
            IntBuffer fi = faces.get(f).mIndices();
            while (fi.hasRemaining()) indices[w++] = fi.get();
        }

        // Per-vertex bone IDs (init to -1 = empty slot) + weights.
        int boneCount = ai.mNumBones();
        int[]   boneIds     = new int[n * 4];
        float[] vertWeights = new float[n * 4];
        Arrays.fill(boneIds, -1);

        Skeleton skel = new Skeleton();
        skel.boneCount = boneCount;
        PointerBuffer bonesBuf = ai.mBones();
        for (int boneIdx = 0; boneIdx < boneCount; boneIdx++) {
            AIBone bone = AIBone.create(bonesBuf.get(boneIdx));
            String boneName = bone.mName().dataString();
            Matrix4f offset = assimpToJoml(bone.mOffsetMatrix());
            skel.boneInfoMap.put(boneName, new BoneInfo(boneIdx, offset));

            AIVertexWeight.Buffer wBuf = bone.mWeights();
            int numWeights = bone.mNumWeights();
            for (int k = 0; k < numWeights; k++) {
                AIVertexWeight vw = wBuf.get(k);
                int vId = vw.mVertexId();
                float weight = vw.mWeight();
                for (int slot = 0; slot < 4; slot++) {
                    int idx = vId * 4 + slot;
                    if (boneIds[idx] == -1) {
                        boneIds[idx]     = boneIdx;
                        vertWeights[idx] = weight;
                        break;
                    }
                }
            }
        }

        AINode aiRoot = scene.mRootNode();
        if (aiRoot != null) {
            skel.rootNode = copyNodeTree(aiRoot);
            skel.globalInverseTransform = assimpToJoml(aiRoot.mTransformation()).invert();
        }

        Logger.info("[VK] Loaded SKINNED model '" + pathForLog + "': "
                + n + " verts / " + (totalIndices / 3) + " tris, "
                + boneCount + " bones");

        VkMesh mesh = new VkMesh(device, physicalDevice, vertices, indices);
        mesh.addSkinning(device, physicalDevice, boneIds, vertWeights, skel);
        return mesh;
    }

    /** Assimp row-major → JOML column-major. Identical to
     *  {@link ModelLoader#assimpToJoml}. */
    private static Matrix4f assimpToJoml(AIMatrix4x4 m) {
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4()
        );
    }

    /** Recursively copy an Assimp node hierarchy into our renderer-agnostic
     *  {@link NodeData} tree. Used by both static-model rigs (skeleton root)
     *  and animation files. Identical to {@link ModelLoader#copyNodeTree}. */
    static NodeData copyNodeTree(AINode aiNode) {
        NodeData node = new NodeData(aiNode.mName().dataString(),
                assimpToJoml(aiNode.mTransformation()));
        int childCount = aiNode.mNumChildren();
        if (childCount > 0) {
            PointerBuffer children = aiNode.mChildren();
            for (int i = 0; i < childCount; i++) {
                node.children.add(copyNodeTree(AINode.create(children.get(i))));
            }
        }
        return node;
    }

    /** Look for a GLB/GLTF embedded texture. Assimp exposes them via
     *  {@code scene.mTextures()}. Returns null if the model has none —
     *  caller can fall back to a sidecar file. */
    private static VkTexture tryExtractEmbeddedTexture(AIScene scene,
                                                       VkDevice device, VkPhysicalDevice physicalDevice,
                                                       long commandPool, VkQueue queue,
                                                       String pathForLog) {
        if (scene.mNumTextures() == 0) return null;
        AITexture aiTex = AITexture.create(scene.mTextures().get(0));
        // mHeight == 0 means the texture is stored as a COMPRESSED image
        // (PNG / JPEG) in mWidth bytes. mHeight > 0 means raw RGBA pixels
        // (rare for GLB; common for some FBX exports). We handle compressed
        // here — STB decodes PNG/JPEG/etc. transparently.
        if (aiTex.mHeight() != 0 || aiTex.mWidth() <= 0) {
            Logger.warn("[VK] Embedded texture in '" + pathForLog
                    + "' is raw-pixel format (height != 0) — not yet supported, skipping");
            return null;
        }
        int byteCount = aiTex.mWidth();
        ByteBuffer encoded = MemoryUtil.memByteBuffer(aiTex.pcData().address(), byteCount);
        try {
            Logger.info("[VK] Extracting embedded texture from '" + pathForLog
                    + "' (" + byteCount + " encoded bytes)");
            return VkTexture.loadFromEncodedBytes(device, physicalDevice, commandPool, queue, encoded);
        } catch (Exception e) {
            Logger.error("[VK] Failed to decode embedded texture from '" + pathForLog + "'", e);
            return null;
        }
    }
}
