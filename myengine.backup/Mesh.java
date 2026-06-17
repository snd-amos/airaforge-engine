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

class Mesh {
    private int vaoId;
    private int vboId;
    private int textCoordsVboId;
    private int normalsVboId;
    private int boneIdsVboId = 0;   // 0 = not skinned
    private int weightsVboId = 0;
    private int idxVboId;
    private int vertexCount;

    public float minX = -0.5f, minY = -0.5f, minZ = -0.5f;
    public float maxX = 0.5f, maxY = 0.5f, maxZ = 0.5f;

    public boolean isSkinned = false;
    public Skeleton skeleton = null;  // null for static meshes

    public Mesh(float[] positions, float[] textCoords, float[] normals, int[] indices) {
        vertexCount = indices.length;

        vaoId = glGenVertexArrays();
        glBindVertexArray(vaoId);

        vboId = glGenBuffers();
        FloatBuffer posBuffer = MemoryUtil.memAllocFloat(positions.length);
        posBuffer.put(positions).flip();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, posBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);

        textCoordsVboId = glGenBuffers();
        FloatBuffer textCoordsBuffer = MemoryUtil.memAllocFloat(textCoords.length);
        textCoordsBuffer.put(textCoords).flip();
        glBindBuffer(GL_ARRAY_BUFFER, textCoordsVboId);
        glBufferData(GL_ARRAY_BUFFER, textCoordsBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(1);

        normalsVboId = glGenBuffers();
        FloatBuffer normalsBuffer = MemoryUtil.memAllocFloat(normals.length);
        normalsBuffer.put(normals).flip();
        glBindBuffer(GL_ARRAY_BUFFER, normalsVboId);
        glBufferData(GL_ARRAY_BUFFER, normalsBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(2);

        idxVboId = glGenBuffers();
        IntBuffer indicesBuffer = MemoryUtil.memAllocInt(indices.length);
        indicesBuffer.put(indices).flip();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, idxVboId);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indicesBuffer, GL_STATIC_DRAW);

        glBindVertexArray(0);

        MemoryUtil.memFree(posBuffer);
        MemoryUtil.memFree(textCoordsBuffer);
        MemoryUtil.memFree(normalsBuffer);
        MemoryUtil.memFree(indicesBuffer);
    }

    public void addBoneData(int[] boneIds, float[] weights) {
        glBindVertexArray(vaoId);

        boneIdsVboId = glGenBuffers();
        IntBuffer idsBuffer = MemoryUtil.memAllocInt(boneIds.length);
        idsBuffer.put(boneIds).flip();
        glBindBuffer(GL_ARRAY_BUFFER, boneIdsVboId);
        glBufferData(GL_ARRAY_BUFFER, idsBuffer, GL_STATIC_DRAW);
        glVertexAttribIPointer(3, 4, GL_INT, 0, 0);
        glEnableVertexAttribArray(3);

        weightsVboId = glGenBuffers();
        FloatBuffer wBuffer = MemoryUtil.memAllocFloat(weights.length);
        wBuffer.put(weights).flip();
        glBindBuffer(GL_ARRAY_BUFFER, weightsVboId);
        glBufferData(GL_ARRAY_BUFFER, wBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(4, 4, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(4);

        glBindVertexArray(0);
        MemoryUtil.memFree(idsBuffer);
        MemoryUtil.memFree(wBuffer);

        isSkinned = true;
    }

    public void render() {
        glBindVertexArray(vaoId);
        glDrawElements(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    public void destroy() {
        Logger.debug("Destroying Mesh - freeing VAO and VBOs");
        glDeleteBuffers(vboId);
        glDeleteBuffers(textCoordsVboId);
        glDeleteBuffers(normalsVboId);
        if (boneIdsVboId != 0) glDeleteBuffers(boneIdsVboId);
        if (weightsVboId != 0) glDeleteBuffers(weightsVboId);
        glDeleteBuffers(idxVboId);
        glDeleteVertexArrays(vaoId);
    }
}