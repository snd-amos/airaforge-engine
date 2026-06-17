package com.myengine;

import java.util.*;

class ComponentRegistry {
    private static final Map<String, Class<? extends Component>> REGISTRY = new HashMap<>();

    static {
        register("MeshRenderer", MeshRenderer.class);
        register("Transform", Transform.class);
        register("Animator", Animator.class); // <--- Added this to allow JSON loading!
        // Drag-and-drop attachable gameplay components. The short names are the
        // UI drag-payload / display labels.
        register("Invisible", InvisibleComponent.class);
        register("Walkthrough", WalkthroughComponent.class);
        register("SpawnPoint", SpawnPointComponent.class);
        register("WallRunnable", WallRunnableComponent.class);
        register("WinZone", WinZoneComponent.class);
        // Issue 3: Component.type serializes as getClass().getSimpleName()
        // (e.g. "InvisibleComponent"), so the load path looks these classes up
        // by their FULL simple name. Without these aliases the deserializer's
        // ComponentRegistry.get("InvisibleComponent") returned null and the
        // component was silently dropped on save→reload. (MeshRenderer /
        // Transform / Animator already matched because their simple name IS
        // their key.)
        register("InvisibleComponent", InvisibleComponent.class);
        register("WalkthroughComponent", WalkthroughComponent.class);
        register("SpawnPointComponent", SpawnPointComponent.class);
        register("WallRunnableComponent", WallRunnableComponent.class);
        register("WinZoneComponent", WinZoneComponent.class);
        // Character Editor locomotion data (Phase 3A) — short + simple-class-name
        // keys so it round-trips through save→reload (see Regression vault).
        register("CharacterLocomotion", CharacterLocomotionComponent.class);
        register("CharacterLocomotionComponent", CharacterLocomotionComponent.class);
    }

    /** Instantiate a registered component by name (used by the Inspector
     *  drag-and-drop drop target). Returns null if unknown / not constructible. */
    public static Component instantiate(String typeName) {
        Class<? extends Component> cls = REGISTRY.get(typeName);
        if (cls == null) return null;
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            Logger.error("ComponentRegistry: cannot instantiate " + typeName, e);
            return null;
        }
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
