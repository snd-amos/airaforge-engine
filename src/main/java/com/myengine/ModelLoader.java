package com.myengine;

import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.IntBuffer;
import java.util.*;

class ModelLoader {

    public static Mesh loadModel(String fileName) {
        String path;
        if (fileName.startsWith(Config.CHARACTER_PATH_PREFIX)) {
            path = Config.CHARACTERS_PATH + fileName.substring(Config.CHARACTER_PATH_PREFIX.length());
        } else {
            path = Config.MODELS_PATH + fileName;
        }

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

        // Append any additional submeshes (multi-material exports become separate
        // AIMesh entries in assimp). We only do this for static models — a
        // second skinned submesh would need its bone indices remapped.
        int numMeshes = meshesBuffer.capacity();
        if (numMeshes > 1 && boneCount == 0) {
            int totalExtraVerts = 0;
            int totalExtraIndices = 0;
            for (int m = 1; m < numMeshes; m++) {
                AIMesh extra = AIMesh.create(meshesBuffer.get(m));
                totalExtraVerts += extra.mNumVertices();
                AIFace.Buffer ef = extra.mFaces();
                for (int i = 0; i < extra.mNumFaces(); i++) totalExtraIndices += ef.get(i).mNumIndices();
            }

            float[] mergedPos = new float[positions.length + totalExtraVerts * 3];
            float[] mergedNorm = new float[normals.length + totalExtraVerts * 3];
            float[] mergedTex = new float[textCoords.length + totalExtraVerts * 2];
            int[] mergedInd = new int[indices.length + totalExtraIndices];
            System.arraycopy(positions, 0, mergedPos, 0, positions.length);
            System.arraycopy(normals, 0, mergedNorm, 0, normals.length);
            System.arraycopy(textCoords, 0, mergedTex, 0, textCoords.length);
            System.arraycopy(indices, 0, mergedInd, 0, indices.length);

            int posOffset = positions.length;
            int normOffset = normals.length;
            int texOffset = textCoords.length;
            int indOffset = indices.length;
            int vertOffset = vertexCount;

            for (int m = 1; m < numMeshes; m++) {
                AIMesh extra = AIMesh.create(meshesBuffer.get(m));
                int evc = extra.mNumVertices();
                AIVector3D.Buffer ev = extra.mVertices();
                for (int i = 0; i < evc; i++) {
                    AIVector3D v = ev.get(i);
                    float vx = v.x(), vy = v.y(), vz = v.z();
                    if (vx < minX) minX = vx; if (vx > maxX) maxX = vx;
                    if (vy < minY) minY = vy; if (vy > maxY) maxY = vy;
                    if (vz < minZ) minZ = vz; if (vz > maxZ) maxZ = vz;
                    mergedPos[posOffset + i * 3]     = vx;
                    mergedPos[posOffset + i * 3 + 1] = vy;
                    mergedPos[posOffset + i * 3 + 2] = vz;
                }
                AIVector3D.Buffer en = extra.mNormals();
                if (en != null) {
                    for (int i = 0; i < evc; i++) {
                        AIVector3D n = en.get(i);
                        mergedNorm[normOffset + i * 3]     = n.x();
                        mergedNorm[normOffset + i * 3 + 1] = n.y();
                        mergedNorm[normOffset + i * 3 + 2] = n.z();
                    }
                }
                AIVector3D.Buffer et = extra.mTextureCoords(0);
                if (et != null) {
                    for (int i = 0; i < evc; i++) {
                        AIVector3D c = et.get(i);
                        mergedTex[texOffset + i * 2]     = c.x();
                        mergedTex[texOffset + i * 2 + 1] = 1.0f - c.y();
                    }
                }
                AIFace.Buffer ef = extra.mFaces();
                int efc = extra.mNumFaces();
                int written = 0;
                for (int i = 0; i < efc; i++) {
                    AIFace face = ef.get(i);
                    IntBuffer fIdx = face.mIndices();
                    while (fIdx.hasRemaining()) {
                        mergedInd[indOffset + written++] = fIdx.get() + vertOffset;
                    }
                }

                posOffset += evc * 3;
                normOffset += evc * 3;
                texOffset += evc * 2;
                indOffset += written;
                vertOffset += evc;
            }

            positions = mergedPos;
            normals = mergedNorm;
            textCoords = mergedTex;
            indices = mergedInd;
            Logger.debug("Merged " + numMeshes + " submeshes for: " + fileName);
        }

        Assimp.aiReleaseImport(scene);

        // Pad degenerate axes so paper-thin meshes (alpha-cutout leaf quads, etc.)
        // produce a non-zero AABB for SelectionBoxRenderer + worldBounds().
        final float EPSILON = 0.01f;
        final float PAD = 0.05f;
        if (maxX - minX < EPSILON) { minX -= PAD; maxX += PAD; }
        if (maxY - minY < EPSILON) { minY -= PAD; maxY += PAD; }
        if (maxZ - minZ < EPSILON) { minZ -= PAD; maxZ += PAD; }

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
        Logger.info(String.format("Rig: %s — %dv/%df, %d bones, %d anims",
                fileName,
                aiMesh.mNumVertices(),
                aiMesh.mNumFaces(),
                aiMesh.mNumBones(),
                scene.mNumAnimations()));
    }
}
