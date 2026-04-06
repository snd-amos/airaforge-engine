package com.myengine;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImString;
import java.io.File;
import java.util.List;

/**
 * ImGui user interface manager for the level editor.
 * Handles rendering of editor panels, asset browser, and inspector.
 */
public class UIManager {
    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();

    private final ImString nameInput = new ImString(256);
    private final ImString texInput = new ImString(256);
    private final ImString modelInput = new ImString(256);

    private final File modelsFolder = new File(Config.MODELS_PATH);

    // Viewport tracking for precise raycasting
    public float viewportX, viewportY, viewportWidth = 800, viewportHeight = 600;
    public boolean isViewportHovered = false;

    /**
     * Initialize ImGui and register platform/renderer backends.
     */
    public void init(long windowPtr) {
        Logger.info("Initializing UIManager");

        ImGui.createContext();
        ImGui.getIO().addConfigFlags(imgui.flag.ImGuiConfigFlags.DockingEnable);

        imGuiGlfw.init(windowPtr, true);
        imGuiGl3.init("#version 130");

        Logger.info("UIManager initialized successfully");
    }

    /**
     * Render all ImGui UI elements.
     */
    public void render(LevelEditorScene scene, int framebufferTextureID) {
        imGuiGlfw.newFrame();
        ImGui.newFrame();

        // Dockspace
        ImGui.dockSpaceOverViewport(ImGui.getMainViewport());

        // Scene Viewport
        renderViewport(framebufferTextureID);

        // Asset Browser
        renderAssetBrowser(scene);

        // Inspector
        renderInspector(scene);

        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    /**
     * Render the 3D scene viewport window.
     */
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

    /**
     * Render the asset browser window.
     */
    private void renderAssetBrowser(LevelEditorScene scene) {
        ImGui.begin("Asset Browser");

        if (ImGui.button("Save Level")) {
            LevelLoader.saveLevel(scene.world.entities, "level.json");
        }
        ImGui.sameLine();
        if (ImGui.button("Load Level")) {
            List<GameObject> loaded = LevelLoader.loadLevel("level.json");
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

    /**
     * Render the inspector window for editing selected object properties.
     */
    private void renderInspector(LevelEditorScene scene) {
        ImGui.begin("Inspector");

        if (scene.selectedBox != null) {
            // Name input
            if (ImGui.inputText("Name", nameInput)) {
                scene.selectedBox.name = nameInput.get();
            }

            ImGui.separator();

            // Transform mode selection
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

            // Position controls
            float[] pos = {scene.selectedBox.transform.x, scene.selectedBox.transform.y, scene.selectedBox.transform.z};
            if (ImGui.dragFloat3("Position", pos, 0.1f)) {
                scene.selectedBox.transform.x = pos[0];
                scene.selectedBox.transform.y = pos[1];
                scene.selectedBox.transform.z = pos[2];
            }

            // Sun intensity (if sun block selected)
            if (scene.selectedBox == scene.sunBlock) {
                float[] intensity = {scene.sunIntensity};
                if (ImGui.dragFloat("Sun Intensity", intensity, 0.05f, 0.0f, 5.0f)) {
                    scene.sunIntensity = intensity[0];
                }
            } else {
                // Rotation and scale controls
                float[] rot = {scene.selectedBox.transform.rotX, scene.selectedBox.transform.rotY, scene.selectedBox.transform.rotZ};
                if (ImGui.dragFloat3("Rotation", rot, 1.0f)) {
                    scene.selectedBox.transform.rotX = rot[0];
                    scene.selectedBox.transform.rotY = rot[1];
                    scene.selectedBox.transform.rotZ = rot[2];
                }

                float[] scale = {scene.selectedBox.transform.scaleX, scene.selectedBox.transform.scaleY, scene.selectedBox.transform.scaleZ};
                if (ImGui.dragFloat3("Scale", scale, 0.05f)) {
                    scene.selectedBox.transform.scaleX = scale[0];
                    scene.selectedBox.transform.scaleY = scale[1];
                    scene.selectedBox.transform.scaleZ = scale[2];
                }
            }

            // MeshRenderer component properties
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
                    mr.modelPath = modelInput.get();
                    if (mr.modelPath.endsWith(".glb") || mr.modelPath.endsWith(".gltf")) {
                        mr.mesh = AssetPool.getMesh(mr.modelPath);
                    } else if (mr.modelPath.isEmpty()) {
                        mr.mesh = null;
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
        }
        ImGui.end();
    }

    /**
     * Spawn a model in front of the player.
     */
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

        scene.world.entities.add(newObj);
        Logger.info("Spawned model: " + modelFileName);
    }

    /**
     * Clean up ImGui resources.
     */
    public void destroy() {
        Logger.info("Destroying UIManager");
        imGuiGl3.dispose();
        imGuiGlfw.dispose();
        ImGui.destroyContext();
        Logger.info("UIManager destroyed successfully");
    }
}