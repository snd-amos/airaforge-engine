package com.myengine;

import java.util.List;
import static org.lwjgl.glfw.GLFW.*;

/**
 * Player camera and physics controller.
 * Handles movement, jumping, flying, collision detection, and raycasting.
 */
public class Player {
    public float x = 0.0f, y = Config.PLAYER_START_Y, z = 0.0f;
    public float yaw = 0.0f, pitch = 0.0f;
    public float yVelocity = 0.0f;
    public boolean isGrounded = false;
    public boolean isFlying = false;

    /**
     * Update player position based on input and physics.
     */
    public void update(boolean[] keys, World world, boolean isTyping) {
        if (isTyping) return;

        float currentSpeed = isFlying ? Config.PLAYER_SPEED * Config.PLAYER_FLY_SPEED_MULTIPLIER : Config.PLAYER_SPEED;
        float yawRad = (float) Math.toRadians(yaw);

        float dx = 0, dz = 0;
        if (keys[GLFW_KEY_W]) {
            dx += (float) Math.sin(yawRad) * currentSpeed;
            dz -= (float) Math.cos(yawRad) * currentSpeed;
        }
        if (keys[GLFW_KEY_S]) {
            dx -= (float) Math.sin(yawRad) * currentSpeed;
            dz += (float) Math.cos(yawRad) * currentSpeed;
        }
        if (keys[GLFW_KEY_A]) {
            dx -= (float) Math.cos(yawRad) * currentSpeed;
            dz -= (float) Math.sin(yawRad) * currentSpeed;
        }
        if (keys[GLFW_KEY_D]) {
            dx += (float) Math.cos(yawRad) * currentSpeed;
            dz += (float) Math.sin(yawRad) * currentSpeed;
        }

        // Apply horizontal movement with collision detection
        x += dx;
        if (!isFlying && checkCollision(x, y, z, world)) {
            x -= dx;
        }
        z += dz;
        if (!isFlying && checkCollision(x, y, z, world)) {
            z -= dz;
        }

        // Handle vertical movement
        if (isFlying) {
            if (keys[GLFW_KEY_SPACE]) y += currentSpeed;
            if (keys[GLFW_KEY_LEFT_SHIFT]) y -= currentSpeed;
            yVelocity = 0;
        } else {
            applyPhysics(world);
            if (keys[GLFW_KEY_SPACE] && isGrounded) {
                yVelocity = Config.JUMP_POWER;
                isGrounded = false;
            }
        }
    }

    /**
     * Apply gravity and handle collision for non-flying movement.
     */
    private void applyPhysics(World world) {
        yVelocity -= Config.GRAVITY;
        y += yVelocity;
        if (checkCollision(x, y, z, world) || y <= Config.PLAYER_START_Y) {
            if (y <= Config.PLAYER_START_Y) {
                y = Config.PLAYER_START_Y;
            } else {
                y -= yVelocity;
            }
            yVelocity = 0;
            isGrounded = true;
        } else {
            isGrounded = false;
        }
    }

    /**
     * Check collision between player and world objects.
     */
    private boolean checkCollision(float px, float py, float pz, World world) {
        List<GameObject> nearby = world.getNearbyEntities(px, pz);

        for (GameObject b : nearby) {
            if (px + Config.PLAYER_RADIUS > b.getMinX() && px - Config.PLAYER_RADIUS < b.getMaxX() &&
                    pz + Config.PLAYER_RADIUS > b.getMinZ() && pz - Config.PLAYER_RADIUS < b.getMaxZ() &&
                    py - 2.0f < b.getMaxY() && py > b.getMinY()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the world-space ray direction from the mouse cursor position.
     */
    public float[] getMouseRay(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        return RayCaster.getMouseRay((float)mouseX, (float)mouseY, screenWidth, screenHeight,
                Config.FOV, pitch, yaw);
    }

    /**
     * Get the GameObject at the mouse cursor using raycasting.
     */
    public GameObject getEntityAtMouse(World world, double mouseX, double mouseY, int screenWidth, int screenHeight) {
        float[] ray = getMouseRay(mouseX, mouseY, screenWidth, screenHeight);

        GameObject closest = null;
        float minDist = Float.MAX_VALUE;

        for (GameObject b : world.entities) {
            float t = RayCaster.rayAABB(this.x, this.y, this.z, ray[0], ray[1], ray[2],
                    b.getMinX(), b.getMinY(), b.getMinZ(),
                    b.getMaxX(), b.getMaxY(), b.getMaxZ());
            if (t > 0 && t < minDist) {
                minDist = t;
                closest = b;
            }
        }
        return closest;
    }

    /**
     * Get the GameObject the player is looking at (forward raycast).
     */
    public GameObject getLookTarget(World world, float range) {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float dirX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float dirY = (float) (-Math.sin(pitchRad));
        float dirZ = (float) (-Math.cos(yawRad) * Math.cos(pitchRad));

        GameObject closest = null;
        float minDist = Float.MAX_VALUE;

        List<GameObject> nearby = world.getNearbyEntities(this.x, this.z);

        for (GameObject b : nearby) {
            float t = RayCaster.rayAABB(this.x, this.y, this.z, dirX, dirY, dirZ,
                    b.getMinX(), b.getMinY(), b.getMinZ(),
                    b.getMaxX(), b.getMaxY(), b.getMaxZ());
            if (t > 0 && t <= range && t < minDist) {
                minDist = t;
                closest = b;
            }
        }
        return closest;
    }
}