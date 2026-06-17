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

class UIManager {
    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();

    private final ImString nameInput = new ImString(256);
    private final ImString texInput = new ImString(256);
    private final ImString modelInput = new ImString(256);
    private final ImString animInput = new ImString(256);
    public final ImBoolean showBones = new ImBoolean(false);

    private final File modelsFolder = new File(Config.MODELS_PATH);

    public float viewportX, viewportY, viewportWidth = 800, viewportHeight = 600;
    public boolean isViewportHovered = false;

    private GameObject lastEditedObject = null;
    private float beforePosX, beforePosY, beforePosZ;
    private float beforeRotX, beforeRotY, beforeRotZ;
    private float beforeScaleX, beforeScaleY, beforeScaleZ;
    private boolean wasEditingPosition = false;
    private boolean wasEditingRotation = false;
    private boolean wasEditingScale = false;

    public void init(long windowPtr) {
        Logger.info("Initializing UIManager");

        ImGui.createContext();
        ImGui.getIO().addConfigFlags(imgui.flag.ImGuiConfigFlags.DockingEnable);

        imGuiGlfw.init(windowPtr, true);
        imGuiGl3.init("#version 130");

        Logger.info("UIManager initialized successfully");
    }

    public void render(LevelEditorScene scene, int framebufferTextureID) {
        imGuiGlfw.newFrame();
        ImGui.newFrame();

        ImGui.dockSpaceOverViewport(ImGui.getMainViewport());

        renderViewport(framebufferTextureID);
        renderAssetBrowser(scene);
        renderInspector(scene);

        ImGui.begin("Shadow Map Debug");
        ImGui.text("Pass 1: Light's Perspective Depth Map");
        if (scene.getShadowMapID() != 0) {
            ImGui.image(scene.getShadowMapID(), 256, 256, 0, 1, 1, 0);
        }
        ImGui.end();

        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    private void renderViewport(int framebufferTextureID) {
        ImGui.begin("Scene Viewport");
        isViewportHovered = ImGui.isWindowHovered();

        ImVec2 vMin = ImGui.getWindowContentRegionMin();
        ImVec2 vMax = ImGui.getWindowContentRegionMax();
        ImVec2 wPos = ImGui.getWindowPos();

        viewportX = wPos.x + vMin.x;
        viewportY = wPos.y + vMin.y;
        viewportWidth = vMax.x - vMin.x;
        viewportHeight = vMax.y - vMin.y;

        ImGui.image(framebufferTextureID, viewportWidth, viewportHeight, 0, 1, 1, 0);
        ImGui.end();
    }

    private void renderAssetBrowser(LevelEditorScene scene) {
        ImGui.begin("Asset Browser");

        if (ImGui.button("Save Level")) {
            LevelLoader.saveLevel(scene.world.entities, scene.getLevelPath());
        }
        ImGui.sameLine();
        if (ImGui.button("Load Level")) {
            List<GameObject> loaded = LevelLoader.loadLevel(scene.getLevelPath());
            if (loaded != null && !loaded.isEmpty()) {
                scene.world.entities = loaded;
                scene.selectedBox = null;

                for (GameObject obj : scene.world.entities) {
                    if ("Sun".equals(obj.name)) {
                        scene.sunBlock = obj;
                        break;
                    }
                }
                Logger.info("Level loaded successfully");
            }
        }
        ImGui.separator();

        ImGui.text("Available Models:");
        ImGui.separator();

        if (ImGui.button("Spawn: Floor")) {
            spawnFloorInFront(scene);
        }

        if (modelsFolder.exists() && modelsFolder.isDirectory()) {
            File[] files = modelsFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().endsWith(".glb") || file.getName().endsWith(".gltf")) {
                        if (ImGui.button("Spawn: " + file.getName())) {
                            spawnModelInFront(scene, file.getName());
                        }
                    }
                }
            }
        } else {
            ImGui.text("Models folder not found at: " + Config.MODELS_PATH);
        }
        ImGui.end();
    }

    private void renderInspector(LevelEditorScene scene) {
        ImGui.begin("Inspector");

        if (scene.selectedBox != null) {
            if (lastEditedObject != scene.selectedBox) {
                wasEditingPosition = false;
                wasEditingRotation = false;
                wasEditingScale = false;
                lastEditedObject = scene.selectedBox;

                nameInput.set(scene.selectedBox.name == null ? "" : scene.selectedBox.name);
                MeshRenderer selMr = scene.selectedBox.getComponent(MeshRenderer.class);
                if (selMr != null) {
                    texInput.set(selMr.texturePath == null ? "" : selMr.texturePath);
                    if (selMr.modelPath == null || selMr.modelPath.isEmpty()) {
                        modelInput.set("cube");
                    } else {
                        modelInput.set(selMr.modelPath);
                    }
                } else {
                    texInput.set("");
                    modelInput.set("");
                }
            }

            if (ImGui.inputText("Name", nameInput)) {
                scene.selectedBox.name = nameInput.get();
            }

            ImGui.separator();

            ImGui.text("Transform Mode:");
            if (ImGui.radioButton("Move", scene.selectedBox.transformMode == 0)) {
                scene.selectedBox.transformMode = 0;
            }
            ImGui.sameLine();
            if (scene.selectedBox != scene.sunBlock) {
                if (ImGui.radioButton("Rotate", scene.selectedBox.transformMode == 1)) {
                    scene.selectedBox.transformMode = 1;
                }
                ImGui.sameLine();
                if (ImGui.radioButton("Scale", scene.selectedBox.transformMode == 2)) {
                    scene.selectedBox.transformMode = 2;
                }
            }

            ImGui.separator();

            float[] pos = {scene.selectedBox.transform.x, scene.selectedBox.transform.y, scene.selectedBox.transform.z};

            if (ImGui.dragFloat3("Position", pos, 0.1f)) {
                if (!wasEditingPosition) {
                    beforePosX = scene.selectedBox.transform.x;
                    beforePosY = scene.selectedBox.transform.y;
                    beforePosZ = scene.selectedBox.transform.z;
                    beforeRotX = scene.selectedBox.transform.rotX;
                    beforeRotY = scene.selectedBox.transform.rotY;
                    beforeRotZ = scene.selectedBox.transform.rotZ;
                    beforeScaleX = scene.selectedBox.transform.scaleX;
                    beforeScaleY = scene.selectedBox.transform.scaleY;
                    beforeScaleZ = scene.selectedBox.transform.scaleZ;
                    wasEditingPosition = true;
                }
                scene.selectedBox.transform.x = pos[0];
                scene.selectedBox.transform.y = pos[1];
                scene.selectedBox.transform.z = pos[2];
            }

            if (wasEditingPosition && !ImGui.isItemActive() && ImGui.isItemDeactivatedAfterEdit()) {
                TransformCommand command = new TransformCommand(
                        scene.selectedBox,
                        beforePosX, beforePosY, beforePosZ,
                        beforeRotX, beforeRotY, beforeRotZ,
                        beforeScaleX, beforeScaleY, beforeScaleZ
                );
                if (command.hasChanged()) {
                    scene.undoRedoManager.execute(command);
                    Logger.info("Position change recorded via Inspector");
                }
                wasEditingPosition = false;
            }

            if (scene.selectedBox == scene.sunBlock) {
                float[] intensity = {scene.sunIntensity};

                if (ImGui.dragFloat("Sun Intensity", intensity, 0.05f, 0.0f, 5.0f)) {
                    scene.sunIntensity = intensity[0];
                }
            } else {
                float[] rot = {scene.selectedBox.transform.rotX, scene.selectedBox.transform.rotY, scene.selectedBox.transform.rotZ};

                if (ImGui.dragFloat3("Rotation", rot, 1.0f)) {
                    if (!wasEditingRotation) {
                        beforePosX = scene.selectedBox.transform.x;
                        beforePosY = scene.selectedBox.transform.y;
                        beforePosZ = scene.selectedBox.transform.z;
                        beforeRotX = scene.selectedBox.transform.rotX;
                        beforeRotY = scene.selectedBox.transform.rotY;
                        beforeRotZ = scene.selectedBox.transform.rotZ;
                        beforeScaleX = scene.selectedBox.transform.scaleX;
                        beforeScaleY = scene.selectedBox.transform.scaleY;
                        beforeScaleZ = scene.selectedBox.transform.scaleZ;
                        wasEditingRotation = true;
                    }
                    scene.selectedBox.transform.rotX = rot[0];
                    scene.selectedBox.transform.rotY = rot[1];
                    scene.selectedBox.transform.rotZ = rot[2];
                }

                if (wasEditingRotation && !ImGui.isItemActive() && ImGui.isItemDeactivatedAfterEdit()) {
                    TransformCommand command = new TransformCommand(
                            scene.selectedBox,
                            beforePosX, beforePosY, beforePosZ,
                            beforeRotX, beforeRotY, beforeRotZ,
                            beforeScaleX, beforeScaleY, beforeScaleZ
                    );
                    if (command.hasChanged()) {
                        scene.undoRedoManager.execute(command);
                        Logger.info("Rotation change recorded via Inspector");
                    }
                    wasEditingRotation = false;
                }

                float[] scale = {scene.selectedBox.transform.scaleX, scene.selectedBox.transform.scaleY, scene.selectedBox.transform.scaleZ};

                if (ImGui.dragFloat3("Scale", scale, 0.05f)) {
                    if (!wasEditingScale) {
                        beforePosX = scene.selectedBox.transform.x;
                        beforePosY = scene.selectedBox.transform.y;
                        beforePosZ = scene.selectedBox.transform.z;
                        beforeRotX = scene.selectedBox.transform.rotX;
                        beforeRotY = scene.selectedBox.transform.rotY;
                        beforeRotZ = scene.selectedBox.transform.rotZ;
                        beforeScaleX = scene.selectedBox.transform.scaleX;
                        beforeScaleY = scene.selectedBox.transform.scaleY;
                        beforeScaleZ = scene.selectedBox.transform.scaleZ;
                        wasEditingScale = true;
                    }
                    scene.selectedBox.transform.scaleX = scale[0];
                    scene.selectedBox.transform.scaleY = scale[1];
                    scene.selectedBox.transform.scaleZ = scale[2];
                }

                if (wasEditingScale && !ImGui.isItemActive() && ImGui.isItemDeactivatedAfterEdit()) {
                    TransformCommand command = new TransformCommand(
                            scene.selectedBox,
                            beforePosX, beforePosY, beforePosZ,
                            beforeRotX, beforeRotY, beforeRotZ,
                            beforeScaleX, beforeScaleY, beforeScaleZ
                    );
                    if (command.hasChanged()) {
                        scene.undoRedoManager.execute(command);
                        Logger.info("Scale change recorded via Inspector");
                    }
                    wasEditingScale = false;
                }
            }

            MeshRenderer mr = scene.selectedBox.getComponent(MeshRenderer.class);
            if (mr != null && scene.selectedBox != scene.sunBlock) {
                ImGui.separator();
                ImGui.text("Component: Mesh Renderer");

                if (ImGui.inputText("Texture", texInput)) {
                    mr.texturePath = texInput.get();
                    if (!mr.texturePath.isEmpty()) {
                        mr.textureID = AssetPool.getTexture(mr.texturePath);
                    } else {
                        mr.textureID = 0;
                    }
                }

                if (ImGui.inputText("Model", modelInput)) {
                    String input = modelInput.get();
                    if ("cube".equals(input) || input.isEmpty()) {
                        mr.modelPath = "";
                        mr.mesh = null;
                    } else {
                        mr.modelPath = input;
                        if (input.endsWith(".glb") || input.endsWith(".gltf")) {
                            mr.mesh = AssetPool.getMesh(input);
                        }
                    }
                }

                if (mr.mesh != null && mr.mesh.skeleton != null) {
                    ImGui.text("Skeleton: " + mr.mesh.skeleton.boneCount + " bones");
                    ImGui.checkbox("Show Bones", showBones);
                }

// animator panel
                Animator animComp = scene.selectedBox.getComponent(Animator.class);
                if (animComp != null) {
                    ImGui.separator();
                    ImGui.text("Component: Animator");
                    ImGui.inputText("Animation File", animInput);
                    if (ImGui.button("Load")) {
                        String fn = animInput.get();
                        if (!fn.isEmpty() && mr.mesh != null && mr.mesh.skeleton != null) {
                            Animation a = AnimationLoader.loadAnimation(fn, mr.mesh.skeleton);
                            if (a != null) {
                                animComp.animationFilePath = fn; // <--- Records the filename for JSON saving
                                animComp.play(a);
                                AnimationDefaults.apply(fn, animComp);
                            }
                        }
                    }
                    ImGui.sameLine();
                    if (ImGui.button(animComp.playing ? "Pause" : "Play")) {
                        if (animComp.currentAnimation != null) animComp.playing = !animComp.playing;
                    }
                    if (animComp.currentAnimation != null) {
                        ImGui.text(String.format("%s  -  %.2f / %.2f ticks",
                                animComp.currentAnimation.name,
                                animComp.currentTime,
                                animComp.currentAnimation.duration));
                    } else {
                        ImGui.text("(no animation loaded)");
                    }

                    ImGui.text("Animation Trim");
                    float[] trimOff = { animComp.trimOffsetX, animComp.trimOffsetY, animComp.trimOffsetZ };
                    if (ImGui.dragFloat3("Offset XYZ", trimOff, 0.01f)) {
                        animComp.trimOffsetX = trimOff[0];
                        animComp.trimOffsetY = trimOff[1];
                        animComp.trimOffsetZ = trimOff[2];
                    }
                    float[] trimTilt = { animComp.trimTiltX, animComp.trimTiltY, animComp.trimTiltZ };
                    if (ImGui.dragFloat3("Tilt XYZ (deg)", trimTilt, 0.1f)) {
                        animComp.trimTiltX = trimTilt[0];
                        animComp.trimTiltY = trimTilt[1];
                        animComp.trimTiltZ = trimTilt[2];
                    }
                    if (!animComp.animationFilePath.isEmpty()
                            && ImGui.button("Save Trim as Default for this Animation")) {
                        AnimationDefaults.saveDefault(animComp.animationFilePath, animComp);
                    }
                }

                if (ImGui.collapsingHeader("Material", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
                    float[] color = {mr.material.r, mr.material.g, mr.material.b};
                    if (ImGui.colorEdit3("Color Tint", color)) {
                        mr.material.setColor(color[0], color[1], color[2]);
                    }
                }
            }

        } else {
            ImGui.text("Select an object in the world to inspect it.");
            wasEditingPosition = false;
            wasEditingRotation = false;
            wasEditingScale = false;
        }
        ImGui.end();
    }

    private void spawnFloorInFront(LevelEditorScene scene) {
        float yawRad = (float) Math.toRadians(scene.player.yaw);
        float pitchRad = (float) Math.toRadians(scene.player.pitch);
        float dx = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float dz = (float) (-Math.cos(yawRad) * Math.cos(pitchRad));

        float px = scene.player.x + dx * 3.0f;
        float pz = scene.player.z + dz * 3.0f;

        GameObject floor = new GameObject((float) Math.round(px), 0, (float) Math.round(pz));
        floor.name = "Floor";
        MeshRenderer mrFloor = new MeshRenderer();
        mrFloor.modelPath = World.FLOOR_MESH_KEY;
        mrFloor.texturePath = "";
        mrFloor.mesh = AssetPool.getMesh(World.FLOOR_MESH_KEY);
        mrFloor.material.setColor(0.5f, 0.5f, 0.5f);
        floor.addComponent(mrFloor);
        scene.world.entities.add(floor);
        Logger.info("Spawned Floor at " + floor.transform.x + "," + floor.transform.z);
    }

    private void spawnModelInFront(LevelEditorScene scene, String modelFileName) {
        float yawRad = (float) Math.toRadians(scene.player.yaw);
        float pitchRad = (float) Math.toRadians(scene.player.pitch);
        float dx = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float dy = (float) (-Math.sin(pitchRad));
        float dz = (float) (-Math.cos(yawRad) * Math.cos(pitchRad));

        float px = scene.player.x + dx * 3.0f;
        float py = scene.player.y + dy * 3.0f;
        float pz = scene.player.z + dz * 3.0f;

        GameObject newObj = new GameObject((float) Math.round(px),
                (float) Math.floor(py + 1.0f) - 0.5f,
                (float) Math.round(pz));
        newObj.name = modelFileName.replace(".glb", "").replace(".gltf", "");

        MeshRenderer mr = new MeshRenderer();
        mr.modelPath = modelFileName;
        mr.mesh = AssetPool.getMesh(modelFileName);
        newObj.addComponent(mr);

        if (mr.mesh != null && mr.mesh.skeleton != null) {
            newObj.addComponent(new Animator());
        }

        scene.world.entities.add(newObj);
        Logger.info("Spawned model: " + modelFileName);
    }

    public void destroy() {
        Logger.info("Destroying UIManager");
        imGuiGl3.dispose();
        imGuiGlfw.dispose();
        ImGui.destroyContext();
        Logger.info("UIManager destroyed successfully");
    }
}