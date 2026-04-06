package com.myengine;

import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Model loading utility using Assimp.
 * Handles .glb, .gltf, and other 3D model formats.
 */
public class ModelLoader {

    /**
     * Load a 3D model from a file.
     * @param fileName Model file name (relative to models folder)
     * @return Mesh object, or null if loading failed
     */
    public static Mesh loadModel(String fileName) {
        String path = Config.MODELS_PATH + fileName;

        int flags = Assimp.aiProcess_Triangulate | Assimp.aiProcess_JoinIdenticalVertices | Assimp.aiProcess_GenSmoothNormals;
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

        // Track bounding box
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        AIVector3D.Buffer vertices = aiMesh.mVertices();
        float[] positions = new float[vertexCount * 3];
        for (int i = 0; i < vertexCount; i++) {
            AIVector3D vertex = vertices.get(i);
            float vx = vertex.x();
            float vy = vertex.y();
            float vz = vertex.z();

            // Update bounds
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

        // Load texture coordinates
        float[] textCoords = new float[vertexCount * 2];
        AIVector3D.Buffer aiTextCoords = aiMesh.mTextureCoords(0);
        if (aiTextCoords != null) {
            for (int i = 0; i < vertexCount; i++) {
                AIVector3D coord = aiTextCoords.get(i);
                textCoords[i * 2] = coord.x();
                textCoords[i * 2 + 1] = 1.0f - coord.y();
            }
        }

        // Load normals
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

        // Load indices
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

        Assimp.aiReleaseImport(scene);

        // Create mesh and set bounds
        Mesh mesh = new Mesh(positions, textCoords, normals, indices);
        mesh.minX = minX;
        mesh.minY = minY;
        mesh.minZ = minZ;
        mesh.maxX = maxX;
        mesh.maxY = maxY;
        mesh.maxZ = maxZ;

        Logger.debug("Loaded model: " + fileName);
        return mesh;
    }
}