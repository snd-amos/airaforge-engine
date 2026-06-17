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