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

class LevelLoader {

    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(GameObject.class, new JsonDeserializer<GameObject>() {
                @Override
                public GameObject deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                    JsonObject jsonObject = json.getAsJsonObject();

                    GameObject obj = new GameObject(0, 0, 0);

                    if (jsonObject.has("uuid")) obj.uuid = jsonObject.get("uuid").getAsString();
                    if (jsonObject.has("name")) obj.name = jsonObject.get("name").getAsString();
                    if (jsonObject.has("transformMode")) obj.transformMode = jsonObject.get("transformMode").getAsInt();

                    if (jsonObject.has("transform")) {
                        obj.transform = context.deserialize(jsonObject.get("transform"), Transform.class);
                    } else {
                        float x = jsonObject.has("x") ? jsonObject.get("x").getAsFloat() : 0;
                        float y = jsonObject.has("y") ? jsonObject.get("y").getAsFloat() : 0;
                        float z = jsonObject.has("z") ? jsonObject.get("z").getAsFloat() : 0;
                        obj.transform = new Transform(x, y, z);

                        if (jsonObject.has("rotX")) obj.transform.rotX = jsonObject.get("rotX").getAsFloat();
                        if (jsonObject.has("rotY")) obj.transform.rotY = jsonObject.get("rotY").getAsFloat();
                        if (jsonObject.has("rotZ")) obj.transform.rotZ = jsonObject.get("rotZ").getAsFloat();

                        if (jsonObject.has("scaleX")) obj.transform.scaleX = jsonObject.get("scaleX").getAsFloat();
                        if (jsonObject.has("scaleY")) obj.transform.scaleY = jsonObject.get("scaleY").getAsFloat();
                        if (jsonObject.has("scaleZ")) obj.transform.scaleZ = jsonObject.get("scaleZ").getAsFloat();
                    }
                    obj.transform.gameObject = obj;

                    if (jsonObject.has("texturePath") || jsonObject.has("modelPath") || jsonObject.has("material")) {
                        MeshRenderer mr = new MeshRenderer();
                        if (jsonObject.has("texturePath")) {
                            String tp = jsonObject.get("texturePath").getAsString();
                            mr.texturePath = tp.equals("pwood.jpg") ? "test.png" : tp;
                        }
                        if (jsonObject.has("modelPath")) mr.modelPath = jsonObject.get("modelPath").getAsString();
                        if (jsonObject.has("material"))
                            mr.material = context.deserialize(jsonObject.get("material"), Material.class);
                        obj.addComponent(mr);
                    }

                    if (jsonObject.has("components")) {
                        JsonArray comps = jsonObject.getAsJsonArray("components");
                        for (JsonElement e : comps) {
                            JsonObject cObj = e.getAsJsonObject();
                            if (cObj.has("type")) {
                                String cType = cObj.get("type").getAsString();
                                Class<? extends Component> componentClass = ComponentRegistry.get(cType);
                                if (componentClass != null) {
                                    Component component = context.deserialize(cObj, componentClass);
                                    if (!"MeshRenderer".equals(cType) || obj.getComponent(MeshRenderer.class) == null) {
                                        obj.addComponent(component);
                                    }
                                }
                            }
                        }
                    }
                    return obj;
                }
            })
            .create();

    public static void saveLevel(List<GameObject> entities, String filepath) {
        try (Writer writer = new FileWriter(filepath)) {
            gson.toJson(entities, writer);
            Logger.info("Level saved successfully to: " + filepath);
        } catch (Exception e) {
            Logger.error("Failed to save level to: " + filepath, e);
        }
    }

    public static List<GameObject> loadLevel(String filepath) {
        try (Reader reader = new FileReader(filepath)) {
            Type listType = new TypeToken<ArrayList<GameObject>>() {
            }.getType();
            List<GameObject> entities = gson.fromJson(reader, listType);

            if (entities == null) {
                Logger.warn("Loaded level is null, returning empty list");
                return new ArrayList<>();
            }

            for (GameObject obj : entities) {
                if (obj.transform == null) {
                    obj.transform = new Transform(0, 0, 0);
                }
                obj.transform.gameObject = obj;

                MeshRenderer mr = obj.getComponent(MeshRenderer.class);
                if (mr != null) {
                    if (mr.modelPath != null && !mr.modelPath.isEmpty()) {
                        mr.mesh = AssetPool.getMesh(mr.modelPath);
                    }
                    if (mr.texturePath != null && !mr.texturePath.isEmpty()) {
                        mr.textureID = AssetPool.getTexture(mr.texturePath);
                    }
                    // prefer embedded GLB texture, then try foo.glb → foo.png
                    if (!mr.modelPath.isEmpty() && "test.png".equals(mr.texturePath)) {
                        int embedded = AssetPool.getEmbeddedTexture(mr.modelPath);
                        if (embedded != 0) {
                            mr.textureID = embedded;
                        } else {
                            String auto = mr.modelPath.replaceAll("\\.(glb|gltf)$", ".png");
                            if (Texture.exists(auto)) {
                                mr.texturePath = auto;
                                mr.textureID = AssetPool.getTexture(auto);
                            }
                        }
                    }
                }

                // Restore Animation State
                Animator anim = obj.getComponent(Animator.class);
                if (anim != null && mr != null && mr.mesh != null && mr.mesh.skeleton != null) {
                    if (anim.animationFilePath != null && !anim.animationFilePath.isEmpty()) {
                        anim.currentAnimation = AnimationLoader.loadAnimation(anim.animationFilePath, mr.mesh.skeleton);
                        // We do NOT call anim.play() because that would reset currentTime to 0!
                        // We just restore the reference so it continues exactly where it left off based on the JSON.
                    }
                }
            }

            Logger.info("Level loaded successfully from: " + filepath);
            return entities;
        } catch (Exception e) {
            Logger.error("Could not load level file: " + filepath, e);
            return new ArrayList<>();
        }
    }
}