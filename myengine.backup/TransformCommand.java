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

class TransformCommand extends Command {
    private GameObject gameObject;

    private float prevX, prevY, prevZ;
    private float prevRotX, prevRotY, prevRotZ;
    private float prevScaleX, prevScaleY, prevScaleZ;

    private float newX, newY, newZ;
    private float newRotX, newRotY, newRotZ;
    private float newScaleX, newScaleY, newScaleZ;

    public TransformCommand(GameObject gameObject,
                            float beforeX, float beforeY, float beforeZ,
                            float beforeRotX, float beforeRotY, float beforeRotZ,
                            float beforeScaleX, float beforeScaleY, float beforeScaleZ) {
        this.gameObject = gameObject;

        this.prevX = beforeX;
        this.prevY = beforeY;
        this.prevZ = beforeZ;
        this.prevRotX = beforeRotX;
        this.prevRotY = beforeRotY;
        this.prevRotZ = beforeRotZ;
        this.prevScaleX = beforeScaleX;
        this.prevScaleY = beforeScaleY;
        this.prevScaleZ = beforeScaleZ;

        captureAfterState();
    }

    public TransformCommand(GameObject gameObject) {
        this.gameObject = gameObject;
        captureBeforeState();
    }

    private void captureBeforeState() {
        if (gameObject == null || gameObject.transform == null) {
            return;
        }

        prevX = gameObject.transform.x;
        prevY = gameObject.transform.y;
        prevZ = gameObject.transform.z;
        prevRotX = gameObject.transform.rotX;
        prevRotY = gameObject.transform.rotY;
        prevRotZ = gameObject.transform.rotZ;
        prevScaleX = gameObject.transform.scaleX;
        prevScaleY = gameObject.transform.scaleY;
        prevScaleZ = gameObject.transform.scaleZ;
    }

    public void captureAfterState() {
        if (gameObject == null || gameObject.transform == null) {
            return;
        }

        newX = gameObject.transform.x;
        newY = gameObject.transform.y;
        newZ = gameObject.transform.z;
        newRotX = gameObject.transform.rotX;
        newRotY = gameObject.transform.rotY;
        newRotZ = gameObject.transform.rotZ;
        newScaleX = gameObject.transform.scaleX;
        newScaleY = gameObject.transform.scaleY;
        newScaleZ = gameObject.transform.scaleZ;
    }

    public boolean hasChanged() {
        return prevX != newX || prevY != newY || prevZ != newZ ||
                prevRotX != newRotX || prevRotY != newRotY || prevRotZ != newRotZ ||
                prevScaleX != newScaleX || prevScaleY != newScaleY || prevScaleZ != newScaleZ;
    }

    @Override
    public void execute() {
        if (gameObject == null || gameObject.transform == null) return;

        gameObject.transform.x = newX;
        gameObject.transform.y = newY;
        gameObject.transform.z = newZ;
        gameObject.transform.rotX = newRotX;
        gameObject.transform.rotY = newRotY;
        gameObject.transform.rotZ = newRotZ;
        gameObject.transform.scaleX = newScaleX;
        gameObject.transform.scaleY = newScaleY;
        gameObject.transform.scaleZ = newScaleZ;
    }

    @Override
    public void undo() {
        if (gameObject == null || gameObject.transform == null) return;

        gameObject.transform.x = prevX;
        gameObject.transform.y = prevY;
        gameObject.transform.z = prevZ;
        gameObject.transform.rotX = prevRotX;
        gameObject.transform.rotY = prevRotY;
        gameObject.transform.rotZ = prevRotZ;
        gameObject.transform.scaleX = prevScaleX;
        gameObject.transform.scaleY = prevScaleY;
        gameObject.transform.scaleZ = prevScaleZ;
    }

    @Override
    public String getDescription() {
        return "Transform " + (gameObject != null ? gameObject.name : "Unknown");
    }
}