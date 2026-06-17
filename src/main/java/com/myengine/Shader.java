package com.myengine;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

class Shader {
    private int programID;
    private Map<String, Integer> uniformLocations = new HashMap<>();

    public Shader(String vertexPath, String fragmentPath) {
        String vShaderCode = "";
        String fShaderCode = "";
        try {
            vShaderCode = new String(Files.readAllBytes(Paths.get(Config.SHADERS_PATH + vertexPath)));
            fShaderCode = new String(Files.readAllBytes(Paths.get(Config.SHADERS_PATH + fragmentPath)));
        } catch (Exception e) {
            Logger.error("Failed to read shader files: " + vertexPath + ", " + fragmentPath, e);
        }

        int vertexID = compileShader(vShaderCode, GL_VERTEX_SHADER);
        int fragmentID = compileShader(fShaderCode, GL_FRAGMENT_SHADER);

        programID = glCreateProgram();
        glAttachShader(programID, vertexID);
        glAttachShader(programID, fragmentID);

        glBindAttribLocation(programID, 0, "aPos");
        glBindAttribLocation(programID, 1, "aTexCoords");
        glBindAttribLocation(programID, 2, "aNormal");
        glBindAttribLocation(programID, 3, "aBoneIds");
        glBindAttribLocation(programID, 4, "aWeights");
        glBindAttribLocation(programID, 5, "aSmoothNormal");
        // Per-instance attributes for CubeBatch / instanced rendering. Ignored
        // by shaders that don't declare them. 6-9 are the 4 columns of the
        // per-instance model matrix; 10-15 are the 6 per-face RGB colours in
        // the order +X, -X, +Y, -Y, +Z, -Z. (Six attribute slots replaced
        // what used to be a single `instTint` so cubes can have a different
        // colour on each face. For a uniform-tint cube all 6 are set equal.)
        glBindAttribLocation(programID, 6, "instModel0");
        glBindAttribLocation(programID, 7, "instModel1");
        glBindAttribLocation(programID, 8, "instModel2");
        glBindAttribLocation(programID, 9, "instModel3");
        glBindAttribLocation(programID, 10, "instFacePosX");
        glBindAttribLocation(programID, 11, "instFaceNegX");
        glBindAttribLocation(programID, 12, "instFacePosY");
        glBindAttribLocation(programID, 13, "instFaceNegY");
        glBindAttribLocation(programID, 14, "instFacePosZ");
        glBindAttribLocation(programID, 15, "instFaceNegZ");

        glLinkProgram(programID);

        if (glGetProgrami(programID, GL_LINK_STATUS) == 0) {
            Logger.error("Shader linking failed: " + glGetProgramInfoLog(programID, 1024));
        }

        glDeleteShader(vertexID);
        glDeleteShader(fragmentID);
    }

    private int compileShader(String code, int type) {
        int id = glCreateShader(type);
        glShaderSource(id, code);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == 0) {
            Logger.error("Shader compilation failed: " + glGetShaderInfoLog(id, 1024));
        }
        return id;
    }

    public void use() {
        glUseProgram(programID);
    }

    public void detach() {
        glUseProgram(0);
    }

    public void setInt(String name, int value) {
        glUniform1i(getUniformLocation(name), value);
    }

    public void setFloat(String name, float value) {
        glUniform1f(getUniformLocation(name), value);
    }

    public void setVec3(String name, float x, float y, float z) {
        glUniform3f(getUniformLocation(name), x, y, z);
    }

    public void setVec4(String name, float x, float y, float z, float w) {
        glUniform4f(getUniformLocation(name), x, y, z, w);
    }

    public void setMat4(String name, FloatBuffer matrix) {
        matrix.clear();
        glUniformMatrix4fv(getUniformLocation(name), false, matrix);
    }

    public void setMat4Array(String name, FloatBuffer matrices) {
        matrices.clear();
        glUniformMatrix4fv(getUniformLocation(name), false, matrices);
    }

    private int getUniformLocation(String name) {
        if (uniformLocations.containsKey(name)) {
            return uniformLocations.get(name);
        }
        int location = glGetUniformLocation(programID, name);
        uniformLocations.put(name, location);
        return location;
    }

    public void destroy() {
        Logger.debug("Destroying Shader program");
        glDeleteProgram(programID);
    }
}
