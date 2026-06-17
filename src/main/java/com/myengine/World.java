package com.myengine;

import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

class World {
    public static final String CUBE_MESH_KEY  = "__cube";
    public static final String SPAWN_POINT_KEY = "__spawn_point";
    public static final String WIN_ZONE_KEY    = "__win_zone";
    // Character-collision definition entity: its scale IS the editable
    // player capsule (scaleX/scaleZ = diameter, scaleY = height; the
    // position is just where it sits in the editor). Physics reads the
    // dimensions on play start. Never collides, never renders a mesh —
    // the editor draws a green wireframe capsule for it.
    public static final String PLAYER_BODY_KEY = "__player_body";

    public List<GameObject> entities = new ArrayList<>();
    private Map<String, List<GameObject>> spatialGrid = new HashMap<>();

    public void init() {
        // Unit quad in the XY plane for the spawn-point billboard. Rendering
        // logic in LevelEditorScene replaces the modelview rotation with the
        // camera's basis each frame so the quad always faces the player.
        float[] qpos = { -0.5f, -0.5f, 0,   0.5f, -0.5f, 0,   0.5f, 0.5f, 0,   -0.5f, 0.5f, 0 };
        float[] qtex = { 0, 1,   1, 1,   1, 0,   0, 0 };
        float[] qnorm = { 0, 0, 1,   0, 0, 1,   0, 0, 1,   0, 0, 1 };
        int[] qind = { 0, 1, 2,   0, 2, 3 };
        Mesh spawnMesh = new Mesh(qpos, qtex, qnorm, qind);
        spawnMesh.minX = -0.5f; spawnMesh.maxX = 0.5f;
        spawnMesh.minY = -0.5f; spawnMesh.maxY = 0.5f;
        spawnMesh.minZ = 0;     spawnMesh.maxZ = 0;
        AssetPool.registerMesh(SPAWN_POINT_KEY, spawnMesh);

        // Unit cube shared by procedural cube GameObjects (modelPath == "").
        // Having a real Mesh — instead of relying on RenderUtils.drawCube() —
        // lets the existing mesh-aware raycasting and collision pick up the
        // object's rotation automatically, so a rotated cube acts as a ramp.
        float[] cpos = {
                -0.5f,  0.5f,  0.5f,   0.5f,  0.5f,  0.5f,   0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,
                -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f, -0.5f, -0.5f,  -0.5f, -0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
                -0.5f, -0.5f, -0.5f,   0.5f, -0.5f, -0.5f,   0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,
                 0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,   0.5f, -0.5f,  0.5f,   0.5f, -0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,  -0.5f,  0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,  -0.5f, -0.5f, -0.5f
        };
        float[] ctex = {
                0,1, 1,1, 1,0, 0,0,  1,1, 0,1, 0,0, 1,0,  0,1, 1,1, 1,0, 0,0,
                1,1, 0,1, 0,0, 1,0,  1,1, 0,1, 0,0, 1,0,  0,1, 1,1, 1,0, 0,0
        };
        float[] cnorm = {
                0,0,1, 0,0,1, 0,0,1, 0,0,1,  0,0,-1, 0,0,-1, 0,0,-1, 0,0,-1,
                0,1,0, 0,1,0, 0,1,0, 0,1,0,  0,-1,0, 0,-1,0, 0,-1,0, 0,-1,0,
                1,0,0, 1,0,0, 1,0,0, 1,0,0,  -1,0,0, -1,0,0, -1,0,0, -1,0,0
        };
        int[] cind = {
                0, 1, 2,  0, 2, 3,   4, 5, 6,  4, 6, 7,   8, 9,10,  8,10,11,
                12,13,14, 12,14,15,  16,17,18, 16,18,19,  20,21,22, 20,22,23
        };
        Mesh cubeMesh = new Mesh(cpos, ctex, cnorm, cind);
        cubeMesh.minX = -0.5f; cubeMesh.maxX = 0.5f;
        cubeMesh.minY = -0.5f; cubeMesh.maxY = 0.5f;
        cubeMesh.minZ = -0.5f; cubeMesh.maxZ = 0.5f;
        AssetPool.registerMesh(CUBE_MESH_KEY, cubeMesh);
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
            // Markers (spawn points, win zones, future trigger zones, etc.) are
            // not part of the collision grid — the player walks through them.
            MeshRenderer mr = obj.getComponent(MeshRenderer.class);
            if (mr != null &&
                    (SPAWN_POINT_KEY.equals(mr.modelPath) || WIN_ZONE_KEY.equals(mr.modelPath)
                     || PLAYER_BODY_KEY.equals(mr.modelPath))) {
                continue;
            }
            // WalkthroughComponent → non-solid: keep it out of the collision
            // grid entirely so the player passes through (trigger volume).
            if (obj.getComponent(WalkthroughComponent.class) != null) continue;

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

    private static Shader skyboxShader = null;
    private static final org.joml.Matrix4f tmpSkyMvp = new org.joml.Matrix4f();
    private static final java.nio.FloatBuffer skyMvpBuf =
            org.lwjgl.BufferUtils.createFloatBuffer(16);

    // Skybox draw — `viewRotOnly` is the camera's view matrix WITHOUT
    // translation (so the sky doesn't move with the player). `projection`
    // is the camera's perspective matrix. MVP = proj * viewRotOnly *
    // scale(100). The cube mesh is at ±0.5 in local space; scale by 100
    // matches the original ±50 skybox extent.
    public void drawSkybox(int skyboxTexture,
                           org.joml.Matrix4f projection,
                           org.joml.Matrix4f viewRotOnly) {
        Mesh cube = AssetPool.getMesh(CUBE_MESH_KEY);
        if (cube == null) return;
        if (skyboxShader == null) {
            skyboxShader = new Shader("skybox.vert", "skybox.frag");
        }

        // Compose MVP without touching the GL matrix stack.
        tmpSkyMvp.set(projection).mul(viewRotOnly).scale(100f);
        skyMvpBuf.clear();
        tmpSkyMvp.get(skyMvpBuf);

        glDepthMask(false);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_CUBE_MAP, skyboxTexture);

        skyboxShader.use();
        skyboxShader.setInt("skybox", 0);
        skyboxShader.setMat4("mvp", skyMvpBuf);

        cube.render();

        skyboxShader.detach();
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
        glDepthMask(true);
    }
}
