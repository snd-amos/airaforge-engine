package com.myengine;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.io.*;
import java.util.*;

class UIManager {
    // ImGui (context + Glfw/GL3 impls) is owned by Window for the lifetime
    // of the app — see Window.java for why.

    private final ImString nameInput = new ImString(256);
    private final ImString texInput = new ImString(256);
    private final ImString modelInput = new ImString(256);
    private final ImString animInput = new ImString(256);
    public final ImBoolean showBones = new ImBoolean(false);
    public final ImBoolean showDebugHud = new ImBoolean(true);
    public final ImBoolean showProfiler = new ImBoolean(true);

    private final File modelsFolder = new File(Config.MODELS_PATH);
    private final File charactersFolder = new File(Config.CHARACTERS_PATH);

    private static boolean isModelFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".glb") || n.endsWith(".gltf") || n.endsWith(".obj") || n.endsWith(".fbx");
    }

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
        // ImGui context + Glfw/GL3 impls live in Window. Nothing per-scene
        // to init here — kept the method (and parameter) so callsites
        // don't change.
        //
        // BUT: MainMenuScene bumps font-global-scale to 2.2x for its big
        // splash buttons, and the now-shared ImGui context preserves that
        // setting when we transition to the editor → editor HUD doubled
        // in size. Snap it back to 1.0 here so the editor renders at the
        // pre-macOS-port density. MainMenuScene re-applies 2.2x when the
        // user returns to the menu.
        ImGui.getIO().setFontGlobalScale(1.0f);
        Logger.info("UIManager initialized successfully");
    }

    public void render(LevelEditorScene scene, int framebufferTextureID) {
        Window.imGuiGlfw.newFrame();
        Window.imGuiGl3.newFrame();
        ImGui.newFrame();

        ImGui.dockSpaceOverViewport(ImGui.getMainViewport());

        renderViewport(framebufferTextureID);
        renderAssetBrowser(scene);
        renderGameplayObjects(scene);
        renderInspector(scene);

        ImGui.begin("Shadow Map Debug");
        ImGui.text("Pass 1: Light's Perspective Depth Map");
        if (scene.getShadowMapID() != 0) {
            ImGui.image(scene.getShadowMapID(), 256, 256, 0, 1, 1, 0);
        }
        ImGui.separator();
        ImGui.text(String.format("Coverage: %.0f x %.0f m",
                Config.SHADOW_ORTHO_SIZE * 2, Config.SHADOW_ORTHO_SIZE * 2));
        ImGui.text(String.format("Resolution: %.2f texels/m",
                Config.SHADOW_MAP_WIDTH / (Config.SHADOW_ORTHO_SIZE * 2)));
        float[] orthoArr = { Config.SHADOW_ORTHO_SIZE };
        if (ImGui.sliderFloat("Ortho half-size", orthoArr, 10f, 300f)) {
            Config.SHADOW_ORTHO_SIZE = orthoArr[0];
        }
        float[] camHArr = { Config.SHADOW_CAM_HEIGHT };
        if (ImGui.sliderFloat("Cam height (along sun)", camHArr, 20f, 400f)) {
            Config.SHADOW_CAM_HEIGHT = camHArr[0];
        }
        float[] farArr = { Config.SHADOW_FAR };
        if (ImGui.sliderFloat("Shadow far", farArr, 100f, 1000f)) {
            Config.SHADOW_FAR = farArr[0];
        }
        ImGui.end();

        if (showDebugHud.get()) {
            renderDebugHud(scene);
        }

        if (showProfiler.get()) {
            renderProfiler();
        }

        ImGui.render();
        Window.imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    private void renderFaceColorPicker(Material mat, int faceIndex, String label) {
        float[] color = {
                mat.faceColors[faceIndex][0],
                mat.faceColors[faceIndex][1],
                mat.faceColors[faceIndex][2]
        };
        if (ImGui.colorEdit3(label, color)) {
            mat.faceColors[faceIndex][0] = color[0];
            mat.faceColors[faceIndex][1] = color[1];
            mat.faceColors[faceIndex][2] = color[2];
        }
    }

    // Markdown table of the current profiler readout. Copied to the clipboard
    // by the "Copy table" button — so when the human is showing Claude a
    // perf snapshot, paste-as-text replaces eyeballing a screenshot.
    private String buildProfilerClipboardText(double frameMs, double fps) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Frame: %.2f ms  (%.0f fps)%n", frameMs, fps));
        sb.append("| Zone | avg ms | max ms | calls |\n");
        sb.append("|------|--------|--------|-------|\n");
        for (Map.Entry<String, Profiler.ZoneStats> e : Profiler.getZones().entrySet()) {
            Profiler.ZoneStats s = e.getValue();
            sb.append(String.format("| %s | %.3f | %.3f | %d |%n",
                    e.getKey(),
                    Profiler.avgMs(s),
                    Profiler.maxMs(s),
                    s.lastFrameCalls));
        }
        return sb.toString();
    }

    private void renderProfiler() {
        ImGui.begin("Profiler");
        long frameNs = Profiler.getLastFrameNs();
        double frameMs = frameNs / 1_000_000.0;
        double fps = frameNs > 0 ? 1_000_000_000.0 / frameNs : 0;
        ImGui.text(String.format("Frame: %.2f ms  (%.0f fps)", frameMs, fps));
        if (Profiler.isWarmingUp()) {
            ImGui.text("Warming up... (" + Profiler.warmupRemaining() + " frames)");
        }
        if (ImGui.button("Reset max")) {
            Profiler.resetMax();
        }
        ImGui.sameLine();
        if (ImGui.button("Copy table")) {
            ImGui.setClipboardText(buildProfilerClipboardText(frameMs, fps));
        }
        ImGui.separator();

        if (ImGui.beginTable("zones", 4)) {
            ImGui.tableSetupColumn("Zone");
            ImGui.tableSetupColumn("avg ms");
            ImGui.tableSetupColumn("max ms");
            ImGui.tableSetupColumn("calls");
            ImGui.tableHeadersRow();

            for (Map.Entry<String, Profiler.ZoneStats> e : Profiler.getZones().entrySet()) {
                Profiler.ZoneStats s = e.getValue();
                ImGui.tableNextRow();
                ImGui.tableNextColumn(); ImGui.text(e.getKey());
                ImGui.tableNextColumn(); ImGui.text(String.format("%.3f", Profiler.avgMs(s)));
                ImGui.tableNextColumn(); ImGui.text(String.format("%.3f", Profiler.maxMs(s)));
                ImGui.tableNextColumn(); ImGui.text(String.valueOf(s.lastFrameCalls));
            }
            ImGui.endTable();
        }
        ImGui.end();
    }

    // Minecraft-F3-style overlay: player coords, yaw/pitch, compass direction
    // and the world axis the camera is closest to facing. Editor-mode only.
    private void renderDebugHud(LevelEditorScene scene) {
        Player p = scene.player;
        float yaw = ((p.yaw % 360f) + 360f) % 360f;  // normalize to [0, 360)
        float pitch = p.pitch;

        String compass;
        if      (yaw < 22.5f || yaw >= 337.5f) compass = "N";
        else if (yaw < 67.5f)                  compass = "NE";
        else if (yaw < 112.5f)                 compass = "E";
        else if (yaw < 157.5f)                 compass = "SE";
        else if (yaw < 202.5f)                 compass = "S";
        else if (yaw < 247.5f)                 compass = "SW";
        else if (yaw < 292.5f)                 compass = "W";
        else                                   compass = "NW";

        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float fx = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float fy = (float) -Math.sin(pitchRad);
        float fz = (float) -(Math.cos(yawRad) * Math.cos(pitchRad));
        // Dominant axis the camera is looking down.
        float ax = Math.abs(fx), ay = Math.abs(fy), az = Math.abs(fz);
        String facing;
        if (ax >= ay && ax >= az)       facing = fx >= 0 ? "+X (East)"  : "-X (West)";
        else if (ay >= ax && ay >= az)  facing = fy >= 0 ? "+Y (Down)"  : "-Y (Up)";
        else                             facing = fz >= 0 ? "+Z (South)" : "-Z (North)";

        ImGui.begin("Debug HUD");
        ImGui.text(String.format("XYZ: %.2f / %.2f / %.2f", p.x, p.y, p.z));
        ImGui.text(String.format("Yaw: %.1f  (%s)", yaw, compass));
        ImGui.text(String.format("Pitch: %.1f", pitch));
        ImGui.text("Facing: " + facing);
        ImGui.text(String.format("Forward: %.2f / %.2f / %.2f", fx, fy, fz));
        ImGui.checkbox("Show this HUD", showDebugHud);
        ImGui.end();
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

        if (modelsFolder.exists() && modelsFolder.isDirectory()) {
            File[] files = modelsFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && isModelFile(file.getName())) {
                        if (ImGui.button("Spawn: " + file.getName())) {
                            spawnModelInFront(scene, file.getName());
                        }
                    }
                }
            }
        } else {
            ImGui.text("Models folder not found at: " + Config.MODELS_PATH);
        }

        ImGui.separator();
        ImGui.text("Characters:");
        ImGui.separator();
        if (charactersFolder.exists() && charactersFolder.isDirectory()) {
            File[] charFiles = charactersFolder.listFiles();
            if (charFiles != null) {
                for (File file : charFiles) {
                    if (file.isFile() && isModelFile(file.getName())) {
                        if (ImGui.button("Spawn: " + file.getName() + " (char)")) {
                            spawnModelInFront(scene, Config.CHARACTER_PATH_PREFIX + file.getName());
                        }
                    }
                }
            }
        } else {
            ImGui.text("Characters folder not found at: " + Config.CHARACTERS_PATH);
        }

        ImGui.separator();
        ImGui.text("Stress Test:");
        ImGui.separator();
        if (ImGui.button("Spawn 100"))  stressSpawnCubes(scene, 100);
        ImGui.sameLine();
        if (ImGui.button("Spawn 500"))  stressSpawnCubes(scene, 500);
        ImGui.sameLine();
        if (ImGui.button("Spawn 2000")) stressSpawnCubes(scene, 2000);
        if (ImGui.button("Spawn 5000")) stressSpawnCubes(scene, 5000);
        ImGui.sameLine();
        if (ImGui.button("Clear stress cubes")) {
            int before = scene.world.entities.size();
            scene.world.entities.removeIf(o -> o.name != null && o.name.startsWith("StressCube"));
            int removed = before - scene.world.entities.size();
            Logger.info("Cleared " + removed + " stress cubes");
        }
        ImGui.end();
    }

    // Spawn a square grid of procedural cubes near the player to give the
    // renderer something to chew on. Used to find the actual perf bottleneck
    // before we build a spatial partition. Each cube is a normal procedural
    // GameObject — so collision, raycasting, shadows all apply.
    private void stressSpawnCubes(LevelEditorScene scene, int count) {
        int side = (int) Math.ceil(Math.sqrt(count));
        float spacing = 1.5f;
        float startX = scene.player.x - side * spacing * 0.5f;
        float startZ = scene.player.z - side * spacing * 0.5f - 5f; // a bit in front
        int spawned = 0;
        for (int z = 0; z < side && spawned < count; z++) {
            for (int x = 0; x < side && spawned < count; x++) {
                GameObject cube = new GameObject(
                        startX + x * spacing,
                        1f,
                        startZ + z * spacing);
                cube.name = "StressCube_" + spawned;
                MeshRenderer mr = new MeshRenderer();
                mr.modelPath = "";
                mr.texturePath = "test.png";
                cube.addComponent(mr);
                scene.world.entities.add(cube);
                spawned++;
            }
        }
        Logger.info("Stress-spawned " + spawned + " cubes (grid " + side + "x" + side + ")");
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
                        if (isModelFile(input)) {
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

                    boolean tilingOn = Boolean.TRUE.equals(mr.material.useWorldSpaceTiling);
                    if (ImGui.checkbox("World-space tiling", tilingOn)) {
                        mr.material.useWorldSpaceTiling = !tilingOn;
                    }
                    if (Boolean.TRUE.equals(mr.material.useWorldSpaceTiling)) {
                        float[] scale = { mr.material.textureScale };
                        if (ImGui.sliderFloat("Texture scale", scale, 0.05f, 8.0f)) {
                            mr.material.textureScale = scale[0];
                        }
                    }

                    // Per-face colors — only consumed by procedural cubes (the
                    // CubeBatch instanced shader picks the colour off the local
                    // face normal). Imported models / characters keep their
                    // single Color Tint and these controls do nothing for them.
                    if (ImGui.collapsingHeader("Face colors")) {
                        boolean perFaceOn = mr.material.faceColors != null;
                        if (ImGui.checkbox("Per-face", perFaceOn)) {
                            if (perFaceOn) mr.material.disablePerFaceColors();
                            else           mr.material.enablePerFaceColors();
                        }
                        if (mr.material.faceColors != null) {
                            renderFaceColorPicker(mr.material, 0, "+X (Right)");
                            renderFaceColorPicker(mr.material, 1, "-X (Left)");
                            renderFaceColorPicker(mr.material, 2, "+Y (Top)");
                            renderFaceColorPicker(mr.material, 3, "-Y (Bottom)");
                            renderFaceColorPicker(mr.material, 4, "+Z (Front)");
                            renderFaceColorPicker(mr.material, 5, "-Z (Back)");
                            if (ImGui.button("Match tint (all faces)")) {
                                for (int f = 0; f < 6; f++) {
                                    mr.material.faceColors[f][0] = mr.material.r;
                                    mr.material.faceColors[f][1] = mr.material.g;
                                    mr.material.faceColors[f][2] = mr.material.b;
                                }
                            }
                        }
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

    // New panel for non-asset gameplay objects (spawn points, future:
    // triggers, checkpoints, etc.). Kept separate from the Asset Browser
    // so model assets and gameplay logic stay visually distinct.
    private void renderGameplayObjects(LevelEditorScene scene) {
        ImGui.begin("Gameplay Objects");
        ImGui.textDisabled("Spawn a Cube primitive, then drag");
        ImGui.textDisabled("SpawnPoint / WinZone components onto it.");
        ImGui.end();
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
        String displayName = modelFileName;
        if (displayName.startsWith(Config.CHARACTER_PATH_PREFIX)) {
            displayName = displayName.substring(Config.CHARACTER_PATH_PREFIX.length());
        }
        newObj.name = displayName.replaceAll("\\.(glb|gltf|obj|fbx)$", "");

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
        // ImGui is app-lifetime now — nothing per-scene to tear down here.
        Logger.info("UIManager destroyed successfully");
    }
}
