package com.myengine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP;

/**
 * World container for all game objects.
 * Manages entities and spatial partitioning for efficient collision detection.
 */
public class World {
    public List<GameObject> entities = new ArrayList<>();

    // Spatial partitioning grid for efficient collision detection
    private Map<String, List<GameObject>> spatialGrid = new HashMap<>();

    /**
     * Add a new entity to the world.
     */
    public void addEntity(float x, float y, float z) {
        GameObject obj = new GameObject(x, y, z);
        obj.addComponent(new MeshRenderer());
        entities.add(obj);
    }

    /**
     * Update spatial grid based on current entity positions.
     * Called once per frame for efficient collision queries.
     */
    public void updateGrid() {
        spatialGrid.clear();
        for (GameObject obj : entities) {
            String key = getCellKey(obj.transform.x, obj.transform.z);
            spatialGrid.computeIfAbsent(key, k -> new ArrayList<>()).add(obj);
        }
    }

    /**
     * Get the grid cell key for a given position.
     */
    private String getCellKey(float x, float z) {
        int cx = (int) Math.floor(x / Config.GRID_CELL_SIZE);
        int cz = (int) Math.floor(z / Config.GRID_CELL_SIZE);
        return cx + "," + cz;
    }

    /**
     * Get all entities in a 3x3 grid area around the given position.
     * Used for efficient collision and raycasting queries.
     */
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

    /**
     * Draw the floor grid for level visualization.
     */
    public void drawFloor() {
        glDisable(GL_TEXTURE_2D);
        glBegin(GL_LINES);
        glColor3f(0.5f, 0.5f, 0.5f);
        float size = Config.FLOOR_GRID_SIZE;
        for (float i = -size; i <= size; i += 1.0f) {
            glVertex3f(-size, -0.99f, i);
            glVertex3f(size, -0.99f, i);
            glVertex3f(i, -0.99f, -size);
            glVertex3f(i, -0.99f, size);
        }
        glEnd();
        glEnable(GL_TEXTURE_2D);
    }

    /**
     * Draw the skybox cubemap.
     */
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