package com.myengine;

/**
 * Ray-casting utilities for picking and collision detection.
 * Consolidates all ray-AABB and raycasting logic.
 */
public class RayCaster {

    /**
     * Ray-AABB intersection test.
     * Returns the parametric distance t where the ray intersects the box.
     * Returns negative if no intersection.
     */
    public static float rayAABB(float ox, float oy, float oz,
                                float dx, float dy, float dz,
                                float minX, float minY, float minZ,
                                float maxX, float maxY, float maxZ) {
        float dirFracX = 1.0f / (dx != 0 ? dx : 0.000001f);
        float dirFracY = 1.0f / (dy != 0 ? dy : 0.000001f);
        float dirFracZ = 1.0f / (dz != 0 ? dz : 0.000001f);

        float t1 = (minX - ox) * dirFracX;
        float t2 = (maxX - ox) * dirFracX;
        float t3 = (minY - oy) * dirFracY;
        float t4 = (maxY - oy) * dirFracY;
        float t5 = (minZ - oz) * dirFracZ;
        float t6 = (maxZ - oz) * dirFracZ;

        float tmin = Math.max(Math.max(Math.min(t1, t2), Math.min(t3, t4)), Math.min(t5, t6));
        float tmax = Math.min(Math.min(Math.max(t1, t2), Math.max(t3, t4)), Math.max(t5, t6));

        if (tmax < 0) return -1.0f; // Box is behind ray
        if (tmin > tmax) return -1.0f; // Ray misses box
        if (tmin < 0) return tmax; // Ray origin inside box, return exit point
        return tmin;
    }

    /**
     * Calculate mouse ray direction from camera perspective.
     * Returns normalized direction vector [x, y, z].
     */
    public static float[] getMouseRay(double mouseX, double mouseY,
                                      int screenWidth, int screenHeight,
                                      float fov, float pitch, float yaw) {
        float nx = (float) (2.0f * mouseX) / screenWidth - 1.0f;
        float ny = 1.0f - (float) (2.0f * mouseY) / screenHeight;
        float fovRad = (float) Math.toRadians(fov / 2.0);
        float aspect = (float) screenWidth / screenHeight;

        float vx = nx * aspect * (float) Math.tan(fovRad);
        float vy = ny * (float) Math.tan(fovRad);
        float vz = -1.0f;

        float length = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        vx /= length; vy /= length; vz /= length;

        float pitchRad = (float) Math.toRadians(-pitch);
        float yawRad = (float) Math.toRadians(-yaw);

        float px = vx;
        float py = vy * (float) Math.cos(pitchRad) - vz * (float) Math.sin(pitchRad);
        float pz = vy * (float) Math.sin(pitchRad) + vz * (float) Math.cos(pitchRad);

        float rx = px * (float) Math.cos(yawRad) + pz * (float) Math.sin(yawRad);
        float ry = py;
        float rz = -px * (float) Math.sin(yawRad) + pz * (float) Math.cos(yawRad);

        return new float[]{rx, ry, rz};
    }
}