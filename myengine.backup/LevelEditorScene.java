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

class LevelEditorScene extends Scene {
    public GameObject selectedBox = null;
    public GameObject sunBlock;
    public float sunIntensity = 0.5f;

    private UIManager uiManager = new UIManager();
    private Shader defaultShader;

    // --- SHADOW MAP FIELDS ---
    private Shader depthShader;
    private ShadowMapFBO shadowFBO;

    // Matrix Buffers
    private FloatBuffer lightProjMatrix = BufferUtils.createFloatBuffer(16);
    private FloatBuffer lightViewMatrix = BufferUtils.createFloatBuffer(16);
    private FloatBuffer tempModelMatrix = BufferUtils.createFloatBuffer(16);
    private FloatBuffer identityBoneMatrices = BufferUtils.createFloatBuffer(100 * 16);

    private Framebuffer framebuffer;
    public UndoRedoManager undoRedoManager = new UndoRedoManager();

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

        glEnable(GL_LIGHT0);
        float[] ambientLight = {0.4f, 0.4f, 0.4f, 1.0f};
        glLightModelfv(GL_LIGHT_MODEL_AMBIENT, ambientLight);

        glEnable(GL_COLOR_MATERIAL);
        glEnable(GL_TEXTURE_2D);
        glColorMaterial(GL_FRONT_AND_BACK, GL_AMBIENT_AND_DIFFUSE);

        defaultShader = new Shader("default.vert", "default.frag");
        depthShader = new Shader("depth.vert", "depth.frag");

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
        if (loaded != null && !loaded.isEmpty()) {
            world.entities = loaded;
            selectedBox = null;

            for (GameObject obj : world.entities) {
                if ("Sun".equals(obj.name)) {
                    sunBlock = obj;
                    break;
                }
            }
        }
        sunBlock = new GameObject(-10, 26, -40);
        sunBlock.name = "Sun";
        sunBlock.addComponent(new MeshRenderer());
        world.entities.add(sunBlock);

        GameObject b1 = new GameObject(0, -0.5f, -2);
        b1.addComponent(new MeshRenderer());
        world.entities.add(b1);

        // Seed a default Floor GameObject if the level doesn't already have one.
        boolean hasFloor = false;
        for (GameObject obj : world.entities) {
            MeshRenderer fmr = obj.getComponent(MeshRenderer.class);
            if (fmr != null && World.FLOOR_MESH_KEY.equals(fmr.modelPath)) {
                hasFloor = true;
                break;
            }
        }
        if (!hasFloor) {
            GameObject floor = new GameObject(0, 0, 0);
            floor.name = "Floor";
            MeshRenderer mrFloor = new MeshRenderer();
            mrFloor.modelPath = World.FLOOR_MESH_KEY;
            mrFloor.texturePath = "";
            mrFloor.mesh = AssetPool.getMesh(World.FLOOR_MESH_KEY);
            mrFloor.material.setColor(0.5f, 0.5f, 0.5f);
            floor.addComponent(mrFloor);
            world.entities.add(floor);
            Logger.info("Seeded default Floor plane");
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
        world.updateGrid();

        if (isEditorMode) {
            boolean[] modifiedKeys = keys.clone();
            if (draggingAxis != -1) {
                (modifiedKeys)[GLFW_KEY_LEFT_SHIFT] = false;
            }
            player.update(modifiedKeys, world, false, dt);
        } else {
            player.update(keys, world, false, dt);
        }

        for (GameObject obj : world.entities) {
            obj.update(dt);
        }
    }

    @Override
    public void render() {
        // =======================================================
        // PASS 1: SHADOW DEPTH MAP
        // =======================================================
        shadowFBO.bind();
        glClear(GL_DEPTH_BUFFER_BIT);

        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        float orthoSize = 30.0f;
        glOrtho(-orthoSize, orthoSize, -orthoSize, orthoSize, 1.0f, 150.0f);

        lightProjMatrix.clear();
        glGetFloatv(GL_PROJECTION_MATRIX, lightProjMatrix);

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        float sx = sunBlock.transform.x;
        float sy = sunBlock.transform.y;
        float sz = sunBlock.transform.z;

        float distance = (float) Math.sqrt(sx*sx + sy*sy + sz*sz);
        float pitch = (float) Math.toDegrees(Math.asin(sy / distance));
        float yaw = (float) Math.toDegrees(Math.atan2(sx, sz));

        glRotatef(pitch, 1.0f, 0.0f, 0.0f);
        glRotatef(-yaw, 0.0f, 1.0f, 0.0f);
        glTranslatef(-sx, -sy, -sz);

        lightViewMatrix.clear();
        glGetFloatv(GL_MODELVIEW_MATRIX, lightViewMatrix);

        glDisable(GL_TEXTURE_2D);
        glDisable(GL_LIGHTING);
        glColorMask(false, false, false, false);

        glDisable(GL_CULL_FACE);

        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(2.0f, 4.0f);

        depthShader.use();

        for (GameObject b : world.entities) {
            if (b == sunBlock) continue;

            MeshRenderer mr = b.getComponent(MeshRenderer.class);
            if (mr == null) continue;

            boolean meshIsSkinned = mr.mesh != null && mr.mesh.isSkinned;
            depthShader.setInt("isSkinned", meshIsSkinned ? 1 : 0);
            if (meshIsSkinned) {
                Animator anim = b.getComponent(Animator.class);
                FloatBuffer bones = (anim != null && anim.currentAnimation != null)
                        ? anim.matricesBuffer
                        : identityBoneMatrices;
                depthShader.setMat4Array("finalBonesMatrices", bones);
            }

            glPushMatrix();
            glTranslatef(b.transform.x, b.transform.y, b.transform.z);
            glRotatef(b.transform.rotX, 1, 0, 0);
            glRotatef(b.transform.rotY, 0, 1, 0);
            glRotatef(b.transform.rotZ, 0, 0, 1);
            glScalef(b.transform.scaleX, b.transform.scaleY, b.transform.scaleZ);

            if (mr.mesh != null) mr.mesh.render();
            else RenderUtils.drawCube();
            glPopMatrix();
        }

        depthShader.detach();

        glDisable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(0.0f, 0.0f);

        glColorMask(true, true, true, true);
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_LIGHTING);

        shadowFBO.unbind(screenWidth, screenHeight);

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

        glLoadIdentity();

        glRotatef(player.roll, 0.0f, 0.0f, 1.0f);
        glRotatef(player.pitch, 1.0f, 0.0f, 0.0f);
        glRotatef(player.yaw, 0.0f, 1.0f, 0.0f);

        glLightfv(GL_LIGHT0, GL_POSITION, new float[]{sunBlock.transform.x, sunBlock.transform.y, sunBlock.transform.z, 1.0f});
        glLightfv(GL_LIGHT0, GL_DIFFUSE, new float[]{sunIntensity, sunIntensity, sunIntensity, 1.0f});

        world.drawSkybox(skyboxTexture);
        glTranslatef(-player.x, -(player.y + player.cameraYOffset), -player.z);

        defaultShader.use();
        defaultShader.setInt("texture_diffuse", 0);
        defaultShader.setInt("shadowMap", 1);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, shadowFBO.getDepthTextureID());
        defaultShader.setMat4("lightProjection", lightProjMatrix);
        defaultShader.setMat4("lightView", lightViewMatrix);
        defaultShader.setVec3("lightPos", sunBlock.transform.x, sunBlock.transform.y, sunBlock.transform.z);
        glActiveTexture(GL_TEXTURE0);

        isHoveringX = isHoveringY = isHoveringZ = isHoveringCenter = false;
        GameObject highlighted = null;

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
        } else {
            highlighted = player.getLookTarget(world, Config.RAYCAST_RANGE);
        }

        // --- RENDER ENTITIES ---
        for (GameObject b : world.entities) {
            if (b == sunBlock && !isEditorMode) continue;

            MeshRenderer mr = b.getComponent(MeshRenderer.class);
            if (mr == null) continue;

            glPushMatrix();
            glLoadIdentity();
            glTranslatef(b.transform.x, b.transform.y, b.transform.z);
            glRotatef(b.transform.rotX, 1, 0, 0);
            glRotatef(b.transform.rotY, 0, 1, 0);
            glRotatef(b.transform.rotZ, 0, 0, 1);
            glScalef(b.transform.scaleX, b.transform.scaleY, b.transform.scaleZ);
            tempModelMatrix.clear();
            glGetFloatv(GL_MODELVIEW_MATRIX, tempModelMatrix);
            glPopMatrix();

            glPushMatrix();
            glTranslatef(b.transform.x, b.transform.y, b.transform.z);

            if (b == sunBlock) {
                glDisable(GL_LIGHTING);
                glColor3f(1, 1, 0);
                glBindTexture(GL_TEXTURE_2D, 0);
                defaultShader.detach();
                RenderUtils.drawCube();
                glEnable(GL_LIGHTING);
            } else {
                defaultShader.use();
                defaultShader.setInt("useTexture", 1);
                defaultShader.setFloat("sunIntensity", sunIntensity);
                defaultShader.setVec3("colorTint", mr.material.r, mr.material.g, mr.material.b);
                defaultShader.setMat4("modelMatrix", tempModelMatrix);

                boolean meshIsSkinned = mr.mesh != null && mr.mesh.isSkinned;
                defaultShader.setInt("isSkinned", meshIsSkinned ? 1 : 0);
                if (meshIsSkinned) {
                    Animator anim = b.getComponent(Animator.class);
                    FloatBuffer bones = (anim != null && anim.currentAnimation != null)
                            ? anim.matricesBuffer
                            : identityBoneMatrices;
                    defaultShader.setMat4Array("finalBonesMatrices", bones);
                }

                glRotatef(b.transform.rotX, 1, 0, 0);
                glRotatef(b.transform.rotY, 0, 1, 0);
                glRotatef(b.transform.rotZ, 0, 0, 1);
                glScalef(b.transform.scaleX, b.transform.scaleY, b.transform.scaleZ);

                glBindTexture(GL_TEXTURE_2D, mr.textureID != 0 ? mr.textureID : cubeTexture);

                if (mr.mesh != null) mr.mesh.render();
                else RenderUtils.drawCube();
            }
            glPopMatrix();
        }

        // --- BUG FIX: SHADER FIREWALL ---
        defaultShader.detach();
        glBindTexture(GL_TEXTURE_2D, 0);

        // --- SKELETON DEBUG OVERLAY ---
        if (uiManager.showBones.get() && selectedBox != null) {
            MeshRenderer selMr = selectedBox.getComponent(MeshRenderer.class);
            Animator animComp = selectedBox.getComponent(Animator.class);

            if (selMr != null && selMr.mesh != null && selMr.mesh.skeleton != null) {
                glPushMatrix();
                glTranslatef(selectedBox.transform.x, selectedBox.transform.y, selectedBox.transform.z);
                glRotatef(selectedBox.transform.rotX, 1, 0, 0);
                glRotatef(selectedBox.transform.rotY, 0, 1, 0);
                glRotatef(selectedBox.transform.rotZ, 0, 0, 1);
                glScalef(selectedBox.transform.scaleX, selectedBox.transform.scaleY, selectedBox.transform.scaleZ);

                if (animComp != null && animComp.currentAnimation != null) {
                    selMr.mesh.skeleton.drawAnimatedDebug(animComp.debugBoneTransforms);
                } else {
                    selMr.mesh.skeleton.drawDebug();
                }

                glPopMatrix();
            }
        }

        if (highlighted != null && highlighted != selectedBox) {
            SelectionBoxRenderer.drawSelectionBox(highlighted, false, -1, false, false, false, false);
        }
        if (selectedBox != null) {
            SelectionBoxRenderer.drawSelectionBox(selectedBox, true, draggingAxis,
                    isHoveringX, isHoveringY, isHoveringZ, isHoveringCenter);
        }

        drawCrosshair();

        if (isEditorMode) {
            framebuffer.unbind(screenWidth, screenHeight);
            uiManager.render(this, framebuffer.getTextureID());
        }
    }

    @Override
    public void destroy() {
        Logger.info("Destroying LevelEditorScene");

        uiManager.destroy();
        framebuffer.destroy();
        if(shadowFBO != null) shadowFBO.destroy();
        defaultShader.destroy();
        if(depthShader != null) depthShader.destroy();
        AssetPool.destroy();

        Logger.info("LevelEditorScene destroyed successfully");
    }

    @Override
    public void onKeyPress(int key, int action) {
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
                key != GLFW_KEY_Z && key != GLFW_KEY_Y && key != GLFW_KEY_X) {
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
            }
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

    private void updateTransformDrag(double deltaY, double deltaX, float sensitivity, boolean isShift) {
        if (selectedBox.transformMode == 0) {
            if (draggingAxis == 1) {
                rawDragY += (float) (deltaY * sensitivity);
                if (isShift) {
                    selectedBox.transform.y = (float) Math.round(rawDragY + 0.5f) - 0.5f;
                } else {
                    selectedBox.transform.y = rawDragY;
                }
            } else if (draggingAxis == 0) {
                rawDragX += (float) (deltaX * sensitivity);
                if (isShift) {
                    selectedBox.transform.x = (float) Math.round(rawDragX);
                } else {
                    selectedBox.transform.x = rawDragX;
                }
            } else if (draggingAxis == 2) {
                rawDragZ -= (float) (deltaY * sensitivity);
                if (isShift) {
                    selectedBox.transform.z = (float) Math.round(rawDragZ);
                } else {
                    selectedBox.transform.z = rawDragZ;
                }
            }
        } else if (selectedBox.transformMode == 1) {
            if (draggingAxis == 0) selectedBox.transform.rotX += (float) (deltaY * Config.GIZMO_ROTATION_SPEED);
            if (draggingAxis == 1) selectedBox.transform.rotY += (float) (deltaX * Config.GIZMO_ROTATION_SPEED);
            if (draggingAxis == 2) selectedBox.transform.rotZ += (float) (deltaX * Config.GIZMO_ROTATION_SPEED);
        } else if (selectedBox.transformMode == 2) {
            float scaleSpeed = sensitivity * Config.GIZMO_SCALE_SPEED;

            if (draggingAxis == 3) {
                float sAmt = (float) (deltaX + deltaY) * scaleSpeed;
                selectedBox.transform.scaleX += sAmt;
                selectedBox.transform.scaleY += sAmt;
                selectedBox.transform.scaleZ += sAmt;
            } else if (draggingAxis == 0) {
                selectedBox.transform.scaleX += (float) (deltaX * scaleSpeed);
            } else if (draggingAxis == 1) {
                selectedBox.transform.scaleY += (float) (deltaY * scaleSpeed);
            } else if (draggingAxis == 2) {
                selectedBox.transform.scaleZ -= (float) (deltaY * scaleSpeed);
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
                    handleLeftClick(localX, localY);
                } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    isPanning = true;
                }
            }

        } else {
            if (action == GLFW_PRESS) {
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
                selectedBox = b;
            } else {
                selectedBox = null;
                draggingAxis = -1;
            }
        }
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
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();

        float aspect = (float) w / (float) h;
        float fH = (float) Math.tan(Math.toRadians(player.currentFov / 2.0)) * Config.Z_NEAR;
        float fW = fH * aspect;

        glFrustum(-fW, fW, -fH, fH, Config.Z_NEAR, Config.Z_FAR);
        glMatrixMode(GL_MODELVIEW);
    }

    private void drawCrosshair() {
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(-1, 1, -1, 1, -1, 1);
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_LIGHTING);
        glDisable(GL_TEXTURE_2D);
        if (isEditorMode) glColor4f(1, 1, 1, 1);
        else glColor4f(0.6f, 0.6f, 0.6f, 0.5f);
        glPointSize(4.0f);
        glBegin(GL_POINTS);
        glVertex2f(0, 0);
        glEnd();
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_LIGHTING);
        glEnable(GL_TEXTURE_2D);
        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    }

}