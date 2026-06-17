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

class SelectionBoxRenderer {

    public static void drawSelectionBox(GameObject object, boolean isSelected,
                                        int draggingAxis, boolean isHoveringX,
                                        boolean isHoveringY, boolean isHoveringZ,
                                        boolean isHoveringCenter) {
        MeshRenderer mr = object.getComponent(MeshRenderer.class);

        glPushAttrib(GL_ENABLE_BIT | GL_CURRENT_BIT | GL_LINE_BIT);
        glPushMatrix();

        glTranslatef(object.transform.x, object.transform.y, object.transform.z);

        if (isSelected && object.transformMode != 1) {
            Gizmo.render(object, false, draggingAxis, isHoveringX, isHoveringY,
                    isHoveringZ, isHoveringCenter);
        }

        if (object.transform != null) {
            glRotatef(object.transform.rotX, 1, 0, 0);
            glRotatef(object.transform.rotY, 0, 1, 0);
            glRotatef(object.transform.rotZ, 0, 0, 1);
        }

        // rotation gizmo bug fixed
        if (isSelected && object.transformMode == 1) {
            Gizmo.render(object, false, draggingAxis, isHoveringX, isHoveringY,
                    isHoveringZ, isHoveringCenter);
        }

        if (object.transform != null) {
            glScalef(object.transform.scaleX, object.transform.scaleY, object.transform.scaleZ);
        }

        glDisable(GL_LIGHTING);
        glDisable(GL_TEXTURE_2D);
        glLineWidth(isSelected ? 3.0f : 2.0f);
        glColor3f(isSelected ? 1.0f : 0.0f, isSelected ? 0.5f : 0.0f, 0.0f);

        // Padding kept constant in world units (0.005) by dividing out scale,
        // so the wireframe stays tight against the model at any scale.
        float padX = 0.005f / Math.max(Math.abs(object.transform.scaleX), 1e-4f);
        float padY = 0.005f / Math.max(Math.abs(object.transform.scaleY), 1e-4f);
        float padZ = 0.005f / Math.max(Math.abs(object.transform.scaleZ), 1e-4f);

        float baseMinX = (mr != null && mr.mesh != null) ? mr.mesh.minX : -0.5f;
        float baseMinY = (mr != null && mr.mesh != null) ? mr.mesh.minY : -0.5f;
        float baseMinZ = (mr != null && mr.mesh != null) ? mr.mesh.minZ : -0.5f;
        float baseMaxX = (mr != null && mr.mesh != null) ? mr.mesh.maxX : 0.5f;
        float baseMaxY = (mr != null && mr.mesh != null) ? mr.mesh.maxY : 0.5f;
        float baseMaxZ = (mr != null && mr.mesh != null) ? mr.mesh.maxZ : 0.5f;

        float mx = baseMinX - padX;
        float my = baseMinY - padY;
        float mz = baseMinZ - padZ;
        float xx = baseMaxX + padX;
        float xy = baseMaxY + padY;
        float xz = baseMaxZ + padZ;

        RenderUtils.drawWireframeAABB(mx, my, mz, xx, xy, xz);

        glPopMatrix();
        glPopAttrib();
    }
}