package com.myengine;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

class LevelLoader {

    /** When true, the GameObject deserializer attaches components via
     *  {@link GameObject#addComponentNoStart}, skipping any {@code start()}
     *  call (which would touch OpenGL through {@link AssetPool}). Toggled
     *  by {@link #loadLevelData} for the no-GL (Vulkan) load path. */
    private static final ThreadLocal<Boolean> SKIP_COMPONENT_START =
            ThreadLocal.withInitial(() -> false);

    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(GameObject.class, new JsonDeserializer<GameObject>() {
                @Override
                public GameObject deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                    JsonObject jsonObject = json.getAsJsonObject();
                    boolean skipStart = SKIP_COMPONENT_START.get();

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
                        if (skipStart) obj.addComponentNoStart(mr); else obj.addComponent(mr);
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
                                        if (skipStart) obj.addComponentNoStart(component);
                                        else           obj.addComponent(component);
                                    }
                                }
                            }
                        }
                    }
                    return obj;
                }
            })
            .create();

    /** Deep-copy a GameObject via JSON round-trip. Transient fields
     *  (Mesh, textureID, boundsCache, gameObject back-refs) are excluded
     *  by GSON automatically. Caller must assign a new UUID before
     *  insertion.
     *
     *  OpenGL path (skipStart=false): MeshRenderer.start() runs during
     *  deserialization and re-resolves mesh/textureID from the GL AssetPool.
     *  Vulkan path (skipStart=true): start() is skipped (no GL context);
     *  the caller must warm the Vk caches itself (e.g. warmEntity). */
    public static GameObject deepCopy(GameObject src) {
        return deepCopy(src, false);
    }

    public static GameObject deepCopy(GameObject src, boolean skipStart) {
        SKIP_COMPONENT_START.set(skipStart);
        try {
            String json = gson.toJson(src);
            Type t = new TypeToken<GameObject>() {}.getType();
            return gson.fromJson(json, t);
        } finally {
            SKIP_COMPONENT_START.remove();
        }
    }

    /** Serialize the entity list to a JSON string (Phase 3 scene snapshot). */
    public static String toJsonString(List<GameObject> entities) {
        return gson.toJson(entities);
    }

    /** Deserialize an entity list from a JSON string WITHOUT touching the
     *  OpenGL AssetPool (no-GL / Vulkan path — same as {@link #loadLevelData}).
     *  Used by the Play/Stop snapshot restore (Phase 3). */
    public static List<GameObject> fromJsonString(String json) {
        if (json == null) return new ArrayList<>();
        SKIP_COMPONENT_START.set(true);
        try {
            Type listType = new TypeToken<ArrayList<GameObject>>() {}.getType();
            List<GameObject> entities = gson.fromJson(json, listType);
            if (entities == null) return new ArrayList<>();
            dropLegacyFloor(entities);
            for (GameObject obj : entities) {
                if (obj.transform == null) obj.transform = new Transform(0, 0, 0);
                obj.transform.gameObject = obj;
            }
            return entities;
        } catch (Exception e) {
            Logger.error("Could not restore scene from snapshot JSON", e);
            return new ArrayList<>();
        } finally {
            SKIP_COMPONENT_START.remove();
        }
    }

    public static void saveLevel(List<GameObject> entities, String filepath) {
        try (Writer writer = new FileWriter(filepath)) {
            gson.toJson(entities, writer);
            Logger.info("Level saved successfully to: " + filepath);
        } catch (Exception e) {
            Logger.error("Failed to save level to: " + filepath, e);
        }
    }

    /** Migration: drop legacy hardcoded floor entities (modelPath
     *  "__floor_plane") from old saves. Floors are now built from scaled
     *  Cube primitives; the old floor mesh key no longer resolves and
     *  leaves an unselectable/invisible leftover otherwise. */
    private static void dropLegacyFloor(List<GameObject> entities) {
        int removed = 0;
        for (java.util.Iterator<GameObject> it = entities.iterator(); it.hasNext();) {
            MeshRenderer mr = it.next().getComponent(MeshRenderer.class);
            if (mr != null && "__floor_plane".equals(mr.modelPath)) { it.remove(); removed++; }
        }
        if (removed > 0) Logger.info("Dropped " + removed + " legacy __floor_plane entit(ies)");
    }

    /** Deserializes the level into GameObjects WITHOUT touching the OpenGL
     *  {@link AssetPool}. Used by the Vulkan renderer, which loads its own
     *  GPU resources keyed off each entity's {@code modelPath} /
     *  {@code texturePath}. The returned objects have their {@code Transform}
     *  and {@code MeshRenderer} fields populated from JSON; {@code mr.mesh}
     *  and {@code mr.textureID} remain null/0 — Vulkan code never reads
     *  those. */
    public static List<GameObject> loadLevelData(String filepath) {
        SKIP_COMPONENT_START.set(true);
        try (Reader reader = new FileReader(filepath)) {
            Type listType = new TypeToken<ArrayList<GameObject>>() {}.getType();
            List<GameObject> entities = gson.fromJson(reader, listType);
            if (entities == null) return new ArrayList<>();
            dropLegacyFloor(entities);
            for (GameObject obj : entities) {
                if (obj.transform == null) obj.transform = new Transform(0, 0, 0);
                obj.transform.gameObject = obj;
            }
            Logger.info("Level data loaded (no-GL): " + filepath + " (" + entities.size() + " entities)");
            return entities;
        } catch (Exception e) {
            Logger.error("Could not load level data: " + filepath, e);
            return new ArrayList<>();
        } finally {
            SKIP_COMPONENT_START.remove();
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

            dropLegacyFloor(entities);

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
                            String bareName = mr.modelPath.startsWith(Config.CHARACTER_PATH_PREFIX)
                                    ? mr.modelPath.substring(Config.CHARACTER_PATH_PREFIX.length())
                                    : mr.modelPath;
                            String auto = bareName.replaceAll("\\.(glb|gltf|obj|fbx)$", ".png");
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
