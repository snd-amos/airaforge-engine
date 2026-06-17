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

class Skeleton {
    public final Map<String, BoneInfo> boneInfoMap = new HashMap<>();
    public int boneCount = 0;
    public NodeData rootNode;
    public Matrix4f globalInverseTransform = new Matrix4f();

    private static final FloatBuffer matBuf = BufferUtils.createFloatBuffer(16);

    // draw the static (tpose) green bones
    public void drawDebug() {
        glPushAttrib(GL_ENABLE_BIT | GL_CURRENT_BIT | GL_LINE_BIT | GL_DEPTH_BUFFER_BIT);
        glDisable(GL_LIGHTING);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_DEPTH_TEST);
        glLineWidth(2.0f);

        if (rootNode != null) {
            glPushMatrix();
            applyMatrix(rootNode.transformation);
            drawNodeRecursive(rootNode);
            glPopMatrix();
        }

        glPopAttrib();
    }

    // anim red bones
    public void drawAnimatedDebug(Matrix4f[] debugBoneTransforms) {
        glPushAttrib(GL_ENABLE_BIT | GL_CURRENT_BIT | GL_LINE_BIT | GL_DEPTH_BUFFER_BIT);
        glDisable(GL_LIGHTING);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_DEPTH_TEST);
        glLineWidth(2.0f);

        if (rootNode != null) {
            drawAnimatedNodeRecursive(rootNode, new Matrix4f(), debugBoneTransforms);
        }
        glPopAttrib();
    }

    private void drawAnimatedNodeRecursive(NodeData node, Matrix4f parentGlobal, Matrix4f[] debugBoneTransforms) {
        Matrix4f currentGlobal;
        BoneInfo info = boneInfoMap.get(node.name);

        if (info != null && info.id < 100) {
            currentGlobal = debugBoneTransforms[info.id];
        } else {
            currentGlobal = new Matrix4f(parentGlobal).mul(node.transformation);
        }

        Vector3f parentPos = new Vector3f();
        parentGlobal.getTranslation(parentPos);
        Vector3f currentPos = new Vector3f();
        currentGlobal.getTranslation(currentPos);

        if (parentGlobal != currentGlobal) {
            if (info != null) glColor3f(1.0f, 0.2f, 0.2f); // Make animated bones Red!
            else glColor3f(0.2f, 0.6f, 0.8f);

            glBegin(GL_LINES);
            glVertex3f(parentPos.x, parentPos.y, parentPos.z);
            glVertex3f(currentPos.x, currentPos.y, currentPos.z);
            glEnd();
        }

        for (NodeData child : node.children) {
            drawAnimatedNodeRecursive(child, currentGlobal, debugBoneTransforms);
        }
    }

    private void drawNodeRecursive(NodeData node) {
        for (NodeData child : node.children) {
            Vector3f childPos = new Vector3f();
            child.transformation.getTranslation(childPos);

            if (boneInfoMap.containsKey(child.name)) glColor3f(0.2f, 1.0f, 0.2f);
            else glColor3f(0.2f, 0.6f, 0.8f);

            glBegin(GL_LINES);
            glVertex3f(0f, 0f, 0f);
            glVertex3f(childPos.x, childPos.y, childPos.z);
            glEnd();

            glPushMatrix();
            applyMatrix(child.transformation);
            drawNodeRecursive(child);
            glPopMatrix();
        }
    }

    private static void applyMatrix(Matrix4f m) {
        matBuf.clear();
        m.get(matBuf);
        glMultMatrixf(matBuf);
    }
}