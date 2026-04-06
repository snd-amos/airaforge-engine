package com.myengine;

/**
 * Centralized configuration for the Game Engine.
 * All magic numbers and tunable parameters are defined here.
 */
public class Config {
    // ============ EDITOR CONFIG ============
    public static final float GIZMO_SENSITIVITY = 0.0014f;
    public static final float GIZMO_ROTATION_SPEED = 0.5f;
    public static final float GIZMO_SCALE_SPEED = 5.0f;
    public static final float GIZMO_SCALE_MIN = 0.01f;
    public static final float MOUSE_LOOK_SENSITIVITY = 0.1f;
    public static final long DOUBLE_TAP_DELAY = 250;

    // ============ PLAYER CONFIG ============
    public static final float PLAYER_SPEED = 0.05f;
    public static final float PLAYER_FLY_SPEED_MULTIPLIER = 2.0f;
    public static final float PLAYER_RADIUS = 0.3f;
    public static final float PLAYER_START_Y = 1.0f;

    // ============ PHYSICS CONFIG ============
    public static final float GRAVITY = 0.007f;
    public static final float JUMP_POWER = 0.15f;

    // ============ CAMERA CONFIG ============
    public static final float FOV = 70.0f;
    public static final float Z_NEAR = 0.1f;
    public static final float Z_FAR = 1000.0f;
    public static final float PITCH_CLAMP = 89.0f;

    // ============ WORLD CONFIG ============
    public static final float GRID_CELL_SIZE = 10.0f;
    public static final float FLOOR_GRID_SIZE = 20.0f;

    // ============ RAYCAST CONFIG ============
    public static final float RAYCAST_RANGE = 5.0f;
    public static final float RAYCAST_STEP = 0.1f;
    public static final float RAYCAST_PLACEMENT_OFFSET = 0.1f;
    public static final float RAYCAST_PLACEMENT_HEIGHT_OFFSET = 1.0f;

    // ============ GIZMO VISUALS CONFIG ============
    public static final float GIZMO_ARROW_LENGTH = 2.0f;
    public static final float GIZMO_ROTATION_RADIUS = 1.5f;
    public static final int GIZMO_CIRCLE_SEGMENTS = 64;
    public static final float GIZMO_SCALE_CUBE_SIZE = 0.3f;
    public static final float GIZMO_CENTER_CUBE_SIZE = 0.15f;

    // ============ VIEWPORT CONFIG ============
    public static final int MIN_VIEWPORT_WIDTH = 100;
    public static final int MIN_VIEWPORT_HEIGHT = 100;

    // ============ ASSET CONFIG ============
    public static final String MODELS_PATH = "src/main/resources/models/";
    public static final String TEXTURES_PATH = "/textures/";
    public static final String SHADERS_PATH = "src/main/resources/shaders/";
}