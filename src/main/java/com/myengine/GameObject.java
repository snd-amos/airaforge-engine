package com.myengine;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base entity in the game world.
 * Contains a Transform and a list of Components (ECS pattern).
 */
public class GameObject {
    public String uuid;
    public String name = "Object";
    public int transformMode = 0; // 0=Move, 1=Rotate, 2=Scale

    public Transform transform;
    public List<Component> components = new ArrayList<>();

    /**
     * Create a new GameObject at the given position.
     */
    public GameObject(float x, float y, float z) {
        this.uuid = UUID.randomUUID().toString();
        this.transform = new Transform(x, y, z);
        this.transform.gameObject = this;
    }

    /**
     * Add a component to this GameObject.
     */
    public void addComponent(Component c) {
        components.add(c);
        c.gameObject = this;
        c.start();
    }

    /**
     * Get a component by type (returns first match).
     */
    public <T extends Component> T getComponent(Class<T> componentClass) {
        for (Component c : components) {
            if (componentClass.isAssignableFrom(c.getClass())) {
                return componentClass.cast(c);
            }
        }
        return null;
    }

    /**
     * Update all components and transform.
     */
    public void update(double dt) {
        transform.update(dt);
        for (Component c : components) {
            c.update(dt);
        }
    }

    // --- Bounding Box Queries (based on MeshRenderer and Transform) ---

    public float getMinX() {
        MeshRenderer mr = getComponent(MeshRenderer.class);
        return transform.x + ((mr != null && mr.mesh != null) ? mr.mesh.minX * transform.scaleX : -0.5f * transform.scaleX);
    }

    public float getMaxX() {
        MeshRenderer mr = getComponent(MeshRenderer.class);
        return transform.x + ((mr != null && mr.mesh != null) ? mr.mesh.maxX * transform.scaleX : 0.5f * transform.scaleX);
    }

    public float getMinY() {
        MeshRenderer mr = getComponent(MeshRenderer.class);
        return transform.y + ((mr != null && mr.mesh != null) ? mr.mesh.minY * transform.scaleY : -0.5f * transform.scaleY);
    }

    public float getMaxY() {
        MeshRenderer mr = getComponent(MeshRenderer.class);
        return transform.y + ((mr != null && mr.mesh != null) ? mr.mesh.maxY * transform.scaleY : 0.5f * transform.scaleY);
    }

    public float getMinZ() {
        MeshRenderer mr = getComponent(MeshRenderer.class);
        return transform.z + ((mr != null && mr.mesh != null) ? mr.mesh.minZ * transform.scaleZ : -0.5f * transform.scaleZ);
    }

    public float getMaxZ() {
        MeshRenderer mr = getComponent(MeshRenderer.class);
        return transform.z + ((mr != null && mr.mesh != null) ? mr.mesh.maxZ * transform.scaleZ : 0.5f * transform.scaleZ);
    }
}