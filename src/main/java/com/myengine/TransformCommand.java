package com.myengine;

class TransformCommand extends Command {
    private GameObject gameObject;

    private float prevX, prevY, prevZ;
    private float prevRotX, prevRotY, prevRotZ;
    private float prevScaleX, prevScaleY, prevScaleZ;

    private float newX, newY, newZ;
    private float newRotX, newRotY, newRotZ;
    private float newScaleX, newScaleY, newScaleZ;

    public TransformCommand(GameObject gameObject,
                            float beforeX, float beforeY, float beforeZ,
                            float beforeRotX, float beforeRotY, float beforeRotZ,
                            float beforeScaleX, float beforeScaleY, float beforeScaleZ) {
        this.gameObject = gameObject;

        this.prevX = beforeX;
        this.prevY = beforeY;
        this.prevZ = beforeZ;
        this.prevRotX = beforeRotX;
        this.prevRotY = beforeRotY;
        this.prevRotZ = beforeRotZ;
        this.prevScaleX = beforeScaleX;
        this.prevScaleY = beforeScaleY;
        this.prevScaleZ = beforeScaleZ;

        captureAfterState();
    }

    public TransformCommand(GameObject gameObject) {
        this.gameObject = gameObject;
        captureBeforeState();
    }

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

    public boolean hasChanged() {
        return prevX != newX || prevY != newY || prevZ != newZ ||
                prevRotX != newRotX || prevRotY != newRotY || prevRotZ != newRotZ ||
                prevScaleX != newScaleX || prevScaleY != newScaleY || prevScaleZ != newScaleZ;
    }

    @Override
    public void execute() {
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
