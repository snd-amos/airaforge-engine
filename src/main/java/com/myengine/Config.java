package com.myengine;

class Config {
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
    // Max height the player auto-steps over without jumping. Small on
    // purpose: curbs / low stairs are stepped, but anything cube-sized
    // (the 1m building blocks) becomes a wall you must jump onto. A large
    // value here is what used to "teleport" the player up onto 1m cubes.
    public static final float PLAYER_STEP_HEIGHT = 0.5f;
    // Not final: avoids javac's compile-time inlining so tweaks here propagate
    // to existing class files without a full rebuild.
    public static float PLAYER_START_Y = 1.8f;

    // ============ PHYSICS CONFIG ============
    public static final float GRAVITY = 0.007f;
    public static final float JUMP_POWER = 0.15f;

    // Surfaces whose normal Y is >= this are walkable (slope <= 45 deg).
    // Steeper surfaces act as walls. cos(45 deg) ~= 0.707.
    public static final float WALKABLE_NORMAL_Y = 0.707f;

    // ============ CLIMBING CONFIG ============
    public static final float CLIMB_MAX_STAMINA = 8.0f;
    public static final float CLIMB_MAX_SPEED = 3.0f;
    public static final float CLIMB_MIN_SPEED = 0.8f;
    public static final float CLIMB_STAMINA_REGEN = 4.0f;
    public static final float CLIMB_MIN_WALL_HEIGHT = 3.0f;
    public static final float CLIMB_MOUNT_THRESHOLD = 0.5f;

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
    public static final int GIZMO_CIRCLE_SEGMENTS = 128;
    // Half-width of the rotation-ring pick band around GIZMO_ROTATION_RADIUS
    // (world units). Wider = easier to grab the ring without pixel precision.
    public static final float GIZMO_ROTATION_HIT_HALF = 0.45f;
    public static final float GIZMO_SCALE_CUBE_SIZE = 0.3f;
    public static final float GIZMO_CENTER_CUBE_SIZE = 0.15f;

    // ============ VIEWPORT CONFIG ============
    public static final int MIN_VIEWPORT_WIDTH = 100;
    public static final int MIN_VIEWPORT_HEIGHT = 100;

    // ============ ASSET CONFIG ============
    public static final String MODELS_PATH = "src/main/resources/models/";
    public static final String CHARACTERS_PATH = "src/main/resources/characters/";
    public static final String CHARACTER_PATH_PREFIX = "characters/";
    public static final String ANIMATIONS_PATH = "src/main/resources/animations/";
    public static final String TEXTURES_PATH = "/textures/";
    public static final String SHADERS_PATH = "src/main/resources/shaders/";
    public static final String GAME_LEVEL_PATH = "level.json";

    // ============ SHADOW CONFIG ============
    public static final int SHADOW_MAP_WIDTH = 2048;
    public static final int SHADOW_MAP_HEIGHT = 2048;
    // Half-size of the orthographic shadow projection in world units. The
    // shadow camera follows the player; this controls how far around the
    // player gets shadowed. Not final so the Inspector slider can tune it.
    // Bigger = more coverage but lower texel density (blurrier shadows).
    public static float SHADOW_ORTHO_SIZE = 60.0f;
    public static float SHADOW_CAM_HEIGHT = 100.0f;
    public static float SHADOW_NEAR = 1.0f;
    public static float SHADOW_FAR  = 300.0f;

    // ---- Vulkan shadow controls (the OpenGL build keeps the fields above) ----
    // Single user-facing coverage control: the side length, in metres, of the
    // ground area that receives shadows around the camera. The Vulkan shadow
    // pass derives the ortho half-size, light pull-back, and far plane from
    // this, and texel-snaps the box to this size — so the user only ever
    // touches one number. Texel density = SHADOW_MAP_WIDTH / SHADOW_DISTANCE.
    public static float SHADOW_DISTANCE = 120.0f;
    // Shadow bias (Vulkan): slope-scaled normal bias + constant depth bias.
    // bias = max(NORMAL_BIAS * (1 - n·l), DEPTH_BIAS). Tuned live in the UI.
    public static float SHADOW_NORMAL_BIAS = 0.001f;
    public static float SHADOW_DEPTH_BIAS  = 0.0002f;

    // ============ TEXTURE CONFIG ============
    // Soft cap for loaded texture dimensions. Anything larger is downscaled
    // before upload. Prevents accidentally huge source PNGs (e.g. a 4725x4725
    // billboard) from eating ~90 MB of VRAM each.
    public static final int MAX_TEXTURE_SIZE = 1024;
}
