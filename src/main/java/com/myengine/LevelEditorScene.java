package com.myengine;

import imgui.ImGui;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import java.util.List;

/**
 * Level editor scene for designing and editing game levels.
 * Features gizmos, viewport rendering, inspector panel, and asset browser.
 * Includes full undo/redo support for all editing operations.
 */
public class LevelEditorScene extends Scene {
    public GameObject selectedBox = null;
    public GameObject sunBlock;
    public float sunIntensity = 0.5f;

    private UIManager uiManager = new UIManager();
    private Shader defaultShader;
    private Framebuffer framebuffer;
    private UndoRedoManager undoRedoManager = new UndoRedoManager();

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

    /**
     * Create a new level editor scene.
     */
    public LevelEditorScene(int width, int height, long glfwWindow) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.viewportW = width;
        this.viewportH = height;
        this.glfwWindow = glfwWindow;
    }

    @Override
    public void init() {
        Logger.info("Initializing LevelEditorScene");

        glEnable(GL_LIGHT0);
        float[] ambientLight = {0.4f, 0.4f, 0.4f, 1.0f};
        glLightModelfv(GL_LIGHT_MODEL_AMBIENT, ambientLight);

        glEnable(GL_COLOR_MATERIAL);
        glEnable(GL_TEXTURE_2D);
        glColorMaterial(GL_FRONT_AND_BACK, GL_AMBIENT_AND_DIFFUSE);

        defaultShader = new Shader("default.vert", "default.frag");
        framebuffer = new Framebuffer(screenWidth, screenHeight);

        cubeTexture = AssetPool.getTexture("pwood.jpg");
        skyboxTexture = Texture.loadCubemap(new String[]{"px.png", "nx.png", "py.png", "ny.png", "pz.png", "nz.png"});

        // Create sun
        sunBlock = new GameObject(-10, 26, -40);
        sunBlock.name = "Sun";
        sunBlock.addComponent(new MeshRenderer());
        world.entities.add(sunBlock);

        // Create default block
        GameObject b1 = new GameObject(0, -0.5f, -2);
        b1.addComponent(new MeshRenderer());
        world.entities.add(b1);

        uiManager.init(glfwWindow);

        glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        glfwSetWindowTitle(glfwWindow, "Engine - EDITOR MODE");

        Logger.info("LevelEditorScene initialized successfully");
    }

    @Override
    public void update(double dt, boolean[] keys) {
        boolean isTyping = ImGui.getIO().getWantCaptureKeyboard();

        // Check for R key press during drag to cancel
        if (isEditorMode && draggingAxis != -1 && selectedBox != null) {
            if (keys[GLFW_KEY_R]) {
                // Cancel the current drag
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

        // Only update player if active
        boolean isActive = uiManager.isViewportHovered || isPanning || draggingAxis != -1;

        if (isEditorMode && isActive && !isTyping) {
            player.update(keys, world, false);
        } else if (!isEditorMode) {
            player.update(keys, world, false);
        }

        world.updateGrid();

        for (GameObject obj : world.entities) {
            obj.update(dt);
        }
    }

    @Override
    public void render() {
        if (isEditorMode) {
            // Handle framebuffer resizing for viewport
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
        glRotatef(player.pitch, 1.0f, 0.0f, 0.0f);
        glRotatef(player.yaw, 0.0f, 1.0f, 0.0f);

        glLightfv(GL_LIGHT0, GL_POSITION, new float[]{sunBlock.transform.x, sunBlock.transform.y, sunBlock.transform.z, 1.0f});
        glLightfv(GL_LIGHT0, GL_DIFFUSE, new float[]{sunIntensity, sunIntensity, sunIntensity, 1.0f});

        world.drawSkybox(skyboxTexture);
        glTranslatef(-player.x, -player.y, -player.z);
        world.drawFloor();

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

        // Render all game objects
        for (GameObject b : world.entities) {
            if (b == sunBlock && !isEditorMode) continue;

            MeshRenderer mr = b.getComponent(MeshRenderer.class);
            if (mr == null) continue;

            glPushMatrix();
            glTranslatef(b.transform.x, b.transform.y, b.transform.z);

            if (b == sunBlock) {
                glDisable(GL_LIGHTING);
                glColor3f(1, 1, 0);
                glBindTexture(GL_TEXTURE_2D, 0);
                RenderUtils.drawCube();
                glEnable(GL_LIGHTING);
            } else {
                glRotatef(b.transform.rotX, 1, 0, 0);
                glRotatef(b.transform.rotY, 0, 1, 0);
                glRotatef(b.transform.rotZ, 0, 0, 1);
                glScalef(b.transform.scaleX, b.transform.scaleY, b.transform.scaleZ);

                glBindTexture(GL_TEXTURE_2D, mr.textureID != 0 ? mr.textureID : cubeTexture);

                if (mr.mesh != null) {
                    glDisable(GL_LIGHTING);
                    defaultShader.use();
                    defaultShader.setFloat("sunIntensity", sunIntensity);
                    defaultShader.setVec3("colorTint", mr.material.r, mr.material.g, mr.material.b);

                    mr.mesh.render();

                    defaultShader.detach();
                    glEnable(GL_LIGHTING);
                } else {
                    glColor3f(mr.material.r, mr.material.g, mr.material.b);
                    RenderUtils.drawCube();
                }
            }
            glPopMatrix();
        }

        // Draw selection highlights
        if (highlighted != null && highlighted != selectedBox) {
            SelectionBoxRenderer.drawSelectionBox(highlighted, false, -1, false, false, false, false);
        }
        if (selectedBox != null) {
            SelectionBoxRenderer.drawSelectionBox(selectedBox, true, draggingAxis,
                    isHoveringX, isHoveringY, isHoveringZ, isHoveringCenter);
        }

        drawCrosshair();
        if (!isEditorMode) drawViewmodel();

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
        defaultShader.destroy();
        AssetPool.destroy();

        Logger.info("LevelEditorScene destroyed successfully");
    }

    @Override
    public void onKeyPress(int key, int action) {
        if (isEditorMode && ImGui.getIO().getWantCaptureKeyboard()) return;

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
            // Undo (Ctrl+Z)
            if (key == GLFW_KEY_Z && action == GLFW_PRESS) {
                if (glfwGetKey(glfwWindow, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS) {
                    if (undoRedoManager.canUndo()) {
                        undoRedoManager.undo();
                        Logger.info("Undo performed");
                        selectedBox = null; // Deselect to avoid referencing deleted objects
                    } else {
                        Logger.warn("Nothing to undo");
                    }
                }
            }

            // Redo (Ctrl+Y)
            if (key == GLFW_KEY_Y && action == GLFW_PRESS) {
                if (glfwGetKey(glfwWindow, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS) {
                    if (undoRedoManager.canRedo()) {
                        undoRedoManager.redo();
                        Logger.info("Redo performed");
                        selectedBox = null; // Deselect to avoid referencing deleted objects
                    } else {
                        Logger.warn("Nothing to redo");
                    }
                }
            }

            // Cancel drag (R key) - for when not actively dragging with mouse
            if (key == GLFW_KEY_R && action == GLFW_PRESS) {
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
                    Logger.info("Transform cancelled");
                }
            }

            // Delete selected object (X key)
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

        // Double-tap space to toggle flying
        if (key == GLFW_KEY_SPACE && action == GLFW_PRESS) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastSpacePressTime < Config.DOUBLE_TAP_DELAY) {
                player.isFlying = !player.isFlying;
                player.yVelocity = 0;
            }
            lastSpacePressTime = currentTime;
        }
    }

    @Override
    public void onMouseMove(double xpos, double ypos) {
        double localX = isEditorMode ? xpos - uiManager.viewportX : xpos;
        double localY = isEditorMode ? ypos - uiManager.viewportY : ypos;

        // Handle gizmo dragging
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

        // Handle camera panning
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

    /**
     * Update transform based on drag input.
     */
    private void updateTransformDrag(double deltaY, double deltaX, float sensitivity, boolean isShift) {
        if (selectedBox.transformMode == 0) {
            // Move mode
            if (draggingAxis == 1) {
                rawDragY += (float) (deltaY * sensitivity);
                selectedBox.transform.y = isShift ? rawDragY : (float) Math.round(rawDragY + 0.5f) - 0.5f;
            } else if (draggingAxis == 0) {
                rawDragX += (float) (deltaX * sensitivity);
                selectedBox.transform.x = isShift ? rawDragX : (float) Math.round(rawDragX);
            } else if (draggingAxis == 2) {
                rawDragZ -= (float) (deltaY * sensitivity);
                selectedBox.transform.z = isShift ? rawDragZ : (float) Math.round(rawDragZ);
            }
        } else if (selectedBox.transformMode == 1) {
            // Rotate mode
            if (draggingAxis == 0) selectedBox.transform.rotX += (float) (deltaY * Config.GIZMO_ROTATION_SPEED);
            if (draggingAxis == 1) selectedBox.transform.rotY += (float) (deltaX * Config.GIZMO_ROTATION_SPEED);
            if (draggingAxis == 2) selectedBox.transform.rotZ += (float) (deltaX * Config.GIZMO_ROTATION_SPEED);
        } else if (selectedBox.transformMode == 2) {
            // Scale mode
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

            // Clamp scale to minimum
            if (selectedBox.transform.scaleX < Config.GIZMO_SCALE_MIN) selectedBox.transform.scaleX = Config.GIZMO_SCALE_MIN;
            if (selectedBox.transform.scaleY < Config.GIZMO_SCALE_MIN) selectedBox.transform.scaleY = Config.GIZMO_SCALE_MIN;
            if (selectedBox.transform.scaleZ < Config.GIZMO_SCALE_MIN) selectedBox.transform.scaleZ = Config.GIZMO_SCALE_MIN;
        }
    }

    @Override
    public void onMouseClick(int button, int action, double mx, double my) {
        if (isEditorMode) {
            // Always process mouse releases to prevent stuck states
            if (action == GLFW_RELEASE) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    // Finish drag and record undo command
                    if (draggingAxis != -1 && selectedBox != null) {
                        TransformCommand command = new TransformCommand(selectedBox);
                        command.captureAfterState(); // Capture the new state after dragging

                        // Only record if something actually changed
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
            // Play mode
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

    /**
     * Handle left mouse click in editor mode.
     */
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

    /**
     * Snap transform for undo/redo.
     */
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

    /**
     * Spawn a block in front of the player in play mode.
     */
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

    /**
     * Update projection matrix for current viewport.
     */
    private void updateProjection(int w, int h) {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();

        float aspect = (float) w / (float) h;
        float fH = (float) Math.tan(Math.toRadians(Config.FOV / 2.0)) * Config.Z_NEAR;
        float fW = fH * aspect;

        glFrustum(-fW, fW, -fH, fH, Config.Z_NEAR, Config.Z_FAR);
        glMatrixMode(GL_MODELVIEW);
    }

    /**
     * Draw crosshair in the center of the screen.
     */
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

    /**
     * Draw a first-person weapon viewmodel.
     */
    private void drawViewmodel() {
        glPushMatrix();
        glLoadIdentity();
        glTranslatef(0.45f, -1.1f, -1.0f);
        glRotatef(-15, 0, 1, 0);
        glEnable(GL_LIGHTING);
        glDisable(GL_TEXTURE_2D);
        glColor3f(0.25f, 0.25f, 0.25f);
        glPushMatrix();
        glScalef(0.04f, 1.2f, 0.04f);
        RenderUtils.drawCube();
        glPopMatrix();
        glTranslatef(0, 0.7f, 0);
        glColor3f(0.55f, 0.55f, 0.55f);
        glPushMatrix();
        glScalef(0.25f, 0.35f, 0.06f);
        RenderUtils.drawCube();
        glPopMatrix();
        glEnable(GL_TEXTURE_2D);
        glColor3f(1, 1, 1);
        glPopMatrix();
    }
}