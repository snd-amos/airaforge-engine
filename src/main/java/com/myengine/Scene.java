package com.myengine;

/**
 * Abstract base class for all scenes (game states).
 * Scenes manage their own entities, rendering, and input handling.
 */
public abstract class Scene {
    /**
     * Initialize the scene (called once when loaded).
     */
    public abstract void init();

    /**
     * Update the scene (called every frame).
     */
    public abstract void update(double dt, boolean[] keys);

    /**
     * Render the scene (called every frame).
     */
    public abstract void render();

    /**
     * Clean up and destroy the scene (called before unloading).
     */
    public abstract void destroy();

    /**
     * Handle mouse click input events.
     */
    public abstract void onMouseClick(int button, int action, double mouseX, double mouseY);

    /**
     * Handle mouse movement input events.
     */
    public abstract void onMouseMove(double xpos, double ypos);

    /**
     * Handle keyboard input events.
     */
    public abstract void onKeyPress(int key, int action);
}