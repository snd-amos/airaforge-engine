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

class Gizmo {

    public static void render(GameObject selectedBox, boolean isSun, int draggingAxis,
                              boolean isHoveringX, boolean isHoveringY, boolean isHoveringZ,
                              boolean isHoveringCenter) {
        glPushAttrib(GL_ENABLE_BIT | GL_CURRENT_BIT | GL_LINE_BIT);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_LIGHTING);
        glDisable(GL_TEXTURE_2D);

        if (selectedBox.transformMode == 0) {
            drawArrow(Config.GIZMO_ARROW_LENGTH, 0, 0, 1, 0, 0, draggingAxis == 0, isHoveringX);
            drawArrow(0, Config.GIZMO_ARROW_LENGTH, 0, 0, 1, 0, draggingAxis == 1, isHoveringY);
            drawArrow(0, 0, Config.GIZMO_ARROW_LENGTH, 0, 0, 1, draggingAxis == 2, isHoveringZ);
        } else if (selectedBox.transformMode == 1 && !isSun) {
            drawGizmoCircle(0, 1, 0, 0, draggingAxis == 0, isHoveringX);
            drawGizmoCircle(1, 0, 1, 0, draggingAxis == 1, isHoveringY);
            drawGizmoCircle(2, 0, 0, 1, draggingAxis == 2, isHoveringZ);
        } else if (selectedBox.transformMode == 2 && !isSun) {
            drawScaleGizmo(Config.GIZMO_ARROW_LENGTH, 0, 0, 1, 0, 0, draggingAxis == 0, isHoveringX);
            drawScaleGizmo(0, Config.GIZMO_ARROW_LENGTH, 0, 0, 1, 0, draggingAxis == 1, isHoveringY);
            drawScaleGizmo(0, 0, Config.GIZMO_ARROW_LENGTH, 0, 0, 1, draggingAxis == 2, isHoveringZ);

            glPushMatrix();
            if (draggingAxis == 3) glColor3f(1, 1, 0);
            else if (isHoveringCenter) glColor3f(1, 1, 1);
            else glColor3f(0.7f, 0.7f, 0.7f);
            glScalef(Config.GIZMO_SCALE_CUBE_SIZE, Config.GIZMO_SCALE_CUBE_SIZE, Config.GIZMO_SCALE_CUBE_SIZE);
            drawGizmoSolidCube();
            glPopMatrix();
        }

        glEnable(GL_DEPTH_TEST);
        glPopAttrib();
    }

    public static int getHitAxis(Player player, GameObject selected, double mouseX, double mouseY,
                                 int width, int height) {
        if (selected == null) return -1;

        float[] ray = RayCaster.getMouseRay((float)mouseX, (float)mouseY, width, height,
                Config.FOV, player.pitch, player.yaw);

        float ox = player.x - selected.transform.x;
        float oy = player.y - selected.transform.y;
        float oz = player.z - selected.transform.z;

        float bestT = Float.MAX_VALUE;
        int hitAxis = -1;

        if (selected.transformMode == 0 || selected.transformMode == 2) {
            // World-space hit test — no inverse rotation, matches the world-space gizmo rendering
            if (selected.transformMode == 2) {
                float tC = RayCaster.rayAABB(ox, oy, oz, ray[0], ray[1], ray[2],
                        -Config.GIZMO_CENTER_CUBE_SIZE, -Config.GIZMO_CENTER_CUBE_SIZE,
                        -Config.GIZMO_CENTER_CUBE_SIZE,
                        Config.GIZMO_CENTER_CUBE_SIZE, Config.GIZMO_CENTER_CUBE_SIZE,
                        Config.GIZMO_CENTER_CUBE_SIZE);
                if (tC > 0 && tC < bestT) { bestT = tC; hitAxis = 3; }
            }

            float tX = RayCaster.rayAABB(ox, oy, oz, ray[0], ray[1], ray[2],
                    0.15f, -0.06f, -0.06f, Config.GIZMO_ARROW_LENGTH + 0.1f, 0.06f, 0.06f);
            if (tX > 0 && tX < bestT) { bestT = tX; hitAxis = 0; }

            float tY = RayCaster.rayAABB(ox, oy, oz, ray[0], ray[1], ray[2],
                    -0.06f, 0.15f, -0.06f, 0.06f, Config.GIZMO_ARROW_LENGTH + 0.1f, 0.06f);
            if (tY > 0 && tY < bestT) { bestT = tY; hitAxis = 1; }

            float tZ = RayCaster.rayAABB(ox, oy, oz, ray[0], ray[1], ray[2],
                    -0.06f, -0.06f, 0.15f, 0.06f, 0.06f, Config.GIZMO_ARROW_LENGTH + 0.1f);
            if (tZ > 0 && tZ < bestT) { bestT = tZ; hitAxis = 2; }

        } else if (selected.transformMode == 1) {
            // Local-space hit test for rotation rings — inverse rotate into object space
            float[] localOri = inverseRotate(ox, oy, oz, selected.transform.rotX,
                    selected.transform.rotY, selected.transform.rotZ);
            float[] localDir = inverseRotate(ray[0], ray[1], ray[2], selected.transform.rotX,
                    selected.transform.rotY, selected.transform.rotZ);

            float tX = (localDir[0] != 0) ? -localOri[0] / localDir[0] : -1;
            if (tX > 0 && tX < bestT) {
                float hitY = localOri[1] + localDir[1] * tX;
                float hitZ = localOri[2] + localDir[2] * tX;
                if (Math.sqrt(hitY*hitY + hitZ*hitZ) > 1.2f && Math.sqrt(hitY*hitY + hitZ*hitZ) < 1.8f) {
                    bestT = tX; hitAxis = 0;
                }
            }

            float tY = (localDir[1] != 0) ? -localOri[1] / localDir[1] : -1;
            if (tY > 0 && tY < bestT) {
                float hitX = localOri[0] + localDir[0] * tY;
                float hitZ = localOri[2] + localDir[2] * tY;
                if (Math.sqrt(hitX*hitX + hitZ*hitZ) > 1.2f && Math.sqrt(hitX*hitX + hitZ*hitZ) < 1.8f) {
                    bestT = tY; hitAxis = 1;
                }
            }

            float tZ = (localDir[2] != 0) ? -localOri[2] / localDir[2] : -1;
            if (tZ > 0 && tZ < bestT) {
                float hitX = localOri[0] + localDir[0] * tZ;
                float hitY = localOri[1] + localDir[1] * tZ;
                if (Math.sqrt(hitX*hitX + hitY*hitY) > 1.2f && Math.sqrt(hitX*hitX + hitY*hitY) < 1.8f) {
                    bestT = tZ; hitAxis = 2;
                }
            }
        }
        return hitAxis;
    }

    private static float[] inverseRotate(float x, float y, float z, float rotX, float rotY, float rotZ) {
        float rZ = (float) Math.toRadians(-rotZ);
        float rY = (float) Math.toRadians(-rotY);
        float rX = (float) Math.toRadians(-rotX);

        float x1 = x * (float) Math.cos(rZ) - y * (float) Math.sin(rZ);
        float y1 = x * (float) Math.sin(rZ) + y * (float) Math.cos(rZ);
        float z1 = z;

        float x2 = x1 * (float) Math.cos(rY) + z1 * (float) Math.sin(rY);
        float y2 = y1;
        float z2 = -x1 * (float) Math.sin(rY) + z1 * (float) Math.cos(rY);

        float x3 = x2;
        float y3 = y2 * (float) Math.cos(rX) - z2 * (float) Math.sin(rX);
        float z3 = y2 * (float) Math.sin(rX) + z2 * (float) Math.cos(rX);

        return new float[]{x3, y3, z3};
    }

    private static void drawArrow(float ex, float ey, float ez, float r, float g, float b,
                                  boolean d, boolean h) {
        if (d) glLineWidth(10.0f);
        else if (h) glLineWidth(8.0f);
        else glLineWidth(4.0f);
        if (d) glColor3f(1, 1, 1);
        else glColor3f(r, g, b);
        glBegin(GL_LINES);
        glVertex3f(0, 0, 0);
        glVertex3f(ex, ey, ez);
        glEnd();
    }

    private static void drawScaleGizmo(float ex, float ey, float ez, float r, float g, float b,
                                       boolean d, boolean h) {
        if (d) glLineWidth(10.0f);
        else if (h) glLineWidth(8.0f);
        else glLineWidth(4.0f);
        if (d) glColor3f(1, 1, 1);
        else glColor3f(r, g, b);
        glBegin(GL_LINES);
        glVertex3f(0, 0, 0);
        glVertex3f(ex, ey, ez);
        glEnd();
        glPushMatrix();
        glTranslatef(ex, ey, ez);
        glScalef(0.2f, 0.2f, 0.2f);
        drawGizmoSolidCube();
        glPopMatrix();
    }

    private static void drawGizmoCircle(int a, float r, float g, float b, boolean d, boolean h) {
        if (d) glLineWidth(8.0f);
        else if (h) glLineWidth(6.0f);
        else glLineWidth(3.0f);
        if (d) glColor3f(1, 1, 1);
        else glColor3f(r, g, b);

        float rad = Config.GIZMO_ROTATION_RADIUS;
        int seg = Config.GIZMO_CIRCLE_SEGMENTS;
        glBegin(GL_LINE_LOOP);
        for (int i = 0; i < seg; i++) {
            float th = (float) (2.0 * Math.PI * i / seg);
            float x = (float) (rad * Math.cos(th));
            float y = (float) (rad * Math.sin(th));
            if (a == 1) glVertex3f(x, 0, y);
            else if (a == 0) glVertex3f(0, x, y);
            else if (a == 2) glVertex3f(x, y, 0);
        }
        glEnd();
    }

    private static void drawGizmoSolidCube() {
        glBegin(GL_QUADS);
        glVertex3f(-0.5f,  0.5f,  0.5f); glVertex3f( 0.5f,  0.5f,  0.5f); glVertex3f( 0.5f, -0.5f,  0.5f); glVertex3f(-0.5f, -0.5f,  0.5f);
        glVertex3f(-0.5f,  0.5f, -0.5f); glVertex3f( 0.5f,  0.5f, -0.5f); glVertex3f( 0.5f, -0.5f, -0.5f); glVertex3f(-0.5f, -0.5f, -0.5f);
        glVertex3f(-0.5f,  0.5f, -0.5f); glVertex3f( 0.5f,  0.5f, -0.5f); glVertex3f( 0.5f,  0.5f,  0.5f); glVertex3f(-0.5f,  0.5f,  0.5f);
        glVertex3f(-0.5f, -0.5f, -0.5f); glVertex3f( 0.5f, -0.5f, -0.5f); glVertex3f( 0.5f, -0.5f,  0.5f); glVertex3f(-0.5f, -0.5f,  0.5f);
        glVertex3f( 0.5f,  0.5f, -0.5f); glVertex3f( 0.5f,  0.5f,  0.5f); glVertex3f( 0.5f, -0.5f,  0.5f); glVertex3f( 0.5f, -0.5f, -0.5f);
        glVertex3f(-0.5f,  0.5f, -0.5f); glVertex3f(-0.5f,  0.5f,  0.5f); glVertex3f(-0.5f, -0.5f,  0.5f); glVertex3f(-0.5f, -0.5f, -0.5f);
        glEnd();
    }
}