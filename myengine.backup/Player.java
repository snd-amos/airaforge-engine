package com.myengine;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT32;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

class Player {
    public enum State { IDLE, WALKING, RUNNING, IN_AIR, WALL_RUNNING, LEDGE_GRABBING, SLIDING }
    public State currentState = State.IDLE;

    public float x = 0.0f, y = Config.PLAYER_START_Y, z = 0.0f;
    public float yaw = 0.0f, pitch = 0.0f, roll = 0.0f;

    public float xVel = 0.0f, yVel = 0.0f, zVel = 0.0f;

    public boolean isGrounded = false;
    public boolean isFlying = false;

    private boolean runningOnLeftWall = false;
    private float grabTargetY = 0.0f;
    private float grabTopY = 0.0f;

    private double jumpCooldown = 0.0;

    private GameObject lastWallJumpedFrom = null;
    private GameObject currentWallObject = null;

    public float currentFov = 70.0f;
    public float cameraYOffset = 0.0f;
    private float headBobTime = 0.0f;

    private final float accelGround = 50.0f;
    private final float accelAir = 15.0f;
    private final float maxWalkSpeed = 6.0f;
    private final float maxRunSpeed = 12.0f;
    private final float frictionGround = 10.0f;
    private final float frictionAir = 1.0f;
    private final float gravity = 25.0f;
    private final float jumpForce = 10.0f;
    private final float wallJumpForwardForce = 14.0f;
    private final float wallJumpSideForce = 6.0f;
    private final float wallJumpVerticalForce = 8.0f;
    private final float stepHeight = 0.6f;

    public void update(boolean[] keys, World world, boolean isTyping, double dt) {
        if (isTyping) return;
        float fdt = (float) dt;
        float yawRad = (float) Math.toRadians(yaw);

        if (jumpCooldown > 0) {
            jumpCooldown -= dt;
        }

        if (isGrounded) {
            lastWallJumpedFrom = null;
            currentWallObject = null;
        }

        float fX = (float) Math.sin(yawRad); float fZ = (float) -Math.cos(yawRad);
        float rX = (float) Math.cos(yawRad); float rZ = (float) Math.sin(yawRad);
        float lX = -rX; float lZ = -rZ;

        float dlX = (fX + lX) * 0.707f; float dlZ = (fZ + lZ) * 0.707f;
        float drX = (fX + rX) * 0.707f; float drZ = (fZ + rZ) * 0.707f;

        float sideCheckDist = 0.8f;
        float checkY = y + 1.0f;

        GameObject wallObjectLeft = getWallObject(x + lX * sideCheckDist, checkY, z + lZ * sideCheckDist, world);
        if (wallObjectLeft == null) wallObjectLeft = getWallObject(x + dlX * sideCheckDist, checkY, z + dlZ * sideCheckDist, world);
        boolean isWallLeft = wallObjectLeft != null;

        GameObject wallObjectRight = getWallObject(x + rX * sideCheckDist, checkY, z + rZ * sideCheckDist, world);
        if (wallObjectRight == null) wallObjectRight = getWallObject(x + drX * sideCheckDist, checkY, z + drZ * sideCheckDist, world);
        boolean isWallRight = wallObjectRight != null;

        // --- LEDGE GRAB ALGORITHM ---
        boolean canGrabLedge = false;

        if (currentState == State.IN_AIR && yVel <= 1.0f && keys[GLFW_KEY_E]) {
            GameObject ledgeBlock = getHighestBlockInFront(x + fX * 0.8f, z + fZ * 0.8f, world);

            if (ledgeBlock != null) {
                float topY = ledgeBlock.getMaxY();

                if (y >= topY - 0.5f && y <= topY + 0.5f) {
                    boolean spaceAboveLedge = !checkCollision(x + fX * 0.8f, topY + 1.8f, z + fZ * 0.8f, world);

                    if (spaceAboveLedge) {
                        canGrabLedge = true;
                        grabTopY = topY;
                        grabTargetY = topY + 0.2f;
                    }
                }
            }
        }

        if (canGrabLedge) {
            currentState = State.LEDGE_GRABBING;
            lastWallJumpedFrom = null; // Reset wall jump penalty on grab
            currentWallObject = null;
        }

        if (currentState == State.LEDGE_GRABBING) {
            xVel = 0; yVel = 0; zVel = 0;
            y = grabTargetY;
            roll += (0.0f - roll) * 10.0f * fdt;

            if (keys[GLFW_KEY_SPACE] && jumpCooldown <= 0) {
                //TELEPORT
                y = grabTopY + 1.81f;
                x += fX * 0.6f;
                z += fZ * 0.6f;

                xVel = 0.0f;
                zVel = 0.0f;
                yVel = -1.0f;

                jumpCooldown = 0.5;
                currentState = State.IN_AIR;
                return;
            } else if (keys[GLFW_KEY_S]) {
                currentState = State.IN_AIR;
                yVel = -2.0f;
                return;
            }

            // SHIMMY MECHANIC
            float shimmySpeed = 3.0f;
            float oldX = x;
            float oldZ = z;

            if (keys[GLFW_KEY_A]) {
                x += lX * shimmySpeed * fdt;
                z += lZ * shimmySpeed * fdt;
            } else if (keys[GLFW_KEY_D]) {
                x += rX * shimmySpeed * fdt;
                z += rZ * shimmySpeed * fdt;
            }

            if (checkCollision(x, y, z, world)) {
                x = oldX;
                z = oldZ;
            }

            // CORNER DETACHMENT
            GameObject currentLedge = getHighestBlockInFront(x + fX * 0.8f, z + fZ * 0.8f, world);
            if (currentLedge == null || Math.abs(currentLedge.getMaxY() - grabTopY) > 0.1f) {
                currentState = State.IN_AIR;
                yVel = -1.0f;
            }

            return;
        }

        // --- FSM WITH POWER SLIDE, WALL RUN ---
        State previousState = currentState;
        boolean wantWallRunLeft = keys[GLFW_KEY_W] && keys[GLFW_KEY_A] && isWallLeft && !isWallRight;
        boolean wantWallRunRight = keys[GLFW_KEY_W] && keys[GLFW_KEY_D] && isWallRight && !isWallLeft;
        boolean wantSlide = keys[GLFW_KEY_C];

        boolean ceilingAbove = isBlockedAbove(world);
        float currentSpeed = (float) Math.sqrt(xVel*xVel + zVel*zVel);

        if (isGrounded && (wantSlide || (currentState == State.SLIDING && ceilingAbove))) {
            if (currentState != State.SLIDING && currentSpeed > 5.0f && wantSlide) {
                xVel += fX * 6.0f; // Slide Boost!
                zVel += fZ * 6.0f;
            }
            if (currentSpeed > 1.0f || ceilingAbove) {
                currentState = State.SLIDING;
            } else {
                currentState = State.IDLE;
            }
        } else if (!isGrounded && !isFlying && yVel <= 2.0f) {
            if (wantWallRunLeft && wallObjectLeft != lastWallJumpedFrom) {
                currentState = State.WALL_RUNNING;
                runningOnLeftWall = true;
                currentWallObject = wallObjectLeft;
            } else if (wantWallRunRight && wallObjectRight != lastWallJumpedFrom) {
                currentState = State.WALL_RUNNING;
                runningOnLeftWall = false;
                currentWallObject = wallObjectRight;
            } else {
                currentState = State.IN_AIR;
            }
        } else if (!isGrounded && !isFlying) {
            currentState = State.IN_AIR;
        } else if (keys[GLFW_KEY_W] || keys[GLFW_KEY_A] || keys[GLFW_KEY_S] || keys[GLFW_KEY_D]) {
            if (keys[GLFW_KEY_LEFT_SHIFT]) {
                currentState = State.RUNNING;
            } else {
                currentState = State.WALKING;
            }
        } else {
            currentState = State.IDLE;
        }

        // PHYSICAL SLIDING CAMERA DROPS & STAND UPS
        if (previousState != State.SLIDING && currentState == State.SLIDING) {
            y -= 1.0f;
            cameraYOffset = 1.0f; // Counter-weight visual offset to create a smooth animation
        } else if (previousState == State.SLIDING && currentState != State.SLIDING) {
            y += 1.0f;
            cameraYOffset = -1.0f;
        }

        float inputX = 0, inputZ = 0;
        if (currentState != State.SLIDING) {
            if (keys[GLFW_KEY_W]) inputZ -= 1;
            if (keys[GLFW_KEY_S]) inputZ += 1;
            if (keys[GLFW_KEY_A]) inputX -= 1;
            if (keys[GLFW_KEY_D]) inputX += 1;
        }

        float length = (float) Math.sqrt(inputX*inputX + inputZ*inputZ);
        if (length > 0) {
            inputX /= length;
            inputZ /= length;
        }

        float dirX = 0, dirZ = 0;
        if (inputZ < 0) { dirX += fX; dirZ += fZ; }
        if (inputZ > 0) { dirX -= fX; dirZ -= fZ; }
        if (inputX < 0) { dirX += lX; dirZ += lZ; }
        if (inputX > 0) { dirX += rX; dirZ += rZ; }

        float dirLength = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dirLength > 0) {
            dirX /= dirLength;
            dirZ /= dirLength;
        }

        if (!isFlying) {
            float currentAccel = (currentState == State.IN_AIR || currentState == State.WALL_RUNNING) ? accelAir : accelGround;
            xVel += dirX * currentAccel * fdt;
            zVel += dirZ * currentAccel * fdt;

            float targetMaxSpeed = (currentState == State.RUNNING) ? maxRunSpeed : maxWalkSpeed;
            float currentFriction = (currentState == State.IN_AIR || currentState == State.WALL_RUNNING) ? frictionAir : frictionGround;
            if (currentState == State.SLIDING) currentFriction = 1.8f; // Ice-like sliding!

            if (length == 0 || currentSpeed > targetMaxSpeed || currentState == State.SLIDING) {
                float drop = currentSpeed * currentFriction * fdt;
                float multiplier = Math.max(currentSpeed - drop, 0) / (currentSpeed == 0 ? 1 : currentSpeed);
                xVel *= multiplier;
                zVel *= multiplier;
            }

            x += xVel * fdt;
            if (checkCollision(x, y, z, world)) {
                if (isGrounded && currentState != State.SLIDING && !checkCollision(x, y + stepHeight, z, world)) {
                    y += stepHeight;
                } else {
                    x -= xVel * fdt;
                    xVel = 0;
                }
            }

            z += zVel * fdt;
            if (checkCollision(x, y, z, world)) {
                if (isGrounded && currentState != State.SLIDING && !checkCollision(x, y + stepHeight, z, world)) {
                    y += stepHeight;
                } else {
                    z -= zVel * fdt;
                    zVel = 0;
                }
            }

            yVel -= gravity * fdt;

            if (currentState == State.WALL_RUNNING) {
                if (yVel < -1.5f) yVel = -1.5f;

                float targetRoll = runningOnLeftWall ? 15.0f : -15.0f;
                roll += (targetRoll - roll) * 10.0f * fdt;
            } else {
                roll += (0.0f - roll) * 15.0f * fdt;
            }

            y += yVel * fdt;

            if (checkCollision(x, y, z, world)) {
                y -= yVel * fdt;
                if (yVel < 0) isGrounded = true;
                yVel = 0;
            } else {
                isGrounded = false;
            }

            if (keys[GLFW_KEY_SPACE] && jumpCooldown <= 0) {
                if (isGrounded && !ceilingAbove) {
                    yVel = jumpForce;
                    isGrounded = false;
                    currentState = State.IN_AIR;
                    jumpCooldown = 0.2;
                } else if (currentState == State.WALL_RUNNING) {
                    yVel = wallJumpVerticalForce;

                    float pushOffX = runningOnLeftWall ? rX : lX;
                    float pushOffZ = runningOnLeftWall ? rZ : lZ;

                    xVel = (fX * wallJumpForwardForce) + (pushOffX * wallJumpSideForce);
                    zVel = (fZ * wallJumpForwardForce) + (pushOffZ * wallJumpSideForce);

                    currentState = State.IN_AIR;
                    jumpCooldown = 0.2;
                    lastWallJumpedFrom = currentWallObject;
                    currentWallObject = null;
                }
            }
        } else {
            float flySpeed = 20.0f;
            x += dirX * flySpeed * fdt;
            z += dirZ * flySpeed * fdt;
            if (keys[GLFW_KEY_SPACE]) y += flySpeed * fdt;
            if (keys[GLFW_KEY_C]) y -= flySpeed * fdt;
            yVel = 0;
            roll = 0.0f;
        }

        // --- PROCEDURAL CAMERA DYNAMICS CALCULATIONS ---
        float targetFov = 70.0f;
        if (currentState == State.RUNNING || currentState == State.WALL_RUNNING) targetFov = 90.0f;
        if (currentState == State.SLIDING) targetFov = 100.0f;
        currentFov += (targetFov - currentFov) * 5.0f * fdt;

        float targetCamOffset = 0.0f;
        if (isGrounded && currentSpeed > 1.0f && currentState != State.SLIDING) {
            headBobTime += currentSpeed * fdt * 1.5f;
            float bobAmp = (currentState == State.RUNNING) ? 0.12f : 0.06f;
            targetCamOffset = (float) Math.sin(headBobTime) * bobAmp;
        } else {
            headBobTime = 0;
        }
        cameraYOffset += (targetCamOffset - cameraYOffset) * 15.0f * fdt;
    }

    private GameObject getHighestBlockInFront(float px, float pz, World world) {
        GameObject highest = null;
        float maxY = -1000f;
        List<GameObject> nearby = world.getNearbyEntities(px, pz);
        for (GameObject b : nearby) {
            float r = Config.PLAYER_RADIUS + 0.1f;
            if (px + r > b.getMinX() && px - r < b.getMaxX() &&
                    pz + r > b.getMinZ() && pz - r < b.getMaxZ()) {
                if (b.getMaxY() > maxY) {
                    maxY = b.getMaxY();
                    highest = b;
                }
            }
        }
        return highest;
    }

    // BUG FIX
    private boolean isBlockedAbove(World world) {
        List<GameObject> nearby = world.getNearbyEntities(x, z);
        for (GameObject b : nearby) {
            if (x + Config.PLAYER_RADIUS > b.getMinX() && x - Config.PLAYER_RADIUS < b.getMaxX() &&
                    z + Config.PLAYER_RADIUS > b.getMinZ() && z - Config.PLAYER_RADIUS < b.getMaxZ() &&
                    y + 0.2f < b.getMaxY() && y + 1.2f > b.getMinY()) {
                return true;
            }
        }
        return false;
    }

    private GameObject getWallObject(float px, float py, float pz, World world) {
        float dynamicFeet = (currentState == State.SLIDING) ? 0.8f : 1.8f;
        List<GameObject> nearby = world.getNearbyEntities(px, pz);
        for (GameObject b : nearby) {
            if (px + Config.PLAYER_RADIUS > b.getMinX() && px - Config.PLAYER_RADIUS < b.getMaxX() &&
                    pz + Config.PLAYER_RADIUS > b.getMinZ() && pz - Config.PLAYER_RADIUS < b.getMaxZ() &&
                    py - dynamicFeet < b.getMaxY() && py + 0.2f > b.getMinY()) {
                return b;
            }
        }
        return null;
    }

    // BUG FIX
    private boolean checkCollision(float px, float py, float pz, World world) {
        float dynamicFeet = (currentState == State.SLIDING) ? 0.8f : 1.8f;
        float dynamicHead = 0.2f;

        List<GameObject> nearby = world.getNearbyEntities(px, pz);
        for (GameObject b : nearby) {
            if (px + Config.PLAYER_RADIUS > b.getMinX() && px - Config.PLAYER_RADIUS < b.getMaxX() &&
                    pz + Config.PLAYER_RADIUS > b.getMinZ() && pz - Config.PLAYER_RADIUS < b.getMaxZ() &&
                    py - dynamicFeet < b.getMaxY() && py + dynamicHead > b.getMinY()) {
                return true;
            }
        }
        return false;
    }

    public float[] getMouseRay(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        return RayCaster.getMouseRay((float)mouseX, (float)mouseY, screenWidth, screenHeight,
                Config.FOV, pitch, yaw);
    }

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