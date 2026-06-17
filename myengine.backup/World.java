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

class World {
    public static final String FLOOR_MESH_KEY = "__floor_plane";

    public List<GameObject> entities = new ArrayList<>();
    private Map<String, List<GameObject>> spatialGrid = new HashMap<>();

    public void init() {
        // Register the built-in floor plane as a named mesh in the asset cache.
        // Vertices are y=0; the Floor GameObject's Transform positions it in world.
        float s = Config.FLOOR_GRID_SIZE;
        float[] pos = { -s, 0, -s,  -s, 0, s,   s, 0, s,   s, 0, -s };
        float[] tex = { 0, 1, 0, 0, 1, 0, 1, 1 };
        float[] norm = { 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0 };
        int[] ind = { 0, 1, 2, 0, 2, 3 };
        Mesh floorMesh = new Mesh(pos, tex, norm, ind);
        floorMesh.minX = -s; floorMesh.maxX = s;
        floorMesh.minZ = -s; floorMesh.maxZ = s;
        floorMesh.minY = 0;  floorMesh.maxY = 0;
        AssetPool.registerMesh(FLOOR_MESH_KEY, floorMesh);
    }

    public void addEntity(float x, float y, float z) {
        GameObject obj = new GameObject(x, y, z);
        obj.addComponent(new MeshRenderer());
        entities.add(obj);
    }

    public void updateGrid() {
        spatialGrid.clear();
        float cell = Config.GRID_CELL_SIZE;
        for (GameObject obj : entities) {
            int minCx = (int) Math.floor(obj.getMinX() / cell);
            int maxCx = (int) Math.floor(obj.getMaxX() / cell);
            int minCz = (int) Math.floor(obj.getMinZ() / cell);
            int maxCz = (int) Math.floor(obj.getMaxZ() / cell);
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    String key = cx + "," + cz;
                    spatialGrid.computeIfAbsent(key, k -> new ArrayList<>()).add(obj);
                }
            }
        }
    }

    private String getCellKey(float x, float z) {
        int cx = (int) Math.floor(x / Config.GRID_CELL_SIZE);
        int cz = (int) Math.floor(z / Config.GRID_CELL_SIZE);
        return cx + "," + cz;
    }

    public List<GameObject> getNearbyEntities(float x, float z) {
        List<GameObject> nearby = new ArrayList<>();
        int cx = (int) Math.floor(x / Config.GRID_CELL_SIZE);
        int cz = (int) Math.floor(z / Config.GRID_CELL_SIZE);

        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                String key = (cx + i) + "," + (cz + j);
                List<GameObject> cell = spatialGrid.get(key);
                if (cell != null) {
                    nearby.addAll(cell);
                }
            }
        }
        return nearby;
    }

    public void drawSkybox(int skyboxTexture) {
        glDisable(GL_LIGHTING);
        glDepthMask(false);
        glEnable(GL_TEXTURE_CUBE_MAP);
        glBindTexture(GL_TEXTURE_CUBE_MAP, skyboxTexture);
        glPushMatrix();

        float s = 50.0f;
        glBegin(GL_QUADS);

        // Negative X
        glTexCoord3f(-1, -1, -1); glVertex3f(-s, -s, -s);
        glTexCoord3f(1, -1, -1); glVertex3f(s, -s, -s);
        glTexCoord3f(1, 1, -1); glVertex3f(s, s, -s);
        glTexCoord3f(-1, 1, -1); glVertex3f(-s, s, -s);

        // Positive X
        glTexCoord3f(-1, -1, 1); glVertex3f(-s, -s, s);
        glTexCoord3f(1, -1, 1); glVertex3f(s, -s, s);
        glTexCoord3f(1, 1, 1); glVertex3f(s, s, s);
        glTexCoord3f(-1, 1, 1); glVertex3f(-s, s, s);

        // Negative X face
        glTexCoord3f(-1, -1, -1); glVertex3f(-s, -s, -s);
        glTexCoord3f(-1, -1, 1); glVertex3f(-s, -s, s);
        glTexCoord3f(-1, 1, 1); glVertex3f(-s, s, s);
        glTexCoord3f(-1, 1, -1); glVertex3f(-s, s, -s);

        // Positive X face
        glTexCoord3f(1, -1, -1); glVertex3f(s, -s, -s);
        glTexCoord3f(1, -1, 1); glVertex3f(s, -s, s);
        glTexCoord3f(1, 1, 1); glVertex3f(s, s, s);
        glTexCoord3f(1, 1, -1); glVertex3f(s, s, -s);

        // Top face
        glTexCoord3f(-1, 1, -1); glVertex3f(-s, s, -s);
        glTexCoord3f(1, 1, -1); glVertex3f(s, s, -s);
        glTexCoord3f(1, 1, 1); glVertex3f(s, s, s);
        glTexCoord3f(-1, 1, 1); glVertex3f(-s, s, s);

        // Bottom face
        glTexCoord3f(-1, -1, -1); glVertex3f(-s, -s, -s);
        glTexCoord3f(1, -1, -1); glVertex3f(s, -s, -s);
        glTexCoord3f(1, -1, 1); glVertex3f(s, -s, s);
        glTexCoord3f(-1, -1, 1); glVertex3f(-s, -s, s);

        glEnd();
        glPopMatrix();
        glDisable(GL_TEXTURE_CUBE_MAP);
        glDepthMask(true);
        glEnable(GL_LIGHTING);
    }
}