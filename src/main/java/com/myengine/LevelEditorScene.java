package com.myengine;

import imgui.ImGui;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

class LevelEditorScene extends Scene {
    public GameObject selectedBox = null;
    public GameObject sunBlock;
    public float sunIntensity = 0.5f;

    private UIManager uiManager = new UIManager();
    private Shader defaultShader;
    private Shader defaultInstancedShader;
    private Shader depthInstancedShader;
    private final CubeBatch cubeBatch = new CubeBatch();
    private Shader outlineShader;

    // --- SHADOW MAP FIELDS ---
    private Shader depthShader;
    private ShadowMapFBO shadowFBO;

    // Matrix Buffers (column-major, OpenGL convention). Populated from JOML
    // matrices each frame; FloatBuffer is the form glUniformMatrix4fv takes.
    private FloatBuffer lightProjMatrix = BufferUtils.createFloatBuffer(16);
    private FloatBuffer lightViewMatrix = BufferUtils.createFloatBuffer(16);
    private FloatBuffer tempModelMatrix = BufferUtils.createFloatBuffer(16);
    private FloatBuffer viewMatrix = BufferUtils.createFloatBuffer(16);
    private FloatBuffer projMatrix = BufferUtils.createFloatBuffer(16);
    private FloatBuffer identityBoneMatrices = BufferUtils.createFloatBuffer(100 * 16);

    // Reusable JOML scratch matrices (avoid per-frame allocation).
    private final Matrix4f tmpProj = new Matrix4f();
    private final Matrix4f tmpView = new Matrix4f();
    private final Matrix4f tmpModel = new Matrix4f();

    private Framebuffer framebuffer;
    public UndoRedoManager undoRedoManager = new UndoRedoManager();

    private GameObject clipboardGameObject = null;

    private int draggingAxis = -1;
    private double lastDragMouseY, lastDragMouseX;
    private float rawDragX = 0, rawDragY = 0, rawDragZ = 0;
    private boolean isHoveringX = false, isHoveringY = false, isHoveringZ = false, isHoveringCenter = false;

    private float snapX, snapY, snapZ;
    private float snapRotX, snapRotY, snapRotZ;
    private float snapScaleX, snapScaleY, snapScaleZ;

    public Player player = new Player();
    public World world = new World();

    private int cubeTexture;
    private int skyboxTexture;

    private boolean isEditorMode = true;
    private boolean isPanning = false;

    private double lastMouseX, lastMouseY;
    private boolean firstMouse = true;
    private long lastSpacePressTime = 0;

    private int screenWidth, screenHeight;
    private int viewportW, viewportH;
    private long glfwWindow;

    private String levelPath;
    private boolean startInEditorMode;

    // --- BOX-SELECT / MULTI-SELECTION ---
    public List<GameObject> selectedObjects = new ArrayList<>();
    private boolean boxSelectMode = false;
    private boolean boxDragging = false;
    private double boxStartX, boxStartY, boxEndX, boxEndY;
    private GameObject groupProxy = null;
    private float prevProxyX, prevProxyY, prevProxyZ;
    private float prevProxyRotX, prevProxyRotY, prevProxyRotZ;
    private float prevProxyScaleX, prevProxyScaleY, prevProxyScaleZ;

    public LevelEditorScene(int width, int height, long glfwWindow) {
        this(width, height, glfwWindow, Config.GAME_LEVEL_PATH, true);
    }

    public LevelEditorScene(int width, int height, long glfwWindow, String levelPath, boolean startInEditorMode) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.viewportW = width;
        this.viewportH = height;
        this.glfwWindow = glfwWindow;
        this.levelPath = levelPath;
        this.startInEditorMode = startInEditorMode;
    }

    public String getLevelPath() {
        return levelPath;
    }

    @Override
    public void init() {
        Logger.info("Initializing LevelEditorScene");

        world.init();

        // Lighting is computed entirely in the fragment shaders (default.frag
        // reads `lightPos` + `sunIntensity` uniforms); the legacy
        // fixed-function lighting / colour-material / texture-enable state
        // is no longer used by anything. Removed for Core-profile compat.

        defaultShader = new Shader("default.vert", "default.frag");
        depthShader = new Shader("depth.vert", "depth.frag");
        defaultInstancedShader = new Shader("default_instanced.vert", "default_instanced.frag");
        depthInstancedShader = new Shader("depth_instanced.vert", "depth.frag");
        outlineShader = new Shader("outline.vert", "outline.frag");
        SelectionBoxRenderer.configureOutline(outlineShader, identityBoneMatrices);

        framebuffer = new Framebuffer(screenWidth, screenHeight);
        shadowFBO = new ShadowMapFBO(Config.SHADOW_MAP_WIDTH, Config.SHADOW_MAP_HEIGHT);
        isEditorMode = startInEditorMode;

        for (int i = 0; i < 100; i++) {
            identityBoneMatrices.put(1).put(0).put(0).put(0);
            identityBoneMatrices.put(0).put(1).put(0).put(0);
            identityBoneMatrices.put(0).put(0).put(1).put(0);
            identityBoneMatrices.put(0).put(0).put(0).put(1);
        }
        identityBoneMatrices.flip();

        cubeTexture = AssetPool.getTexture("test.png");
        skyboxTexture = Texture.loadCubemap(new String[]{"px.png", "nx.png", "py.png", "ny.png", "pz.png", "nz.png"});

        uiManager.init(glfwWindow);

        java.util.List<GameObject> loaded = LevelLoader.loadLevel(levelPath);
        sunBlock = null;
        if (loaded != null && !loaded.isEmpty()) {
            // Dedupe any "Sun" entries that piled up from an earlier bug where
            // a fresh Sun was added on every load. Keep the first, drop the rest.
            java.util.List<GameObject> deduped = new java.util.ArrayList<>();
            boolean foundSun = false;
            for (GameObject obj : loaded) {
                if ("Sun".equals(obj.name)) {
                    if (foundSun) continue;
                    foundSun = true;
                    sunBlock = obj;
                }
                deduped.add(obj);
            }
            world.entities = deduped;
            selectedBox = null;
        }

        // Only seed a default Sun if the level didn't already have one.
        if (sunBlock == null) {
            sunBlock = new GameObject(-10, 26, -40);
            sunBlock.name = "Sun";
            sunBlock.addComponent(new MeshRenderer());
            world.entities.add(sunBlock);
            Logger.info("Seeded default Sun");
        }

        // Build the spatial grid once before the first player update so
        // collision works on frame 0 (without this, the player falls through
        // the floor because getNearbyEntities returns empty).
        world.updateGrid();

        // Reset the player's transform/velocity to a known-good state on every
        // scene entry. Belt-and-braces: do not rely on Player's field defaults
        // (which can get stale if Config constants were inlined at compile time).
        player.x = 0f;
        player.y = Config.PLAYER_START_Y;
        player.z = 0f;
        player.xVel = player.yVel = player.zVel = 0f;
        player.isGrounded = false;
        player.currentState = Player.State.IDLE;

        // If we're entering PLAY mode (Start Game from menu) and the level has
        // a Spawn Point placed, teleport the player to it. Editor mode keeps
        // its neutral (0, PLAYER_START_Y, 0) origin so the user always opens
        // the level facing the world center.
        if (!isEditorMode) {
            applySpawnPointIfPresent();
        }

        if (isEditorMode) {
            glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            glfwSetWindowTitle(glfwWindow, "Engine - EDITOR MODE");
        } else {
            glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            glfwSetWindowTitle(glfwWindow, "Engine - PLAY MODE");
            selectedBox = null;
        }

        Logger.info("LevelEditorScene initialized successfully");
    }

    public int getShadowMapID() {
        return shadowFBO != null ? shadowFBO.getDepthTextureID() : 0;
    }

    @Override
    public void update(double dt, boolean[] keys) {
        if (isEditorMode && draggingAxis != -1 && selectedBox != null) {
            if (keys[GLFW_KEY_R]) {
                selectedBox.transform.x = snapX;
                selectedBox.transform.y = snapY;
                selectedBox.transform.z = snapZ;
                selectedBox.transform.rotX = snapRotX;
                selectedBox.transform.rotY = snapRotY;
                selectedBox.transform.rotZ = snapRotZ;
                selectedBox.transform.scaleX = snapScaleX;
                selectedBox.transform.scaleY = snapScaleY;
                selectedBox.transform.scaleZ = snapScaleZ;
                draggingAxis = -1;
                Logger.info("Transform cancelled - reverted to previous state");
            }
        }

        // Rebuild spatial grid BEFORE player physics so collisions always see
        // the current entity positions (especially on the very first frame).
        try (var z = Profiler.zone("World.updateGrid")) {
            world.updateGrid();
        }

        try (var z = Profiler.zone("Player physics")) {
            if (isEditorMode) {
                boolean[] modifiedKeys = keys.clone();
                if (draggingAxis != -1) {
                    (modifiedKeys)[GLFW_KEY_LEFT_SHIFT] = false;
                }
                player.update(modifiedKeys, world, false, dt);
            } else {
                player.update(keys, world, false, dt);
                checkWinZones();
            }
        }

        try (var z = Profiler.zone("Entity update")) {
            for (GameObject obj : world.entities) {
                obj.update(dt);
            }
        }

        // If a multi-selection is active, propagate any gizmo-driven delta on
        // the group proxy to every real selected object, then re-anchor the
        // proxy at the new group center.
        applyGroupDeltaAndReset();
    }

    // Procedural cubes route through the instanced batch; per-entity render
    // skips them and CubeBatch draws them as one call per texture group.
    private boolean isInstanceableCube(GameObject obj, MeshRenderer mr) {
        if (obj == sunBlock) return false;
        if (mr == null || mr.mesh == null) return false;
        return mr.modelPath == null || mr.modelPath.isEmpty();
    }

    @Override
    public void render() {
        // Collect instanceable cubes once per frame — shared between shadow
        // and main passes, so we only iterate world.entities once for this.
        try (var z = Profiler.zone("CubeBatch.collect")) {
            cubeBatch.clear();
            for (GameObject b : world.entities) {
                MeshRenderer mr = b.getComponent(MeshRenderer.class);
                if (isInstanceableCube(b, mr)) cubeBatch.submit(b, mr);
            }
        }

        try (var pZone = Profiler.zone("Shadow pass")) {
        // =======================================================
        // PASS 1: SHADOW DEPTH MAP
        // =======================================================
        shadowFBO.bind();
        glClear(GL_DEPTH_BUFFER_BIT);

        // Shadow projection — ortho box that follows the player. Built in
        // JOML and written into lightProjMatrix for shader uploads (the
        // instanced shadow shader also reads from it).
        float orthoSize = Config.SHADOW_ORTHO_SIZE;
        tmpProj.identity().ortho(-orthoSize, orthoSize, -orthoSize, orthoSize,
                Config.SHADOW_NEAR, Config.SHADOW_FAR);
        lightProjMatrix.clear();
        tmpProj.get(lightProjMatrix);

        // Sun position is a *direction* — the shadow camera sits
        // SHADOW_CAM_HEIGHT units back along that direction from the player,
        // so the shadow frustum follows the player instead of being pinned
        // to the world origin. Lighting direction in default.frag still uses
        // the raw sun position so the directional light itself is unchanged.
        float sx = sunBlock.transform.x;
        float sy = sunBlock.transform.y;
        float sz = sunBlock.transform.z;
        float sunLen = (float) Math.sqrt(sx*sx + sy*sy + sz*sz);
        float dirX = sx / sunLen, dirY = sy / sunLen, dirZ = sz / sunLen;
        float pitch = (float) Math.toDegrees(Math.asin(dirY));
        float yaw = (float) Math.toDegrees(Math.atan2(dirX, dirZ));
        float camX = player.x + dirX * Config.SHADOW_CAM_HEIGHT;
        float camY = player.y + dirY * Config.SHADOW_CAM_HEIGHT;
        float camZ = player.z + dirZ * Config.SHADOW_CAM_HEIGHT;

        // Light view matrix = rotate(pitch, X) * rotate(-yaw, Y) * translate(-cam)
        // — matches the old glRotatef/glTranslatef sequence exactly.
        tmpView.identity()
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(-yaw))
                .translate(-camX, -camY, -camZ);
        lightViewMatrix.clear();
        tmpView.get(lightViewMatrix);

        glColorMask(false, false, false, false);
        glDisable(GL_CULL_FACE);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(2.0f, 4.0f);

        depthShader.use();
        // view & projection are the same for every entity in the shadow
        // pass — upload once before the loop.
        depthShader.setMat4("viewMatrix", lightViewMatrix);
        depthShader.setMat4("projMatrix", lightProjMatrix);

        for (GameObject b : world.entities) {
            if (b == sunBlock) continue;

            MeshRenderer mr = b.getComponent(MeshRenderer.class);
            if (mr == null) continue;
            if (World.SPAWN_POINT_KEY.equals(mr.modelPath)) continue;
            if (World.WIN_ZONE_KEY.equals(mr.modelPath)) continue;
            if (isInstanceableCube(b, mr)) continue;

            boolean meshIsSkinned = mr.mesh != null && mr.mesh.isSkinned;
            depthShader.setInt("isSkinned", meshIsSkinned ? 1 : 0);
            if (meshIsSkinned) {
                Animator anim = b.getComponent(Animator.class);
                FloatBuffer bones = (anim != null && anim.currentAnimation != null)
                        ? anim.matricesBuffer
                        : identityBoneMatrices;
                depthShader.setMat4Array("finalBonesMatrices", bones);
            }

            // Foliage: alpha-discard transparent fragments in the depth pass
            // so the shadow follows the visible leaf shape, not the leaf-quad
            // rectangle. Everything else writes depth unconditionally.
            boolean useAlpha = mr.isFoliage && mr.textureID > 0;
            depthShader.setInt("useTexture", useAlpha ? 1 : 0);
            if (useAlpha) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, mr.textureID);
                depthShader.setInt("texture_diffuse", 0);
            }

            RenderState.modelMatrix(b.transform, tmpModel);
            tempModelMatrix.clear();
            tmpModel.get(tempModelMatrix);
            depthShader.setMat4("modelMatrix", tempModelMatrix);

            if (mr.mesh != null) mr.mesh.render();
        }

        depthShader.detach();

        // Instanced cube shadow draw — collapses N cubes into one draw call.
        if (cubeBatch.instanceCount() > 0) {
            Mesh cubeMesh = AssetPool.getMesh(World.CUBE_MESH_KEY);
            if (cubeMesh != null) {
                depthInstancedShader.use();
                depthInstancedShader.setMat4("viewMatrix", lightViewMatrix);
                depthInstancedShader.setMat4("projMatrix", lightProjMatrix);
                cubeBatch.flush(cubeMesh, depthInstancedShader, false);
                depthInstancedShader.detach();
            }
        }

        glDisable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(0.0f, 0.0f);
        glColorMask(true, true, true, true);

        shadowFBO.unbind(screenWidth, screenHeight);
        } // end Shadow pass zone

        // Hoisted so both the Main pass zone and the Selection zone can read it.
        GameObject highlighted = null;

        try (var pZone = Profiler.zone("Main pass")) {
        // =======================================================
        // PASS 2: NORMAL RENDERING
        // =======================================================
        if (isEditorMode) {
            if (framebuffer.getTextureID() > 0 &&
                    (uiManager.viewportWidth != viewportW || uiManager.viewportHeight != viewportH)) {
                viewportW = (int) uiManager.viewportWidth;
                viewportH = (int) uiManager.viewportHeight;
                if (viewportW > Config.MIN_VIEWPORT_WIDTH && viewportH > Config.MIN_VIEWPORT_HEIGHT) {
                    framebuffer.resize(viewportW, viewportH);
                }
            }
            framebuffer.bind();
            updateProjection(viewportW, viewportH);
        } else {
            framebuffer.unbind(screenWidth, screenHeight);
            updateProjection(screenWidth, screenHeight);
        }

        glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Camera view matrix built in JOML. The rotation-only intermediate
        // is what the skybox uses (sky doesn't translate with the player);
        // we then post-multiply the camera translation for full world view.
        tmpView.identity()
                .rotateZ((float) Math.toRadians(player.roll))
                .rotateX((float) Math.toRadians(player.pitch))
                .rotateY((float) Math.toRadians(player.yaw));
        // Skybox uses projection + rotation-only view.
        world.drawSkybox(skyboxTexture, tmpProj, tmpView);
        // Now finish the view with the camera translation.
        tmpView.translate(-player.x, -(player.y + player.cameraYOffset), -player.z);
        viewMatrix.clear();
        tmpView.get(viewMatrix);
        // Publish view+proj for downstream renderers (selection outline,
        // gizmo, line/wireframe overlays, etc.).
        RenderState.setViewProj(tmpView, tmpProj);

        defaultShader.use();
        defaultShader.setInt("texture_diffuse", 0);
        defaultShader.setInt("shadowMap", 1);
        defaultShader.setMat4("viewMatrix", viewMatrix);
        defaultShader.setMat4("projMatrix", projMatrix);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, shadowFBO.getDepthTextureID());
        defaultShader.setMat4("lightProjection", lightProjMatrix);
        defaultShader.setMat4("lightView", lightViewMatrix);
        defaultShader.setVec3("lightPos", sunBlock.transform.x, sunBlock.transform.y, sunBlock.transform.z);
        glActiveTexture(GL_TEXTURE0);

        isHoveringX = isHoveringY = isHoveringZ = isHoveringCenter = false;

        if (isEditorMode) {
            double[] xposArr = new double[1], yposArr = new double[1];
            glfwGetCursorPos(glfwWindow, xposArr, yposArr);
            double localMouseX = xposArr[0] - uiManager.viewportX;
            double localMouseY = yposArr[0] - uiManager.viewportY;

            if (uiManager.isViewportHovered) {
                if (selectedBox != null) {
                    int axis = Gizmo.getHitAxis(player, selectedBox, localMouseX, localMouseY, viewportW, viewportH);
                    if (axis == 0) isHoveringX = true;
                    else if (axis == 1) isHoveringY = true;
                    else if (axis == 2) isHoveringZ = true;
                    else if (axis == 3) isHoveringCenter = true;
                }
                if (!isHoveringX && !isHoveringY && !isHoveringZ && !isHoveringCenter) {
                    highlighted = player.getEntityAtMouse(world, localMouseX, localMouseY, viewportW, viewportH);
                }
            }
        } else if (player.isFlying) {
            highlighted = player.getLookTarget(world, Config.RAYCAST_RANGE);
        }


        // --- RENDER ENTITIES ---
        for (GameObject b : world.entities) {
            if (b == sunBlock && !isEditorMode) continue;

            MeshRenderer mr = b.getComponent(MeshRenderer.class);
            if (mr == null) continue;
            // Procedural cubes are drawn via the instanced batch below.
            if (isInstanceableCube(b, mr)) continue;

            boolean isSpawnPoint = World.SPAWN_POINT_KEY.equals(mr.modelPath);
            boolean isWinZone   = World.WIN_ZONE_KEY.equals(mr.modelPath);
            boolean isMarker    = isSpawnPoint || isWinZone;
            // Markers are editor-only — hide them entirely while playing.
            if (isMarker && !isEditorMode) continue;

            // Build the entity's model matrix in JOML. For billboards we
            // also need a "view-cancelling" rotation so the quad always
            // faces the camera; compute that by inserting view⁻¹·rotation
            // — easier to express by building (translation only) and
            // post-multiplying view⁻¹'s rotation, but the simplest
            // equivalent that matches the old behaviour is to use a model
            // matrix whose rotation part is the inverse of the camera's
            // rotation (so view*model has identity rotation in the upper-
            // left, leaving the quad facing the camera).
            if (isMarker) {
                // Pure-translate world model, then cancel the view rotation
                // by applying the inverse rotation around the entity's
                // position. JOML helpers do this in two lines.
                tmpModel.translation(b.transform.x, b.transform.y, b.transform.z);
                // Append the inverse of the rotation part of viewMatrix so
                // view*model has identity rotation (= billboard facing the
                // camera). We have only rotation in the first 3 cols of
                // tmpView's pre-translation snapshot — but here we already
                // composed the full view, so we extract the 3x3 rotation
                // and invert it (transpose works since it's orthonormal).
                org.joml.Matrix3f rot3 = new org.joml.Matrix3f();
                tmpView.get3x3(rot3);
                rot3.transpose();
                tmpModel.mul(new Matrix4f().set(rot3));
            } else {
                RenderState.modelMatrix(b.transform, tmpModel);
            }
            tempModelMatrix.clear();
            tmpModel.get(tempModelMatrix);

            if (b == sunBlock) {
                // Tiny yellow cube marking the sun's world position in the
                // editor. tmpModel was just populated with the sun's TRS
                // transform; pass it to LineRenderer3D's flat-colour draw.
                defaultShader.detach();
                Mesh cube = AssetPool.getMesh(World.CUBE_MESH_KEY);
                if (cube != null) LineRenderer3D.drawMesh(cube, tmpModel, 1f, 1f, 0f);
                continue;
            }

            defaultShader.use();
            defaultShader.setInt("useTexture", 1);
            // Markers are UI — flat-bright, not lit.
            defaultShader.setFloat("sunIntensity", isMarker ? 1.0f : sunIntensity);
            defaultShader.setVec3("colorTint", mr.material.r, mr.material.g, mr.material.b);
            defaultShader.setMat4("modelMatrix", tempModelMatrix);
            // World-space triplanar tiling (cubes/floors) or stored UVs
            // (imported props). Markers always use stored UVs so the
            // billboard quad samples the full sprite, not a tiled crop.
            boolean triplanar = Boolean.TRUE.equals(mr.material.useWorldSpaceTiling) && !isMarker;
            defaultShader.setInt("useWorldSpaceTiling", triplanar ? 1 : 0);
            defaultShader.setFloat("textureScale", mr.material.textureScale);

            boolean meshIsSkinned = mr.mesh != null && mr.mesh.isSkinned;
            defaultShader.setInt("isSkinned", meshIsSkinned ? 1 : 0);
            if (meshIsSkinned) {
                Animator anim = b.getComponent(Animator.class);
                FloatBuffer bones = (anim != null && anim.currentAnimation != null)
                        ? anim.matricesBuffer
                        : identityBoneMatrices;
                defaultShader.setMat4Array("finalBonesMatrices", bones);
            }

            if (isMarker) {
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            }

            glBindTexture(GL_TEXTURE_2D, mr.textureID != 0 ? mr.textureID : cubeTexture);

            // Win zones don't own a mesh (their AABB is the unit-cube fallback);
            // borrow the spawn-point quad for the billboard.
            Mesh meshToRender = mr.mesh;
            if (isWinZone) {
                meshToRender = AssetPool.getMesh(World.SPAWN_POINT_KEY);
            }
            if (meshToRender != null) {
                meshToRender.render();
            }

            if (isMarker) {
                glDisable(GL_BLEND);
            }
        }

        // --- BUG FIX: SHADER FIREWALL ---
        defaultShader.detach();

        // Instanced cube draw — one call per (texture+tiling+scale) group.
        if (cubeBatch.instanceCount() > 0) {
            Mesh cubeMesh = AssetPool.getMesh(World.CUBE_MESH_KEY);
            if (cubeMesh != null) {
                defaultInstancedShader.use();
                defaultInstancedShader.setMat4("viewMatrix", viewMatrix);
                defaultInstancedShader.setMat4("projMatrix", projMatrix);
                defaultInstancedShader.setMat4("lightProjection", lightProjMatrix);
                defaultInstancedShader.setMat4("lightView", lightViewMatrix);
                defaultInstancedShader.setVec3("lightPos",
                        sunBlock.transform.x, sunBlock.transform.y, sunBlock.transform.z);
                defaultInstancedShader.setFloat("sunIntensity", sunIntensity);
                defaultInstancedShader.setInt("shadowMap", 1);
                // shadowMap is already bound on texture unit 1 from the per-entity loop.
                cubeBatch.flush(cubeMesh, defaultInstancedShader, true);
                defaultInstancedShader.detach();
            }
        }

        glBindTexture(GL_TEXTURE_2D, 0);
        } // end Main pass zone

        try (var pZone = Profiler.zone("Selection + overlays")) {
        // --- SKELETON DEBUG OVERLAY ---
        if (uiManager.showBones.get() && selectedBox != null) {
            MeshRenderer selMr = selectedBox.getComponent(MeshRenderer.class);
            Animator animComp = selectedBox.getComponent(Animator.class);

            if (selMr != null && selMr.mesh != null && selMr.mesh.skeleton != null) {
                Matrix4f selModel = RenderState.modelMatrix(selectedBox.transform, tmpModel);
                if (animComp != null && animComp.currentAnimation != null) {
                    selMr.mesh.skeleton.drawAnimatedDebug(animComp.debugBoneTransforms, selModel);
                } else {
                    selMr.mesh.skeleton.drawDebug(selModel);
                }
            }
        }

        // Phase 1 — clear the stencil and mark EVERY outlined object's
        // silhouette before any halo is painted. With every interior marked
        // up front, Pass B for any one object refuses to paint inside any
        // other selected/hovered object — fixes the "hover's black bleeds
        // into the selected cube's bottom" bug.
        //
        // glStencilMask(0xFF) here is critical: glClear is masked by the
        // stencil write mask, and if some earlier pass left it at 0x00 the
        // clear becomes a no-op and stale marks from previous frames remain.
        glStencilMask(0xFF);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);
        if (highlighted != null && highlighted != selectedBox &&
                SelectionBoxRenderer.usesStencilSilhouette(highlighted, false)) {
            SelectionBoxRenderer.drawSilhouetteMark(highlighted);
        }
        if (selectedObjects.size() > 1) {
            for (GameObject o : selectedObjects) {
                if (SelectionBoxRenderer.usesStencilSilhouette(o, true)) {
                    SelectionBoxRenderer.drawSilhouetteMark(o);
                }
            }
        } else if (selectedBox != null &&
                SelectionBoxRenderer.usesStencilSilhouette(selectedBox, true)) {
            SelectionBoxRenderer.drawSilhouetteMark(selectedBox);
        }

        // Phase 2 — paint halos / AABBs / capsules. The silhouette branch
        // inside drawWireframeOnly now does Pass B only; it reads the
        // stencil marks from Phase 1.
        if (highlighted != null && highlighted != selectedBox) {
            SelectionBoxRenderer.drawSelectionBox(highlighted, false, -1, false, false, false, false);
        }

        if (selectedObjects.size() > 1) {
            // Multi-selection: orange wireframes on every real object, and a
            // SINGLE gizmo at the group center via the proxy.
            for (GameObject o : selectedObjects) {
                SelectionBoxRenderer.drawWireframeOnly(o, true);
            }
            if (selectedBox != null) {
                SelectionBoxRenderer.drawGizmoOnly(selectedBox, draggingAxis,
                        isHoveringX, isHoveringY, isHoveringZ, isHoveringCenter);
            }
        } else if (selectedBox != null) {
            SelectionBoxRenderer.drawSelectionBox(selectedBox, true, draggingAxis,
                    isHoveringX, isHoveringY, isHoveringZ, isHoveringCenter);
        }


        // Player collision capsule — drawn in editor mode so you can see how
        // the player's hitbox lines up with the world. Rendered with depth
        // test off so it stays visible even when below the camera.
        if (isEditorMode) {
            drawPlayerCapsule();
        }

        // 2D overlay for the box-select drag rectangle.
        if (boxDragging) {
            drawBoxSelectOverlay();
        }

        drawCrosshair();
        drawStaminaBar();
        } // end Selection + overlays zone


        if (isEditorMode) {
            try (var pZone = Profiler.zone("UI (ImGui)")) {
                framebuffer.unbind(screenWidth, screenHeight);
                uiManager.render(this, framebuffer.getTextureID());
            }
        }
    }

    private void drawPlayerCapsule() {
        glDisable(GL_DEPTH_TEST);
        GLCompat.lineWidth(1.5f);
        Matrix4f capsuleModel = tmpModel.identity().translation(
                player.x, player.y - 0.8f, player.z);
        RenderUtils.drawWireframeCapsule(0, 0, 0, 2.0f, Config.PLAYER_RADIUS,
                capsuleModel, 0.3f, 0.85f, 0.4f);
        glEnable(GL_DEPTH_TEST);
        GLCompat.lineWidth(1.0f);
    }

    private void drawStaminaBar() {
        if (player.climbStamina >= Config.CLIMB_MAX_STAMINA && player.currentState != Player.State.CLIMBING) return;

        float ratio = Math.max(0f, Math.min(1f, player.climbStamina / Config.CLIMB_MAX_STAMINA));

        float barW = 0.3f;
        float barH = 0.025f;
        float x0 = -barW / 2.0f;
        float y0 = 0.9f;
        float x1 = x0 + barW;
        float y1 = y0 + barH;

        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Background bar.
        Renderer2D.fillRect(x0, y0, x1, y1, 0.08f, 0.08f, 0.08f, 0.75f);
        // Fill bar — colour-coded by stamina.
        float fr, fg, fb;
        if (ratio > 0.5f)        { fr = 0.30f; fg = 0.85f; fb = 0.40f; }
        else if (ratio > 0.25f)  { fr = 0.95f; fg = 0.75f; fb = 0.20f; }
        else                     { fr = 0.95f; fg = 0.25f; fb = 0.20f; }
        Renderer2D.fillRect(x0, y0, x0 + barW * ratio, y1, fr, fg, fb, 0.9f);
        // White outline around the whole bar.
        Renderer2D.outlineRect(x0, y0, x1, y1, 1f, 1f, 1f, 0.85f);

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    @Override
    public void destroy() {
        Logger.info("Destroying LevelEditorScene");

        uiManager.destroy();
        framebuffer.destroy();
        if(shadowFBO != null) shadowFBO.destroy();
        defaultShader.destroy();
        if(depthShader != null) depthShader.destroy();
        if(outlineShader != null) outlineShader.destroy();
        AssetPool.destroy();

        Logger.info("LevelEditorScene destroyed successfully");
    }

    @Override
    public void onKeyPress(int key, int action) {
        if (key == GLFW_KEY_F3 && action == GLFW_PRESS) {
            uiManager.showDebugHud.set(!uiManager.showDebugHud.get());
            return;
        }

        if (key == GLFW_KEY_R && action == GLFW_PRESS) {
            if (isEditorMode) {
                if (draggingAxis != -1 && selectedBox != null) {
                    selectedBox.transform.x = snapX;
                    selectedBox.transform.y = snapY;
                    selectedBox.transform.z = snapZ;
                    selectedBox.transform.rotX = snapRotX;
                    selectedBox.transform.rotY = snapRotY;
                    selectedBox.transform.rotZ = snapRotZ;
                    selectedBox.transform.scaleX = snapScaleX;
                    selectedBox.transform.scaleY = snapScaleY;
                    selectedBox.transform.scaleZ = snapScaleZ;
                    draggingAxis = -1;
                    Logger.info("Gizmo transform cancelled");
                } else if (selectedBox != null && undoRedoManager.canUndo()) {
                    undoRedoManager.undo();
                    Logger.info("Inspector change undone");
                }
            }
            return;
        }

        if (ImGui.getIO().getWantCaptureKeyboard() &&
                key != GLFW_KEY_SPACE && key != GLFW_KEY_W && key != GLFW_KEY_A &&
                key != GLFW_KEY_S && key != GLFW_KEY_D && key != GLFW_KEY_LEFT_SHIFT &&
                key != GLFW_KEY_Z && key != GLFW_KEY_Y && key != GLFW_KEY_X &&
                key != GLFW_KEY_C && key != GLFW_KEY_V) {
            return;
        }

        if (key == GLFW_KEY_P && action == GLFW_PRESS) {
            isEditorMode = !isEditorMode;
            if (isEditorMode) {
                glfwSetWindowTitle(glfwWindow, "Engine - EDITOR MODE");
                glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
            } else {
                glfwSetWindowTitle(glfwWindow, "Engine - PLAY MODE");
                glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                firstMouse = true;
                selectedBox = null;
                exitMultiSelect();
                // Teleport to the placed Spawn Point (if any) on entering play.
                applySpawnPointIfPresent();
            }
        }

        if (isEditorMode && key == GLFW_KEY_B && action == GLFW_PRESS) {
            // Toggle box-select mode. Press once to arm, drag the mouse to draw the
            // selection rect; release to commit. Press B again to cancel.
            boxSelectMode = !boxSelectMode;
            boxDragging = false;
            if (boxSelectMode) {
                Logger.info("Box-select armed — drag to select multiple objects");
            } else {
                Logger.info("Box-select cancelled");
            }
            return;
        }

        if (isEditorMode) {
            if (key == GLFW_KEY_Z && action == GLFW_PRESS) {
                if (glfwGetKey(glfwWindow, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS) {
                    if (undoRedoManager.canUndo()) {
                        undoRedoManager.undo();
                        Logger.info("Undo performed");
                        selectedBox = null;
                    } else {
                        Logger.warn("Nothing to undo");
                    }
                }
            }

            if (key == GLFW_KEY_Y && action == GLFW_PRESS) {
                if (glfwGetKey(glfwWindow, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS) {
                    if (undoRedoManager.canRedo()) {
                        undoRedoManager.redo();
                        Logger.info("Redo performed");
                        selectedBox = null;
                    } else {
                        Logger.warn("Nothing to redo");
                    }
                }
            }

            if (key == GLFW_KEY_X && action == GLFW_PRESS) {
                if (selectedBox != null && selectedBox != sunBlock) {
                    DeleteObjectCommand command = new DeleteObjectCommand(world, selectedBox);
                    undoRedoManager.execute(command);
                    selectedBox = null;
                    draggingAxis = -1;
                    Logger.info("Object deleted and recorded for undo");
                }
            }

            if (key == GLFW_KEY_C && action == GLFW_PRESS) {
                if (glfwGetKey(glfwWindow, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS
                        && selectedBox != null && selectedBox != sunBlock) {
                    clipboardGameObject = LevelLoader.deepCopy(selectedBox);
                    Transform t = selectedBox.transform;
                    Logger.info("Copied object " + selectedBox.name
                            + " at X: " + t.x + ", Y: " + t.y + ", Z: " + t.z);
                }
            }

            if (key == GLFW_KEY_V && action == GLFW_PRESS) {
                if (glfwGetKey(glfwWindow, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS
                        && clipboardGameObject != null) {
                    GameObject pasted = LevelLoader.deepCopy(clipboardGameObject);
                    pasted.uuid = UUID.randomUUID().toString();
                    pasted.transform.x += 0.5f;
                    pasted.transform.gameObject = pasted;
                    world.entities.add(pasted);
                    selectedBox = pasted;
                    Logger.info("Pasted object " + pasted.name + " (uuid " + pasted.uuid + ")");
                }
            }
        }

        if (key == GLFW_KEY_SPACE && action == GLFW_PRESS) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastSpacePressTime < Config.DOUBLE_TAP_DELAY) {
                player.isFlying = !player.isFlying;
                player.xVel = 0;
                player.yVel = 0;
                player.zVel = 0;
            }
            lastSpacePressTime = currentTime;
        }
    }

    @Override
    public void onMouseMove(double xpos, double ypos) {
        double localX = isEditorMode ? xpos - uiManager.viewportX : xpos;
        double localY = isEditorMode ? ypos - uiManager.viewportY : ypos;

        if (boxDragging) {
            boxEndX = localX;
            boxEndY = localY;
            return;
        }

        if (isEditorMode && draggingAxis != -1 && selectedBox != null) {
            double deltaY = lastDragMouseY - localY;
            double deltaX = localX - lastDragMouseX;

            float dist = (float) Math.sqrt(
                    Math.pow(player.x - selectedBox.transform.x, 2) +
                            Math.pow(player.y - selectedBox.transform.y, 2) +
                            Math.pow(player.z - selectedBox.transform.z, 2)
            );
            float sensitivity = dist * Config.GIZMO_SENSITIVITY;
            boolean isShift = glfwGetKey(glfwWindow, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;

            updateTransformDrag(deltaY, deltaX, sensitivity, isShift);
            lastDragMouseY = localY;
            lastDragMouseX = localX;
            return;
        }

        if (isEditorMode && !isPanning) {
            lastMouseX = localX;
            lastMouseY = localY;
            return;
        }

        if (firstMouse) {
            lastMouseX = localX;
            lastMouseY = localY;
            firstMouse = false;
        }

        if (!isEditorMode || isPanning) {
            player.yaw += (float) ((localX - lastMouseX) * Config.MOUSE_LOOK_SENSITIVITY);
            player.pitch -= (float) ((lastMouseY - localY) * Config.MOUSE_LOOK_SENSITIVITY);
            if (player.pitch > Config.PITCH_CLAMP) player.pitch = Config.PITCH_CLAMP;
            if (player.pitch < -Config.PITCH_CLAMP) player.pitch = -Config.PITCH_CLAMP;
        }

        lastMouseX = localX;
        lastMouseY = localY;
    }

    // Project mouse delta onto the screen-space direction of a world axis,
    // given the camera's current yaw and pitch. Returns a signed scalar that's
    // the amount the user "dragged along" the axis on screen. With this, the
    // gizmo behaves correctly regardless of which way the camera is facing
    // (the previous code assumed yaw=0, pitch=0 and broke as soon as the
    // player looked anywhere else).
    private float axisScreenDelta(int axis, double deltaX, double deltaY) {
        float yawRad = (float) Math.toRadians(player.yaw);
        float pitchRad = (float) Math.toRadians(player.pitch);
        float cy = (float) Math.cos(yawRad), sy = (float) Math.sin(yawRad);
        float cp = (float) Math.cos(pitchRad), sp = (float) Math.sin(pitchRad);
        // Camera basis: forward=(sy*cp,-sp,-cy*cp), right=(cy,0,sy), up=right×forward.
        // Projection of a unit world axis onto (right, up):
        float sxProj, syProj;
        if (axis == 0) {         // world X
            sxProj = cy;
            syProj = sy * sp;
        } else if (axis == 1) {  // world Y
            sxProj = 0f;
            syProj = cp;
        } else {                 // world Z
            sxProj = sy;
            syProj = -cy * sp;
        }
        return (float) deltaX * sxProj + (float) deltaY * syProj;
    }

    private void updateTransformDrag(double deltaY, double deltaX, float sensitivity, boolean isShift) {
        if (selectedBox.transformMode == 0) {
            if (draggingAxis >= 0 && draggingAxis <= 2) {
                float drag = axisScreenDelta(draggingAxis, deltaX, deltaY) * sensitivity;
                if (draggingAxis == 0) {
                    rawDragX += drag;
                    selectedBox.transform.x = isShift ? Math.round(rawDragX) : rawDragX;
                } else if (draggingAxis == 1) {
                    rawDragY += drag;
                    selectedBox.transform.y = isShift
                            ? (Math.round(rawDragY + 0.5f) - 0.5f)
                            : rawDragY;
                } else {
                    rawDragZ += drag;
                    selectedBox.transform.z = isShift ? Math.round(rawDragZ) : rawDragZ;
                }
            }
        } else if (selectedBox.transformMode == 1) {
            // Rotate around the WORLD axis matching the dragged ring, not the
            // object's local axis. Compose the delta rotation with the object's
            // current orientation via matrix multiplication, then re-extract
            // Euler angles so glRotatef rendering still produces the same pose.
            float angleX = (draggingAxis == 0) ? (float) (deltaY * Config.GIZMO_ROTATION_SPEED) : 0;
            float angleY = (draggingAxis == 1) ? (float) (deltaX * Config.GIZMO_ROTATION_SPEED) : 0;
            float angleZ = (draggingAxis == 2) ? (float) (deltaX * Config.GIZMO_ROTATION_SPEED) : 0;
            if (angleX != 0 || angleY != 0 || angleZ != 0) {
                Matrix4f delta = new Matrix4f()
                        .rotateX((float) Math.toRadians(angleX))
                        .rotateY((float) Math.toRadians(angleY))
                        .rotateZ((float) Math.toRadians(angleZ));
                Matrix4f objRot = new Matrix4f()
                        .rotateX((float) Math.toRadians(selectedBox.transform.rotX))
                        .rotateY((float) Math.toRadians(selectedBox.transform.rotY))
                        .rotateZ((float) Math.toRadians(selectedBox.transform.rotZ));
                Matrix4f combined = new Matrix4f(delta).mul(objRot);
                Vector3f euler = new Vector3f();
                combined.getEulerAnglesXYZ(euler);
                selectedBox.transform.rotX = (float) Math.toDegrees(euler.x);
                selectedBox.transform.rotY = (float) Math.toDegrees(euler.y);
                selectedBox.transform.rotZ = (float) Math.toDegrees(euler.z);
            }
        } else if (selectedBox.transformMode == 2) {
            float scaleSpeed = sensitivity * Config.GIZMO_SCALE_SPEED;

            if (draggingAxis == 3) {
                float sAmt = (float) (deltaX + deltaY) * scaleSpeed;
                selectedBox.transform.scaleX += sAmt;
                selectedBox.transform.scaleY += sAmt;
                selectedBox.transform.scaleZ += sAmt;
            } else if (draggingAxis >= 0 && draggingAxis <= 2) {
                float drag = axisScreenDelta(draggingAxis, deltaX, deltaY) * scaleSpeed;
                if (draggingAxis == 0) selectedBox.transform.scaleX += drag;
                else if (draggingAxis == 1) selectedBox.transform.scaleY += drag;
                else selectedBox.transform.scaleZ += drag;
            }

            if (selectedBox.transform.scaleX < Config.GIZMO_SCALE_MIN) selectedBox.transform.scaleX = Config.GIZMO_SCALE_MIN;
            if (selectedBox.transform.scaleY < Config.GIZMO_SCALE_MIN) selectedBox.transform.scaleY = Config.GIZMO_SCALE_MIN;
            if (selectedBox.transform.scaleZ < Config.GIZMO_SCALE_MIN) selectedBox.transform.scaleZ = Config.GIZMO_SCALE_MIN;
        }
    }

    @Override
    public void onMouseClick(int button, int action, double mx, double my) {
        if (isEditorMode) {
            if (action == GLFW_RELEASE) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    if (draggingAxis != -1 && selectedBox != null) {
                        TransformCommand command = new TransformCommand(selectedBox);
                        command.captureAfterState();

                        if (command.hasChanged()) {
                            undoRedoManager.execute(command);
                            Logger.info("Transform recorded: " + selectedBox.name);
                        }
                    }
                    draggingAxis = -1;
                }
                if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    isPanning = false;
                }
            }

            if (!uiManager.isViewportHovered) return;

            double localX = mx - uiManager.viewportX;
            double localY = my - uiManager.viewportY;

            if (action == GLFW_PRESS) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    if (boxSelectMode) {
                        boxDragging = true;
                        boxStartX = localX;
                        boxStartY = localY;
                        boxEndX = localX;
                        boxEndY = localY;
                    } else {
                        handleLeftClick(localX, localY);
                    }
                } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    isPanning = true;
                }
            }

            if (action == GLFW_RELEASE && button == GLFW_MOUSE_BUTTON_LEFT && boxDragging) {
                boxDragging = false;
                finalizeBoxSelect();
                boxSelectMode = false;
            }

        } else {
            if (action == GLFW_PRESS && player.isFlying) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    GameObject target = player.getLookTarget(world, Config.RAYCAST_RANGE);
                    if (target != null && target != sunBlock) {
                        DeleteObjectCommand command = new DeleteObjectCommand(world, target);
                        undoRedoManager.execute(command);
                        Logger.debug("Deleted object: " + target.name);
                    }
                } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    spawnBlockInFront();
                }
            }
        }
    }

    private void handleLeftClick(double localX, double localY) {
        int hit = Gizmo.getHitAxis(player, selectedBox, localX, localY, viewportW, viewportH);
        if (hit != -1) {
            draggingAxis = hit;
            lastDragMouseY = localY;
            lastDragMouseX = localX;
            snapTransform();
            rawDragX = selectedBox.transform.x;
            rawDragY = selectedBox.transform.y;
            rawDragZ = selectedBox.transform.z;
        } else {
            GameObject b = player.getEntityAtMouse(world, localX, localY, viewportW, viewportH);
            if (b != null) {
                // Single click on a real object — exit any multi-selection.
                exitMultiSelect();
                selectedBox = b;
            } else {
                exitMultiSelect();
                selectedBox = null;
                draggingAxis = -1;
            }
        }
    }

    private void exitMultiSelect() {
        selectedObjects.clear();
        groupProxy = null;
    }

    // Warp the player to the first Spawn Point in the level, or to (0,
    // PLAYER_START_Y, 0) if no Spawn Point is placed. Used on play-mode entry
    // and on Win Zone trigger.
    private void applySpawnPointIfPresent() {
        for (GameObject obj : world.entities) {
            MeshRenderer mr = obj.getComponent(MeshRenderer.class);
            if (mr != null && World.SPAWN_POINT_KEY.equals(mr.modelPath)) {
                player.x = obj.transform.x;
                player.y = obj.transform.y + Config.PLAYER_START_Y;
                player.z = obj.transform.z;
                player.xVel = player.yVel = player.zVel = 0f;
                player.isGrounded = false;
                Logger.info("Spawned at Spawn Point: ("
                        + player.x + "," + player.y + "," + player.z + ")");
                return;
            }
        }
        // No Spawn Point — fall back to the origin so the player can never get
        // stuck inside a triggered Win Zone forever.
        player.x = 0f;
        player.y = Config.PLAYER_START_Y;
        player.z = 0f;
        player.xVel = player.yVel = player.zVel = 0f;
        player.isGrounded = false;
    }

    // In play mode, check if the player's camera is inside any Win Zone's AABB
    // (the scaled trigger volume). First hit warps the player back to spawn.
    private void checkWinZones() {
        for (GameObject obj : world.entities) {
            boolean trigger = obj.getComponent(WinZoneComponent.class) != null;
            if (!trigger) {
                MeshRenderer mr = obj.getComponent(MeshRenderer.class);
                trigger = mr != null && World.WIN_ZONE_KEY.equals(mr.modelPath);
            }
            if (!trigger) continue;
            if (player.x >= obj.getMinX() && player.x <= obj.getMaxX() &&
                    player.y >= obj.getMinY() && player.y <= obj.getMaxY() &&
                    player.z >= obj.getMinZ() && player.z <= obj.getMaxZ()) {
                Logger.info("Win Zone reached - " + obj.name);
                GameStateManager.requestWin();
                applySpawnPointIfPresent();
                return;
            }
        }
    }

    // Called when the box-select drag ends. Projects every entity's center to
    // screen, collects those inside the rect, and sets up a transient group
    // proxy that drives the gizmo for the whole selection.
    private void finalizeBoxSelect() {
        double minX = Math.min(boxStartX, boxEndX);
        double maxX = Math.max(boxStartX, boxEndX);
        double minY = Math.min(boxStartY, boxEndY);
        double maxY = Math.max(boxStartY, boxEndY);

        // Tiny drag = treat as click; ignore.
        if (Math.abs(maxX - minX) < 4 && Math.abs(maxY - minY) < 4) {
            return;
        }

        Matrix4f viewProj = buildViewProjection();
        int[] viewport = new int[]{0, 0, viewportW, viewportH};
        Vector3f winPos = new Vector3f();

        List<GameObject> hits = new ArrayList<>();
        for (GameObject obj : world.entities) {
            if (obj == sunBlock) continue;
            // Use the center of the AABB for the inclusion test.
            float cx = (obj.getMinX() + obj.getMaxX()) * 0.5f;
            float cy = (obj.getMinY() + obj.getMaxY()) * 0.5f;
            float cz = (obj.getMinZ() + obj.getMaxZ()) * 0.5f;
            viewProj.project(cx, cy, cz, viewport, winPos);
            // winPos.z in [0,1] when in front of near/far plane; flip Y to match
            // the viewport-local mouse coords (top=0).
            if (winPos.z < 0f || winPos.z > 1f) continue;
            double sx = winPos.x;
            double sy = viewportH - winPos.y;
            if (sx >= minX && sx <= maxX && sy >= minY && sy <= maxY) {
                hits.add(obj);
            }
        }

        if (hits.isEmpty()) {
            exitMultiSelect();
            selectedBox = null;
            Logger.info("Box-select: 0 objects");
            return;
        }
        if (hits.size() == 1) {
            // Just one — treat as single-select, no proxy.
            exitMultiSelect();
            selectedBox = hits.get(0);
            Logger.info("Box-select resolved to single object: " + selectedBox.name);
            return;
        }

        selectedObjects = hits;
        // Create a transient proxy at the group center. The existing gizmo code
        // operates on `selectedBox`; the proxy is just its driver. Each frame we
        // diff the proxy's transform vs prevProxy* and apply the delta to every
        // real selected object, then reset the proxy.
        float[] center = computeGroupCenter();
        groupProxy = new GameObject(center[0], center[1], center[2]);
        groupProxy.name = "(Group: " + hits.size() + " objects)";
        groupProxy.transformMode = 0; // start in move mode
        selectedBox = groupProxy;
        recordProxyState();
        Logger.info("Box-select: " + hits.size() + " objects grouped");
    }

    private float[] computeGroupCenter() {
        float sx = 0, sy = 0, sz = 0;
        for (GameObject o : selectedObjects) {
            sx += o.transform.x;
            sy += o.transform.y;
            sz += o.transform.z;
        }
        int n = Math.max(selectedObjects.size(), 1);
        return new float[]{ sx / n, sy / n, sz / n };
    }

    private void recordProxyState() {
        if (groupProxy == null) return;
        prevProxyX = groupProxy.transform.x;
        prevProxyY = groupProxy.transform.y;
        prevProxyZ = groupProxy.transform.z;
        prevProxyRotX = groupProxy.transform.rotX;
        prevProxyRotY = groupProxy.transform.rotY;
        prevProxyRotZ = groupProxy.transform.rotZ;
        prevProxyScaleX = groupProxy.transform.scaleX;
        prevProxyScaleY = groupProxy.transform.scaleY;
        prevProxyScaleZ = groupProxy.transform.scaleZ;
    }

    // Build a view*projection matrix in JOML matching the camera used during
    // rendering. Used to project world points to screen for box-select.
    private Matrix4f buildViewProjection() {
        float aspect = (float) viewportW / Math.max(viewportH, 1);
        Matrix4f proj = new Matrix4f().perspective(
                (float) Math.toRadians(Config.FOV), aspect,
                Config.Z_NEAR, Config.Z_FAR);
        Matrix4f view = new Matrix4f()
                .rotate((float) Math.toRadians(player.roll), 0, 0, 1)
                .rotate((float) Math.toRadians(player.pitch), 1, 0, 0)
                .rotate((float) Math.toRadians(player.yaw), 0, 1, 0)
                .translate(-player.x, -(player.y + player.cameraYOffset), -player.z);
        return proj.mul(view);
    }

    // After the gizmo drag has modified groupProxy.transform, propagate the
    // delta to every real object and reset the proxy. Called once per frame
    // in update() when a multi-selection is active. Handles translate, rotate
    // around group center, and scale around group center.
    private void applyGroupDeltaAndReset() {
        if (groupProxy == null || selectedObjects.size() < 2) return;

        float dx = groupProxy.transform.x - prevProxyX;
        float dy = groupProxy.transform.y - prevProxyY;
        float dz = groupProxy.transform.z - prevProxyZ;

        float drx = groupProxy.transform.rotX - prevProxyRotX;
        float dry = groupProxy.transform.rotY - prevProxyRotY;
        float drz = groupProxy.transform.rotZ - prevProxyRotZ;

        // Scale change as a multiplicative factor; guard against div-by-zero.
        float sfx = groupProxy.transform.scaleX / Math.max(Math.abs(prevProxyScaleX), 1e-6f);
        float sfy = groupProxy.transform.scaleY / Math.max(Math.abs(prevProxyScaleY), 1e-6f);
        float sfz = groupProxy.transform.scaleZ / Math.max(Math.abs(prevProxyScaleZ), 1e-6f);

        // Group center for rotate/scale pivot. Use prev center (where the proxy
        // last anchored) so the pivot doesn't drift mid-drag.
        float cx = prevProxyX, cy = prevProxyY, cz = prevProxyZ;

        boolean translated = dx != 0 || dy != 0 || dz != 0;
        boolean rotated   = drx != 0 || dry != 0 || drz != 0;
        boolean scaled    = sfx != 1 || sfy != 1 || sfz != 1;

        if (translated || rotated || scaled) {
            Matrix4f rotDelta = null;
            if (rotated) {
                rotDelta = new Matrix4f()
                        .rotateX((float) Math.toRadians(drx))
                        .rotateY((float) Math.toRadians(dry))
                        .rotateZ((float) Math.toRadians(drz));
            }

            for (GameObject o : selectedObjects) {
                if (translated) {
                    o.transform.x += dx;
                    o.transform.y += dy;
                    o.transform.z += dz;
                }

                if (scaled) {
                    // Pivot at group center: shift offset, scale, restore.
                    float ox = o.transform.x - cx;
                    float oy = o.transform.y - cy;
                    float oz = o.transform.z - cz;
                    o.transform.x = cx + ox * sfx;
                    o.transform.y = cy + oy * sfy;
                    o.transform.z = cz + oz * sfz;
                    o.transform.scaleX *= sfx;
                    o.transform.scaleY *= sfy;
                    o.transform.scaleZ *= sfz;
                }

                if (rotated) {
                    // Rotate the object's position offset around the group center.
                    Vector3f offset = new Vector3f(
                            o.transform.x - cx,
                            o.transform.y - cy,
                            o.transform.z - cz);
                    rotDelta.transformPosition(offset);
                    o.transform.x = cx + offset.x;
                    o.transform.y = cy + offset.y;
                    o.transform.z = cz + offset.z;

                    // Compose the delta with the object's own Euler rotation.
                    Matrix4f objRot = new Matrix4f()
                            .rotateX((float) Math.toRadians(o.transform.rotX))
                            .rotateY((float) Math.toRadians(o.transform.rotY))
                            .rotateZ((float) Math.toRadians(o.transform.rotZ));
                    Matrix4f combined = new Matrix4f(rotDelta).mul(objRot);
                    Vector3f euler = new Vector3f();
                    combined.getEulerAnglesXYZ(euler);
                    o.transform.rotX = (float) Math.toDegrees(euler.x);
                    o.transform.rotY = (float) Math.toDegrees(euler.y);
                    o.transform.rotZ = (float) Math.toDegrees(euler.z);
                }
            }
        }

        // Re-anchor the proxy's position at the new group center each frame so
        // translate gizmo and pivot stay correct. Keep rotation/scale at their
        // accumulated values so the inspector reflects what the user has done
        // — diffing against prev still gives the delta on the next change.
        float[] center = computeGroupCenter();
        groupProxy.transform.x = center[0];
        groupProxy.transform.y = center[1];
        groupProxy.transform.z = center[2];
        recordProxyState();
    }

    private void snapTransform() {
        snapX = selectedBox.transform.x;
        snapY = selectedBox.transform.y;
        snapZ = selectedBox.transform.z;
        snapRotX = selectedBox.transform.rotX;
        snapRotY = selectedBox.transform.rotY;
        snapRotZ = selectedBox.transform.rotZ;
        snapScaleX = selectedBox.transform.scaleX;
        snapScaleY = selectedBox.transform.scaleY;
        snapScaleZ = selectedBox.transform.scaleZ;
    }

    private void spawnBlockInFront() {
        float yawRad = (float) Math.toRadians(player.yaw);
        float pitchRad = (float) Math.toRadians(player.pitch);
        float dx = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float dy = (float) (-Math.sin(pitchRad));
        float dz = (float) (-Math.cos(yawRad) * Math.cos(pitchRad));

        GameObject hitBlock = null;
        float hitDist = 0;
        List<GameObject> nearby = world.getNearbyEntities(player.x, player.z);

        for (float i = 0; i < Config.RAYCAST_RANGE; i += Config.RAYCAST_STEP) {
            float tx = player.x + (dx * i);
            float ty = player.y + (dy * i);
            float tz = player.z + (dz * i);
            for (GameObject b : nearby) {
                if (tx > b.getMinX() && tx < b.getMaxX() &&
                        ty > b.getMinY() && ty < b.getMaxY() &&
                        tz > b.getMinZ() && tz < b.getMaxZ()) {
                    hitBlock = b;
                    hitDist = i;
                    break;
                }
            }
            if (hitBlock != null) break;
        }

        float px, py, pz;
        if (hitBlock != null) {
            px = player.x + (dx * (hitDist - Config.RAYCAST_PLACEMENT_OFFSET));
            py = player.y + (dy * (hitDist - Config.RAYCAST_PLACEMENT_OFFSET));
            pz = player.z + (dz * (hitDist - Config.RAYCAST_PLACEMENT_OFFSET));
        } else {
            px = player.x + dx * 3.0f;
            py = player.y + dy * 3.0f;
            pz = player.z + dz * 3.0f;
        }

        GameObject newObj = new GameObject((float) Math.round(px),
                (float) Math.floor(py + Config.RAYCAST_PLACEMENT_HEIGHT_OFFSET) - 0.5f,
                (float) Math.round(pz));
        newObj.addComponent(new MeshRenderer());
        world.entities.add(newObj);
    }

    private void updateProjection(int w, int h) {
        // Build the perspective projection in JOML; the result is written
        // into the shared projMatrix FloatBuffer used by every shader and
        // also into RenderState for downstream renderers (selection,
        // gizmo, skybox, etc.) to read.
        float aspect = (float) w / (float) h;
        tmpProj.identity().perspective(
                (float) Math.toRadians(player.currentFov),
                aspect,
                Config.Z_NEAR,
                Config.Z_FAR);
        projMatrix.clear();
        tmpProj.get(projMatrix);
    }

    // Draws the box-select rectangle in viewport-local pixels.
    private void drawBoxSelectOverlay() {
        // Convert pixel coords (top-left origin) to NDC; Renderer2D takes
        // NDC directly so the GL matrix stack stays untouched.
        float x0 = Renderer2D.pxX((float) Math.min(boxStartX, boxEndX), viewportW);
        float x1 = Renderer2D.pxX((float) Math.max(boxStartX, boxEndX), viewportW);
        // pixel-Y grows downward, NDC-Y grows upward → swap min/max so the
        // resulting NDC rectangle stays consistent (x0,y0 = bottom-left).
        float y0 = Renderer2D.pxY((float) Math.max(boxStartY, boxEndY), viewportH);
        float y1 = Renderer2D.pxY((float) Math.min(boxStartY, boxEndY), viewportH);

        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        GLCompat.lineWidth(1.5f);

        Renderer2D.fillRect(x0, y0, x1, y1, 0.3f, 0.6f, 1.0f, 0.15f);
        Renderer2D.outlineRect(x0, y0, x1, y1, 0.4f, 0.75f, 1.0f, 1.0f);

        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    private void drawCrosshair() {
        glDisable(GL_DEPTH_TEST);
        glPointSize(4.0f);
        float a = isEditorMode ? 1.0f : 0.5f;
        float v = isEditorMode ? 1.0f : 0.6f;
        Renderer2D.drawPoint(0f, 0f, v, v, v, a);
        glEnable(GL_DEPTH_TEST);
    }

}