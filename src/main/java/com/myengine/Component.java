package com.myengine;

/**
 * Abstract base class for all game object components.
 * Implements the Component part of the Entity-Component System.
 */
public abstract class Component {
    /** Reference to the GameObject this component is attached to */
    public transient GameObject gameObject = null;

    /** Serializable type name for JSON deserialization */
    public String type = this.getClass().getSimpleName();

    /**
     * Called once when the component is first added to a GameObject.
     */
    public void start() {}

    /**
     * Called every frame to update the component.
     * @param dt Delta time since last frame in seconds
     */
    public void update(double dt) {}
}