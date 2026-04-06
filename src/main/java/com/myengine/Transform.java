package com.myengine;

/**
 * Transform component for position, rotation, and scale.
 * Every GameObject has exactly one Transform.
 */
public class Transform extends Component {
    public float x, y, z;
    public float rotX, rotY, rotZ;
    public float scaleX = 1.0f, scaleY = 1.0f, scaleZ = 1.0f;

    /**
     * Create a new Transform at the given position.
     */
    public Transform(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void start() {
        // Transform doesn't need special initialization
    }

    @Override
    public void update(double dt) {
        // Transform updates are handled by physics/input systems
    }
}