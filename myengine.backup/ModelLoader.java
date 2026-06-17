package com.myengine;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT32;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

class ModelLoader {

    public static Mesh loadModel(String fileName) {
        String path = Config.MODELS_PATH + fileName;

        int flags = Assimp.aiProcess_Triangulate | Assimp.aiProcess_JoinIdenticalVertices
                | Assimp.aiProcess_GenSmoothNormals | Assimp.aiProcess_LimitBoneWeights;
        AIScene scene = Assimp.aiImportFile(path, flags);

        if (scene == null) {
            Logger.error("Error loading model: " + path + " - " + Assimp.aiGetErrorString());
            return null;
        }

        PointerBuffer meshesBuffer = scene.mMeshes();
        if (meshesBuffer == null || meshesBuffer.capacity() == 0) {
            Logger.warn("Model has no meshes: " + fileName);
            Assimp.aiReleaseImport(scene);
            return null;
        }

        AIMesh aiMesh = AIMesh.create(meshesBuffer.get(0));
        int vertexCount = aiMesh.mNumVertices();

        printRigInfo(fileName, scene, aiMesh);

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        AIVector3D.Buffer vertices = aiMesh.mVertices();
        float[] positions = new float[vertexCount * 3];
        for (int i = 0; i < vertexCount; i++) {
            AIVector3D vertex = vertices.get(i);
            float vx = vertex.x();
            float vy = vertex.y();
            float vz = vertex.z();

            if (vx < minX) minX = vx;
            if (vy < minY) minY = vy;
            if (vz < minZ) minZ = vz;
            if (vx > maxX) maxX = vx;
            if (vy > maxY) maxY = vy;
            if (vz > maxZ) maxZ = vz;

            positions[i * 3] = vx;
            positions[i * 3 + 1] = vy;
            positions[i * 3 + 2] = vz;
        }

        float[] textCoords = new float[vertexCount * 2];
        AIVector3D.Buffer aiTextCoords = aiMesh.mTextureCoords(0);
        if (aiTextCoords != null) {
            for (int i = 0; i < vertexCount; i++) {
                AIVector3D coord = aiTextCoords.get(i);
                textCoords[i * 2] = coord.x();
                textCoords[i * 2 + 1] = 1.0f - coord.y();
            }
        }

        float[] normals = new float[vertexCount * 3];
        AIVector3D.Buffer aiNormals = aiMesh.mNormals();
        if (aiNormals != null) {
            for (int i = 0; i < vertexCount; i++) {
                AIVector3D normal = aiNormals.get(i);
                normals[i * 3] = normal.x();
                normals[i * 3 + 1] = normal.y();
                normals[i * 3 + 2] = normal.z();
            }
        }

        int faceCount = aiMesh.mNumFaces();
        AIFace.Buffer faces = aiMesh.mFaces();
        List<Integer> indicesList = new ArrayList<>();
        for (int i = 0; i < faceCount; i++) {
            AIFace face = faces.get(i);
            IntBuffer indices = face.mIndices();
            while (indices.hasRemaining()) {
                indicesList.add(indices.get());
            }
        }

        int[] indices = new int[indicesList.size()];
        for (int i = 0; i < indicesList.size(); i++) {
            indices[i] = indicesList.get(i);
        }

        // embedded texture
        if (scene.mNumTextures() > 0) {
            AITexture aiTex = AITexture.create(scene.mTextures().get(0));
            if (aiTex.mHeight() == 0 && aiTex.mWidth() > 0) { // compressed format (PNG/JPEG)
                int byteCount = aiTex.mWidth();
                byte[] bytes = new byte[byteCount];
                MemoryUtil.memByteBuffer(aiTex.pcData().address(), byteCount).get(bytes);
                try {
                    java.awt.image.BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                    if (img != null) {
                        int texId = Texture.uploadImage(img);
                        if (texId > 0) {
                            AssetPool.cacheEmbeddedTexture(fileName, texId);
                            Logger.debug("Extracted embedded texture from: " + fileName);
                        }
                    }
                } catch (Exception e) {
                    Logger.warn("Could not decode embedded texture in: " + fileName);
                }
            }
        }

        // per-vertex bone IDs + weights (max 4 influences per vertex, enforced by aiProcess_LimitBoneWeights)
        int boneCount = aiMesh.mNumBones();
        int[] boneIds = null;
        float[] vertWeights = null;
        Skeleton skeleton = null;
        if (boneCount > 0) {
            boneIds = new int[vertexCount * 4];
            vertWeights = new float[vertexCount * 4];
            java.util.Arrays.fill(boneIds, -1);

            skeleton = new Skeleton();
            skeleton.boneCount = boneCount;

            PointerBuffer bonesBuf = aiMesh.mBones();
            for (int boneIdx = 0; boneIdx < boneCount; boneIdx++) {
                AIBone bone = AIBone.create(bonesBuf.get(boneIdx));
                String boneName = bone.mName().dataString();
                Matrix4f offset = assimpToJoml(bone.mOffsetMatrix());
                skeleton.boneInfoMap.put(boneName, new BoneInfo(boneIdx, offset));

                AIVertexWeight.Buffer wBuf = bone.mWeights();
                int numWeights = bone.mNumWeights();
                for (int w = 0; w < numWeights; w++) {
                    AIVertexWeight vw = wBuf.get(w);
                    int vId = vw.mVertexId();
                    float weight = vw.mWeight();
                    for (int slot = 0; slot < 4; slot++) {
                        int idx = vId * 4 + slot;
                        if (boneIds[idx] == -1) {
                            boneIds[idx] = boneIdx;
                            vertWeights[idx] = weight;
                            break;
                        }
                    }
                }
            }

            AINode aiRoot = scene.mRootNode();
            if (aiRoot != null) {
                skeleton.rootNode = copyNodeTree(aiRoot);
                skeleton.globalInverseTransform = assimpToJoml(aiRoot.mTransformation()).invert();
            }
        }

        Assimp.aiReleaseImport(scene);

        Mesh mesh = new Mesh(positions, textCoords, normals, indices);
        mesh.minX = minX;
        mesh.minY = minY;
        mesh.minZ = minZ;
        mesh.maxX = maxX;
        mesh.maxY = maxY;
        mesh.maxZ = maxZ;

        if (boneIds != null) {
            mesh.addBoneData(boneIds, vertWeights);
            mesh.skeleton = skeleton;
            Logger.debug("Skinned mesh: " + fileName + " (" + boneCount + " bones)");
        }

        Logger.debug("Loaded model: " + fileName);
        return mesh;
    }

    private static Matrix4f assimpToJoml(AIMatrix4x4 m) {
        // assimp is row-major, JOML constructor takes column major (column by column)
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4()
        );
    }

    static NodeData copyNodeTree(AINode aiNode) {
        NodeData node = new NodeData(aiNode.mName().dataString(), assimpToJoml(aiNode.mTransformation()));
        int childCount = aiNode.mNumChildren();
        if (childCount > 0) {
            PointerBuffer children = aiNode.mChildren();
            for (int i = 0; i < childCount; i++) {
                node.children.add(copyNodeTree(AINode.create(children.get(i))));
            }
        }
        return node;
    }

    private static void printRigInfo(String fileName, AIScene scene, AIMesh aiMesh) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Rig inspection: ").append(fileName).append(" ===\n");
        sb.append("Vertices: ").append(aiMesh.mNumVertices())
                .append(", Faces: ").append(aiMesh.mNumFaces()).append("\n");

        int boneCount = aiMesh.mNumBones();
        sb.append("Bones in primary mesh: ").append(boneCount).append("\n");
        if (boneCount > 0) {
            PointerBuffer bones = aiMesh.mBones();
            sb.append("Bone names: ");
            for (int i = 0; i < boneCount; i++) {
                AIBone b = AIBone.create(bones.get(i));
                sb.append(b.mName().dataString());
                if (i < boneCount - 1) sb.append(", ");
            }
            sb.append("\n");
        }

        int animCount = scene.mNumAnimations();
        sb.append("Animations: ").append(animCount).append("\n");
        if (animCount > 0) {
            PointerBuffer anims = scene.mAnimations();
            for (int i = 0; i < animCount; i++) {
                AIAnimation a = AIAnimation.create(anims.get(i));
                sb.append("  [").append(i).append("] ").append(a.mName().dataString())
                        .append(" - duration=").append(a.mDuration())
                        .append(" ticks, tps=").append(a.mTicksPerSecond())
                        .append(", channels=").append(a.mNumChannels()).append("\n");
            }
        }

        sb.append("Node hierarchy:\n");
        AINode root = scene.mRootNode();
        if (root != null) {
            appendNode(sb, root, 0);
        }
        sb.append("=== End rig inspection ===");

        Logger.info(sb.toString());
    }

    private static void appendNode(StringBuilder sb, AINode node, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append("- ").append(node.mName().dataString()).append("\n");

        int childCount = node.mNumChildren();
        if (childCount > 0) {
            PointerBuffer children = node.mChildren();
            for (int i = 0; i < childCount; i++) {
                appendNode(sb, AINode.create(children.get(i)), depth + 1);
            }
        }
    }
}