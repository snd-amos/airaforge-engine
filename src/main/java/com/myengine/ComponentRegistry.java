package com.myengine;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for component types used in deserialization.
 * Provides type-safe component creation from JSON.
 */
public class ComponentRegistry {
    private static final Map<String, Class<? extends Component>> REGISTRY = new HashMap<>();

    static {
        register("MeshRenderer", MeshRenderer.class);
        register("Transform", Transform.class);
    }

    public static void register(String typeName, Class<? extends Component> componentClass) {
        REGISTRY.put(typeName, componentClass);
    }

    public static Class<? extends Component> get(String typeName) {
        return REGISTRY.get(typeName);
    }

    public static boolean contains(String typeName) {
        return REGISTRY.containsKey(typeName);
    }
}