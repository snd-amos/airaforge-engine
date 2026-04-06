package com.myengine;

import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * GPU mesh data holder.
 * Manages VAO and VBO for efficient rendering with vertex data.
 */
public class Mesh {
    private int vaoId;
    private int vboId;
    private int textCoordsVboId;
    private int normalsVboId;
    private int idxVboId;
    private int vertexCount;

    public float minX = -0.5f, minY = -0.5f, minZ = -0.5f;
    public float maxX = 0.5f, maxY = 0.5f, maxZ = 0.5f;

    /**
     * Create a mesh from vertex data.
     */
    public Mesh(float[] positions, float[] textCoords, float[] normals, int[] indices) {
        vertexCount = indices.length;

        // 1. Generate the Master VAO
        vaoId = glGenVertexArrays();
        glBindVertexArray(vaoId);

        // 2. Positions VBO (Attribute 0)
        vboId = glGenBuffers();
        FloatBuffer posBuffer = MemoryUtil.memAllocFloat(positions.length);
        posBuffer.put(positions).flip();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, posBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);

        // 3. Texture Coords VBO (Attribute 1)
        textCoordsVboId = glGenBuffers();
        FloatBuffer textCoordsBuffer = MemoryUtil.memAllocFloat(textCoords.length);
        textCoordsBuffer.put(textCoords).flip();
        glBindBuffer(GL_ARRAY_BUFFER, textCoordsVboId);
        glBufferData(GL_ARRAY_BUFFER, textCoordsBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(1);

        // 4. Normals VBO (Attribute 2)
        normalsVboId = glGenBuffers();
        FloatBuffer normalsBuffer = MemoryUtil.memAllocFloat(normals.length);
        normalsBuffer.put(normals).flip();
        glBindBuffer(GL_ARRAY_BUFFER, normalsVboId);
        glBufferData(GL_ARRAY_BUFFER, normalsBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(2);

        // 5. Indices VBO
        idxVboId = glGenBuffers();
        IntBuffer indicesBuffer = MemoryUtil.memAllocInt(indices.length);
        indicesBuffer.put(indices).flip();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, idxVboId);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indicesBuffer, GL_STATIC_DRAW);

        // Unbind the VAO
        glBindVertexArray(0);

        MemoryUtil.memFree(posBuffer);
        MemoryUtil.memFree(textCoordsBuffer);
        MemoryUtil.memFree(normalsBuffer);
        MemoryUtil.memFree(indicesBuffer);
    }

    /**
     * Render this mesh using its VAO and indices.
     */
    public void render() {
        glBindVertexArray(vaoId);
        glDrawElements(GL_TRIANGLES, vertexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    /**
     * Destroy this mesh and free all GPU memory.
     * Call this when the mesh is no longer needed.
     */
    public void destroy() {
        Logger.debug("Destroying Mesh - freeing VAO and VBOs");
        glDeleteBuffers(vboId);
        glDeleteBuffers(textCoordsVboId);
        glDeleteBuffers(normalsVboId);
        glDeleteBuffers(idxVboId);
        glDeleteVertexArrays(vaoId);
    }
}