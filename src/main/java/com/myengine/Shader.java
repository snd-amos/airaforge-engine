package com.myengine;

import static org.lwjgl.opengl.GL20.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * GLSL Shader program wrapper.
 * Handles compilation, linking, and uniform management.
 */
public class Shader {
    private int programID;
    private Map<String, Integer> uniformLocations = new HashMap<>();

    /**
     * Create and compile a shader program from vertex and fragment shader files.
     */
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
        glLinkProgram(programID);

        if (glGetProgrami(programID, GL_LINK_STATUS) == 0) {
            Logger.error("Shader linking failed: " + glGetProgramInfoLog(programID, 1024));
        }

        glDeleteShader(vertexID);
        glDeleteShader(fragmentID);
    }

    /**
     * Compile a single shader from source code.
     */
    private int compileShader(String code, int type) {
        int id = glCreateShader(type);
        glShaderSource(id, code);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == 0) {
            Logger.error("Shader compilation failed: " + glGetShaderInfoLog(id, 1024));
        }
        return id;
    }

    /**
     * Use this shader program for rendering.
     */
    public void use() {
        glUseProgram(programID);
    }

    /**
     * Stop using this shader program.
     */
    public void detach() {
        glUseProgram(0);
    }

    /**
     * Set a uniform float value.
     */
    public void setFloat(String name, float value) {
        glUniform1f(getUniformLocation(name), value);
    }

    /**
     * Set a uniform vec3 value.
     */
    public void setVec3(String name, float x, float y, float z) {
        glUniform3f(getUniformLocation(name), x, y, z);
    }

    /**
     * Get or cache the uniform location.
     */
    private int getUniformLocation(String name) {
        if (uniformLocations.containsKey(name)) {
            return uniformLocations.get(name);
        }
        int location = glGetUniformLocation(programID, name);
        uniformLocations.put(name, location);
        return location;
    }

    /**
     * Destroy this shader program and free GPU memory.
     */
    public void destroy() {
        Logger.debug("Destroying Shader program");
        glDeleteProgram(programID);
    }
}