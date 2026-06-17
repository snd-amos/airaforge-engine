package com.myengine;

/**
 * Definitive data container for a character's physics / locomotion attributes.
 * Phase 3A — edited live via the Character Editor's "Locomotion Attributes"
 * sliders; consumed by the movement / physics systems in later phases.
 *
 * <p>Defaults mirror the engine's historical player tuning (radius 0.3, half-
 * height ~1.0, walk 6 m/s, sprint 12 m/s, jump 10 m/s, step 0.5 — see
 * {@code Player.java} / {@code Config.PLAYER_STEP_HEIGHT}).
 *
 * <p>Registered in {@link ComponentRegistry} (under both its short and simple-
 * class-name keys) so it survives save→reload — see the Regression vault in
 * CLAUDE.md. (We use a custom {@code JsonDeserializer} + {@code ComponentRegistry},
 * NOT a Gson {@code RuntimeTypeAdapterFactory}.)
 */
class CharacterLocomotionComponent extends Component {
    public float collisionRadius     = 0.3f;
    public float collisionHalfHeight = 1.0f;
    /** Phase 6B — vertical offset of the collision capsule's centre relative to
     *  the transform/eye. Shifts the whole capsule up (+) / down (−). */
    public float collisionOffsetY    = 0.0f;
    public float walkSpeed           = 6.0f;
    public float sprintSpeed         = 12.0f;
    public float jumpVelocity        = 10.0f;
    public float stepHeight          = 0.5f;

    // ---- Phase 3B: first-person integration ------------------------------
    /** Eye height (metres above the character's transform origin) the
     *  first-person camera sits at. Default 1.6 ≈ human eye level. Mirrors the
     *  player camera's existing {@code Player.cameraYOffset} — kept here so a
     *  character's eye line is authored alongside its collision capsule.
     *  NOTE: in the live engine the camera IS the shared {@code Player}
     *  (see {@code VulkanMain.runPlayerPhysics}: {@code camY = player.y +
     *  player.cameraYOffset + player.stepCamOffset}), so this value is the
     *  per-character authoring counterpart, not a second camera. */
    public float cameraHeightOffset = 1.6f;

    /** Index of the mesh submesh to hide while this character is the active
     *  first-person avatar in play mode (default -1 = hide nothing). The
     *  Vulkan mesh path MERGES all submeshes into a single {@code VkMesh}
     *  (one {@code indexCount}, no per-submesh ranges — see
     *  {@code VkMeshLoader}), so any value &gt;= 0 hides the whole avatar mesh:
     *  exactly the "don't render my own body in first person" behaviour.
     *  Consumed by {@code VulkanMain.hiddenAtRuntime}. */
    public int hiddenSubmeshIndex = -1;

    // ---- Phase 6A: animation state machine ------------------------------
    /** Animation clip FILE PATHS for the four core locomotion states. Stored as
     *  String paths (e.g. {@code "characters/Hazmat_Idle.glb"}) because the
     *  engine loads clips by path via {@code AnimationLoader.loadAnimation};
     *  plain public Strings serialize cleanly through Gson. Empty = unassigned.
     *  Driven in play mode by {@code VulkanMain.updateLocomotionStateMachine}. */
    public String animIdle   = "";
    public String animWalk   = "";
    public String animSprint = "";
    public String animJump   = "";
}
