package com.myengine;

/**
 * Command for undoing/redoing transform changes.
 * Captures position, rotation, and scale before and after changes.
 */
public class TransformCommand extends Command {
    private GameObject gameObject;

    // Before state
    private float prevX, prevY, prevZ;
    private float prevRotX, prevRotY, prevRotZ;
    private float prevScaleX, prevScaleY, prevScaleZ;

    // After state
    private float newX, newY, newZ;
    private float newRotX, newRotY, newRotZ;
    private float newScaleX, newScaleY, newScaleZ;

    /**
     * Create a transform command.
     * Automatically captures the current state as the "before" state.
     */
    public TransformCommand(GameObject gameObject) {
        this.gameObject = gameObject;
        captureBeforeState();
    }

    /**
     * Capture the current transform state as the BEFORE state.
     * Call this immediately when starting to track a transform change.
     */
    private void captureBeforeState() {
        if (gameObject == null || gameObject.transform == null) {
            return;
        }

        prevX = gameObject.transform.x;
        prevY = gameObject.transform.y;
        prevZ = gameObject.transform.z;
        prevRotX = gameObject.transform.rotX;
        prevRotY = gameObject.transform.rotY;
        prevRotZ = gameObject.transform.rotZ;
        prevScaleX = gameObject.transform.scaleX;
        prevScaleY = gameObject.transform.scaleY;
        prevScaleZ = gameObject.transform.scaleZ;
    }

    /**
     * Capture the current transform state as the AFTER state.
     * Call this after you've finished making changes.
     */
    public void captureAfterState() {
        if (gameObject == null || gameObject.transform == null) {
            return;
        }

        newX = gameObject.transform.x;
        newY = gameObject.transform.y;
        newZ = gameObject.transform.z;
        newRotX = gameObject.transform.rotX;
        newRotY = gameObject.transform.rotY;
        newRotZ = gameObject.transform.rotZ;
        newScaleX = gameObject.transform.scaleX;
        newScaleY = gameObject.transform.scaleY;
        newScaleZ = gameObject.transform.scaleZ;
    }

    /**
     * Check if there were actually any changes.
     */
    public boolean hasChanged() {
        return prevX != newX || prevY != newY || prevZ != newZ ||
                prevRotX != newRotX || prevRotY != newRotY || prevRotZ != newRotZ ||
                prevScaleX != newScaleX || prevScaleY != newScaleY || prevScaleZ != newScaleZ;
    }

    @Override
    public void execute() {
        // Apply the new state (redo)
        if (gameObject == null || gameObject.transform == null) return;

        gameObject.transform.x = newX;
        gameObject.transform.y = newY;
        gameObject.transform.z = newZ;
        gameObject.transform.rotX = newRotX;
        gameObject.transform.rotY = newRotY;
        gameObject.transform.rotZ = newRotZ;
        gameObject.transform.scaleX = newScaleX;
        gameObject.transform.scaleY = newScaleY;
        gameObject.transform.scaleZ = newScaleZ;
    }

    @Override
    public void undo() {
        // Restore the old state (undo)
        if (gameObject == null || gameObject.transform == null) return;

        gameObject.transform.x = prevX;
        gameObject.transform.y = prevY;
        gameObject.transform.z = prevZ;
        gameObject.transform.rotX = prevRotX;
        gameObject.transform.rotY = prevRotY;
        gameObject.transform.rotZ = prevRotZ;
        gameObject.transform.scaleX = prevScaleX;
        gameObject.transform.scaleY = prevScaleY;
        gameObject.transform.scaleZ = prevScaleZ;
    }

    @Override
    public String getDescription() {
        return "Transform " + (gameObject != null ? gameObject.name : "Unknown");
    }
}