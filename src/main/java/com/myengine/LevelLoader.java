package com.myengine;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Level serialization and deserialization using Gson.
 * Saves/loads GameObjects and components to/from JSON.
 */
public class LevelLoader {

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

                    // Deserialize transform
                    if (jsonObject.has("transform")) {
                        obj.transform = context.deserialize(jsonObject.get("transform"), Transform.class);
                    } else {
                        // Legacy format support
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

                    // Legacy visual data migration
                    if (jsonObject.has("texturePath") || jsonObject.has("modelPath") || jsonObject.has("material")) {
                        MeshRenderer mr = new MeshRenderer();
                        if (jsonObject.has("texturePath")) mr.texturePath = jsonObject.get("texturePath").getAsString();
                        if (jsonObject.has("modelPath")) mr.modelPath = jsonObject.get("modelPath").getAsString();
                        if (jsonObject.has("material")) mr.material = context.deserialize(jsonObject.get("material"), Material.class);
                        obj.addComponent(mr);
                    }

                    // Deserialize components
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

    /**
     * Save a level to a JSON file.
     */
    public static void saveLevel(List<GameObject> entities, String filepath) {
        try (Writer writer = new FileWriter(filepath)) {
            gson.toJson(entities, writer);
            Logger.info("Level saved successfully to: " + filepath);
        } catch (Exception e) {
            Logger.error("Failed to save level to: " + filepath, e);
        }
    }

    /**
     * Load a level from a JSON file.
     */
    public static List<GameObject> loadLevel(String filepath) {
        try (Reader reader = new FileReader(filepath)) {
            Type listType = new TypeToken<ArrayList<GameObject>>(){}.getType();
            List<GameObject> entities = gson.fromJson(reader, listType);

            if (entities == null) {
                Logger.warn("Loaded level is null, returning empty list");
                return new ArrayList<>();
            }

            // Relink assets and initialize components
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