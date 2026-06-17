package com.myengine;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiBackendFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.glfw.ImGuiImplGlfw;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.myengine.VkUtil.check;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan renderer entry point. Owns the window, the camera, the scene
 * assets (model, floor, skybox, shadow map), the three rendering pipelines
 * (model, shadow, skybox), and the per-frame draw loop.
 *
 * <p>What this class does NOT own — extracted into focused helpers so
 * VulkanMain stays a "game" file:
 * <ul>
 *   <li>{@link VkContext} — instance / debug / device / queues / command pool.</li>
 *   <li>{@link VkSwapchain} — swapchain / framebuffers / depth / render pass / sync.</li>
 *   <li>{@link VkPipelineBuilder} — fluent builder for VkPipeline (collapses the
 *       fixed-function boilerplate). Each pipeline in this file fits in ~15 lines.</li>
 *   <li>{@link VkShadowMap} — depth target + render pass + sampler for sun shadows.</li>
 *   <li>{@link VkMesh}, {@link VkTexture}, {@link VkMeshLoader} — geometry + textures.</li>
 * </ul>
 *
 * <p>Convention notes (matching the OpenGL renderer so engine state ports 1:1):
 * <ul>
 *   <li>Camera yaw/pitch in degrees. yaw=0 looks toward -Z. Positive pitch =
 *       look DOWN (mirrors Player.java's dirY = -sin(pitch)).</li>
 *   <li>View matrix: {@code rotateX(pitch) · rotateY(yaw) · translate(-camPos)}.
 *       Matches LevelEditorScene.java lines 441-448.</li>
 *   <li>Push-constant layouts: see MODEL_PC_FLOATS / SHADOW_PC_FLOATS.</li>
 *   <li>Y-flip is done via a negative-height viewport (Vulkan's down-Y NDC).</li>
 * </ul>
 */
public class VulkanMain {

    static {
        // LWJGL's per-thread MemoryStack defaults to 64KB. VkInstance creation
        // enumerates every device extension into a stack buffer; modern NVIDIA
        // drivers exceed 64KB by themselves. Bump BEFORE any LWJGL static init.
        if (System.getProperty("org.lwjgl.system.stackSize") == null) {
            System.setProperty("org.lwjgl.system.stackSize", "512");
        }
    }

    // ---- config knobs ----
    private static final boolean ENABLE_VALIDATION = true;
    private static final int  MAX_FRAMES_IN_FLIGHT = 2;
    private static final float CLEAR_R = 0.10f, CLEAR_G = 0.40f, CLEAR_B = 0.70f;

    // Push-constant float counts. Named so the build site + push sites stay in sync.
    private static final int MODEL_PC_FLOATS  = 60;  // 3 mat4 + 3 vec4 (material/tint/sunDir)
    private static final int SHADOW_PC_FLOATS = 36;  // 2 mat4 + 1 vec4 (foliage flag)
    // Cube pipeline PC: viewProj + lightSpace (per-frame) + material + sunDir
    // (per-group + per-frame). Per-entity model matrix and face colours come
    // via the per-instance vertex binding.
    private static final int CUBE_PC_FLOATS   = 40;  // 2 mat4 + 2 vec4

    // Asset paths.
    private static final String LEVEL_PATH    = "level.json";
    private static final String MODELS_DIR    = "src/main/resources/models/";
    private static final String TEXTURES_DIR  = "src/main/resources/textures/";
    private static final String[] SKYBOX_FACES = { "px.png","nx.png","py.png","ny.png","pz.png","nz.png" };

    // Sun direction — recomputed per frame from the "Sun" entity's position
    // in the level (normalized to a unit direction vector). Falls back to a
    // default if no sun entity exists. Drives BOTH the shadow camera and the
    // model fragment shader's diffuse calc, so the shadow stays aligned with
    // the lit/unlit sides of geometry. Editor convention: see LevelEditorScene
    // lines 322-328 (the OpenGL renderer derives lightView the same way).
    private static final float[] SUN_DIR_DEFAULT = normalize3(0.5f, 0.8f, 0.3f);
    private float sunDirX = SUN_DIR_DEFAULT[0];
    private float sunDirY = SUN_DIR_DEFAULT[1];
    private float sunDirZ = SUN_DIR_DEFAULT[2];
    // Sun light intensity — matches LevelEditorScene.sunIntensity (default 0.5).
    private float sunIntensity = 0.5f;

    // ---- core infra ----
    private long         glfwWindow;
    private VkContext    ctx;
    private VkSwapchain  swap;
    private VkCommandBuffer[] commandBuffers;
    private double       startNanos;

    // ---- scene assets ----
    // The world is the deserialized contents of level.json. Each entity's
    // Transform + MeshRenderer drives one or more draws per frame. VkMesh /
    // VkTexture / descriptor-set resources are built lazily on first
    // reference and cached so repeats are cheap.
    private List<GameObject> entities = new ArrayList<>();
    private final Map<String, VkMesh>    meshCache              = new HashMap<>();
    private final Map<String, VkTexture> textureCache           = new HashMap<>();
    private final Map<String, VkTexture> embeddedTextureByModel = new HashMap<>();
    private final Map<String, Long>      descriptorSetCache     = new HashMap<>();
    // Tracks asset keys whose load already failed, so we don't retry every
    // frame (typos in the Inspector's Model/Texture fields, missing files,
    // etc.). Reset only by Reload Level.
    private final java.util.Set<String> failedAssetKeys = new java.util.HashSet<>();

    private VkMesh    skyboxMesh;
    private VkTexture skyboxCubemap;
    private VkShadowMap shadowMap;

    // ---- Offscreen scene target (Scene Viewport panel source) ----
    // The scene render pass renders into this; ImGui samples its colour
    // view inside the Scene Viewport panel. Resized to match the panel.
    // In play mode (no panels), the offscreen image fills the swapchain
    // via a fullscreen ImGui pass.
    private VkOffscreenTarget   sceneTarget;
    // Character Editor (Phase 1): a SECOND offscreen target sampled by its own
    // ImGui panel, isolated from the Scene Viewport.
    private VkOffscreenTarget   characterTarget;
    private long                characterTexId;    // ImGui handle for characterTarget.colorView()
    // Phase 2 (AAA overhaul): a THIRD offscreen target — the "Game" viewport,
    // rendered from the active player's camera (no editor debug overlays).
    private VkOffscreenTarget   gameTarget;
    private long                gameTexId;
    private final imgui.type.ImBoolean showGame = new imgui.type.ImBoolean(true);
    private int                 gameWantW = 960, gameWantH = 540;
    private final CharacterEditorWindow characterEditor = new CharacterEditorWindow();
    // Isolated preview entity — NEVER added to the main `entities` list; it
    // exists only for the Character Editor's offscreen pass (Phase 2).
    private GameObject          previewCharacter;
    // Phase 4: the world avatar last spawned by "Set as Active Player". Tracked
    // so a re-press removes the previous one (no duplicate avatars).
    private GameObject          activePlayerEntity;
    // Phase 6C: F5 third-person debug camera (play mode). Pulls the camera back
    // behind the player and force-renders the avatar so animations are visible.
    private boolean             isThirdPerson = false;
    private static final float CHAR_CLEAR_R = 0.12f, CHAR_CLEAR_G = 0.14f, CHAR_CLEAR_B = 0.18f;
    private VkLineRenderer      lineRenderer;
    private VkGizmo             gizmo;
    private VkSelectionRenderer selectionRenderer;
    private VkOutlineRenderer   outlineRenderer;
    // Gizmo interaction state — mirrors LevelEditorScene fields.
    private int     draggingAxis    = -1;
    private boolean isHoveringX     = false;
    private boolean isHoveringY     = false;
    private boolean isHoveringZ     = false;
    private boolean isHoveringCenter = false;
    // Raw gizmo hover-axis (0/1/2 = +X/+Y/+Z, 3 = centre, 4/5/6 = -X/-Y/-Z for
    // the 6-way extrude gizmo, -1 = none). Carries the hover state to render().
    private int     gizmoHoverAxis  = -1;
    // Drag-edit working state — LevelEditorScene lines 52-53.
    private double lastDragMouseX, lastDragMouseY;
    private float  rawDragX, rawDragY, rawDragZ;
    // Free-move (Move mode, axis 3 / centre cube) — snapshot taken on
    // drag start so each frame can project the mouse ray onto a plane
    // through the entity perpendicular to the camera-forward at the
    // ORIGINAL camera-to-entity distance. Without this the entity
    // would "drift" toward / away from the camera as the mouse moves
    // because the projection plane would track the moving entity.
    private float freeMoveDistance = 0f;
    // Raw (unsnapped) accumulators for Rotate + Scale. Without these,
    // shift-snap re-reads the already-snapped transform.rot* / scale*
    // value next frame, adds a tiny delta, and rounds it back to the
    // same snap step → drag feels sticky / dead. With raw accumulators
    // the per-frame deltas pile up smoothly; the snap is only applied
    // when writing the value out to the entity.
    // Rotate: snapshotted on drag start (the entity's rotation BEFORE
    // any drag occurred). rotDragAccum is the total degrees applied
    // around the active world axis since the drag began.
    private float preDragRotX, preDragRotY, preDragRotZ;
    private float rotDragAccum = 0f;
    // Ray-plane rotation drag state: the previous frame's angle on the ring's
    // plane (degrees) and whether it's been anchored yet this drag. The gizmo
    // maps the cursor's intersection with the ring plane to an absolute angle,
    // accumulating per-frame increments into rotDragAccum (no screen-delta dead
    // zones / inversion). Re-anchored when the edge-on fallback kicks in.
    private float rotDragPrevAngle = 0f;
    private boolean rotDragHasStart = false;
    // Scale: continuous per-axis accumulator. Initialized to the
    // entity's current scale on drag start.
    private float rawScaleX = 1f, rawScaleY = 1f, rawScaleZ = 1f;
    private long              viewportTexId;        // ImGui handle for sceneTarget.colorView()
    private long              shadowMapTexId;       // ImGui handle for the shadow depth view
    private long              shadowMapImGuiSampler;  // separate sampler so depth shows greyscale
    private long              shadowMapImGuiView;     // R-swizzled view (depth → greyscale RGB)

    // Scene Viewport panel geometry — populated each frame by
    // renderSceneViewport(). Used by click-to-select to map screen mouse
    // coords into viewport-local coords. Mirrors UIManager fields 31-33.
    private float   viewportX, viewportY, viewportW = 1024, viewportH = 720;
    private boolean isViewportHovered = false;
    // Pending click: when the user clicks inside the viewport in editor
    // mode, the GLFW mouse-button callback records the global mouse coords
    // here. The next mainLoop iteration consumes them after ImGui has
    // updated viewportX/Y/W/H, so coords map correctly.
    private boolean pendingClickInViewport = false;
    private double  pendingClickX, pendingClickY;

    // ---- ImGui ----
    private ImGuiImplGlfw   imGuiGlfw;
    private VkImGuiRenderer imGuiVk;
    private VkViewportRenderer viewportRenderer;   // multi-viewport renderer (detached panels)
    private VkGlfwPlatformIO   platformBackend;    // multi-viewport platform (GLFW NO_API windows)

    // Master switch for ImGui multi-viewport (drag panels out as real OS
    // windows). Currently OFF — internal docking only. The full backend
    // (VkViewportRenderer + VkGlfwPlatformIO + VkImGuiRenderer.record) is built
    // and wired, but two integration problems make detached windows unusable:
    //   1) Multi-swapchain present contention — the secondary vkQueueSubmit /
    //      vkQueuePresentKHR interleave with the main MAILBOX present on the
    //      shared queue with no cross-window ordering → jitter / black flashes.
    //   2) ViewportsEnable switches ImGui to GLOBAL desktop mouse coords, but
    //      the engine's raycaster reads main-window-local glfwGetCursorPos and
    //      subtracts the Scene Viewport panel's getWindowPos() — which becomes
    //      global — so picking / box-select compute garbage.
    // PERMANENTLY OFF (Multi-Process pivot): single-threaded multi-swapchain
    // presentation was incompatible with our one-queue structure (VUID errors /
    // tearing / jitter). The editor is locked to internal docking; detached game
    // views are now a SEPARATE PROCESS (see --runtime). VkViewportRenderer +
    // VkGlfwPlatformIO are kept as dormant archive code, instantiated ONLY in the
    // dead `if (MULTI_VIEWPORT)` block in initImGui — so ViewportsEnable is never
    // set and the picking ImGui-coord path stays correct in the single window.
    private static final boolean MULTI_VIEWPORT = false;
    private float fpsAvg = 0f;

    // ---- Editor / Play mode state ----
    // Conventions ported verbatim from LevelEditorScene.java:
    //   - Start in Editor mode (line 66: isEditorMode = true).
    //   - P toggles between modes (lines 802-816).
    //   - Editor mode: cursor visible, ImGui panels show, no mouselook.
    //   - Play mode:   cursor disabled, mouselook on, panels hidden.
    private boolean isEditorMode = true;
    // Multi-Process pivot: when launched with --runtime, the engine skips the
    // menu + editor entirely and boots straight into Play (standalone game).
    private boolean runtimeMode = false;
    // Deferred-boot latch: when runtimeMode, enterRuntimePlay() runs on the
    // first main-loop frame (not in run()) so the Vulkan context is fully
    // live for the level-reload GPU uploads. Reset to false after firing.
    private boolean runtimePlayPending = true;
    private GameObject selectedEntity = null;
    private GameObject clipboardEntity = null;
    // Hover-pick — what the cursor is OVER right now. Updated every frame
    // when the cursor is inside the Scene Viewport; drawn in dim orange so
    // the user sees what an LMB click would select before they click.
    // Same separation as LevelEditorScene's selectedBox / highlighted pair.
    private GameObject highlighted = null;
    // App-level scene state. Start in Main Menu (matches OpenGL Window.run:47
    // which immediately switches into MainMenuScene). Esc from Editor/Play
    // returns here (matches Window.java:160-164).
    private boolean inMainMenu = true;

    // Real Player + World (sub-phase E). World is constructed WITHOUT
    // calling init() — that path registers GL Mesh objects in AssetPool
    // and needs a GL context. We only use World as a container for the
    // entities list + spatial grid used by MeshCollider.
    // Player drives the camera in BOTH editor and play mode (matching
    // LevelEditorScene which always runs player.update). isFlying is
    // toggled by double-tap Space, exactly like the OpenGL build.
    private final World  world  = new World();
    private final Player player = new Player();
    // Double-tap space → Player.isFlying toggle. Matches
    // LevelEditorScene.onKeyPress lines 867-876.
    private long lastSpacePressMs = 0L;
    // Undo / redo — matches LevelEditorScene's manager.
    private final UndoRedoManager undoRedo = new UndoRedoManager();
    // Inspector edit-state tracking — ported from UIManager fields 34-40.
    private GameObject lastEditedObject = null;
    private float beforePosX, beforePosY, beforePosZ;
    private float beforeRotX, beforeRotY, beforeRotZ;
    private float beforeScaleX, beforeScaleY, beforeScaleZ;
    private boolean wasEditingPosition = false;
    private boolean wasEditingRotation = false;
    private boolean wasEditingScale    = false;

    // Inspector edit buffers — match UIManager.java fields 15-18.
    private final imgui.type.ImString nameInput   = new imgui.type.ImString(256);
    private final imgui.type.ImString texInput    = new imgui.type.ImString(256);
    private final imgui.type.ImString modelInput  = new imgui.type.ImString(256);
    private final imgui.type.ImString animInput   = new imgui.type.ImString(256);
    // Asset Browser — one full-width search + per-section expand state.
    private final imgui.type.ImString assetSearch = new imgui.type.ImString(64);
    private final boolean[] expModels = { false };   // Models & Characters pane
    private final boolean[] expAnims  = { false };
    private final boolean[] expTex    = { false };

    /** One Asset Browser tile: display name, drag payload, optional click. */
    private record AssetEntry(String name, String payload, Runnable onClick) {}
    private GameObject lastInspectedObject = null;

    // Panel visibility toggles — driven by the "Window" main-menu items and
    // each panel's close [x]. ImGui persists window pos/size/dock/collapse in
    // imgui.ini; these booleans drive which panels are open.
    private final imgui.type.ImBoolean showHierarchy       = new imgui.type.ImBoolean(true);
    private final imgui.type.ImBoolean showInspector       = new imgui.type.ImBoolean(true);
    private final imgui.type.ImBoolean showAssetBrowser    = new imgui.type.ImBoolean(true);
    private final imgui.type.ImBoolean showGameplayObjects = new imgui.type.ImBoolean(true);
    private final imgui.type.ImBoolean showEnvironment     = new imgui.type.ImBoolean(true);
    private final imgui.type.ImBoolean showProfiler        = new imgui.type.ImBoolean(true);
    private final imgui.type.ImBoolean showDebugHud        = new imgui.type.ImBoolean(true);
    private final imgui.type.ImBoolean showBones           = new imgui.type.ImBoolean(false);

    // Set by the GLFW framebuffer-size callback or a Settings display change;
    // drives main-swapchain recreation (see recreateSwapchain / drawFrame).
    private boolean framebufferResized = false;
    // Shared Settings popup — reachable from the Main Menu and the editor menu bar.
    private final SettingsUI settingsUI = new SettingsUI();
    // Lightweight persistence of editor panel open/closed state across restarts.
    private static final String EDITOR_PREFS_PATH = "editor_prefs.json";

    // Asset folders we list in the Asset Browser. Same paths the OpenGL
    // UIManager reads (Config.MODELS_PATH / Config.CHARACTERS_PATH).
    private final java.io.File modelsFolder     = new java.io.File(Config.MODELS_PATH);
    private final java.io.File charactersFolder = new java.io.File(Config.CHARACTERS_PATH);

    // ---- descriptor wiring (model pipeline) ----
    private long descriptorSetLayout;   // binding 0=material, binding 1=shadow map
    private long descriptorPool;

    // ---- skybox descriptor wiring (own pool/layout — just one cubemap binding) ----
    private long skyboxDescriptorSetLayout;
    private long skyboxDescriptorPool;
    private long skyboxDescriptorSet;

    // ---- pipelines ----
    private long modelPipelineLayout,  modelPipeline;
    private long shadowPipelineLayout, shadowPipeline;
    private long skyboxPipelineLayout, skyboxPipeline;
    // Instanced cubes — sub-phase F. All procedural cubes (modelPath=="")
    // funnel through this pipeline so per-face colours work and we use
    // ONE draw call per material group instead of one per cube.
    private long cubePipelineLayout,   cubePipeline;
    private VkCubeBatch cubeBatch;
    // Skinned characters — sub-phase G. Separate pipeline because the
    // vertex shader reads per-vertex bone IDs/weights (binding 1) and
    // sources finalBoneMatrices[] from a UBO at (set=1, binding=0).
    private long skinnedModelPipelineLayout, skinnedModelPipeline;
    private long skinnedShadowPipelineLayout, skinnedShadowPipeline;
    private VkSkinnedRuntime skinnedRuntime;
    // Marker sprite pipeline — same PC + descriptors as the model pipeline
    // but with alpha blending on + depth write off so spawn/win zone
    // billboards have clean anti-aliased edges instead of the dark
    // alpha-cutoff outline the opaque model pipeline produced.
    private long markerPipelineLayout, markerPipeline;
    // Sub-phase H — 2D overlay (stamina bar in play mode, box-select rect in editor).
    private VkRenderer2D renderer2D;
    // Animation cache — keyed by filename, shared across Animators that load
    // the same file (mirrors AssetPool.getOrLoadAnimation on the GL side).
    private final java.util.Map<String, Animation> animationCache = new java.util.HashMap<>();
    // Last frame's clock tick for Animator.update(dt).
    private double lastAnimTickSec = -1.0;

    // ---- camera + input ----
    private float camX = 0f, camY = 2f, camZ = 8f;
    private float camYaw   = 0f;        // 0 = look -Z
    private float camPitch = 10f;       // positive = look DOWN
    private float camRoll  = 0f;        // Player.roll — slide tilt
    private final boolean[] keys = new boolean[512];
    private double lastMouseX, lastMouseY;
    private boolean firstMouseCallback = true;
    // Editor mouselook is gated on right-mouse-button — drag-look matches
    // LevelEditorScene.isPanning (set on RMB-press at line 1057-1058,
    // cleared on RMB-release at line 1036-1037). Play mode ignores this
    // flag and mouselooks unconditionally.
    private boolean isPanning = false;
    private double lastFrameNanos;
    private int currentFrame = 0;

    // Multi-selection (CTRL+Click). selectedObjects holds every selected
    // entity; explicitPivotObject is the ACTIVE element — a REAL object the
    // gizmo snaps to, and the other selected objects orbit / scale relative to
    // it. Set by the last CTRL+Left-Click, or chosen via CTRL+Right-Click.
    // prevPivot* snapshots the pivot's transform; applyPivotDeltaAndReset()
    // diffs it each frame and propagates the delta to the rest of the
    // selection (no invisible centroid proxy any more).
    private final java.util.List<GameObject> selectedObjects = new java.util.ArrayList<>();
    private GameObject explicitPivotObject = null;
    private float prevPivotX, prevPivotY, prevPivotZ;
    private float prevPivotRotX, prevPivotRotY, prevPivotRotZ;
    private float prevPivotScaleX, prevPivotScaleY, prevPivotScaleZ;
    // CTRL held at mouse-down for the queued viewport LMB click.
    private boolean pendingClickCtrl = false;
    // Queued CTRL+Right-Click pivot pick (processed in the main loop).
    private boolean pendingPivotClick = false;
    private double  pendingPivotX, pendingPivotY;

    // Skybox cube — unit cube, 8 floats/vertex matching VkMesh's layout.
    // Normals/UVs are ignored by the skybox shader.
    private static final float[] SKYBOX_CUBE_VERTICES = {
            -0.5f,-0.5f,-0.5f, 0,0,0, 0,0,
             0.5f,-0.5f,-0.5f, 0,0,0, 0,0,
             0.5f, 0.5f,-0.5f, 0,0,0, 0,0,
            -0.5f, 0.5f,-0.5f, 0,0,0, 0,0,
            -0.5f,-0.5f, 0.5f, 0,0,0, 0,0,
             0.5f,-0.5f, 0.5f, 0,0,0, 0,0,
             0.5f, 0.5f, 0.5f, 0,0,0, 0,0,
            -0.5f, 0.5f, 0.5f, 0,0,0, 0,0
    };
    private static final int[] SKYBOX_CUBE_INDICES = {
            0,1,2, 0,2,3,   4,6,5, 4,7,6,
            0,3,7, 0,7,4,   1,5,6, 1,6,2,
            3,2,6, 3,6,7,   0,4,5, 0,5,1
    };

    // =============================================================
    //                          Shaders
    // =============================================================
    // Model PC layout (208B): mat4 model, mat4 viewProj, mat4 lightSpace, vec4 material.
    // Set 0 / binding 0 = diffuse texture; binding 1 = shadow map.

    private static final String MODEL_VERT = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 viewProj;\n"
            + "    mat4 lightSpace;\n"
            + "    vec4 material;\n"
            + "    vec4 tint;\n"
            + "    vec4 sunDir;\n"
            + "} pc;\n"
            + "layout(location = 0) in vec3 inPos;\n"
            + "layout(location = 1) in vec3 inNormal;\n"
            + "layout(location = 2) in vec2 inUV;\n"
            + "layout(location = 0) out vec3 vScaledLocalPos;\n"   // for triplanar (scale-only)
            + "layout(location = 1) out vec3 vWorldNormal;\n"
            + "layout(location = 2) out vec2 vUV;\n"
            + "layout(location = 3) out vec4 vLightSpacePos;\n"
            + "layout(location = 4) out vec3 vLocalNormal;\n"      // for triplanar blend
            + "void main() {\n"
            + "    vec4 worldPos = pc.model * vec4(inPos, 1.0);\n"
            + "    gl_Position    = pc.viewProj * worldPos;\n"
            + "    // Extract per-axis scale from the model matrix as the\n"
            + "    // length of its column vectors. Apply to local pos so\n"
            + "    // triplanar tiling tracks SCALE only — translation +\n"
            + "    // rotation no longer slide the texture across the mesh.\n"
            + "    vec3 scale = vec3(length(pc.model[0].xyz),\n"
            + "                      length(pc.model[1].xyz),\n"
            + "                      length(pc.model[2].xyz));\n"
            + "    vScaledLocalPos = inPos * scale;\n"
            + "    vWorldNormal    = normalize(mat3(pc.model) * inNormal);\n"
            + "    vLocalNormal    = inNormal;\n"
            + "    vUV             = inUV;\n"
            + "    vLightSpacePos  = pc.lightSpace * worldPos;\n"
            + "}\n";

    // Triplanar block ported from default.frag:46-60.
    // ShadowCalculation ported from default.frag:18-39 with Vulkan tweaks:
    //  - Don't remap Z to [0,1]; Vulkan NDC Z is already in that range.
    //  - The 0.5+0.5 remap applies only to XY.
    private static final String MODEL_FRAG = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 viewProj;\n"
            + "    mat4 lightSpace;\n"
            + "    vec4 material;\n"
            + "    vec4 tint;\n"
            + "    vec4 sunDir;\n"
            + "} pc;\n"
            + "layout(set = 0, binding = 0) uniform sampler2D texDiffuse;\n"
            + "layout(set = 0, binding = 1) uniform sampler2D shadowMap;\n"
            + "layout(location = 0) in  vec3 vScaledLocalPos;\n"   // triplanar position
            + "layout(location = 1) in  vec3 vWorldNormal;\n"
            + "layout(location = 2) in  vec2 vUV;\n"
            + "layout(location = 3) in  vec4 vLightSpacePos;\n"
            + "layout(location = 4) in  vec3 vLocalNormal;\n"      // triplanar blend
            + "layout(location = 0) out vec4 outColor;\n"
            + "\n"
            + "float shadowCalc(vec3 normal, vec3 lightDir) {\n"
            + "    vec3 projCoords = vLightSpacePos.xyz / vLightSpacePos.w;\n"
            + "    projCoords.xy = projCoords.xy * 0.5 + 0.5;\n"
            + "    if (projCoords.z > 1.0 || projCoords.x < 0.0 || projCoords.x > 1.0\n"
            + "                            || projCoords.y < 0.0 || projCoords.y > 1.0) return 0.0;\n"
            + "    float currentDepth = projCoords.z;\n"
            // Normal bias (material.z, slope-scaled) + depth bias (material.w),
            // both fed live from the Shadow Map Debug UI via the previously
            // unused material.zw slots (no push-constant growth).
            + "    float bias = max(pc.material.z * (1.0 - dot(normal, lightDir)), pc.material.w);\n"
            + "    float shadow = 0.0;\n"
            + "    vec2 texel = 1.0 / vec2(textureSize(shadowMap, 0));\n"
            + "    for (int x = -1; x <= 1; ++x)\n"
            + "        for (int y = -1; y <= 1; ++y) {\n"
            + "            float pcfDepth = texture(shadowMap, projCoords.xy + vec2(x, y) * texel).r;\n"
            + "            shadow += currentDepth - bias > pcfDepth ? 1.0 : 0.0;\n"
            + "        }\n"
            + "    return shadow / 9.0;\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 albedo;\n"
            + "    if (pc.material.x > 0.5) {\n"
            + "        // Triplanar in LOCAL space (scaled). Translation +\n"
            + "        // rotation no longer slide the texture; only scale\n"
            + "        // changes tile density. Blend weights use local\n"
            + "        // normal so face selection is rotation-invariant too.\n"
            + "        float s = pc.material.y;\n"
            + "        vec3 n = abs(normalize(vLocalNormal));\n"
            + "        n = pow(n, vec3(4.0));\n"
            + "        n = n / (n.x + n.y + n.z);\n"
            + "        vec4 cX = texture(texDiffuse, vScaledLocalPos.zy * s);\n"
            + "        vec4 cY = texture(texDiffuse, vScaledLocalPos.xz * s);\n"
            + "        vec4 cZ = texture(texDiffuse, vScaledLocalPos.xy * s);\n"
            + "        albedo  = cX * n.x + cY * n.y + cZ * n.z;\n"
            + "    } else {\n"
            + "        albedo = texture(texDiffuse, vUV);\n"
            + "    }\n"
            + "    if (albedo.a < 0.1) discard;\n"          // foliage alpha cutout (default.frag:66)
            + "    vec3  norm     = normalize(vWorldNormal);\n"
            + "    vec3  sunDir   = normalize(pc.sunDir.xyz);\n"
            + "    float sunInt   = pc.sunDir.w;\n"          // intensity packed into the w slot
            + "    float diff     = max(dot(norm, sunDir), 0.0);\n"
            + "    float shadow   = shadowCalc(norm, sunDir);\n"
            + "    vec3  diffuse  = diff * sunInt * albedo.rgb;\n"   // matches default.frag:72
            + "    vec3  ambient  = 0.4 * albedo.rgb;\n"             // matches default.frag:74
            + "    vec3  lit      = (ambient + (1.0 - shadow) * diffuse) * pc.tint.rgb;\n"
            + "    outColor = vec4(lit, albedo.a);\n"
            + "}\n";

    // ---- Marker fragment shader (spawn / win-zone sprite billboards) ----
    // Same PC + descriptor layout as MODEL_FRAG so the marker pipeline
    // can reuse recordModelDraw. Differences from MODEL_FRAG:
    //   - No lighting / shadow lookup — UI feel, full-brightness sprite.
    //   - No triplanar — markers always sample the bound texture with
    //     the cube's stored UVs (it's a unit quad anyway).
    //   - Discard threshold lowered to 0.02 so only truly-empty pixels
    //     are killed; the rest go through alpha blending in the
    //     pipeline (markerPipeline uses colorBlendAlpha) for clean
    //     anti-aliased edges instead of the hard-cutoff "outline" the
    //     opaque model pipeline produced.
    private static final String MARKER_FRAG = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 viewProj;\n"
            + "    mat4 lightSpace;\n"
            + "    vec4 material;\n"
            + "    vec4 tint;\n"
            + "    vec4 sunDir;\n"
            + "} pc;\n"
            + "layout(set = 0, binding = 0) uniform sampler2D texDiffuse;\n"
            + "layout(set = 0, binding = 1) uniform sampler2D shadowMap;\n"   // unused, layout-compat
            + "layout(location = 0) in  vec3 vScaledLocalPos;\n"
            + "layout(location = 1) in  vec3 vWorldNormal;\n"
            + "layout(location = 2) in  vec2 vUV;\n"
            + "layout(location = 3) in  vec4 vLightSpacePos;\n"
            + "layout(location = 4) in  vec3 vLocalNormal;\n"
            + "layout(location = 0) out vec4 outColor;\n"
            + "void main() {\n"
            + "    vec4 c = texture(texDiffuse, vUV);\n"
            // Hard cutoff at 0.5 instead of a soft 0.02 — keeps only
            // the solidly-opaque pixels. Anti-aliased PNG edges have
            // semi-transparent white-ish pixels that would otherwise
            // render as a bright halo around the sprite (the "white
            // outline" the user reported). With this threshold the
            // sprite renders as exactly the visible PNG content, no
            // extra halo.
            + "    if (c.a < 0.5) discard;\n"
            + "    outColor = vec4(c.rgb * pc.tint.rgb, 1.0);\n"
            + "}\n";

    // ---- Skinned model shaders (sub-phase G) ----
    // Same PC layout as the static model pipeline; an additional vertex
    // input binding 1 carries per-vertex ivec4 boneIds + vec4 weights.
    // Set 1, binding 0 is the bone-matrix UBO (100 mat4 = 6400B).
    // Vertex shader is a verbatim port of default.vert's skinning branch.
    private static final String SKINNED_MODEL_VERT = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 viewProj;\n"
            + "    mat4 lightSpace;\n"
            + "    vec4 material;\n"
            + "    vec4 tint;\n"
            + "    vec4 sunDir;\n"
            + "} pc;\n"
            + "layout(set = 1, binding = 0) uniform Bones {\n"
            + "    mat4 boneMatrices[100];\n"
            + "} bones;\n"
            + "layout(location = 0) in vec3  inPos;\n"
            + "layout(location = 1) in vec3  inNormal;\n"
            + "layout(location = 2) in vec2  inUV;\n"
            + "layout(location = 3) in ivec4 inBoneIds;\n"
            + "layout(location = 4) in vec4  inWeights;\n"
            + "layout(location = 0) out vec3 vScaledLocalPos;\n"
            + "layout(location = 1) out vec3 vWorldNormal;\n"
            + "layout(location = 2) out vec2 vUV;\n"
            + "layout(location = 3) out vec4 vLightSpacePos;\n"
            + "layout(location = 4) out vec3 vLocalNormal;\n"
            + "void main() {\n"
            + "    // Per-vertex skin transform — verbatim from default.vert:32-44.\n"
            + "    vec4 totalPos = vec4(0.0);\n"
            + "    vec3 totalNrm = vec3(0.0);\n"
            + "    for (int i = 0; i < 4; ++i) {\n"
            + "        int bId = inBoneIds[i];\n"
            + "        if (bId < 0) continue;\n"
            + "        if (bId >= 100) { totalPos = vec4(inPos, 1.0); totalNrm = inNormal; break; }\n"
            + "        mat4 bm = bones.boneMatrices[bId];\n"
            + "        totalPos += (bm * vec4(inPos, 1.0)) * inWeights[i];\n"
            + "        totalNrm += (mat3(bm) * inNormal) * inWeights[i];\n"
            + "    }\n"
            + "    // If no weights at all (orphan vertex), fall back to bind pose.\n"
            + "    if (inWeights.x + inWeights.y + inWeights.z + inWeights.w < 0.0001) {\n"
            + "        totalPos = vec4(inPos, 1.0);\n"
            + "        totalNrm = inNormal;\n"
            + "    }\n"
            + "    vec4 worldPos = pc.model * totalPos;\n"
            + "    gl_Position = pc.viewProj * worldPos;\n"
            + "    vec3 scale = vec3(length(pc.model[0].xyz),\n"
            + "                      length(pc.model[1].xyz),\n"
            + "                      length(pc.model[2].xyz));\n"
            + "    vScaledLocalPos = totalPos.xyz * scale;\n"
            + "    vWorldNormal    = normalize(mat3(pc.model) * totalNrm);\n"
            + "    vLocalNormal    = totalNrm;\n"
            + "    vUV             = inUV;\n"
            + "    vLightSpacePos  = pc.lightSpace * worldPos;\n"
            + "}\n";

    // Skinned fragment shader is byte-identical to MODEL_FRAG — the only
    // difference is the vertex side. Inlined here to keep both pipelines
    // self-contained for the shaderc inputs.
    private static final String SKINNED_MODEL_FRAG = MODEL_FRAG;

    // Shadow PC layout (144B): mat4 model + mat4 lightSpace + vec4 flags
    // (flags.x = isFoliage). Visible to both stages so the fragment can
    // read the flag without a separate per-pipeline split.
    // The fragment shader samples the entity's diffuse texture alpha (when
    // foliage) and discards transparent fragments so leaf trees cast their
    // proper silhouette — matches OpenGL depth.frag:13-16.
    // Descriptor set 0 is the model layout (binding 0 = material tex,
    // binding 1 = shadow map — bound but unread here).
    private static final String SHADOW_VERT = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 lightSpace;\n"
            + "    vec4 flags;\n"
            + "} pc;\n"
            + "layout(location = 0) in vec3 inPos;\n"
            + "layout(location = 1) in vec3 inNormal;\n"   // unused — keeps stride compatible
            + "layout(location = 2) in vec2 inUV;\n"
            + "layout(location = 0) out vec2 vUV;\n"
            + "void main() {\n"
            + "    vUV = inUV;\n"
            + "    gl_Position = pc.lightSpace * pc.model * vec4(inPos, 1.0);\n"
            + "}\n";
    private static final String SHADOW_FRAG = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 lightSpace;\n"
            + "    vec4 flags;\n"
            + "} pc;\n"
            + "layout(set = 0, binding = 0) uniform sampler2D texDiffuse;\n"
            + "layout(location = 0) in vec2 vUV;\n"
            + "void main() {\n"
            + "    if (pc.flags.x > 0.5) {\n"
            + "        float a = texture(texDiffuse, vUV).a;\n"
            + "        if (a < 0.1) discard;\n"
            + "    }\n"
            + "}\n";

    // ---- Skinned shadow vertex shader (sub-phase G follow-up) ----
    // Same SHADOW_FRAG (foliage alpha discard) — only the vertex side
    // applies the bone-matrix blend so the cast shadow follows the
    // animated mesh instead of the bind pose. Port of depth.vert's
    // skinning branch.
    private static final String SKINNED_SHADOW_VERT = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    mat4 model;\n"
            + "    mat4 lightSpace;\n"
            + "    vec4 flags;\n"
            + "} pc;\n"
            + "layout(set = 1, binding = 0) uniform Bones {\n"
            + "    mat4 boneMatrices[100];\n"
            + "} bones;\n"
            + "layout(location = 0) in vec3  inPos;\n"
            + "layout(location = 1) in vec3  inNormal;\n"
            + "layout(location = 2) in vec2  inUV;\n"
            + "layout(location = 3) in ivec4 inBoneIds;\n"
            + "layout(location = 4) in vec4  inWeights;\n"
            + "layout(location = 0) out vec2 vUV;\n"
            + "void main() {\n"
            + "    vec4 totalPos = vec4(0.0);\n"
            + "    for (int i = 0; i < 4; ++i) {\n"
            + "        int bId = inBoneIds[i];\n"
            + "        if (bId < 0) continue;\n"
            + "        if (bId >= 100) { totalPos = vec4(inPos, 1.0); break; }\n"
            + "        totalPos += (bones.boneMatrices[bId] * vec4(inPos, 1.0)) * inWeights[i];\n"
            + "    }\n"
            + "    if (inWeights.x + inWeights.y + inWeights.z + inWeights.w < 0.0001) {\n"
            + "        totalPos = vec4(inPos, 1.0);\n"
            + "    }\n"
            + "    vUV = inUV;\n"
            + "    gl_Position = pc.lightSpace * pc.model * totalPos;\n"
            + "}\n";

    // ============================================================
    //                Instanced cube shaders (sub-phase F)
    // ============================================================
    // Per-instance bindings (binding 1, INSTANCE input rate):
    //   loc 3..6  — mat4 model    (4× vec4 columns)
    //   loc 7..12 — 6× vec3 face colour (+X, -X, +Y, -Y, +Z, -Z)
    // Stride 136 B / 34 floats — matches CubeBatch.FLOATS_PER_INSTANCE.

    private static final String CUBE_INSTANCED_VERT = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC {\n"
            + "    vec4 material;\n"     // x=triplanar, y=texScale, z=foliage, w=unused
            + "    mat4 viewProj;\n"
            + "    mat4 lightSpace;\n"
            + "    vec4 sunDir;\n"       // xyz dir, w intensity
            + "} pc;\n"
            + "layout(location = 0) in vec3 inPos;\n"
            + "layout(location = 1) in vec3 inNormal;\n"
            + "layout(location = 2) in vec2 inUV;\n"
            + "layout(location = 3) in vec4 instModel0;\n"
            + "layout(location = 4) in vec4 instModel1;\n"
            + "layout(location = 5) in vec4 instModel2;\n"
            + "layout(location = 6) in vec4 instModel3;\n"
            + "layout(location = 7) in vec3 instFacePosX;\n"
            + "layout(location = 8) in vec3 instFaceNegX;\n"
            + "layout(location = 9) in vec3 instFacePosY;\n"
            + "layout(location = 10) in vec3 instFaceNegY;\n"
            + "layout(location = 11) in vec3 instFacePosZ;\n"
            + "layout(location = 12) in vec3 instFaceNegZ;\n"
            + "layout(location = 0) out vec3 vScaledLocalPos;\n"  // for triplanar
            + "layout(location = 1) out vec3 vWorldNormal;\n"
            + "layout(location = 2) out vec2 vUV;\n"
            + "layout(location = 3) out vec4 vLightSpacePos;\n"
            + "layout(location = 4) out vec3 vFaceTint;\n"
            + "layout(location = 5) out vec3 vLocalNormal;\n"      // for triplanar blend
            + "void main() {\n"
            + "    mat4 model = mat4(instModel0, instModel1, instModel2, instModel3);\n"
            + "    vec4 worldPos = model * vec4(inPos, 1.0);\n"
            + "    // Local pos scaled by the per-instance matrix's scale\n"
            + "    // (cube → scale of the column vectors). Triplanar then\n"
            + "    // depends ONLY on scale, not on cube position/rotation.\n"
            + "    vec3 scale = vec3(length(model[0].xyz),\n"
            + "                      length(model[1].xyz),\n"
            + "                      length(model[2].xyz));\n"
            + "    vScaledLocalPos = inPos * scale;\n"
            + "    vWorldNormal = normalize(mat3(model) * inNormal);\n"
            + "    vLocalNormal = inNormal;\n"
            + "    vUV = inUV;\n"
            + "    vLightSpacePos = pc.lightSpace * worldPos;\n"
            + "    // Face colour from local-space normal (cube has per-face normals).\n"
            + "    if      (inNormal.x >  0.5) vFaceTint = instFacePosX;\n"
            + "    else if (inNormal.x < -0.5) vFaceTint = instFaceNegX;\n"
            + "    else if (inNormal.y >  0.5) vFaceTint = instFacePosY;\n"
            + "    else if (inNormal.y < -0.5) vFaceTint = instFaceNegY;\n"
            + "    else if (inNormal.z >  0.5) vFaceTint = instFacePosZ;\n"
            + "    else                         vFaceTint = instFaceNegZ;\n"
            + "    gl_Position = pc.viewProj * worldPos;\n"
            + "}\n";

    private static final String CUBE_INSTANCED_FRAG = ""
            + "#version 450\n"
            // Layout MUST match the vertex shader exactly — Vulkan pushes
            // ONE shared block, not per-stage blocks. If the order differs,
            // the fragment reads the wrong bytes from memory (e.g.
            // `pc.material.x` ends up sampling the translation column of
            // the lightSpace matrix → tiling toggle does nothing and
            // texture scale "multiplies like crazy at distance" because
            // the value scales with light position).
            + "layout(push_constant) uniform PC {\n"
            + "    vec4 material;\n"     // x=triplanar, y=texScale, z=foliage, w=unused
            + "    mat4 viewProj;\n"
            + "    mat4 lightSpace;\n"
            + "    vec4 sunDir;\n"       // xyz dir, w intensity
            + "} pc;\n"
            + "layout(set = 0, binding = 0) uniform sampler2D texDiffuse;\n"
            + "layout(set = 0, binding = 1) uniform sampler2D shadowMap;\n"
            + "layout(location = 0) in  vec3 vScaledLocalPos;\n"
            + "layout(location = 1) in  vec3 vWorldNormal;\n"
            + "layout(location = 2) in  vec2 vUV;\n"
            + "layout(location = 3) in  vec4 vLightSpacePos;\n"
            + "layout(location = 4) in  vec3 vFaceTint;\n"
            + "layout(location = 5) in  vec3 vLocalNormal;\n"
            + "layout(location = 0) out vec4 outColor;\n"
            + "\n"
            + "float shadowCalc(vec3 normal, vec3 lightDir) {\n"
            + "    vec3 projCoords = vLightSpacePos.xyz / vLightSpacePos.w;\n"
            + "    projCoords.xy = projCoords.xy * 0.5 + 0.5;\n"
            + "    if (projCoords.z > 1.0 || projCoords.x < 0.0 || projCoords.x > 1.0\n"
            + "                            || projCoords.y < 0.0 || projCoords.y > 1.0) return 0.0;\n"
            + "    float currentDepth = projCoords.z;\n"
            // Cube material.z carries the foliage flag, so only the constant
            // depth bias is live here (material.w); normal bias stays a const
            // — axis-aligned boxes barely show slope acne anyway.
            + "    float bias = max(0.001 * (1.0 - dot(normal, lightDir)), pc.material.w);\n"
            + "    float shadow = 0.0;\n"
            + "    vec2 texel = 1.0 / vec2(textureSize(shadowMap, 0));\n"
            + "    for (int x = -1; x <= 1; ++x)\n"
            + "        for (int y = -1; y <= 1; ++y) {\n"
            + "            float pcfDepth = texture(shadowMap, projCoords.xy + vec2(x, y) * texel).r;\n"
            + "            shadow += currentDepth - bias > pcfDepth ? 1.0 : 0.0;\n"
            + "        }\n"
            + "    return shadow / 9.0;\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 albedo;\n"
            + "    if (pc.material.x > 0.5) {\n"
            + "        // Triplanar in scaled-local space (translation +\n"
            + "        // rotation no longer affect the texture; only scale).\n"
            + "        float s = pc.material.y;\n"
            + "        vec3 n = abs(normalize(vLocalNormal));\n"
            + "        n = pow(n, vec3(4.0));\n"
            + "        n = n / (n.x + n.y + n.z);\n"
            + "        vec4 cX = texture(texDiffuse, vScaledLocalPos.zy * s);\n"
            + "        vec4 cY = texture(texDiffuse, vScaledLocalPos.xz * s);\n"
            + "        vec4 cZ = texture(texDiffuse, vScaledLocalPos.xy * s);\n"
            + "        albedo  = cX * n.x + cY * n.y + cZ * n.z;\n"
            + "    } else {\n"
            + "        albedo = texture(texDiffuse, vUV);\n"
            + "    }\n"
            + "    if (albedo.a < 0.1) discard;\n"
            + "    vec3  norm    = normalize(vWorldNormal);\n"
            + "    vec3  sunDir  = normalize(pc.sunDir.xyz);\n"
            + "    float sunInt  = pc.sunDir.w;\n"
            + "    float diff    = max(dot(norm, sunDir), 0.0);\n"
            + "    float shadow  = shadowCalc(norm, sunDir);\n"
            + "    vec3  diffuse = diff * sunInt * albedo.rgb;\n"
            + "    vec3  ambient = 0.4 * albedo.rgb;\n"
            + "    // Multiply by the per-face tint instead of a global tint.\n"
            + "    vec3  lit     = (ambient + (1.0 - shadow) * diffuse) * vFaceTint;\n"
            + "    outColor = vec4(lit, albedo.a);\n"
            + "}\n";

    // Skybox PC: just an MVP (64B).
    private static final String SKYBOX_VERT = ""
            + "#version 450\n"
            + "layout(push_constant) uniform PC { mat4 mvp; } pc;\n"
            + "layout(location = 0) in vec3 inPos;\n"
            + "layout(location = 0) out vec3 vDir;\n"
            + "void main() {\n"
            + "    vDir = inPos;\n"
            + "    gl_Position = pc.mvp * vec4(inPos, 1.0);\n"
            + "}\n";
    private static final String SKYBOX_FRAG = ""
            + "#version 450\n"
            + "layout(set = 0, binding = 0) uniform samplerCube skybox;\n"
            + "layout(location = 0) in  vec3 vDir;\n"
            + "layout(location = 0) out vec4 outColor;\n"
            + "void main() { outColor = texture(skybox, vDir); }\n";

    // =============================================================
    //                       Entry point
    // =============================================================

    public static void main(String[] args) {
        VulkanMain app = new VulkanMain();
        // CLI boot mode: --runtime / -runtime / -game launches the standalone
        // game (no editor); anything else is the normal Editor.
        for (String a : args) {
            if ("--runtime".equalsIgnoreCase(a) || "-runtime".equalsIgnoreCase(a)
                    || "-game".equalsIgnoreCase(a)) {
                app.runtimeMode = true;
            }
        }
        app.run();
    }

    private void run() {
        try {
            initWindow();
            initVulkan();
            // Runtime (standalone game): do NOT enter Play here — defer it to
            // the first main-loop frame (see runtimePlayPending). Running the
            // level reload + GPU asset upload from inside the loop guarantees
            // the Vulkan device, command pools and queues are 100% live before
            // prewarmEntityAssets allocates/uploads any buffers — fixes the
            // empty standalone world (Issue 3).
            mainLoop();
        } catch (Exception t) {
            Logger.error("Vulkan path crashed", t);
            throw t;
        } finally {
            cleanup();
        }
    }

    /** Multi-Process pivot — boot the engine as a standalone GAME instance:
     *  no main menu, no editor UI, straight into Play mode rendering fullscreen
     *  from the player / active-player camera (the normal !isEditorMode path).
     *  level.json is already loaded by initVulkan. */
    private void enterRuntimePlay() {
        inMainMenu   = false;
        isEditorMode = false;
        player.isFlying = false;
        GameStateManager.setPlaying(true);
        ImGui.getIO().setFontGlobalScale(1.0f);

        // Guarantee a fully-loaded + GPU-warmed world before the first frame.
        // initVulkan already loaded the level, but reloading here is the one
        // code path that deterministically does loadLevelData → prewarm
        // (mesh/texture/descriptor upload) → updateGrid in the right order,
        // so the standalone game can never boot to an empty (skybox-only)
        // scene from a half-initialized asset cache.
        reloadLevelFromDisk();

        // Bind the active-player avatar. Try, in order: the "(Player)" name
        // suffix (set when "Set as Active Player" deep-copies into the
        // world), then ANY entity carrying a CharacterLocomotionComponent,
        // then a plainly-named "Player". Without this binding the camera
        // had no body to follow and the standalone game booted blank.
        activePlayerEntity = null;
        player.locomotion = null;
        for (GameObject g : entities) {
            if (g.name != null && g.name.endsWith("(Player)")) { activePlayerEntity = g; break; }
        }
        if (activePlayerEntity == null) {
            for (GameObject g : entities) {
                if (g.getComponent(CharacterLocomotionComponent.class) != null) { activePlayerEntity = g; break; }
            }
        }
        if (activePlayerEntity == null) {
            for (GameObject g : entities) {
                if ("Player".equals(g.name)) { activePlayerEntity = g; break; }
            }
        }
        if (activePlayerEntity != null) {
            player.locomotion = activePlayerEntity.getComponent(CharacterLocomotionComponent.class);
        }

        // Forcefully (re)initialize the controller. initPlayer resets the
        // physics state and positions the player at the Spawn Point (or
        // origin if none). Track whether a spawn point actually placed us.
        boolean hasSpawn = false;
        for (GameObject g : entities) {
            MeshRenderer mr = g.getComponent(MeshRenderer.class);
            if (g.getComponent(SpawnPointComponent.class) != null
                    || (mr != null && World.SPAWN_POINT_KEY.equals(mr.modelPath))) { hasSpawn = true; break; }
        }
        initPlayer();
        // No spawn point in the level → start the camera at the avatar so
        // the scene renders from a valid pose instead of empty origin.
        if (!hasSpawn && activePlayerEntity != null) {
            player.x = activePlayerEntity.transform.x;
            player.y = activePlayerEntity.transform.y + player.eyeHeight();
            player.z = activePlayerEntity.transform.z;
            player.xVel = player.yVel = player.zVel = 0f;
            player.isGrounded = false;
        }

        // Sync the cam* fields drawFrame reads RIGHT NOW so the very first
        // recorded frame uses a valid view matrix (defensive — updateCamera
        // also does this each frame).
        camX = player.x;
        camY = player.y + player.cameraYOffset + player.stepCamOffset;
        camZ = player.z;
        camYaw = player.yaw; camPitch = player.pitch; camRoll = player.roll;

        glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        firstMouseCallback = true;
        glfwSetWindowTitle(glfwWindow, "JEngine — Runtime");
        Logger.info("[VK] Booted in RUNTIME mode — standalone Play. activePlayer="
                + (activePlayerEntity != null ? activePlayerEntity.name : "<none>")
                + " cam=(" + camX + "," + camY + "," + camZ + ")");
    }

    // Child game processes spawned by "Launch Standalone Game" — killed on
    // editor teardown so closing the editor closes its game windows.
    private final java.util.List<Process> childProcesses = new java.util.ArrayList<>();

    // Phase 3A — IPC. Editor = server (broadcasts), Runtime = client (listens).
    private static final int IPC_PORT = 7777;
    private NetworkSync netSync;

    /** Phase 2 — spawn a brand-new OS process of the engine in --runtime mode
     *  (a separate standalone game window), reusing this JVM's java binary +
     *  classpath. */
    private void launchRuntimeProcess() {
        try {
            String javaBin = System.getProperty("java.home") + java.io.File.separator
                    + "bin" + java.io.File.separator + "java";
            String classpath = System.getProperty("java.class.path");
            java.util.List<String> cmd = java.util.Arrays.asList(
                    javaBin, "-cp", classpath, "com.myengine.VulkanMain", "--runtime");
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            childProcesses.add(p);
            Logger.info("[VK] Launched standalone game process (pid " + p.pid() + ")");
        } catch (Exception e) {
            Logger.error("[VK] Failed to launch standalone game process", e);
        }
    }

    // =============================================================
    //                    Window + input
    // =============================================================

    private void initWindow() {
        Logger.info("[VK] Initializing GLFW window for Vulkan");
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        if (!glfwVulkanSupported()) {
            throw new IllegalStateException("GLFW reports no Vulkan support on this machine. " +
                    "Install up-to-date GPU drivers (Windows/Linux) or check the MoltenVK native " +
                    "jar is on the classpath (macOS).");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        // Phase 1 (AAA overhaul): borderless — strip the OS title bar / frame.
        // The custom ImGui title bar (buildMainMenuBar) provides the menu +
        // min/max/close controls and window dragging.
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);

        // Boot at the user's last saved resolution / display mode instead of a
        // hardcoded 1280×720 (fixes the Settings persistence bug).
        DisplaySettings ds = DisplaySettings.load();
        glfwWindow = glfwCreateWindow(ds.windowWidth, ds.windowHeight, "JEngine — Vulkan", NULL, NULL);
        if (glfwWindow == NULL) throw new RuntimeException("glfwCreateWindow failed");
        // Apply fullscreen / borderless BEFORE callbacks are installed so the
        // boot-time monitor switch doesn't spuriously flag a swapchain resize.
        if (ds.fullscreenMode != 0) SettingsUI.applyToWindow(glfwWindow, ds);

        // Start at the Main Menu — matches Window.run:47 (which immediately
        // changeScene's to MainMenuScene). Cursor visible, dark background.
        glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        glfwSetWindowTitle(glfwWindow, "JEngine Parkour - Main Menu");

        // Any framebuffer size change (Settings resolution / display-mode
        // switch, OS-driven resize) invalidates the Vulkan swapchain — flag it
        // so drawFrame recreates before the next present.
        glfwSetFramebufferSizeCallback(glfwWindow, (window, fbw, fbh) -> framebufferResized = true);

        glfwSetCursorPosCallback(glfwWindow, (window, x, y) -> {
            if (firstMouseCallback) { lastMouseX = x; lastMouseY = y; firstMouseCallback = false; return; }
            double dx = x - lastMouseX, dy = y - lastMouseY;
            lastMouseX = x; lastMouseY = y;
            if (inMainMenu) return;

            // Gizmo drag — runs in editor mode while a gizmo axis is held.
            // Matches LevelEditorScene.onMouseMove lines 890-905.
            if (isEditorMode && draggingAxis != -1 && selectedEntity != null) {
                // Step 4: viewport-local coords from ImGui mouse pos (same space
                // as viewportX), so picking/drag stay correct when ViewportsEnable
                // makes ImGui coords global-desktop. ImGui's chained cursor
                // callback has already updated io.MousePos for this event.
                double localX = ImGui.getMousePosX() - viewportX;
                double localY = ImGui.getMousePosY() - viewportY;
                boolean isShift = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;
                if (selectedEntity.transformMode == 0 && draggingAxis == 3) {
                    // Free-move handle: position follows the mouse cursor.
                    freeMoveFollowMouse(localX, localY, isShift);
                } else if (selectedEntity.transformMode == 1
                        && draggingAxis >= 0 && draggingAxis <= 2) {
                    // ROTATE — ray-plane intersection (absolute cursor → angle
                    // on the selected ring's plane). No screen-delta dead zones.
                    updateRotationDrag(localX, localY, isShift);
                } else {
                    double deltaY = lastDragMouseY - localY;
                    double deltaX = localX - lastDragMouseX;
                    float dist = (float) Math.sqrt(
                            Math.pow(player.x - selectedEntity.transform.x, 2) +
                            Math.pow(player.y - selectedEntity.transform.y, 2) +
                            Math.pow(player.z - selectedEntity.transform.z, 2));
                    float sensitivity = dist * Config.GIZMO_SENSITIVITY;
                    updateTransformDrag(deltaY, deltaX, sensitivity, isShift);
                }
                lastDragMouseX = localX;
                lastDragMouseY = localY;
                return;
            }

            // Mouselook gating — matches LevelEditorScene.onMouseMove
            // (lines 908, 920): editor mode skips unless RMB held; play
            // mode always mouselooks.
            if (isEditorMode && !isPanning) return;
            player.yaw   += (float) (dx * Config.MOUSE_LOOK_SENSITIVITY);
            player.pitch += (float) (dy * Config.MOUSE_LOOK_SENSITIVITY);
            if (player.pitch >  Config.PITCH_CLAMP) player.pitch =  Config.PITCH_CLAMP;
            if (player.pitch < -Config.PITCH_CLAMP) player.pitch = -Config.PITCH_CLAMP;
        });
        glfwSetMouseButtonCallback(glfwWindow, (window, button, action, mods) -> {
            // Editor mode: RMB press/release toggles drag-look (isPanning).
            // Anchor lastMouse on each transition so the camera doesn't
            // snap. Matches LevelEditorScene.onMouseClick lines 1036-1058.
            if (isEditorMode && button == GLFW_MOUSE_BUTTON_RIGHT) {
                boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;
                if (action == GLFW_PRESS && ctrl) {
                    // CTRL+RMB picks the explicit pivot (active element); it does
                    // NOT start drag-look. Queue it for the main loop to raycast.
                    // Step 4: capture in ImGui-space (matches viewportX) so it's
                    // correct with ViewportsEnable on.
                    pendingPivotX = ImGui.getMousePosX();
                    pendingPivotY = ImGui.getMousePosY();
                    pendingPivotClick = true;
                } else if (action == GLFW_PRESS && isViewportHovered) {
                    // Phase 5D: only arm drag-look when the RMB press lands on
                    // the Scene Viewport. Without this, an RMB-drag inside the
                    // Character Editor (preview pan) also panned the main camera
                    // (input leak). We gate on isViewportHovered rather than
                    // getWantCaptureMouse() because the Scene Viewport is itself
                    // an ImGui window (capture is always true while hovering the
                    // 3D view — see the LMB-select note below).
                    isPanning = true;  firstMouseCallback = true;
                }
                if (action == GLFW_RELEASE)      { isPanning = false; firstMouseCallback = true; }
            }

            // LMB release while a gizmo axis is held — commit the drag as a
            // TransformCommand for undo. Matches LevelEditorScene lines
            // 1024-1034.
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_RELEASE
                    && isEditorMode && draggingAxis != -1 && selectedEntity != null) {
                // Skip undo for a multi-selection drag — proper group undo
                // would need a composite command restoring every member's
                // pre-drag transform. Single selection records normally.
                if (selectedObjects.size() < 2) {
                    TransformCommand cmd = new TransformCommand(selectedEntity,
                            beforePosX, beforePosY, beforePosZ,
                            beforeRotX, beforeRotY, beforeRotZ,
                            beforeScaleX, beforeScaleY, beforeScaleZ);
                    if (cmd.hasChanged()) {
                        undoRedo.execute(cmd);
                        Logger.info("Gizmo transform recorded for undo");
                    }
                }
                draggingAxis = -1;
            }

            // LMB press in editor — queue a viewport-local hit-test. The
            // mainLoop consumer checks isViewportHovered (cursor over the
            // Scene Viewport panel specifically) before processing, so
            // clicks on Inspector / Asset Browser / etc. get dropped there.
            // We do NOT gate on WantCaptureMouse here — the Scene Viewport
            // IS an ImGui window so that flag is ALWAYS true while hovering
            // the 3D view, which would block every click. The OpenGL build
            // gates the same way (Window.java just forwards the click;
            // LevelEditorScene checks uiManager.isViewportHovered).
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS
                    && isEditorMode) {
                // Step 4: ImGui-space click pos (matches viewportX) — correct
                // whether ImGui coords are window-local or global-desktop.
                pendingClickX = ImGui.getMousePosX();
                pendingClickY = ImGui.getMousePosY();
                pendingClickInViewport = true;
                // Capture CTRL at mouse-down (NOT in the key callback, so it
                // never intercepts CTRL+C/V/Z/Y). Drives multi-select toggle.
                pendingClickCtrl = (mods & GLFW_MOD_CONTROL) != 0;
            }

            // Editor build tools in play mode are gated on FLY mode (the gate
            // is `!isEditorMode && player.isFlying`). Walking = strict gameplay
            // (no place/break/select); double-tap Space to fly re-enables the
            // creative tools. LMB raycast-deletes the entity in the crosshair,
            // RMB places a cube at the hit point (snapped) or 1 m ahead.
            if (action == GLFW_PRESS && !isEditorMode && player.isFlying) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    GameObject target = pickEntityFromCamera();
                    if (target != null && !"Sun".equals(target.name)) {
                        entities.remove(target);
                        if (target == selectedEntity) selectedEntity = null;
                        selectedObjects.remove(target);
                        Logger.info("Play(fly): deleted " + target.name);
                    }
                } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    placeCubeFromCamera();
                }
            }
        });
        glfwSetKeyCallback(glfwWindow, (window, key, scancode, action, mods) -> {
            // While the user is typing into an ImGui text input (Inspector
            // fields, Animation File path, etc.), the editor shortcuts (P,
            // X, Ctrl+Z/Y, double-tap-Space) must NOT fire — they were
            // hijacking keystrokes like "p" or "x" from filenames being
            // typed. Esc is gated too: first Esc clears ImGui focus, second
            // Esc escapes to menu. Movement keys still get recorded into
            // keys[] (Player.update gates on `isTyping` independently).
            boolean isTyping = ImGui.getIO().getWantTextInput();

            if (!isTyping && key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                // Runtime (standalone game): Esc quits instantly — there is no
                // editor menu to fall back to. Editor build: Esc → main menu,
                // or quit if already at the menu (matches Window.java:160-164).
                if (runtimeMode || inMainMenu) {
                    glfwSetWindowShouldClose(window, true);
                } else {
                    enterMainMenu();
                }
            }

            // Undo / Redo / Delete — ported verbatim from
            // LevelEditorScene.java:831-865 (editor mode only).
            if (!isTyping && isEditorMode) {
                if (key == GLFW_KEY_Z && action == GLFW_PRESS
                        && glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS) {
                    if (undoRedo.canUndo()) {
                        undoRedo.undo();
                        selectedEntity = null;
                        Logger.info("Undo performed");
                    } else Logger.warn("Nothing to undo");
                }
                if (key == GLFW_KEY_Y && action == GLFW_PRESS
                        && glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS) {
                    if (undoRedo.canRedo()) {
                        undoRedo.redo();
                        selectedEntity = null;
                        Logger.info("Redo performed");
                    } else Logger.warn("Nothing to redo");
                }
                if (key == GLFW_KEY_X && action == GLFW_PRESS
                        && selectedEntity != null && !"Sun".equals(selectedEntity.name)) {
                    final GameObject target = selectedEntity;
                    final int idx = entities.indexOf(target);
                    // Phase 5E Issue 3: deleting the avatar must drop the
                    // engine's references too, or its collision/skeleton debug
                    // overlays keep drawing (ghost) and physics stays bound to a
                    // dead loco component. Restores default (unbound) physics.
                    if (target == activePlayerEntity) {
                        activePlayerEntity = null;
                        player.locomotion = null;
                    }
                    Command del = new Command() {
                        @Override public void execute() {
                            entities.remove(target);
                        }
                        @Override public void undo() {
                            if (idx >= 0 && idx <= entities.size()) entities.add(idx, target);
                            else entities.add(target);
                        }
                        @Override public String getDescription() {
                            return "Delete " + (target.name == null ? "(unnamed)" : target.name);
                        }
                    };
                    undoRedo.execute(del);
                    selectedEntity = null;
                    draggingAxis   = -1;
                    Logger.info("Object deleted and recorded for undo");
                }

                // Ctrl+C — deep-copy the selected entity into the clipboard.
                if (key == GLFW_KEY_C && action == GLFW_PRESS
                        && glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS
                        && selectedEntity != null && !"Sun".equals(selectedEntity.name)) {
                    clipboardEntity = LevelLoader.deepCopy(selectedEntity, true);
                    Transform t = selectedEntity.transform;
                    Logger.info("Copied object " + selectedEntity.name
                            + " at X: " + t.x + ", Y: " + t.y + ", Z: " + t.z);
                }

                // Ctrl+V — clone the clipboard entity with a new UUID + offset.
                if (key == GLFW_KEY_V && action == GLFW_PRESS
                        && glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS
                        && clipboardEntity != null) {
                    GameObject pasted = LevelLoader.deepCopy(clipboardEntity, true);
                    pasted.uuid = java.util.UUID.randomUUID().toString();
                    pasted.transform.x += 0.5f;
                    pasted.transform.gameObject = pasted;
                    entities.add(pasted);
                    warmEntity(pasted);
                    selectedEntity = pasted;
                    Logger.info("Pasted object " + pasted.name + " (uuid " + pasted.uuid + ")");
                }
            }

            // (Box-select removed — multi-select is now CTRL+Left-Click.)

            // P — toggle editor / play. Ported verbatim from LevelEditorScene.java:802-816.
            // Gated on `!isTyping` so typing "p" into a text field (e.g. into
            // an Animation File path like "run.glb") doesn't drop into play mode.
            if (!isTyping && key == GLFW_KEY_P && action == GLFW_PRESS) {
                isEditorMode = !isEditorMode;
                if (isEditorMode) {
                    glfwSetWindowTitle(window, "Engine - EDITOR MODE");
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                    // Phase 3B Play->Editor sync: the editor camera IS the
                    // shared Player (camX/Y/Z + camYaw/Pitch are snapshotted
                    // from `player` every frame in runPlayerPhysics), so
                    // returning to the editor keeps the camera at the player's
                    // FINAL play-mode pose — no reset, no teleport-back. Do NOT
                    // add a saved-editor-camera restore here; that would
                    // reintroduce the snap-back this invariant prevents.
                } else {
                    glfwSetWindowTitle(window, "Engine - PLAY MODE");
                    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    firstMouseCallback = true;  // re-anchor so the camera doesn't snap
                    selectedEntity = null;
                    // Issue 1: force editor noclip OFF so the player never
                    // drops into gameplay still flying. Zero velocity too so a
                    // leftover fly impulse doesn't carry into the spawn.
                    player.isFlying = false;
                    player.xVel = player.yVel = player.zVel = 0;
                    currentLocoState = -1;   // Phase 6A: re-evaluate anim state on play entry
                    // Phase 4B Issue 1: if an avatar has been set as the active
                    // player, START PLAY exactly where it sits in the editor
                    // (its current transform), not the level Spawn Point / 0,0,0.
                    // transform.y is the avatar's FEET, so add eyeHeight() to put
                    // the camera at head level; yaw is the inverse of the avatar
                    // yaw mapping (see syncActivePlayerAvatar: rotY = -yaw).
                    if (activePlayerEntity != null) {
                        Transform t = activePlayerEntity.transform;
                        player.x = t.x;
                        player.y = t.y + player.eyeHeight();
                        player.z = t.z;
                        // 180° offset (see syncActivePlayerAvatar): inverse of
                        // rotY = 180 - yaw, so yaw = 180 - rotY.
                        player.yaw = 180f - t.rotY;
                        player.isGrounded = false;
                    } else {
                        // Teleport to the spawn point — matches LevelEditorScene.java:814.
                        applySpawnPointIfPresent();
                    }
                }
            }

            // Phase 6C: F5 toggles the third-person debug camera.
            if (!isTyping && key == GLFW_KEY_F5 && action == GLFW_PRESS) {
                isThirdPerson = !isThirdPerson;
                Logger.info("Third-person camera: " + (isThirdPerson ? "ON" : "OFF"));
            }

            // Double-tap Space toggles fly mode — ported verbatim from
            // LevelEditorScene.java:867-876. Player.update reads isFlying
            // to skip gravity / collision when enabled.
            if (!isTyping && key == GLFW_KEY_SPACE && action == GLFW_PRESS) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastSpacePressMs < Config.DOUBLE_TAP_DELAY) {
                    player.isFlying = !player.isFlying;
                    player.xVel = player.yVel = player.zVel = 0;
                }
                lastSpacePressMs = nowMs;
            }

            // Issue 2 (keyboard leak): ONLY suppress presses while the user is
            // actively typing into an ImGui text field (getWantTextInput).
            // getWantCaptureKeyboard() is true whenever any ImGui window merely
            // has focus, which froze WASD/camera in the editor — too broad.
            // RELEASES are always recorded so a key held before focusing a field
            // can't get stuck "down".
            if (key >= 0 && key < keys.length) {
                if (action == GLFW_RELEASE || !ImGui.getIO().getWantTextInput()) {
                    keys[key] = (action != GLFW_RELEASE);
                }
            }
        });
    }

    /** Run the engine-shared Player physics each frame, then snapshot
     *  position / yaw / pitch into the cam* fields drawFrame reads.
     *  Same path the OpenGL editor uses (LevelEditorScene.update line
     *  256 ish: {@code player.update(keys, world, false, dt)}). */
    private void updateCamera() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0) lastFrameNanos = now;
        double dt = (now - lastFrameNanos) / 1e9;
        // Clamp dt so a slow frame doesn't tunnel the player through
        // geometry (same Window.java:55-58 clamp).
        if (dt > 0.1) dt = 0.1;
        lastFrameNanos = now;

        if (inMainMenu) return;

        // Rebuild the spatial grid only as often as the player moves —
        // every frame is fine for now; we can throttle later.
        world.updateGrid();

        boolean isTyping = ImGui.getIO().getWantTextInput();
        player.update(keys, world, isTyping, dt);
        if (!isEditorMode) {
            checkWinZones();
            syncActivePlayerAvatar();   // Issues 2 & 3: drag the body with the camera
            updateLocomotionStateMachine();   // Phase 6A: pick Idle/Walk/Sprint/Jump
        }

        // Sub-phase G: drive every Animator forward. Identical to
        // LevelEditorScene loop that calls each component's update(dt).
        updateAnimators(dt);
        // Phase 6A: the isolated preview character is NOT in `entities`, so
        // updateAnimators skips it — advance its Animator here so assigned
        // clips actually play in the Character Editor.
        if (previewCharacter != null) {
            Animator pa = previewCharacter.getComponent(Animator.class);
            if (pa != null) {
                if (pa.finalBoneMatrices == null) pa.start();
                if (pa.gameObject == null) pa.gameObject = previewCharacter;
                pa.update(dt);
            }
        }

        // Multi-select pivot: any gizmo / Inspector edit went into the
        // explicit pivot object's transform; propagate the delta (translate /
        // orbit / scale-from-pivot) to every other selected entity.
        applyPivotDeltaAndReset();

        // Snapshot into the cam fields drawFrame reads. cameraYOffset
        // adds head-bob on the OpenGL side. roll is the wall-run /
        // slide tilt and feeds the view matrix's first rotateZ.
        camX = player.x;
        // stepCamOffset = visual step low-pass (Player snaps the body, eases
        // the eye) — keeps stairs/curbs smooth without lagging the collider.
        camY = player.y + player.cameraYOffset + player.stepCamOffset;
        camZ = player.z;
        camYaw   = player.yaw;
        camPitch = player.pitch;
        camRoll  = player.roll;

        // Phase 6C: third-person debug camera (play mode). Keep the same look
        // direction (camYaw/camPitch unchanged) but pull the camera position
        // BACK along the view-forward vector + a little up, so the player's body
        // sits in front of the camera and is visible.
        if (isThirdPerson && !isEditorMode) {
            float yawR = (float) Math.toRadians(camYaw), pitchR = (float) Math.toRadians(camPitch);
            float fX = (float) (Math.sin(yawR) * Math.cos(pitchR));
            float fY = (float) (-Math.sin(pitchR));
            float fZ = (float) (-Math.cos(yawR) * Math.cos(pitchR));
            float dist = 3.0f;
            camX -= fX * dist;
            camY -= fY * dist - 0.5f;   // pull back + raise 0.5 over the shoulder
            camZ -= fZ * dist;
        }
    }

    /** Walk every entity, lazy-load its Animator's animation file the
     *  first time it's seen, then advance the Animator with the per-
     *  frame dt so {@code finalBoneMatrices} is fresh for the draw. */
    private void updateAnimators(double dt) {
        for (GameObject g : entities) {
            Animator a = g.getComponent(Animator.class);
            if (a == null) continue;
            // Animator was deserialized; ensure the transient bone arrays
            // got initialized (Gson bypasses constructors).
            if (a.finalBoneMatrices == null) a.start();
            // gameObject ref needed for recompute() — usually set by
            // Component start(), but the no-start level path skips it.
            // Try to backfill if missing.
            if (a.gameObject == null) a.gameObject = g;

            ensureAnimatorRig(a);
            a.update(dt);
        }
    }

    /** Lazy-load the Animator's animation file the first time we see it.
     *  Routes the load through {@link AnimationLoader} (renderer-agnostic).
     *  The skeleton comes from the entity's already-loaded VkMesh. */
    private void ensureAnimatorRig(Animator a) {
        if (a.currentAnimation != null) return;
        if (a.animationFilePath == null || a.animationFilePath.isEmpty()) return;
        MeshRenderer mr = a.gameObject.getComponent(MeshRenderer.class);
        if (mr == null) return;
        VkMesh mesh = meshFor(mr);
        if (mesh == null || !mesh.isSkinned()) return;

        String key = a.animationFilePath;
        Animation cached = animationCache.get(key);
        if (cached == null) {
            cached = AnimationLoader.loadAnimation(key, mesh.skeleton());
            if (cached == null) {
                Logger.warn("[VK] Animation file failed to load: " + key);
                a.animationFilePath = "";   // don't retry every frame
                return;
            }
            animationCache.put(key, cached);
            // Apply persisted trim defaults (animation_trims.json).
            AnimationDefaults.apply(key, a);
        }
        a.play(cached);
    }

    /** Phase 6A — load (cached) + play an animation by file path on the given
     *  Animator. No-op for empty paths / non-skinned meshes / load failure.
     *  Sets {@code animationFilePath} so the rig-loader doesn't fight it. */
    private void playAnimByPath(Animator anim, String path) {
        if (anim == null || path == null || path.isEmpty()) return;
        if (anim.gameObject == null) return;
        MeshRenderer mr = anim.gameObject.getComponent(MeshRenderer.class);
        if (mr == null) return;
        VkMesh mesh = meshFor(mr);
        if (mesh == null || !mesh.isSkinned()) return;
        Animation clip = animationCache.get(path);
        if (clip == null) {
            clip = AnimationLoader.loadAnimation(path, mesh.skeleton());
            if (clip == null) { Logger.warn("[VK] State-machine anim failed: " + path); return; }
            animationCache.put(path, clip);
            AnimationDefaults.apply(path, anim);
        }
        anim.animationFilePath = path;
        anim.play(clip);
    }

    /** Phase 6A — get (or lazily create on a skinned mesh) the Animator for an
     *  entity. Returns null if the mesh isn't skinned. */
    private Animator ensureEntityAnimator(GameObject g) {
        Animator a = g.getComponent(Animator.class);
        if (a != null) { if (a.gameObject == null) a.gameObject = g; return a; }
        MeshRenderer mr = g.getComponent(MeshRenderer.class);
        VkMesh mesh = (mr != null) ? meshFor(mr) : null;
        if (mesh == null || !mesh.isSkinned()) return null;
        a = new Animator();
        a.start();
        a.gameObject = g;
        g.addComponentNoStart(a);
        return a;
    }

    /** Phase 6A — play-mode locomotion state machine. Picks Idle/Walk/Sprint/
     *  Jump from the Player's horizontal speed, grounded flag, and Shift, then
     *  switches the avatar's Animator clip ONLY when the state changes (so the
     *  clip isn't restarted every frame). Clip paths come from the LIVE bound
     *  loco (`player.locomotion`), so editing a slot updates play instantly. */
    private int currentLocoState = -1;   // 0 idle, 1 walk, 2 sprint, 3 jump
    private void updateLocomotionStateMachine() {
        if (activePlayerEntity == null) { currentLocoState = -1; return; }
        CharacterLocomotionComponent loco = player.locomotion;
        if (loco == null) return;
        Animator anim = ensureEntityAnimator(activePlayerEntity);
        if (anim == null) return;

        float hSpeed = (float) Math.sqrt(player.xVel * player.xVel + player.zVel * player.zVel);
        boolean sprint = keys[GLFW_KEY_LEFT_SHIFT];
        int state;
        if (!player.isGrounded)      state = 3;   // jump / fall
        else if (hSpeed < 0.1f)      state = 0;   // idle
        else if (sprint)             state = 2;   // sprint
        else                         state = 1;   // walk

        if (state == currentLocoState) return;    // no change → don't restart
        currentLocoState = state;
        String path = switch (state) {
            case 1  -> loco.animWalk;
            case 2  -> loco.animSprint;
            case 3  -> loco.animJump;
            default -> loco.animIdle;
        };
        playAnimByPath(anim, path);
    }

    /** Phase 6A — Character Editor "assign animation" callback: play the idle
     *  clip on the preview so the user immediately sees motion. */
    private void previewPlayIdle() {
        if (previewCharacter == null) return;
        CharacterLocomotionComponent loco =
                previewCharacter.getComponent(CharacterLocomotionComponent.class);
        if (loco == null || loco.animIdle == null || loco.animIdle.isEmpty()) return;
        Animator anim = ensureEntityAnimator(previewCharacter);
        playAnimByPath(anim, loco.animIdle);
    }

    /** Phase 6B — per-slot Play button: force the preview to play one specific
     *  clip path (regardless of state). */
    private void previewPlayPath(String path) {
        if (previewCharacter == null || path == null || path.isEmpty()) return;
        playAnimByPath(ensureEntityAnimator(previewCharacter), path);
    }

    /** Phase 6B — "Clear Character" button: drop the preview back to a blank
     *  state and reset the camera framing for the next model. */
    private void clearPreviewCharacter() {
        previewCharacter = null;
        previewCameraYaw = 45f;
        previewCameraPitch = 20f;
        previewCameraDistance = 3.0f;
        previewPanOffset.set(0f, 1f, 0f);   // frame the chest, not the feet
    }

    /** Seed Player from the loaded level. Mirrors LevelEditorScene init
     *  (lines 201-216): reset to {@code PLAYER_START_Y}, teleport to the
     *  spawn-point entity if present. */
    private void initPlayer() {
        player.x = 0f;
        player.y = Config.PLAYER_START_Y;
        player.z = 0f;
        player.xVel = player.yVel = player.zVel = 0f;
        player.isGrounded = false;
        player.currentState = Player.State.IDLE;
        applySpawnPointIfPresent();
    }

    /** Teleport the player to the spawn-point entity from level.json (if
     *  one exists). Ported verbatim from LevelEditorScene.applySpawnPointIfPresent
     *  (lines 1116-1137). Y offset is {@code Config.PLAYER_START_Y} above
     *  the spawn's world position (eye height, not foot). */
    private void applySpawnPointIfPresent() {
        // Capsule dimensions come from the standalone "Character Collision"
        // gameplay object (if one exists); falls back to the defaults the
        // Player fields already hold. Position comes from the Spawn Point.
        for (GameObject obj : entities) {
            MeshRenderer mr = obj.getComponent(MeshRenderer.class);
            if (mr != null && World.PLAYER_BODY_KEY.equals(mr.modelPath)) {
                player.capsuleRadius = Math.max(0.1f, obj.transform.scaleX * 0.5f);
                player.capsuleHeight = Math.max(0.5f, obj.transform.scaleY);
                Logger.info("Player capsule from Character Collision: r="
                        + player.capsuleRadius + " h=" + player.capsuleHeight);
                break;
            }
        }
        // Spawn position: a GameObject tagged SpawnPointComponent wins;
        // otherwise the legacy SPAWN_POINT_KEY marker.
        for (GameObject obj : entities) {
            MeshRenderer mr = obj.getComponent(MeshRenderer.class);
            boolean isSpawn = obj.getComponent(SpawnPointComponent.class) != null
                    || (mr != null && World.SPAWN_POINT_KEY.equals(mr.modelPath));
            if (isSpawn) {
                player.x = obj.transform.x;
                player.y = obj.transform.y + player.eyeHeight();
                player.z = obj.transform.z;
                player.xVel = player.yVel = player.zVel = 0f;
                player.isGrounded = false;
                Logger.info("Spawned at Spawn Point: ("
                        + player.x + "," + player.y + "," + player.z + ")");
                return;
            }
        }
        // No Spawn Point — fall back to origin so the player can't get
        // stuck inside a Win Zone forever.
        player.x = 0f;
        player.y = Config.PLAYER_START_Y;
        player.z = 0f;
        player.xVel = player.yVel = player.zVel = 0f;
        player.isGrounded = false;
    }

    /** Win Zone trigger: if the player walks into a Win Zone's AABB, warp
     *  back to spawn. Play-mode only, matches
     *  LevelEditorScene.checkWinZones (lines 1141-1152). */
    private void checkWinZones() {
        for (GameObject obj : entities) {
            boolean trigger = obj.getComponent(WinZoneComponent.class) != null;
            if (!trigger) {
                MeshRenderer mr = obj.getComponent(MeshRenderer.class);
                trigger = mr != null && World.WIN_ZONE_KEY.equals(mr.modelPath);
            }
            if (!trigger) continue;
            if (player.x >= obj.getMinX() && player.x <= obj.getMaxX() &&
                    player.y >= obj.getMinY() && player.y <= obj.getMaxY() &&
                    player.z >= obj.getMinZ() && player.z <= obj.getMaxZ()) {
                Logger.info("Win Zone reached - " + obj.name);
                GameStateManager.requestWin();
                applySpawnPointIfPresent();
                return;
            }
        }
    }

    // =============================================================
    //                  Vulkan + scene init
    // =============================================================

    private void initVulkan() {
        ctx  = new VkContext(glfwWindow, ENABLE_VALIDATION);
        swap = new VkSwapchain(ctx, glfwWindow, MAX_FRAMES_IN_FLIGHT);

        // Shadow map — bound at binding 1 of every model-pipeline set, so
        // it must exist before any descriptor set is allocated.
        shadowMap = new VkShadowMap(ctx.device(), ctx.physicalDevice(),
                swap.depthFormat(), Config.SHADOW_MAP_WIDTH, Config.SHADOW_MAP_HEIGHT);
        Logger.info("[VK] Shadow map " + Config.SHADOW_MAP_WIDTH + "x" + Config.SHADOW_MAP_HEIGHT);

        // Descriptor layout + pipelines.
        createModelDescriptorSetLayout();
        createModelPipeline();
        createShadowPipeline();
        createModelDescriptorPool();
        // Sub-phase G — skinned characters. Runtime must exist before the
        // pipeline (the pipeline reads boneSetLayout from the runtime).
        skinnedRuntime = new VkSkinnedRuntime(ctx);
        createSkinnedModelPipeline();
        createSkinnedShadowPipeline();
        createMarkerPipeline();

        // Skybox — fixed cube + cubemap, plus its own descriptor pool/set.
        skyboxMesh    = new VkMesh(ctx.device(), ctx.physicalDevice(),
                SKYBOX_CUBE_VERTICES, SKYBOX_CUBE_INDICES);
        skyboxCubemap = VkTexture.loadCubemap(ctx.device(), ctx.physicalDevice(),
                ctx.commandPool(), ctx.graphicsQueue(), SKYBOX_FACES);
        createSkyboxDescriptors();
        createSkyboxPipeline();

        // Load the level (no-GL data path) and warm the asset caches so the
        // first frame isn't a stutter-fest of synchronous mesh/texture loads.
        entities = LevelLoader.loadLevelData(LEVEL_PATH);
        world.entities = entities;
        prewarmEntityAssets();
        world.updateGrid();
        initPlayer();
        snapCameraToSpawn();

        // Offscreen target — the Scene Viewport panel samples from this.
        // Compatible attachment formats with swap.renderPass() so the model /
        // shadow / skybox pipelines (built against swap) work here unchanged.
        // Initial size matches the swapchain; renderSceneViewport reports the
        // panel size each frame and we resize when it changes.
        sceneTarget = new VkOffscreenTarget(ctx,
                VK_FORMAT_B8G8R8A8_UNORM, swap.depthFormat(),
                swap.extentW(), swap.extentH());
        viewportW = swap.extentW();
        viewportH = swap.extentH();

        // Character Editor offscreen target — same colour/depth formats so it
        // stays render-pass-compatible; starts at a small default size and
        // resizes to its panel's content region each frame.
        characterTarget = new VkOffscreenTarget(ctx,
                VK_FORMAT_B8G8R8A8_UNORM, swap.depthFormat(), 512, 512);

        // Phase 2 — "Game" viewport target (same formats → render-pass-compatible
        // with the scene/model/skybox pipelines built against swap).
        gameTarget = new VkOffscreenTarget(ctx,
                VK_FORMAT_B8G8R8A8_UNORM, swap.depthFormat(), 960, 540);

        // Line + gizmo + selection — share the offscreen render pass so
        // they overlay the scene before ImGui samples it. The cube mesh
        // they use is populated lazily by meshFor on first procedural
        // cube hit; prewarm a stub here so we don't trip on first frame.
        VkMesh cubeMesh = ensureCubeMesh();
        lineRenderer      = new VkLineRenderer(ctx, sceneTarget.renderPass());
        gizmo             = new VkGizmo(lineRenderer, cubeMesh);
        selectionRenderer = new VkSelectionRenderer(lineRenderer);
        outlineRenderer   = new VkOutlineRenderer(ctx, sceneTarget.renderPass(), descriptorSetLayout);
        // Hand the skinned runtime to the outline renderer so it can
        // build its (mark + draw) skinned pipelines for bone-deformed
        // character silhouettes.
        outlineRenderer.setSkinnedRuntime(skinnedRuntime, sceneTarget.renderPass());
        // Instanced cube pipeline + batch — sub-phase F. Built AFTER
        // descriptorSetLayout + sceneTarget exist (above).
        createCubePipeline();
        cubeBatch = new VkCubeBatch(ctx);
        renderer2D = new VkRenderer2D(ctx);

        createCommandBuffers();
        initImGui();
        initImGuiTextures();

        // Phase 3A — IPC sockets. Runtime instance = client (listens for
        // transforms); editor = server (will broadcast them). Loopback only.
        netSync = runtimeMode ? NetworkSync.initClient(IPC_PORT)
                              : NetworkSync.initServer(IPC_PORT);

        startNanos = System.nanoTime();
        Logger.info("[VK] Initialization complete — entering render loop");
    }

    /** Ensure the shared procedural cube VkMesh exists in the cache and
     *  return it — the gizmo + selection wireframe need it from the very
     *  first frame, before any cube entity has triggered a lazy load. */
    private VkMesh ensureCubeMesh() {
        VkMesh existing = meshCache.get(World.CUBE_MESH_KEY);
        if (existing != null) return existing;
        VkMesh built = buildCubeMesh();
        meshCache.put(World.CUBE_MESH_KEY, built);
        return built;
    }

    /** Register offscreen-scene + shadow-map textures with the ImGui
     *  backend so ImGui.image can display them. The viewport TexID is
     *  re-bound when the offscreen target resizes (drawFrame). */
    private void initImGuiTextures() {
        viewportTexId = imGuiVk.registerTexture(sceneTarget.colorView(), sceneTarget.sampler());
        characterTexId = imGuiVk.registerTexture(characterTarget.colorView(), characterTarget.sampler());
        gameTexId = imGuiVk.registerTexture(gameTarget.colorView(), gameTarget.sampler());

        // Shadow map is a depth image. ImGui samples RGBA from a single
        // .r channel by default — without swizzling the panel shows pure
        // red. Make a separate image view that swizzles G and B to read
        // R, so the depth shows as proper greyscale (matches OpenGL
        // ShadowMapFBO.java:46-47 which uses GL_TEXTURE_SWIZZLE_G/B).
        // Also use a non-compare sampler — the shader's PCF lookups want
        // compareEnable=false (they read raw depth), so reuse that.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(shadowMap.image())
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(shadowMap.format());
            info.components()
                    .r(VK_COMPONENT_SWIZZLE_R)
                    .g(VK_COMPONENT_SWIZZLE_R)
                    .b(VK_COMPONENT_SWIZZLE_R)
                    .a(VK_COMPONENT_SWIZZLE_ONE);
            info.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateImageView(ctx.device(), info, null, p),
                    "vkCreateImageView(shadow imgui swizzled)");
            shadowMapImGuiView = p.get(0);
        }
        shadowMapImGuiSampler = shadowMap.sampler();   // share the existing sampler
        shadowMapTexId = imGuiVk.registerTexture(shadowMapImGuiView, shadowMapImGuiSampler);
        Logger.info("[VK] ImGui textures registered (viewport, shadow)");
    }

    /** Bring up Dear ImGui matching the OpenGL build's setup (Window.java
     *  lines 148-153): docking enabled, .ini layout persistence on, GLFW
     *  callbacks chained so ImGui sees clicks/keys AND ours still fire. */
    private void initImGui() {
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        // DockingEnable lets the user dock panels into dock-zones around the
        // viewport — the missing flag that meant panels couldn't be docked
        // / arranged like they used to be. Matches Window.java:149.
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        // Explicit .ini file — window positions/sizes/dock layout/collapsed
        // state are written here on exit and reloaded on startup, so the AAA
        // docking layout survives engine restarts. (A past regression set this
        // to null and killed persistence — keep it named.)
        io.setIniFilename("imgui.ini");
        // NOTE: ViewportsEnable is deliberately NOT set yet. It must be off when
        // ImGuiImplGlfw.init runs below, so that backend SKIPS initialising its
        // own (GL-context, Vulkan-incompatible) platform window interface. We
        // enable viewports further down, after our own Vulkan-safe platform +
        // renderer backends are registered.

        // Phase 1 (AAA overhaul): professional dark theme.
        applyDarkTheme(ImGui.getStyle());

        // Global font scale stays 1.0 EVERYWHERE for consistency across all
        // screens (the Main Menu enlarges only its OWN window via
        // setWindowFontScale). Past bug: the menu set global 2.2 and other
        // screens saw oversized text until a transition reset it.
        io.setFontGlobalScale(1.0f);

        imGuiGlfw = new ImGuiImplGlfw();
        // installCallbacks=true: ImGui chains its callbacks on top of the
        // ones we set in initWindow(). Matches Window.java:152. Kept for
        // main-window input + monitor enumeration; its platform window
        // interface stays uninitialised (ViewportsEnable is still off here).
        imGuiGlfw.init(glfwWindow, true);

        imGuiVk = new VkImGuiRenderer(ctx, swap.renderPass());

        if (MULTI_VIEWPORT) {
            // Multi-viewport renderer backend: one Vulkan presentation stack per
            // detached panel. Built against the main swapchain's colour format so
            // its shared render pass + ImGui pipeline variant stay compatible.
            // register() wires the 5 renderer callbacks onto ImGui's platform IO.
            viewportRenderer = new VkViewportRenderer(ctx, imGuiVk, swap.imageFormat());
            viewportRenderer.register(ImGui.getPlatformIO());

            // Multi-viewport PLATFORM backend: hand-rolled GLFW child windows, all
            // created with GLFW_CLIENT_API = GLFW_NO_API so a Vulkan surface can be
            // made for each (fixes the ImGuiImplGlfw GL-context crash). Registering
            // these OVERRIDES the platform window-management callbacks.
            platformBackend = new VkGlfwPlatformIO(glfwWindow, imGuiGlfw);
            platformBackend.register(ImGui.getPlatformIO());

            // Turn the system on. PlatformHasViewports + RendererHasViewports
            // advertise that we host detached windows ourselves; the end-of-frame
            // pump (updatePlatformWindows / renderPlatformWindowsDefault) drives
            // create / render / present / destroy.
            io.addConfigFlags(ImGuiConfigFlags.ViewportsEnable);
            io.addBackendFlags(ImGuiBackendFlags.PlatformHasViewports
                    | ImGuiBackendFlags.RendererHasViewports);
        }
        // else: internal docking only. DockingEnable (set above) +
        // dockSpaceOverViewport (buildEditorUI) give seamless drag / tab / dock
        // INSIDE the main Vulkan window. ImGui mouse coords stay main-window-
        // local, so the raycaster / box-select keep working, and there's a
        // single swapchain so no present contention. viewportRenderer /
        // platformBackend stay null and the flag-guarded pump is a no-op.

        // Restore the user's last panel open/closed state (overrides the
        // ImBoolean(true) defaults) so closed panels stay closed on restart.
        loadEditorPrefs();
    }

    /** Phase 1 (AAA overhaul) — professional dark theme: deep-grey backgrounds,
     *  subtle frames, a blue accent for hover/active, and rounded geometry. */
    private static void applyDarkTheme(ImGuiStyle s) {
        s.setWindowRounding(6f);
        s.setChildRounding(6f);
        s.setFrameRounding(4f);
        s.setPopupRounding(4f);
        s.setGrabRounding(4f);
        s.setScrollbarRounding(6f);
        s.setTabRounding(4f);
        s.setWindowBorderSize(1f);
        s.setFrameBorderSize(0f);
        s.setWindowPadding(10f, 10f);
        s.setFramePadding(8f, 5f);
        s.setItemSpacing(8f, 6f);
        s.setScrollbarSize(14f);

        final float[] BG     = { 0.118f, 0.118f, 0.118f, 1f }; // #1E1E1E
        final float[] BG_DK  = { 0.090f, 0.090f, 0.090f, 1f }; // titles / menubar
        final float[] FRAME  = { 0.165f, 0.165f, 0.165f, 1f }; // #2A2A2A
        final float[] FRAMEH = { 0.220f, 0.220f, 0.220f, 1f };
        final float[] ACC    = { 0.180f, 0.450f, 0.900f, 1f }; // blue accent
        final float[] ACCH   = { 0.260f, 0.560f, 1.000f, 1f };
        final float[] TEXT   = { 0.870f, 0.870f, 0.870f, 1f };
        final float[] TEXTD  = { 0.500f, 0.500f, 0.500f, 1f };
        final float[] BORDER = { 0.060f, 0.060f, 0.060f, 1f };

        s.setColor(ImGuiCol.Text, TEXT[0], TEXT[1], TEXT[2], TEXT[3]);
        s.setColor(ImGuiCol.TextDisabled, TEXTD[0], TEXTD[1], TEXTD[2], TEXTD[3]);
        s.setColor(ImGuiCol.WindowBg, BG[0], BG[1], BG[2], 1f);
        s.setColor(ImGuiCol.ChildBg, BG[0], BG[1], BG[2], 1f);
        s.setColor(ImGuiCol.PopupBg, 0.13f, 0.13f, 0.13f, 0.98f);
        s.setColor(ImGuiCol.Border, BORDER[0], BORDER[1], BORDER[2], 1f);
        s.setColor(ImGuiCol.FrameBg, FRAME[0], FRAME[1], FRAME[2], 1f);
        s.setColor(ImGuiCol.FrameBgHovered, FRAMEH[0], FRAMEH[1], FRAMEH[2], 1f);
        s.setColor(ImGuiCol.FrameBgActive, ACC[0], ACC[1], ACC[2], 0.55f);
        s.setColor(ImGuiCol.TitleBg, BG_DK[0], BG_DK[1], BG_DK[2], 1f);
        s.setColor(ImGuiCol.TitleBgActive, 0.14f, 0.14f, 0.14f, 1f);
        s.setColor(ImGuiCol.TitleBgCollapsed, BG_DK[0], BG_DK[1], BG_DK[2], 1f);
        s.setColor(ImGuiCol.MenuBarBg, BG_DK[0], BG_DK[1], BG_DK[2], 1f);
        s.setColor(ImGuiCol.ScrollbarBg, BG_DK[0], BG_DK[1], BG_DK[2], 1f);
        s.setColor(ImGuiCol.ScrollbarGrab, FRAMEH[0], FRAMEH[1], FRAMEH[2], 1f);
        s.setColor(ImGuiCol.ScrollbarGrabHovered, 0.30f, 0.30f, 0.30f, 1f);
        s.setColor(ImGuiCol.ScrollbarGrabActive, ACC[0], ACC[1], ACC[2], 1f);
        s.setColor(ImGuiCol.CheckMark, ACCH[0], ACCH[1], ACCH[2], 1f);
        s.setColor(ImGuiCol.SliderGrab, ACC[0], ACC[1], ACC[2], 1f);
        s.setColor(ImGuiCol.SliderGrabActive, ACCH[0], ACCH[1], ACCH[2], 1f);
        s.setColor(ImGuiCol.Button, FRAME[0], FRAME[1], FRAME[2], 1f);
        s.setColor(ImGuiCol.ButtonHovered, FRAMEH[0], FRAMEH[1], FRAMEH[2], 1f);
        s.setColor(ImGuiCol.ButtonActive, ACC[0], ACC[1], ACC[2], 1f);
        s.setColor(ImGuiCol.Header, FRAME[0], FRAME[1], FRAME[2], 1f);
        s.setColor(ImGuiCol.HeaderHovered, FRAMEH[0], FRAMEH[1], FRAMEH[2], 1f);
        s.setColor(ImGuiCol.HeaderActive, ACC[0], ACC[1], ACC[2], 1f);
        s.setColor(ImGuiCol.Separator, BORDER[0], BORDER[1], BORDER[2], 1f);
        s.setColor(ImGuiCol.SeparatorHovered, ACC[0], ACC[1], ACC[2], 0.7f);
        s.setColor(ImGuiCol.ResizeGrip, FRAME[0], FRAME[1], FRAME[2], 1f);
        s.setColor(ImGuiCol.ResizeGripHovered, FRAMEH[0], FRAMEH[1], FRAMEH[2], 1f);
        s.setColor(ImGuiCol.ResizeGripActive, ACC[0], ACC[1], ACC[2], 1f);
        s.setColor(ImGuiCol.Tab, BG_DK[0], BG_DK[1], BG_DK[2], 1f);
        s.setColor(ImGuiCol.TabHovered, FRAMEH[0], FRAMEH[1], FRAMEH[2], 1f);
        s.setColor(ImGuiCol.TabActive, FRAME[0], FRAME[1], FRAME[2], 1f);
        s.setColor(ImGuiCol.TabUnfocused, BG_DK[0], BG_DK[1], BG_DK[2], 1f);
        s.setColor(ImGuiCol.TabUnfocusedActive, FRAME[0], FRAME[1], FRAME[2], 1f);
        s.setColor(ImGuiCol.DockingPreview, ACC[0], ACC[1], ACC[2], 0.7f);
        s.setColor(ImGuiCol.DockingEmptyBg, BG_DK[0], BG_DK[1], BG_DK[2], 1f);
    }

    /** Iterate the level once at startup, building each unique
     *  mesh / texture / descriptor set so subsequent frames just look them
     *  up. Skips markers and entities without a MeshRenderer. */
    private void prewarmEntityAssets() {
        for (GameObject g : entities) {
            MeshRenderer mr = g.getComponent(MeshRenderer.class);
            if (mr == null) continue;
            applyMeshRendererDefaults(mr);
            if (isMarker(mr)) continue;
            VkMesh m = meshFor(mr);  // populates meshCache and embeddedTextureByModel
            populateVkLocalGeometry(mr, m);
            descriptorSetFor(mr);    // populates textureCache + descriptorSetCache
        }
        Logger.info("[VK] Prewarm: " + meshCache.size() + " meshes, "
                + textureCache.size() + " textures, "
                + descriptorSetCache.size() + " descriptor sets");
    }

    /** Apply the fields {@link MeshRenderer#start} normally sets — auto-
     *  flag foliage by filename, fill in the default for the nullable
     *  {@code useWorldSpaceTiling}. Called from the no-GL load path
     *  (Vulkan never invokes MeshRenderer.start). */
    private static void applyMeshRendererDefaults(MeshRenderer mr) {
        if (mr.modelPath != null && mr.modelPath.toLowerCase().contains("leaf")) {
            mr.isFoliage = true;
        }
        if (mr.material.useWorldSpaceTiling == null) {
            boolean procedural = mr.modelPath == null
                    || mr.modelPath.isEmpty()
                    || World.CUBE_MESH_KEY.equals(mr.modelPath);
            mr.material.useWorldSpaceTiling = procedural;
        }
    }

    /** Reset the player to the spawn point + clear velocity. Used at
     *  startup and after a Load Level. Player physics drives the camera
     *  from this frame on (updateCamera snapshots player → cam*). */
    private void snapCameraToSpawn() {
        initPlayer();
    }

    // ---- Asset lookup helpers ----

    /** Returns the mesh key for an entity: a procedural-cube/floor token,
     *  null for markers (caller should skip those), or the modelPath
     *  itself for imported models. Mirrors AssetPool.getMesh's logic. */
    private static String meshKey(MeshRenderer mr) {
        if (mr.modelPath == null || mr.modelPath.isEmpty()) return World.CUBE_MESH_KEY;
        // The player-body capsule has no mesh at all — the editor draws a
        // wireframe capsule for it. Must come before the isMarker branch
        // (which maps to the sprite quad).
        if (World.PLAYER_BODY_KEY.equals(mr.modelPath))     return null;
        // Markers share the same billboard quad mesh — collapse both
        // SPAWN_POINT and WIN_ZONE to one key so we only build/upload once.
        if (isMarker(mr))                                   return World.SPAWN_POINT_KEY;
        return mr.modelPath;
    }

    /** Should {@code g}'s mesh be hidden in the MAIN scene pass?
     *  Consolidated play-state rule (Emergency Consolidation Pass):
     *
     *  A "play view" = standalone runtime OR Editor Play ('P', isEditorMode
     *  false). In the editor (not playing) nothing is hidden — everything
     *  stays visible + editable.
     *
     *  In a play view:
     *   - InvisibleComponent / SpawnPointComponent markers are hidden.
     *   - The ACTIVE PLAYER's own avatar is hidden ONLY when first-person
     *     (!isThirdPerson) AND its "Hide Avatar" flag is set
     *     (hiddenSubmeshIndex >= 0). Third-person force-draws it; a
     *     non-active character is never auto-hidden by this rule. */
    private boolean hiddenAtRuntime(GameObject g) {
        boolean playView = runtimeMode || !isEditorMode;
        if (!playView) return false;

        if (g.getComponent(InvisibleComponent.class) != null
            || g.getComponent(SpawnPointComponent.class) != null) return true;

        if (g == activePlayerEntity && !isThirdPerson) {
            CharacterLocomotionComponent loco = g.getComponent(CharacterLocomotionComponent.class);
            return loco != null && loco.hiddenSubmeshIndex >= 0;
        }
        return false;
    }

    private static boolean isMarker(MeshRenderer mr) {
        return World.SPAWN_POINT_KEY.equals(mr.modelPath)
            || World.WIN_ZONE_KEY.equals(mr.modelPath)
            || World.PLAYER_BODY_KEY.equals(mr.modelPath);
    }

    /** Procedural cubes — empty modelPath OR the procedural cube key —
     *  go through the instanced cube pipeline (sub-phase F). Mirrors
     *  LevelEditorScene.isInstanceableCube line 283. */
    private static boolean isInstanceableCube(MeshRenderer mr) {
        return mr.modelPath == null
            || mr.modelPath.isEmpty()
            || World.CUBE_MESH_KEY.equals(mr.modelPath);
    }

    /** Build / fetch the VkMesh for an entity. Procedural cube + floor are
     *  built on first use; imported models go through VkMeshLoader (which
     *  may also yield an embedded texture — cached for textureFor). */
    private VkMesh meshFor(MeshRenderer mr) {
        String key = meshKey(mr);
        if (key == null) return null;
        VkMesh cached = meshCache.get(key);
        if (cached != null) return cached;
        if (failedAssetKeys.contains("mesh:" + key)) return null;

        try {
            VkMesh m;
            if (World.CUBE_MESH_KEY.equals(key))         m = buildCubeMesh();
            else if (World.SPAWN_POINT_KEY.equals(key))  m = buildSpawnQuadMesh();
            else {
                // Character models live in CHARACTERS_PATH, regular models
                // in MODELS_DIR. The modelPath ("characters/Hazmat.glb") is
                // stored with the prefix; strip it and route to the right
                // base dir. Mirrors AssetPool.getMesh + ModelLoader's path
                // logic on the OpenGL side.
                String diskPath = key.startsWith(Config.CHARACTER_PATH_PREFIX)
                        ? Config.CHARACTERS_PATH + key.substring(Config.CHARACTER_PATH_PREFIX.length())
                        : MODELS_DIR + key;
                VkMeshLoader.LoadResult res = VkMeshLoader.load(ctx.device(), ctx.physicalDevice(),
                        ctx.commandPool(), ctx.graphicsQueue(), diskPath);
                m = res.mesh();
                if (res.embeddedTexture() != null) embeddedTextureByModel.put(key, res.embeddedTexture());
            }
            meshCache.put(key, m);
            return m;
        } catch (Exception e) {
            Logger.error("[VK] Failed to load mesh '" + key + "'; entity will be skipped", e);
            failedAssetKeys.add("mesh:" + key);
            return null;
        }
    }

    /** Texture lookup: embedded GLB texture wins if present for the model;
     *  otherwise load {@code textures/<texturePath>} from disk. */
    private VkTexture textureFor(MeshRenderer mr) {
        String mkey = meshKey(mr);
        if (mkey != null && embeddedTextureByModel.containsKey(mkey)) {
            return embeddedTextureByModel.get(mkey);
        }
        String tp = (mr.texturePath == null || mr.texturePath.isEmpty()) ? "test.png" : mr.texturePath;
        VkTexture cached = textureCache.get(tp);
        if (cached != null) return cached;
        if (failedAssetKeys.contains("tex:" + tp)) return null;
        try {
            VkTexture t = VkTexture.loadFromFile(ctx.device(), ctx.physicalDevice(),
                    ctx.commandPool(), ctx.graphicsQueue(), TEXTURES_DIR + tp);
            textureCache.put(tp, t);
            return t;
        } catch (Exception e) {
            Logger.error("[VK] Failed to load texture '" + tp + "'; entity will be skipped", e);
            failedAssetKeys.add("tex:" + tp);
            return null;
        }
    }

    /** One descriptor set per (material texture, shadow map) pair. The
     *  shadow map is global so we share sets by material texture identity. */
    private long descriptorSetFor(MeshRenderer mr) {
        String mkey = meshKey(mr);
        String dsKey = (mkey != null && embeddedTextureByModel.containsKey(mkey))
                ? "emb:" + mkey
                : "tex:" + ((mr.texturePath == null || mr.texturePath.isEmpty()) ? "test.png" : mr.texturePath);
        Long cached = descriptorSetCache.get(dsKey);
        if (cached != null) return cached;
        VkTexture tex = textureFor(mr);
        if (tex == null) return 0L;   // caller should skip this entity
        long set = allocModelDescriptorSet(tex, dsKey);
        descriptorSetCache.put(dsKey, set);
        return set;
    }

    /** Unit quad in the XY plane used as the spawn/win-zone billboard.
     *  Verbatim port of World.java:34-44 (qpos / qtex / qnorm / qind). */
    private VkMesh buildSpawnQuadMesh() {
        float[] verts = {
                -0.5f, -0.5f, 0,   0,0,1,   0,1,
                 0.5f, -0.5f, 0,   0,0,1,   1,1,
                 0.5f,  0.5f, 0,   0,0,1,   1,0,
                -0.5f,  0.5f, 0,   0,0,1,   0,0
        };
        int[] indices = { 0, 1, 2,   0, 2, 3 };
        return new VkMesh(ctx.device(), ctx.physicalDevice(), verts, indices);
    }

    /** Unit cube — 24 vertices (per-face normals/UVs). Ported verbatim
     *  from World.java:51-71. */
    private VkMesh buildCubeMesh() {
        float[] pos = {
                -0.5f,  0.5f,  0.5f,   0.5f,  0.5f,  0.5f,   0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,
                -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f, -0.5f, -0.5f,  -0.5f, -0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
                -0.5f, -0.5f, -0.5f,   0.5f, -0.5f, -0.5f,   0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,
                 0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,   0.5f, -0.5f,  0.5f,   0.5f, -0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,  -0.5f,  0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,  -0.5f, -0.5f, -0.5f
        };
        float[] norm = {
                0,0,1, 0,0,1, 0,0,1, 0,0,1,  0,0,-1, 0,0,-1, 0,0,-1, 0,0,-1,
                0,1,0, 0,1,0, 0,1,0, 0,1,0,  0,-1,0, 0,-1,0, 0,-1,0, 0,-1,0,
                1,0,0, 1,0,0, 1,0,0, 1,0,0,  -1,0,0, -1,0,0, -1,0,0, -1,0,0
        };
        float[] uv = {
                0,1, 1,1, 1,0, 0,0,  1,1, 0,1, 0,0, 1,0,  0,1, 1,1, 1,0, 0,0,
                1,1, 0,1, 0,0, 1,0,  1,1, 0,1, 0,0, 1,0,  0,1, 1,1, 1,0, 0,0
        };
        // Interleave into the 8-floats-per-vertex layout VkMesh expects.
        float[] verts = new float[24 * 8];
        for (int i = 0; i < 24; i++) {
            verts[i*8 + 0] = pos[i*3 + 0];
            verts[i*8 + 1] = pos[i*3 + 1];
            verts[i*8 + 2] = pos[i*3 + 2];
            verts[i*8 + 3] = norm[i*3 + 0];
            verts[i*8 + 4] = norm[i*3 + 1];
            verts[i*8 + 5] = norm[i*3 + 2];
            verts[i*8 + 6] = uv[i*2 + 0];
            verts[i*8 + 7] = uv[i*2 + 1];
        }
        int[] indices = {
                0,1,2,  0,2,3,    4,5,6,  4,6,7,    8,9,10, 8,10,11,
                12,13,14, 12,14,15, 16,17,18, 16,18,19, 20,21,22, 20,22,23
        };
        return new VkMesh(ctx.device(), ctx.physicalDevice(), verts, indices);
    }

    /** Build a TRS model matrix from a {@link Transform} — same convention
     *  as {@link RenderState#modelMatrix}: translate → Rx → Ry → Rz → scale. */
    private static Matrix4f modelMatrixFromTransform(Transform t) {
        return new Matrix4f()
                .translate(t.x, t.y, t.z)
                .rotateX((float) Math.toRadians(t.rotX))
                .rotateY((float) Math.toRadians(t.rotY))
                .rotateZ((float) Math.toRadians(t.rotZ))
                .scale(t.scaleX, t.scaleY, t.scaleZ);
    }

    // ---- Pipelines (each ~15 lines thanks to VkPipelineBuilder) ----

    private void createModelPipeline() {
        VkPipelineBuilder.Built b = new VkPipelineBuilder(ctx.device(), "model")
                .shaders(MODEL_VERT, MODEL_FRAG)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R32G32_SFLOAT,    6 * Float.BYTES))
                .descriptorSetLayout(descriptorSetLayout)
                .pushConstants(MODEL_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(true, true, VK_COMPARE_OP_LESS)
                .colorBlendOpaque()
                .renderPass(swap.renderPass(), 0)
                .build();
        modelPipelineLayout = b.layout();
        modelPipeline       = b.pipeline();
    }

    /** Marker pipeline — billboards (spawn / win zone) render with:
     *   - depth test OFF + depth write OFF → always visible on top of
     *     scene geometry. Markers are UI-style sprites, not in-world
     *     objects; previously they were depth-tested and could be
     *     hidden behind cubes / walls, which the user reported as
     *     "sprites hide behind cubes". Now they always show.
     *   - alpha blending + hard discard threshold in MARKER_FRAG. The
     *     discard kills semi-transparent edge pixels that would
     *     otherwise show as a white halo around pixel-art PNGs.
     *  Same vertex layout / PC / descriptor set as the model pipeline. */
    private void createMarkerPipeline() {
        VkPipelineBuilder.Built b = new VkPipelineBuilder(ctx.device(), "marker")
                .shaders(MODEL_VERT, MARKER_FRAG)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R32G32_SFLOAT,    6 * Float.BYTES))
                .descriptorSetLayout(descriptorSetLayout)
                .pushConstants(MODEL_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(false, false, VK_COMPARE_OP_ALWAYS)   // always-on-top
                .colorBlendAlpha()
                .renderPass(swap.renderPass(), 0)
                .build();
        markerPipelineLayout = b.layout();
        markerPipeline       = b.pipeline();
    }

    /** Skinned-model pipeline (sub-phase G). Adds a second vertex binding
     *  for per-vertex bone IDs + weights, and a second descriptor set
     *  carrying the per-character bone-matrix UBO. PC layout is identical
     *  to the static model pipeline so push code is shared. */
    private void createSkinnedModelPipeline() {
        // Skinning binding 1: ivec4 boneIds (16B) + vec4 weights (16B) = 32B stride.
        final int SK_STRIDE = 4 * Integer.BYTES + 4 * Float.BYTES;
        VkPipelineBuilder.Built b = new VkPipelineBuilder(ctx.device(), "skinnedModel")
                .shaders(SKINNED_MODEL_VERT, SKINNED_MODEL_FRAG)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R32G32_SFLOAT,    6 * Float.BYTES))
                .vertexLayoutSecondary(SK_STRIDE,
                        VkPipelineBuilder.attr(3, VK_FORMAT_R32G32B32A32_SINT,    0),
                        VkPipelineBuilder.attr(4, VK_FORMAT_R32G32B32A32_SFLOAT, 16))
                .descriptorSetLayouts(descriptorSetLayout, skinnedRuntime.boneSetLayout())
                .pushConstants(MODEL_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(true, true, VK_COMPARE_OP_LESS)
                .colorBlendOpaque()
                .renderPass(swap.renderPass(), 0)
                .build();
        skinnedModelPipelineLayout = b.layout();
        skinnedModelPipeline       = b.pipeline();
    }

    /** Skinned shadow pipeline (sub-phase G follow-up). Same depthBias /
     *  push-constants as the static shadow pipeline; adds the skinning
     *  vertex binding + bone UBO descriptor set so the cast shadow
     *  deforms with the animation. */
    private void createSkinnedShadowPipeline() {
        final int SK_STRIDE = 4 * Integer.BYTES + 4 * Float.BYTES;
        VkPipelineBuilder.Built b = new VkPipelineBuilder(ctx.device(), "skinnedShadow")
                .shaders(SKINNED_SHADOW_VERT, SHADOW_FRAG)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R32G32_SFLOAT,    6 * Float.BYTES))
                .vertexLayoutSecondary(SK_STRIDE,
                        VkPipelineBuilder.attr(3, VK_FORMAT_R32G32B32A32_SINT,    0),
                        VkPipelineBuilder.attr(4, VK_FORMAT_R32G32B32A32_SFLOAT, 16))
                .descriptorSetLayouts(descriptorSetLayout, skinnedRuntime.boneSetLayout())
                .pushConstants(SHADOW_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(true, true, VK_COMPARE_OP_LESS)
                .depthBias(4.0f, 2.0f)
                .noColorBlend()
                .renderPass(shadowMap.renderPass(), 0)
                .build();
        skinnedShadowPipelineLayout = b.layout();
        skinnedShadowPipeline       = b.pipeline();
    }

    private void createShadowPipeline() {
        // depthBias(4.0, 2.0) mirrors glPolygonOffset(2.0, 4.0) from the GL shadow pass.
        // Vertex layout matches the model pipeline (8 floats/vert: pos + normal
        // + uv) — the shadow vertex shader reads UV for foliage alpha discard.
        // Descriptor set 0 reuses the model layout; binding 0 = material tex
        // (used by the fragment for alpha), binding 1 = shadow map (bound but
        // unread here — validation is happy with bound-but-unused descriptors).
        VkPipelineBuilder.Built b = new VkPipelineBuilder(ctx.device(), "shadow")
                .shaders(SHADOW_VERT, SHADOW_FRAG)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R32G32_SFLOAT,    6 * Float.BYTES))
                .descriptorSetLayout(descriptorSetLayout)
                .pushConstants(SHADOW_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(true, true, VK_COMPARE_OP_LESS)
                .depthBias(4.0f, 2.0f)
                .noColorBlend()
                .renderPass(shadowMap.renderPass(), 0)
                .build();
        shadowPipelineLayout = b.layout();
        shadowPipeline       = b.pipeline();
    }

    private void createCubePipeline() {
        // Vertex layout: same 8-floats/vertex as the regular model pipeline
        // for the cube mesh (binding 0). Per-instance binding 1 holds the
        // model matrix (4× vec4) + 6 face-colour vec3s — total stride 136B.
        final int INST_STRIDE = (16 + 18) * Float.BYTES;
        VkPipelineBuilder.Built b = new VkPipelineBuilder(ctx.device(), "cube")
                .shaders(CUBE_INSTANCED_VERT, CUBE_INSTANCED_FRAG)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0),
                        VkPipelineBuilder.attr(1, VK_FORMAT_R32G32B32_SFLOAT, 3 * Float.BYTES),
                        VkPipelineBuilder.attr(2, VK_FORMAT_R32G32_SFLOAT,    6 * Float.BYTES))
                .instanceLayout(INST_STRIDE,
                        // mat4 model → 4× vec4 at locs 3,4,5,6
                        VkPipelineBuilder.attr(3, VK_FORMAT_R32G32B32A32_SFLOAT,  0),
                        VkPipelineBuilder.attr(4, VK_FORMAT_R32G32B32A32_SFLOAT, 16),
                        VkPipelineBuilder.attr(5, VK_FORMAT_R32G32B32A32_SFLOAT, 32),
                        VkPipelineBuilder.attr(6, VK_FORMAT_R32G32B32A32_SFLOAT, 48),
                        // 6 face colours (vec3) at locs 7..12
                        VkPipelineBuilder.attr(7,  VK_FORMAT_R32G32B32_SFLOAT,  64),
                        VkPipelineBuilder.attr(8,  VK_FORMAT_R32G32B32_SFLOAT,  76),
                        VkPipelineBuilder.attr(9,  VK_FORMAT_R32G32B32_SFLOAT,  88),
                        VkPipelineBuilder.attr(10, VK_FORMAT_R32G32B32_SFLOAT, 100),
                        VkPipelineBuilder.attr(11, VK_FORMAT_R32G32B32_SFLOAT, 112),
                        VkPipelineBuilder.attr(12, VK_FORMAT_R32G32B32_SFLOAT, 124))
                .descriptorSetLayout(descriptorSetLayout)
                .pushConstants(CUBE_PC_FLOATS * Float.BYTES,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                .depth(true, true, VK_COMPARE_OP_LESS)
                .colorBlendOpaque()
                .renderPass(sceneTarget.renderPass(), 0)
                .build();
        cubePipelineLayout = b.layout();
        cubePipeline       = b.pipeline();
    }

    private void createSkyboxPipeline() {
        // depthWrite=false, depthCompareOp=LESS_OR_EQUAL so existing depth wins.
        // Matches the glDepthMask(false) trick in World.drawSkybox.
        VkPipelineBuilder.Built b = new VkPipelineBuilder(ctx.device(), "skybox")
                .shaders(SKYBOX_VERT, SKYBOX_FRAG)
                .vertexLayout(8 * Float.BYTES,
                        VkPipelineBuilder.attr(0, VK_FORMAT_R32G32B32_SFLOAT, 0))
                .descriptorSetLayout(skyboxDescriptorSetLayout)
                .pushConstants(16 * Float.BYTES, VK_SHADER_STAGE_VERTEX_BIT)
                .depth(true, false, VK_COMPARE_OP_LESS_OR_EQUAL)
                .colorBlendOpaque()
                .renderPass(swap.renderPass(), 0)
                .build();
        skyboxPipelineLayout = b.layout();
        skyboxPipeline       = b.pipeline();
    }

    // ---- Descriptor wiring ----

    /** Two sampler bindings: binding 0=material texture, binding 1=shadow map.
     *  Both visible to the fragment shader. Every model-pipeline set must
     *  satisfy both. */
    private void createModelDescriptorSetLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, stack);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(b);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(ctx.device(), info, null, p),
                    "vkCreateDescriptorSetLayout(model)");
            descriptorSetLayout = p.get(0);
        }
    }

    private void createModelDescriptorPool() {
        final int MAX_SETS = 64;  // one per unique level texture + headroom
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(MAX_SETS * 2);  // 2 samplers per set
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(sizes).maxSets(MAX_SETS);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorPool(ctx.device(), info, null, p),
                    "vkCreateDescriptorPool(model)");
            descriptorPool = p.get(0);
        }
    }

    /** Allocate one model-pipeline descriptor set and write both bindings. */
    private long allocModelDescriptorSet(VkTexture material, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer p = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(ctx.device(), alloc, p),
                    "vkAllocateDescriptorSets(" + label + ")");
            long set = p.get(0);

            VkDescriptorImageInfo.Buffer matImg = VkDescriptorImageInfo.calloc(1, stack);
            matImg.get(0).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(material.imageView()).sampler(material.sampler());
            VkDescriptorImageInfo.Buffer shadowImg = VkDescriptorImageInfo.calloc(1, stack);
            shadowImg.get(0).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(shadowMap.imageView()).sampler(shadowMap.sampler());

            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(2, stack);
            w.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(matImg);
            w.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(shadowImg);
            vkUpdateDescriptorSets(ctx.device(), w, null);
            return set;
        }
    }

    /** Skybox has its own one-binding layout + pool (only 1 cubemap, ever).
     *  Building it separately keeps the model layout uncluttered. */
    private void createSkyboxDescriptors() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Layout.
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1, stack);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo lInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(b);
            LongBuffer pL = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(ctx.device(), lInfo, null, pL),
                    "vkCreateDescriptorSetLayout(skybox)");
            skyboxDescriptorSetLayout = pL.get(0);

            // Pool.
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            VkDescriptorPoolCreateInfo pInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(sizes).maxSets(1);
            LongBuffer pP = stack.mallocLong(1);
            check(vkCreateDescriptorPool(ctx.device(), pInfo, null, pP),
                    "vkCreateDescriptorPool(skybox)");
            skyboxDescriptorPool = pP.get(0);

            // Set.
            VkDescriptorSetAllocateInfo aInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(skyboxDescriptorPool)
                    .pSetLayouts(stack.longs(skyboxDescriptorSetLayout));
            LongBuffer pS = stack.mallocLong(1);
            check(vkAllocateDescriptorSets(ctx.device(), aInfo, pS),
                    "vkAllocateDescriptorSets(skybox)");
            skyboxDescriptorSet = pS.get(0);

            VkDescriptorImageInfo.Buffer img = VkDescriptorImageInfo.calloc(1, stack);
            img.get(0).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(skyboxCubemap.imageView()).sampler(skyboxCubemap.sampler());
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
            w.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(skyboxDescriptorSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).pImageInfo(img);
            vkUpdateDescriptorSets(ctx.device(), w, null);
        }
    }

    private void createCommandBuffers() {
        commandBuffers = new VkCommandBuffer[MAX_FRAMES_IN_FLIGHT];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo info = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(ctx.commandPool())
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(MAX_FRAMES_IN_FLIGHT);
            org.lwjgl.PointerBuffer pCb = stack.mallocPointer(MAX_FRAMES_IN_FLIGHT);
            check(vkAllocateCommandBuffers(ctx.device(), info, pCb), "vkAllocateCommandBuffers");
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                commandBuffers[i] = new VkCommandBuffer(pCb.get(i), ctx.device());
            }
        }
    }

    // =============================================================
    //                       Render loop
    // =============================================================

    private void mainLoop() {
        long frameCount = 0;
        long lastReport = System.currentTimeMillis();
        while (!glfwWindowShouldClose(glfwWindow)) {
            // Deferred standalone-runtime Play setup (Issue 3): runs once, on
            // the first loop iteration, AFTER initVulkan has fully brought up
            // the device/queues/command pools — so reloadLevelFromDisk's
            // prewarm GPU uploads can't be bypassed by a half-ready context.
            if (runtimeMode && runtimePlayPending) {
                runtimePlayPending = false;
                enterRuntimePlay();
            }
            Profiler.frameStart();
            try (var z = Profiler.zone("Poll + camera")) {
                glfwPollEvents();
                updateCamera();
            }

            try (var z = Profiler.zone("ImGui build")) {
                // Build ImGui this frame BEFORE drawFrame so the draw data is
                // ready by the time we record commands. Main menu shows the
                // big centred panel; editor mode shows the panel set; play
                // mode shows nothing.
                imGuiGlfw.newFrame();
                ImGui.newFrame();
                if (inMainMenu)        buildMainMenuUI();
                else if (isEditorMode) buildEditorUI();
                // Phase 5C: the active-player avatar is Position-locked — force
                // Move mode so the gizmo only ever translates it (no scale /
                // rotate handles), regardless of the Inspector's visibility.
                if (selectedEntity != null && selectedEntity == activePlayerEntity) {
                    selectedEntity.transformMode = 0;
                }
                // Hover-pick — refresh every frame the cursor is over the
                // Scene Viewport. Mirrors LevelEditorScene's per-frame
                // `highlighted = player.getEntityAtMouse(...)` at line 485.
                // Drawn dimmer than the selected entity so you can see
                // what a click would land on before pressing the button.
                highlighted = null;
                // Reset gizmo-axis hover state every frame; recomputed
                // below when the cursor is over the viewport in editor mode.
                isHoveringX = isHoveringY = isHoveringZ = isHoveringCenter = false;
                gizmoHoverAxis = -1;
                if (isEditorMode && isViewportHovered && draggingAxis == -1) {
                    // Step 4: ImGui mouse pos (same space as viewportX) so hover-
                    // pick is correct with ViewportsEnable's global coords.
                    double localX = ImGui.getMousePosX() - viewportX;
                    double localY = ImGui.getMousePosY() - viewportY;
                    if (localX >= 0 && localY >= 0
                            && localX < viewportW && localY < viewportH) {
                        // Per-frame gizmo hover detection — highlights
                        // the axis (or centre cube) the cursor is over
                        // so the user can see what a click would grab
                        // BEFORE pressing the mouse button.
                        if (selectedEntity != null) {
                            boolean extrudeHover = selectedEntity.transformMode == 2 && ctrlHeld();
                            int hover = gizmo.getHitAxis(selectedEntity,
                                    mouseRayFromViewport(localX, localY),
                                    player.x, player.y, player.z, extrudeHover);
                            gizmoHoverAxis   = hover;
                            isHoveringX      = (hover == 0);
                            isHoveringY      = (hover == 1);
                            isHoveringZ      = (hover == 2);
                            isHoveringCenter = (hover == 3);
                        }
                        // Don't run entity hover-pick if the gizmo claimed the
                        // cursor (any of the up-to-7 handles) — keeps the entity
                        // silhouette from competing with the gizmo highlight.
                        if (gizmoHoverAxis == -1) {
                            highlighted = pickEntityAt(localX, localY);
                            // Paint-select: CTRL+LMB held → continuously add the
                            // hovered entity to the selection (once each).
                            if (highlighted != null && ctrlHeld()
                                    && glfwGetMouseButton(glfwWindow, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS) {
                                paintSelect(highlighted);
                            }
                        }
                    }
                } else if (!isEditorMode) {
                    // Play mode: the aim raycast / hover silhouette only runs
                    // while FLYING (creative build). Walking = strict gameplay,
                    // so clear any stale highlight (no selection silhouette).
                    highlighted = player.isFlying ? pickEntityFromCamera() : null;
                }

                // Consume click-to-select AFTER panels updated viewport
                // geometry. Click is dropped if cursor isn't over the
                // Scene Viewport panel.
                if (pendingClickInViewport) {
                    pendingClickInViewport = false;
                    if (isViewportHovered) {
                        double localX = pendingClickX - viewportX;
                        double localY = pendingClickY - viewportY;
                        if (localX >= 0 && localY >= 0
                                && localX < viewportW && localY < viewportH) {
                            {
                                // Gizmo-axis hit first (LevelEditorScene.handleLeftClick).
                                // Only when an entity is already selected.
                                boolean extrudeHit = selectedEntity != null
                                        && selectedEntity.transformMode == 2 && ctrlHeld();
                                int hit = (selectedEntity != null)
                                        ? gizmo.getHitAxis(selectedEntity,
                                                mouseRayFromViewport(localX, localY),
                                                player.x, player.y, player.z, extrudeHit)
                                        : -1;
                                if (hit != -1) {
                                    draggingAxis = hit;
                                    lastDragMouseX = localX;
                                    lastDragMouseY = localY;
                                    rawDragX = selectedEntity.transform.x;
                                    rawDragY = selectedEntity.transform.y;
                                    rawDragZ = selectedEntity.transform.z;
                                    // Rotate raw accumulators — snapshot
                                    // pre-drag Euler so each frame can
                                    // recompose from the same starting
                                    // point (instead of from the last,
                                    // possibly-snapped state).
                                    preDragRotX = selectedEntity.transform.rotX;
                                    preDragRotY = selectedEntity.transform.rotY;
                                    preDragRotZ = selectedEntity.transform.rotZ;
                                    rotDragAccum = 0f;
                                    rotDragHasStart = false;   // ray-plane angle anchored on first move
                                    // Scale raw accumulators — start at the
                                    // entity's current scale so per-frame
                                    // deltas pile up continuously.
                                    rawScaleX = selectedEntity.transform.scaleX;
                                    rawScaleY = selectedEntity.transform.scaleY;
                                    rawScaleZ = selectedEntity.transform.scaleZ;
                                    // Free-move (Move mode + centre cube)
                                    // needs the projection-plane distance
                                    // snapshotted at drag start. Use the
                                    // entity's distance from the camera
                                    // along the camera-forward axis.
                                    if (selectedEntity.transformMode == 0 && hit == 3) {
                                        float[] fwd = cameraLookDirection();
                                        float dx = selectedEntity.transform.x - camX;
                                        float dy = selectedEntity.transform.y - camY;
                                        float dz = selectedEntity.transform.z - camZ;
                                        freeMoveDistance = dx * fwd[0] + dy * fwd[1] + dz * fwd[2];
                                        if (freeMoveDistance < 0.5f) freeMoveDistance = 0.5f;
                                    }
                                    snapshotBeforeEdit();   // start undo recording
                                } else {
                                    GameObject picked = pickEntityAt(localX, localY);
                                    if (pendingClickCtrl) {
                                        ctrlClickToggleSelect(picked);
                                    } else {
                                        // Plain LMB: clear any multi-selection,
                                        // select only the clicked object.
                                        selectedObjects.clear();
                                        explicitPivotObject = null;
                                        selectedEntity = picked;
                                    }
                                }
                            }
                        }
                    }
                }

                // CTRL+Right-Click → set the explicit pivot (must be a member
                // of the current multi-selection). Processed here so it reuses
                // the viewport-hover gate + raycast.
                if (pendingPivotClick) {
                    pendingPivotClick = false;
                    if (isViewportHovered) {
                        double px = pendingPivotX - viewportX;
                        double py = pendingPivotY - viewportY;
                        if (px >= 0 && py >= 0 && px < viewportW && py < viewportH) {
                            GameObject p = pickEntityAt(px, py);
                            if (p != null && selectedObjects.contains(p)) {
                                explicitPivotObject = p;
                                selectedEntity = p;        // gizmo snaps to the pivot
                                recordPivotState();
                                Logger.info("Pivot set to " + p.name);
                            }
                        }
                    }
                }
                ImGui.render();
            }

            try (var z = Profiler.zone("Draw frame")) {
                // Skip rendering while the window is minimized — the
                // framebuffer is 0x0, vkAcquireNextImageKHR would return
                // OUT_OF_DATE and the swapchain would get into a bad
                // state we don't currently recover from. When the user
                // restores the window, drawing resumes. Block briefly
                // with glfwWaitEventsTimeout so we don't busy-spin.
                int[] iw = new int[1], ih = new int[1];
                glfwGetFramebufferSize(glfwWindow, iw, ih);
                if (iw[0] == 0 || ih[0] == 0) {
                    glfwWaitEventsTimeout(0.1);
                } else {
                    drawFrame();
                }
            }

            // Multi-viewport pump — AFTER drawFrame() submitted + presented the
            // main frame. updatePlatformWindows() creates/destroys/resizes the
            // detached OS windows; renderPlatformWindowsDefault() drives each
            // window's Renderer_RenderWindow (own acquire → record → submit with
            // its OWN fence) + Renderer_SwapBuffers (own present). Every
            // viewport's sync is strictly isolated from the main frame's, so
            // these independent submits never deadlock. Guarded so it no-ops if
            // viewports are ever disabled.
            if ((ImGui.getIO().getConfigFlags() & ImGuiConfigFlags.ViewportsEnable) != 0) {
                ImGui.updatePlatformWindows();
                ImGui.renderPlatformWindowsDefault();
            }
            Profiler.frameEnd();

            frameCount++;
            long now = System.currentTimeMillis();
            if (now - lastReport >= 2000) {
                fpsAvg = frameCount * 1000f / (now - lastReport);
                frameCount = 0; lastReport = now;
            }
        }
        vkDeviceWaitIdle(ctx.device());
    }

    /** Build all editor-mode panels. Mirrors the panel set in
     *  {@link UIManager#render}: Asset Browser, Gameplay Objects, Inspector,
     *  Debug HUD, plus a temporary Scene Tree (Vulkan-specific) for selection
     *  until we land the Scene Viewport panel in the next slice. */
    // =============================================================
    //                       Scene transitions
    // =============================================================
    // Mirrors GameStateManager + Window's Esc-to-menu wiring.

    /** Switch to the main menu: cursor visible, panels hidden, scene
     *  rendering suppressed, font scaled up to match MainMenuScene. */
    private void enterMainMenu() {
        inMainMenu = true;
        selectedEntity = null;
        ImGui.getIO().setFontGlobalScale(1.0f);   // consistency — menu scales its own window
        glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        glfwSetWindowTitle(glfwWindow, "JEngine - Main Menu");
    }

    /** "Open Level Editor" — opens LevelEditorScene with editor mode on. */
    private void enterEditorMode() {
        // Same fresh-reload behaviour as play mode.
        reloadLevelFromDisk();
        inMainMenu = false;
        isEditorMode = true;
        ImGui.getIO().setFontGlobalScale(1.0f);
        glfwSetInputMode(glfwWindow, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        glfwSetWindowTitle(glfwWindow, "Engine - EDITOR MODE");
    }

    /** Re-read level.json from disk and replace the in-memory entity list.
     *  Cached GPU resources (meshes, textures, descriptor sets) survive —
     *  they're keyed by mesh path / texture path so loading the same
     *  level back just reattaches the same handles. Selection / group
     *  state is cleared so we don't keep dangling references to objects
     *  that no longer exist. */
    private void reloadLevelFromDisk() {
        try {
            entities = LevelLoader.loadLevelData(LEVEL_PATH);
            world.entities = entities;
            // Tear down any stale selection — the GameObject refs are
            // gone now.
            selectedEntity = null;
            highlighted = null;
            selectedObjects.clear();
            explicitPivotObject = null;
            draggingAxis = -1;
            // Re-warm assets BEFORE updating the spatial grid: the grid
            // uses each entity's worldBounds, which depend on vkLocalBounds
            // (populated by prewarm). If we updated grid first, the floor
            // would only be registered in cell (0,0) using the default
            // ±0.5 bounds, and the player would fall through anywhere
            // not within ~1m of the origin.
            prewarmEntityAssets();
            world.updateGrid();
            Logger.info("[VK] Level reloaded from disk: " + LEVEL_PATH + " (" + entities.size() + " entities)");
        } catch (Exception e) {
            Logger.error("[VK] Level reload failed", e);
        }
    }

    /** Ported from MainMenuScene.render (lines 75-117). Single centred
     *  "Main Menu" panel with "JEngine Parkour" header + 3 large blue
     *  buttons. Positioned dynamically based on the current window size. */
    private void buildMainMenuUI() {
        // Phase 1 (AAA overhaul): the borderless window has no OS controls, so
        // the custom title bar (with min/max/close + drag) must be present here
        // too, not just in the editor.
        buildMainMenuBar();

        int[] wpx = new int[1], hpx = new int[1];
        glfwGetWindowSize(glfwWindow, wpx, hpx);
        int w = 630, h = 540;
        ImGui.setNextWindowSize(w, h);
        ImGui.setNextWindowPos(
                wpx[0] / 2.0f - (float) w / 2,
                hpx[0] / 2.0f - (float) h / 2);
        // Themed window (inherits the global dark theme — no hardcoded colors).
        ImGui.begin("Main Menu");
        ImGui.setWindowFontScale(2.2f);   // big-menu look, LOCAL to this window
        ImGui.text("JEngine Parkour");
        ImGui.separator();
        // Editor-first engine: "Start Game" removed.
        int bw = 600, bh = 140;
        if (ImGui.button("Open Level Editor", bw, bh))  enterEditorMode();
        if (ImGui.button("Settings", bw, bh))           settingsUI.open();
        if (ImGui.button("Quit", bw, bh))               glfwSetWindowShouldClose(glfwWindow, true);
        ImGui.end();

        // Render the shared Settings modal at frame top-level (outside the
        // Main Menu window). Applying a change flags a swapchain recreate.
        settingsUI.render(glfwWindow, () -> framebufferResized = true);
    }

    private void buildEditorUI() {
        // Unified custom title bar at the very top (sets titleBarHeight).
        buildMainMenuBar();
        // Play/Stop toolbar strip right under the title bar (sets toolbarHeight).
        renderToolbar();
        // Dockspace host filling the viewport BELOW the title bar + toolbar.
        buildDockspace(titleBarHeight + toolbarHeight);

        // Central, always-on documents: editor Scene + runtime Game viewports.
        renderSceneViewport();
        renderGameWindow();

        // Modular panels — each gated by its toggle and passed its ImBoolean
        // so the window's close [x] flips the same flag the menu does.
        if (showHierarchy.get())       renderSceneTree();        // "Hierarchy"
        if (showInspector.get())       renderInspector();
        if (showAssetBrowser.get())    renderAssetBrowser();
        if (showGameplayObjects.get()) renderGameplayObjects();
        if (showEnvironment.get())     renderShadowMapDebug();   // "Environment & Rendering"
        if (showProfiler.get())        renderProfiler();
        if (showDebugHud.get())        renderDebugHud();

        // Character Editor panel — shows the dedicated offscreen target,
        // accepts a dragged "CHAR_MODEL" asset, and edits the preview's
        // locomotion component live via sliders.
        characterEditor.render(characterTexId, this::loadPreviewCharacter,
                previewCharacter != null
                        ? previewCharacter.getComponent(CharacterLocomotionComponent.class)
                        : null,
                this::setActivePlayer,
                this::orbitPreviewCamera,
                this::zoomPreviewCamera,
                previewCharacter != null ? previewCharacter.transform : null,
                this::panPreviewCamera,
                this::swapPreviewTexture,
                this::applyUniformPreviewScale,
                this::previewPlayIdle,
                this::previewPlayPath,
                this::clearPreviewCharacter);

        // Shared Settings modal — opened from the menu bar's "Settings" menu.
        // Applying a display/resolution change flags a swapchain recreate.
        settingsUI.render(glfwWindow, () -> framebufferResized = true);
    }

    // Phase 1 (AAA overhaul) — custom borderless title-bar state.
    private boolean draggingTitleBar = false;
    private double  grabRelX, grabRelY;             // cursor pos within window at drag start
    private boolean isWindowMaximized = false;
    private final int[] restoreBox = new int[4];    // x, y, w, h before maximize
    private float   titleBarHeight = 0f;            // set each frame by buildMainMenuBar
    private float   toolbarHeight  = 0f;            // set each frame by renderToolbar
    // Phase 3: scene snapshot captured on Play, restored on Stop.
    private String  sceneSnapshotJson = null;

    /** Phase 1 (AAA overhaul) — single UNIFIED borderless title bar: one
     *  horizontal strip holding (left) the File/Edit/Window/Settings dropdown
     *  buttons, (center) the engine name, and (right) the Minimize / Maximize /
     *  Close buttons. NOT a `beginMainMenuBar` (that drew a 2nd bar under this
     *  one) — a decoration-less window pinned to (0,0). Empty space drags the
     *  OS window. */
    private void buildMainMenuBar() {
        ImGuiViewport vp = ImGui.getMainViewport();
        titleBarHeight = ImGui.getFrameHeight() + 10f;

        ImGui.setNextWindowPos(vp.getPosX(), vp.getPosY());
        ImGui.setNextWindowSize(vp.getSizeX(), titleBarHeight);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8f, 5f);
        int flags = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoDocking
                | ImGuiWindowFlags.NoScrollWithMouse | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.NoBringToFrontOnFocus;
        ImGui.begin("##TitleBar", flags);
        ImGui.popStyleVar(3);

        // LEFT — dropdown menus as buttons + popups (one row, no 2nd bar).
        if (ImGui.button("File"))     ImGui.openPopup("##menuFile");
        ImGui.sameLine();
        if (ImGui.button("Edit"))     ImGui.openPopup("##menuEdit");
        ImGui.sameLine();
        if (ImGui.button("Window"))   ImGui.openPopup("##menuWindow");
        ImGui.sameLine();
        if (ImGui.button("Run"))      ImGui.openPopup("##menuRun");
        ImGui.sameLine();
        if (ImGui.button("Settings")) ImGui.openPopup("##menuSettings");

        // CENTER — engine name.
        String name = "JEngine";
        ImGui.sameLine((ImGui.getWindowWidth() - ImGui.calcTextSize(name).x) * 0.5f);
        ImGui.text(name);

        // RIGHT — window controls.
        final float btnW = 30f, spacing = ImGui.getStyle().getItemSpacing().x;
        ImGui.sameLine(ImGui.getWindowWidth() - (btnW * 3 + spacing * 2) - 8f);
        if (ImGui.button("_##min", btnW, 0f)) glfwIconifyWindow(glfwWindow);
        ImGui.sameLine();
        if (ImGui.button((isWindowMaximized ? "[ ]##max" : "[]##max"), btnW, 0f)) toggleMaximizeWindow();
        ImGui.sameLine();
        if (ImGui.button("X##close", btnW, 0f)) glfwSetWindowShouldClose(glfwWindow, true);

        // Drag the OS window by the empty strip (not over a menu/button).
        handleTitleBarDrag();

        // Menu popups (overlay — don't affect the strip's layout).
        if (ImGui.beginPopup("##menuFile")) {
            if (ImGui.menuItem("Save Level")) LevelLoader.saveLevel(entities, LEVEL_PATH);
            ImGui.separator();
            if (ImGui.menuItem("Exit"))       glfwSetWindowShouldClose(glfwWindow, true);
            ImGui.endPopup();
        }
        if (ImGui.beginPopup("##menuEdit")) {
            if (ImGui.menuItem("Undo", "Ctrl+Z", false, undoRedo.canUndo())) { undoRedo.undo(); selectedEntity = null; }
            if (ImGui.menuItem("Redo", "Ctrl+Y", false, undoRedo.canRedo())) { undoRedo.redo(); selectedEntity = null; }
            ImGui.endPopup();
        }
        if (ImGui.beginPopup("##menuWindow")) {
            ImGui.menuItem("Game", "", showGame);
            ImGui.menuItem("Hierarchy", "", showHierarchy);
            ImGui.menuItem("Inspector", "", showInspector);
            ImGui.menuItem("Asset Browser", "", showAssetBrowser);
            ImGui.menuItem("Gameplay Objects", "", showGameplayObjects);
            ImGui.menuItem("Environment & Rendering", "", showEnvironment);
            ImGui.separator();
            ImGui.menuItem("Profiler", "", showProfiler);
            ImGui.menuItem("Debug HUD", "", showDebugHud);
            ImGui.menuItem("Character Editor", "", characterEditor.openFlag());
            ImGui.endPopup();
        }
        if (ImGui.beginPopup("##menuRun")) {
            // Phase 2 — spawn a separate OS process running the engine in
            // --runtime mode (standalone game window, no editor).
            if (ImGui.menuItem("Launch Standalone Game")) launchRuntimeProcess();
            ImGui.endPopup();
        }
        if (ImGui.beginPopup("##menuSettings")) {
            if (ImGui.menuItem("Display & Resolution...")) settingsUI.open();
            ImGui.endPopup();
        }

        ImGui.end();
    }

    /** Phase 1 (AAA overhaul) — full-viewport dockspace host BELOW the title
     *  bar (the title bar isn't a real menu bar, so the work area isn't reduced
     *  automatically). A transparent, decoration-less window owns the DockSpace
     *  so panels tab / snap / dock without covering the title strip. */
    private void buildDockspace(float topOffset) {
        ImGuiViewport vp = ImGui.getMainViewport();
        ImGui.setNextWindowPos(vp.getPosX(), vp.getPosY() + topOffset);
        ImGui.setNextWindowSize(vp.getSizeX(), vp.getSizeY() - topOffset);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoCollapse
                | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus
                | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoBackground;
        ImGui.begin("##DockHost", flags);
        ImGui.popStyleVar(3);
        ImGui.dockSpace(ImGui.getID("##EngineDockSpace"), 0f, 0f,
                ImGuiDockNodeFlags.PassthruCentralNode);
        ImGui.end();
    }

    /** Phase 3 — Play/Stop toolbar: a thin full-width strip under the title bar
     *  with centered Play ▶ and Stop ■ buttons. The active state is accented
     *  (blue Play when stopped, red Stop when playing). */
    private void renderToolbar() {
        ImGuiViewport vp = ImGui.getMainViewport();
        toolbarHeight = ImGui.getFrameHeight() + 12f;
        ImGui.setNextWindowPos(vp.getPosX(), vp.getPosY() + titleBarHeight);
        ImGui.setNextWindowSize(vp.getSizeX(), toolbarHeight);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
        int flags = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoDocking
                | ImGuiWindowFlags.NoScrollWithMouse | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.NoBringToFrontOnFocus;
        ImGui.begin("##Toolbar", flags);
        ImGui.popStyleVar(2);

        boolean playing = GameStateManager.isPlaying();
        final float btnW = 44f, spacing = ImGui.getStyle().getItemSpacing().x;
        ImGui.sameLine((ImGui.getWindowWidth() - (btnW * 2 + spacing)) * 0.5f);

        // Play ▶ — blue when stopped (the action you can take).
        ImGui.pushStyleColor(ImGuiCol.Button,
                !playing ? 0.18f : 0.165f, !playing ? 0.45f : 0.165f, !playing ? 0.90f : 0.165f, 1f);
        if (ImGui.button(">##play", btnW, 0f) && !playing) startPlay();
        ImGui.popStyleColor();
        ImGui.sameLine();
        // Stop ■ — red when playing.
        ImGui.pushStyleColor(ImGuiCol.Button,
                playing ? 0.80f : 0.165f, playing ? 0.22f : 0.165f, playing ? 0.22f : 0.165f, 1f);
        if (ImGui.button("[]##stop", btnW, 0f) && playing) stopPlay();
        ImGui.popStyleColor();

        ImGui.end();
    }

    /** Phase 3 — Play: snapshot the whole scene to JSON so Stop can restore it,
     *  then enter the playing state and kick the avatar's idle animation. */
    private void startPlay() {
        if (GameStateManager.isPlaying()) return;
        sceneSnapshotJson = LevelLoader.toJsonString(entities);
        GameStateManager.setPlaying(true);
        currentLocoState = -1;
        if (activePlayerEntity != null) {
            CharacterLocomotionComponent loco =
                    activePlayerEntity.getComponent(CharacterLocomotionComponent.class);
            Animator a = activePlayerEntity.getComponent(Animator.class);
            if (a != null && loco != null && loco.animIdle != null && !loco.animIdle.isEmpty()) {
                playAnimByPath(a, loco.animIdle);
            }
        }
        Logger.info("[VK] Play: scene snapshot captured (" + entities.size() + " entities)");
    }

    /** Phase 3 — Stop: clear the live world and rebuild it from the snapshot so
     *  play-mode edits don't persist. Re-finds the active player avatar (its
     *  name ends with " (Player)") and re-warms GPU assets. */
    private void stopPlay() {
        if (!GameStateManager.isPlaying()) return;
        GameStateManager.setPlaying(false);
        if (sceneSnapshotJson == null) return;
        java.util.List<GameObject> restored = LevelLoader.fromJsonString(sceneSnapshotJson);
        if (restored == null) return;
        entities = restored;
        world.entities = entities;
        selectedEntity = null;
        activePlayerEntity = null;
        player.locomotion = null;
        for (GameObject g : entities) {
            if (g.name != null && g.name.endsWith("(Player)")) {
                activePlayerEntity = g;
                player.locomotion = g.getComponent(CharacterLocomotionComponent.class);
                break;
            }
        }
        prewarmEntityAssets();
        world.updateGrid();
        Logger.info("[VK] Stop: scene restored from snapshot (" + entities.size() + " entities)");
    }

    /** Move the borderless OS window by dragging the empty title-bar area. Uses
     *  a fixed grab-offset (cursor pos within the window at press) so the math
     *  doesn't feed back on itself as the window moves. */
    private void handleTitleBarDrag() {
        boolean overEmptyBar = ImGui.isWindowHovered() && !ImGui.isAnyItemHovered();
        if (overEmptyBar && ImGui.isMouseClicked(0) && !isWindowMaximized) {
            double[] cx = new double[1], cy = new double[1];
            glfwGetCursorPos(glfwWindow, cx, cy);
            grabRelX = cx[0]; grabRelY = cy[0];
            draggingTitleBar = true;
        }
        if (!ImGui.isMouseDown(0)) draggingTitleBar = false;
        if (draggingTitleBar) {
            int[] wx = new int[1], wy = new int[1];
            glfwGetWindowPos(glfwWindow, wx, wy);
            double[] cx = new double[1], cy = new double[1];
            glfwGetCursorPos(glfwWindow, cx, cy);
            // global cursor = window pos + relative cursor; keep the grab point
            // under the cursor: newWinPos = globalCursor - grabRel.
            glfwSetWindowPos(glfwWindow,
                    (int) (wx[0] + cx[0] - grabRelX),
                    (int) (wy[0] + cy[0] - grabRelY));
        }
    }

    /** Manual maximize/restore (the window is non-resizable, so glfwMaximize is
     *  a no-op — set the size programmatically to the monitor work area). */
    private void toggleMaximizeWindow() {
        if (!isWindowMaximized) {
            int[] wx = new int[1], wy = new int[1], ww = new int[1], wh = new int[1];
            glfwGetWindowPos(glfwWindow, wx, wy);
            glfwGetWindowSize(glfwWindow, ww, wh);
            restoreBox[0] = wx[0]; restoreBox[1] = wy[0];
            restoreBox[2] = ww[0]; restoreBox[3] = wh[0];
            int[] mx = new int[1], my = new int[1], mw = new int[1], mh = new int[1];
            glfwGetMonitorWorkarea(glfwGetPrimaryMonitor(), mx, my, mw, mh);
            glfwSetWindowPos(glfwWindow, mx[0], my[0]);
            glfwSetWindowSize(glfwWindow, mw[0], mh[0]);
            isWindowMaximized = true;
        } else {
            glfwSetWindowPos(glfwWindow, restoreBox[0], restoreBox[1]);
            glfwSetWindowSize(glfwWindow, restoreBox[2], restoreBox[3]);
            isWindowMaximized = false;
        }
    }

    // ----------------------------- Scene Viewport ---------------------------
    // Ported from UIManager.renderViewport (lines 215-230). The scene is
    // rendered offscreen by drawFrame; here we just display the result and
    // record where it was placed so click-to-select can map mouse coords.

    private void renderSceneViewport() {
        ImGui.begin("Scene Viewport");
        isViewportHovered = ImGui.isWindowHovered();

        imgui.ImVec2 vMin = ImGui.getWindowContentRegionMin();
        imgui.ImVec2 vMax = ImGui.getWindowContentRegionMax();
        imgui.ImVec2 wPos = ImGui.getWindowPos();
        viewportX = wPos.x + vMin.x;
        viewportY = wPos.y + vMin.y;
        viewportW = vMax.x - vMin.x;
        viewportH = vMax.y - vMin.y;

        // NO UV flip — unlike the OpenGL build (UIManager.java:228 which
        // uses 0,1,1,0), Vulkan's offscreen image already has V going
        // DOWN (texture origin = top-left). The negative-height viewport
        // in the offscreen scene pass puts world-up at the top of the
        // texture, so a straight 0,0–1,1 sample displays it right-side up.
        if (viewportTexId != 0 && viewportW > 0 && viewportH > 0) {
            ImGui.image(viewportTexId, viewportW, viewportH, 0, 0, 1, 1);
        }
        ImGui.end();
    }

    /** Phase 2 — "Game" viewport panel: shows the runtime camera's view
     *  (gameTarget). Reports its content size for the per-frame resize, and
     *  renders the texture (or a hint when no active player is set). */
    private void renderGameWindow() {
        if (!showGame.get()) return;
        // Consolidation: the Game viewport only exists when there's an active
        // player to view. With none bound the window was a zombie (open,
        // rendering nothing, swallowing dock space) — don't open it at all.
        if (activePlayerEntity == null) return;
        ImGui.begin("Game", showGame);
        imgui.ImVec2 avail = ImGui.getContentRegionAvail();
        int w = (int) avail.x, h = (int) avail.y;
        if (w > 0 && h > 0) { gameWantW = w; gameWantH = h; }
        if (!GameStateManager.isPlaying()) {
            // Only live/updated while playing (Phase 3 item 4).
            ImGui.textDisabled("Press Play to run the game.");
        } else if (gameTexId != 0 && w > 0 && h > 0) {
            ImGui.image(gameTexId, w, h, 0, 0, 1, 1);
        }
        ImGui.end();
    }

    // ---------------------------- Shadow Map Debug --------------------------
    // Ported from UIManager.render (lines 70-92). Sliders mutate the shared
    // Config fields exactly the same way.

    private void renderShadowMapDebug() {
        ImGui.begin("Environment & Rendering", showEnvironment);
        ImGui.text("Pass 1: Light's Perspective Depth Map");
        if (shadowMapTexId != 0) {
            ImGui.image(shadowMapTexId, 256, 256, 0, 1, 1, 0);
        }
        ImGui.separator();

        // Read-outs: fixed map resolution + the texel density the current
        // Shadow Distance buys. Density drops as distance grows — that's the
        // coverage-vs-sharpness trade made visible.
        ImGui.text(String.format("Resolution: %d x %d px",
                Config.SHADOW_MAP_WIDTH, Config.SHADOW_MAP_HEIGHT));
        ImGui.text(String.format("Texel density: %.1f texels/m",
                Config.SHADOW_MAP_WIDTH / Config.SHADOW_DISTANCE));
        ImGui.separator();

        // --- Single coverage control. Cam pull-back, far plane and the
        // texel grid are all derived from this in the shadow pass, so the
        // user never touches raw ortho/near/far again. ---
        float[] dist = { Config.SHADOW_DISTANCE };
        if (ImGui.sliderFloat("Shadow Distance", dist, 20f, 400f, "%.0f m")) {
            Config.SHADOW_DISTANCE = dist[0];
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip(
                "How far around the camera shadows are drawn, in metres.\n"
              + "Larger reaches further but spreads the same texture over\n"
              + "more ground, so shadows get blockier (see Texel density).");

        float[] dBias = { Config.SHADOW_DEPTH_BIAS };
        if (ImGui.sliderFloat("Depth Bias", dBias, 0.0f, 0.005f, "%.4f")) {
            Config.SHADOW_DEPTH_BIAS = dBias[0];
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip(
                "Nudges the shadow test slightly deeper so a surface stops\n"
              + "shadowing itself (the grainy 'shadow acne').\n"
              + "Too high and shadows visibly detach from their object.");

        float[] nBias = { Config.SHADOW_NORMAL_BIAS };
        if (ImGui.sliderFloat("Normal Bias", nBias, 0.0f, 0.005f, "%.4f")) {
            Config.SHADOW_NORMAL_BIAS = nBias[0];
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip(
                "Extra bias that grows on surfaces angled away from the sun,\n"
              + "where acne is worst. Raise this before Depth Bias if you\n"
              + "see graininess on slopes and ramps.");

        ImGui.end();
    }

    // ----------------------------- Asset Browser ----------------------------
    // Ported from UIManager.renderAssetBrowser (lines 232-313).

    private void renderAssetBrowser() {
        ImGui.begin("Asset Browser", showAssetBrowser);

        if (ImGui.button("Save Level")) {
            LevelLoader.saveLevel(entities, LEVEL_PATH);
        }
        ImGui.sameLine();
        if (ImGui.button("Load Level")) {
            java.util.List<GameObject> loaded = LevelLoader.loadLevelData(LEVEL_PATH);
            if (loaded != null && !loaded.isEmpty()) {
                entities = loaded;
                selectedEntity = null;
                prewarmEntityAssets();
                snapCameraToSpawn();
                Logger.info("Level reloaded (" + entities.size() + " entities)");
            }
        }
        // Full-width search across every section.
        ImGui.separator();
        ImGui.text("Search:");
        ImGui.sameLine();
        ImGui.pushItemWidth(-1);
        ImGui.inputText("##assetSearch", assetSearch);
        ImGui.popItemWidth();
        String q = assetSearch.get().trim().toLowerCase();

        // Side-by-side panes: a 3-column stretch table divides the wide bottom
        // dock into equal, resizable sections. Each pane flows its items DOWN
        // (3 + "..." expand). Models & Characters share a pane (both spawn on
        // click + drag CHAR_MODEL); Animations = character .glb list for the
        // editor's anim slots; Textures drag as TEXTURE onto the Inspector.
        java.util.List<AssetEntry> modelChar = new java.util.ArrayList<>(
                listAssets(modelsFolder, false, q, n -> n, n -> () -> spawnModelInFront(n)));
        modelChar.addAll(listAssets(charactersFolder, false, q,
                n -> Config.CHARACTER_PATH_PREFIX + n,
                n -> () -> spawnModelInFront(Config.CHARACTER_PATH_PREFIX + n)));

        int paneFlags = imgui.flag.ImGuiTableFlags.BordersInnerV
                | imgui.flag.ImGuiTableFlags.Resizable
                | imgui.flag.ImGuiTableFlags.SizingStretchSame;
        if (ImGui.beginTable("##assetPanes", 3, paneFlags)) {
            ImGui.tableNextRow();
            ImGui.tableSetColumnIndex(0);
            assetPane("Textures", listAssets(new java.io.File(TEXTURES_DIR), true, q,
                    n -> n, null), "TEXTURE", expTex);
            ImGui.tableSetColumnIndex(1);
            assetPane("Models & Characters", modelChar, "CHAR_MODEL", expModels);
            ImGui.tableSetColumnIndex(2);
            assetPane("Animations", listAssets(charactersFolder, false, q,
                    n -> Config.CHARACTER_PATH_PREFIX + n, null), "CHAR_MODEL", expAnims);
            ImGui.endTable();
        }

        ImGui.separator();
        ImGui.text("Stress Test:");
        ImGui.separator();
        if (ImGui.button("Spawn 100"))  stressSpawnCubes(100);
        ImGui.sameLine();
        if (ImGui.button("Spawn 500"))  stressSpawnCubes(500);
        ImGui.sameLine();
        if (ImGui.button("Spawn 2000")) stressSpawnCubes(2000);
        if (ImGui.button("Spawn 5000")) stressSpawnCubes(5000);
        ImGui.sameLine();
        if (ImGui.button("Clear stress cubes")) {
            int before = entities.size();
            entities.removeIf(o -> o.name != null && o.name.startsWith("StressCube"));
            int removed = before - entities.size();
            Logger.info("Cleared " + removed + " stress cubes");
        }
        ImGui.end();
    }

    /** Collect the matching files in {@code folder} into Asset Browser tiles.
     *  {@code texturesOnly} filters by image extension, else by {@link #isModelFile};
     *  {@code q} is the (lowercased) search filter; {@code payloadFn} builds the
     *  drag payload; {@code clickFn} (nullable) builds the per-tile click action. */
    private java.util.List<AssetEntry> listAssets(java.io.File folder, boolean texturesOnly, String q,
            java.util.function.Function<String, String> payloadFn,
            java.util.function.Function<String, Runnable> clickFn) {
        java.util.List<AssetEntry> out = new java.util.ArrayList<>();
        if (folder == null || !folder.isDirectory()) return out;
        java.io.File[] files = folder.listFiles();
        if (files == null) return out;
        for (java.io.File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName(), ln = n.toLowerCase();
            boolean ok = texturesOnly
                    ? (ln.endsWith(".png") || ln.endsWith(".jpg") || ln.endsWith(".jpeg"))
                    : isModelFile(n);
            if (!ok) continue;
            if (!q.isEmpty() && !ln.contains(q)) continue;
            out.add(new AssetEntry(n, payloadFn.apply(n), clickFn != null ? clickFn.apply(n) : null));
        }
        return out;
    }

    /** Render one Asset Browser pane (one table column): a {@code text +
     *  separator} header, then the items flowing DOWN as full-column-width
     *  buttons — 3 visible + a "..." expand (or "Show less" when expanded).
     *  Each button is a drag source for {@code payloadType} and runs its
     *  optional click action. */
    private void assetPane(String header, java.util.List<AssetEntry> entries,
                           String payloadType, boolean[] expanded) {
        ImGui.text(header);
        ImGui.separator();
        if (entries.isEmpty()) { ImGui.textDisabled("(none)"); return; }

        final float w = ImGui.getContentRegionAvail().x;   // fill the column
        final int total = entries.size();
        final int limit = expanded[0] ? total : Math.min(3, total);

        for (int i = 0; i < limit; i++) {
            AssetEntry e = entries.get(i);
            if (ImGui.button(e.name() + "##" + header + i, w, 0f)
                    && e.onClick() != null) e.onClick().run();
            if (ImGui.beginDragDropSource()) {
                ImGui.setDragDropPayload(payloadType, e.payload());
                ImGui.text(e.name());
                ImGui.endDragDropSource();
            }
        }

        if (!expanded[0] && total > limit) {
            if (ImGui.button("... (" + (total - limit) + " more)##exp" + header, w, 0f)) expanded[0] = true;
        } else if (expanded[0] && total > 3) {
            if (ImGui.button("Show less##exp" + header, w, 0f)) expanded[0] = false;
        }
    }

    // --------------------------- Gameplay Objects ---------------------------
    // Ported from UIManager.renderGameplayObjects (lines 640-651).

    private void renderGameplayObjects() {
        ImGui.begin("Gameplay Objects", showGameplayObjects);
        // Phase 4B Issue 3: "Spawn: Character Collision" removed — the player
        // body/capsule is now defined by the CharacterLocomotionComponent
        // (Character Editor → "Set as Active Player"), making the standalone
        // PLAYER_BODY_KEY object obsolete.

        // Drag-and-drop component palette. Drag a label onto the Inspector
        // of a selected GameObject to attach that component.
        ImGui.separator();
        ImGui.text("Components (drag onto Inspector):");
        componentDragSource("Invisible");
        componentDragSource("Walkthrough");
        componentDragSource("SpawnPoint");
        componentDragSource("WallRunnable");
        componentDragSource("WinZone");
        ImGui.end();
    }

    /** Instantiate via ComponentRegistry and attach (no duplicates).
     *  addComponentNoStart — Vulkan path has no GL context for start(). */
    private void attachComponentByName(GameObject target, String typeName) {
        Component existing = null;
        Class<? extends Component> cls = ComponentRegistry.get(typeName);
        if (cls != null) existing = target.getComponent(cls);
        if (existing != null) { Logger.info(typeName + " already attached"); return; }
        Component c = ComponentRegistry.instantiate(typeName);
        if (c == null) return;
        target.addComponentNoStart(c);
        Logger.info("Attached " + typeName + " to " + target.name);
    }

    /** One draggable component chip. Payload = the ComponentRegistry name. */
    private void componentDragSource(String typeName) {
        ImGui.button("[] " + typeName);
        if (ImGui.beginDragDropSource()) {
            ImGui.setDragDropPayload("COMPONENT", typeName);   // String payload
            ImGui.text("Attach " + typeName);
            ImGui.endDragDropSource();
        }
    }

    // ------------------------------- Inspector ------------------------------
    // Trimmed port of UIManager.renderInspector. No undo wiring in this slice
    // (that's Phase 17). Edits modify Transform/MeshRenderer fields directly.

    private void renderInspector() {
        ImGui.begin("Inspector", showInspector);
        if (selectedEntity == null) {
            ImGui.text("Select an object in the Scene Tree to inspect it.");
            ImGui.end();
            return;
        }

        // Sync the inputText buffers when the selection changes.
        if (lastInspectedObject != selectedEntity) {
            lastInspectedObject = selectedEntity;
            wasEditingPosition = false;
            wasEditingRotation = false;
            wasEditingScale    = false;
            nameInput.set(selectedEntity.name == null ? "" : selectedEntity.name);
            MeshRenderer selMr = selectedEntity.getComponent(MeshRenderer.class);
            if (selMr != null) {
                texInput.set(selMr.texturePath == null ? "" : selMr.texturePath);
                modelInput.set((selMr.modelPath == null || selMr.modelPath.isEmpty())
                        ? "cube" : selMr.modelPath);
            } else {
                texInput.set(""); modelInput.set("");
            }
        }

        if (ImGui.inputText("Name", nameInput)) {
            selectedEntity.name = nameInput.get();
        }

        // Phase 5C — Active Player avatar lockdown: Position only. Scale /
        // Rotation / components / mesh-material are hidden (size + orient it in
        // the Character Editor's Base Scale / Base Y-Rotation instead), and the
        // transform mode is forced to Move so the gizmo can only translate it.
        if (selectedEntity == activePlayerEntity) {
            selectedEntity.transformMode = 0;
            ImGui.separator();
            ImGui.textDisabled("Active Player avatar");
            ImGui.text("Edit Scale / Rotation in the Character Editor.");
            ImGui.separator();
            float[] pos = { selectedEntity.transform.x, selectedEntity.transform.y, selectedEntity.transform.z };
            if (UIUtils.dragFloat3("Position", pos, 0.1f)) {
                if (!wasEditingPosition) { snapshotBeforeEdit(); wasEditingPosition = true; }
                selectedEntity.transform.x = pos[0];
                selectedEntity.transform.y = pos[1];
                selectedEntity.transform.z = pos[2];
            }
            commitEditIfDeactivated(true, false, false);
            ImGui.end();
            return;
        }

        // --- Component drop target ---
        // Dropping a palette chip here attaches that component to the
        // selected GameObject via ComponentRegistry.
        ImGui.button("+ Drop Component Here");
        if (ImGui.beginDragDropTarget()) {
            Object payload = ImGui.acceptDragDropPayload("COMPONENT");
            if (payload instanceof String) attachComponentByName(selectedEntity, (String) payload);
            ImGui.endDragDropTarget();
        }
        // List attached gameplay components (with remove buttons).
        for (Component c : new java.util.ArrayList<>(selectedEntity.components)) {
            if (c instanceof InvisibleComponent || c instanceof WalkthroughComponent
                    || c instanceof SpawnPointComponent || c instanceof WallRunnableComponent
                    || c instanceof WinZoneComponent) {
                ImGui.bulletText(c.getClass().getSimpleName());
                ImGui.sameLine();
                if (ImGui.smallButton("x##" + c.getClass().getSimpleName())) {
                    selectedEntity.components.remove(c);
                }
            }
        }
        ImGui.separator();

        ImGui.text("Transform Mode:");
        if (ImGui.radioButton("Move",  selectedEntity.transformMode == 0)) selectedEntity.transformMode = 0;
        ImGui.sameLine();
        if (ImGui.radioButton("Rotate", selectedEntity.transformMode == 1)) selectedEntity.transformMode = 1;
        ImGui.sameLine();
        if (ImGui.radioButton("Scale",  selectedEntity.transformMode == 2)) selectedEntity.transformMode = 2;
        ImGui.separator();

        // Position — drag + undo recording (matches UIManager.java:391-423).
        float[] pos = { selectedEntity.transform.x, selectedEntity.transform.y, selectedEntity.transform.z };
        if (UIUtils.dragFloat3("Position", pos, 0.1f)) {
            if (!wasEditingPosition) { snapshotBeforeEdit(); wasEditingPosition = true; }
            selectedEntity.transform.x = pos[0];
            selectedEntity.transform.y = pos[1];
            selectedEntity.transform.z = pos[2];
        }
        commitEditIfDeactivated(true, false, false);

        boolean isSun = "Sun".equals(selectedEntity.name);
        if (isSun) {
            // Sun Intensity slider — matches UIManager.java:426-430.
            float[] intensity = { sunIntensity };
            if (ImGui.dragFloat("Sun Intensity", intensity, 0.05f, 0.0f, 5.0f)) {
                sunIntensity = intensity[0];
            }
        } else {
            float[] rot = { selectedEntity.transform.rotX, selectedEntity.transform.rotY, selectedEntity.transform.rotZ };
            if (UIUtils.dragFloat3("Rotation", rot, 1.0f)) {
                if (!wasEditingRotation) { snapshotBeforeEdit(); wasEditingRotation = true; }
                selectedEntity.transform.rotX = rot[0];
                selectedEntity.transform.rotY = rot[1];
                selectedEntity.transform.rotZ = rot[2];
            }
            commitEditIfDeactivated(false, true, false);

            float[] scl = { selectedEntity.transform.scaleX, selectedEntity.transform.scaleY, selectedEntity.transform.scaleZ };
            if (UIUtils.dragFloat3("Scale", scl, 0.05f)) {
                if (!wasEditingScale) { snapshotBeforeEdit(); wasEditingScale = true; }
                selectedEntity.transform.scaleX = scl[0];
                selectedEntity.transform.scaleY = scl[1];
                selectedEntity.transform.scaleZ = scl[2];
            }
            commitEditIfDeactivated(false, false, true);
        }

        MeshRenderer mr = selectedEntity.getComponent(MeshRenderer.class);

        // Player Capsule editor — shown when the Character Collision
        // object is selected. Its scale IS the capsule definition
        // (scaleX/Z = diameter, scaleY = height), so these fields are a
        // friendlier view of the same values the Scale gizmo edits.
        // Physics reads them on play start in applySpawnPointIfPresent.
        if (mr != null && World.PLAYER_BODY_KEY.equals(mr.modelPath)) {
            ImGui.separator();
            ImGui.text("Player Capsule (collider)");
            float[] radius = { Math.max(0.1f, selectedEntity.transform.scaleX * 0.5f) };
            if (ImGui.dragFloat("Radius", radius, 0.01f, 0.1f, 2.0f)) {
                selectedEntity.transform.scaleX = radius[0] * 2f;
                selectedEntity.transform.scaleZ = radius[0] * 2f;
            }
            float[] height = { Math.max(0.5f, selectedEntity.transform.scaleY) };
            if (ImGui.dragFloat("Height", height, 0.02f, 0.5f, 5.0f)) {
                selectedEntity.transform.scaleY = height[0];
            }
            ImGui.text(String.format("Eye height: %.2f  (90%% of height)", height[0] * 0.9f));
        }

        if (mr != null && !isSun) {
            ImGui.separator();
            ImGui.text("Component: Mesh Renderer");
            if (ImGui.inputText("Texture", texInput)) mr.texturePath = texInput.get();
            // Phase 5E — drop a texture from the Asset Browser onto the field
            // to swap it live. The descriptor-set cache is keyed by texture
            // path, so simply changing texturePath + warming the new set makes
            // the next frame draw with the new texture.
            if (ImGui.beginDragDropTarget()) {
                Object payload = ImGui.acceptDragDropPayload("TEXTURE");
                if (payload instanceof String) {
                    String tex = (String) payload;
                    mr.texturePath = tex;
                    texInput.set(tex);
                    descriptorSetFor(mr);
                    // If this is the active-player avatar, mirror the swap onto
                    // the live preview character so the Character Editor agrees.
                    if (selectedEntity == activePlayerEntity && previewCharacter != null) {
                        MeshRenderer pmr = previewCharacter.getComponent(MeshRenderer.class);
                        if (pmr != null) { pmr.texturePath = tex; descriptorSetFor(pmr); }
                    }
                }
                ImGui.endDragDropTarget();
            }
            if (ImGui.inputText("Model",   modelInput)) {
                String input = modelInput.get();
                mr.modelPath = ("cube".equals(input) || input.isEmpty()) ? "" : input;
            }

            // Animator panel — port of UIManager.java:533-580. Only shown when
            // the entity has an Animator component. Add-Animator button below
            // makes one for any skinned mesh on demand.
            renderAnimatorSection(selectedEntity, mr);

            // Material section — ported from UIManager.java:582-625. Default
            // open so users see tint/tiling at a glance.
            if (ImGui.collapsingHeader("Material", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
                float[] color = { mr.material.r, mr.material.g, mr.material.b };
                if (ImGui.colorEdit3("Color Tint", color)) {
                    mr.material.setColor(color[0], color[1], color[2]);
                }
                boolean tilingOn = Boolean.TRUE.equals(mr.material.useWorldSpaceTiling);
                if (ImGui.checkbox("World-space tiling", tilingOn)) {
                    mr.material.useWorldSpaceTiling = !tilingOn;
                }
                if (Boolean.TRUE.equals(mr.material.useWorldSpaceTiling)) {
                    // dragFloat instead of sliderFloat so the user can both
                    // drag (incremental) AND Ctrl+Click to type an exact
                    // value — same UX as the Position/Rotation/Scale fields.
                    // Speed 0.01, soft min 0.001, soft max unbounded.
                    float[] scale = { mr.material.textureScale };
                    if (UIUtils.dragFloat("Texture scale", scale, 0.01f, 0.001f, 100.0f)) {
                        mr.material.textureScale = Math.max(0.001f, scale[0]);
                    }
                }
                // Face Colors — port of UIManager.java:603-624. NB: the
                // Vulkan renderer doesn't yet sample per-face colours
                // (single-tint shader path); editing here saves to JSON
                // and works in the OpenGL build until the Vulkan shader
                // gains per-face sampling in the next slice.
                if (ImGui.collapsingHeader("Face colors")) {
                    boolean perFaceOn = mr.material.faceColors != null;
                    if (ImGui.checkbox("Per-face", perFaceOn)) {
                        if (perFaceOn) mr.material.disablePerFaceColors();
                        else           mr.material.enablePerFaceColors();
                    }
                    if (mr.material.faceColors != null) {
                        renderFaceColorPicker(mr.material, 0, "+X (Right)");
                        renderFaceColorPicker(mr.material, 1, "-X (Left)");
                        renderFaceColorPicker(mr.material, 2, "+Y (Top)");
                        renderFaceColorPicker(mr.material, 3, "-Y (Bottom)");
                        renderFaceColorPicker(mr.material, 4, "+Z (Front)");
                        renderFaceColorPicker(mr.material, 5, "-Z (Back)");
                        if (ImGui.button("Match tint (all faces)")) {
                            for (int f = 0; f < 6; f++) {
                                mr.material.faceColors[f][0] = mr.material.r;
                                mr.material.faceColors[f][1] = mr.material.g;
                                mr.material.faceColors[f][2] = mr.material.b;
                            }
                        }
                    }
                }
            }
        }
        ImGui.end();
    }

    /** Snapshot the selected entity's transform before an Inspector drag
     *  starts — recorded so {@link TransformCommand} can build the undo
     *  step on item-deactivated-after-edit. Matches the pattern in
     *  UIManager.renderInspector (lines 394-405, 435-446, 469-480). */
    private void snapshotBeforeEdit() {
        if (selectedEntity == null) return;
        beforePosX = selectedEntity.transform.x;
        beforePosY = selectedEntity.transform.y;
        beforePosZ = selectedEntity.transform.z;
        beforeRotX = selectedEntity.transform.rotX;
        beforeRotY = selectedEntity.transform.rotY;
        beforeRotZ = selectedEntity.transform.rotZ;
        beforeScaleX = selectedEntity.transform.scaleX;
        beforeScaleY = selectedEntity.transform.scaleY;
        beforeScaleZ = selectedEntity.transform.scaleZ;
    }

    /** Commit the in-progress Inspector edit as a {@link TransformCommand}
     *  once the dragFloat3 widget is released. Matches the
     *  {@code isItemDeactivatedAfterEdit} pattern at UIManager.java:411-423
     *  / 452-464 / 486-498. */
    private void commitEditIfDeactivated(boolean isPos, boolean isRot, boolean isScale) {
        if (!ImGui.isItemDeactivatedAfterEdit()) return;
        boolean editing = (isPos && wasEditingPosition)
                || (isRot && wasEditingRotation)
                || (isScale && wasEditingScale);
        if (!editing || selectedEntity == null) return;
        TransformCommand cmd = new TransformCommand(selectedEntity,
                beforePosX, beforePosY, beforePosZ,
                beforeRotX, beforeRotY, beforeRotZ,
                beforeScaleX, beforeScaleY, beforeScaleZ);
        if (cmd.hasChanged()) {
            undoRedo.execute(cmd);
            Logger.info((isPos ? "Position" : isRot ? "Rotation" : "Scale")
                    + " change recorded via Inspector");
        }
        if (isPos)   wasEditingPosition = false;
        if (isRot)   wasEditingRotation = false;
        if (isScale) wasEditingScale    = false;
    }

    /** Animator inspector section — port of UIManager.java:533-580.
     *  Skinned characters that lack an Animator get an "Add Animator"
     *  button so the user can attach + configure one without editing JSON. */
    private void renderAnimatorSection(GameObject entity, MeshRenderer mr) {
        // Bone count / "Add Animator" affordance for skinned meshes.
        VkMesh vmesh = (mr != null) ? meshForOrNull(mr) : null;
        if (vmesh != null && vmesh.isSkinned()) {
            ImGui.separator();
            ImGui.text("Skeleton: " + vmesh.skeleton().boneCount + " bones");
            ImGui.checkbox("Show Bones", showBones);
        }

        Animator anim = entity.getComponent(Animator.class);
        if (anim == null) {
            if (vmesh != null && vmesh.isSkinned()) {
                if (ImGui.button("Add Animator")) {
                    Animator a = new Animator();
                    a.start();
                    a.gameObject = entity;
                    entity.addComponentNoStart(a);
                }
            }
            return;
        }

        ImGui.separator();
        ImGui.text("Component: Animator");
        // Keep the inputText buffer in sync with whatever's currently set —
        // re-snapshot on selection change (same condition the texture +
        // model fields use).
        if (lastInspectedObject == entity) {
            String currentPath = anim.animationFilePath == null ? "" : anim.animationFilePath;
            if (!currentPath.equals(animInput.get())) {
                // Only override if the buffer is empty (don't fight the user typing)
                if (animInput.get().isEmpty()) animInput.set(currentPath);
            }
        }
        ImGui.inputText("Animation File", animInput);
        if (ImGui.button("Load")) {
            String fn = animInput.get();
            if (!fn.isEmpty() && vmesh != null && vmesh.skeleton() != null) {
                Animation cached = animationCache.get(fn);
                if (cached == null) {
                    cached = AnimationLoader.loadAnimation(fn, vmesh.skeleton());
                    if (cached != null) animationCache.put(fn, cached);
                }
                if (cached != null) {
                    anim.animationFilePath = fn;
                    anim.play(cached);
                    AnimationDefaults.apply(fn, anim);
                }
            }
        }
        ImGui.sameLine();
        if (ImGui.button(anim.playing ? "Pause" : "Play")) {
            if (anim.currentAnimation != null) anim.playing = !anim.playing;
        }
        if (anim.currentAnimation != null) {
            ImGui.text(String.format("%s  -  %.2f / %.2f ticks",
                    anim.currentAnimation.name,
                    anim.currentTime,
                    anim.currentAnimation.duration));
        } else {
            ImGui.text("(no animation loaded)");
        }

        ImGui.text("Animation Trim");
        float[] trimOff = { anim.trimOffsetX, anim.trimOffsetY, anim.trimOffsetZ };
        if (ImGui.dragFloat3("Offset XYZ", trimOff, 0.01f)) {
            anim.trimOffsetX = trimOff[0];
            anim.trimOffsetY = trimOff[1];
            anim.trimOffsetZ = trimOff[2];
        }
        float[] trimTilt = { anim.trimTiltX, anim.trimTiltY, anim.trimTiltZ };
        if (ImGui.dragFloat3("Tilt XYZ (deg)", trimTilt, 0.1f)) {
            anim.trimTiltX = trimTilt[0];
            anim.trimTiltY = trimTilt[1];
            anim.trimTiltZ = trimTilt[2];
        }
        if (anim.animationFilePath != null && !anim.animationFilePath.isEmpty()
                && ImGui.button("Save Trim as Default for this Animation")) {
            AnimationDefaults.saveDefault(anim.animationFilePath, anim);
        }
    }

    /** Draw the per-frame 2D overlays into the scene render pass:
     *  the stamina bar in play mode + the box-select rectangle when a
     *  drag is in progress. Mirrors LevelEditorScene.drawStaminaBar +
     *  drawBoxSelectOverlay. The 2D pass runs LAST inside the scene
     *  render pass so it sits on top of everything else. */
    private void renderTwoDOverlay(VkCommandBuffer cb, long renderPass, int sceneW, int sceneH) {
        // Stamina bar — play mode only. Shown when stamina < max OR while
        // actively climbing (matches LevelEditorScene.drawStaminaBar:721).
        if (!isEditorMode
                && (player.climbStamina < Config.CLIMB_MAX_STAMINA
                    || player.currentState == Player.State.CLIMBING)) {
            float ratio = Math.max(0f, Math.min(1f, player.climbStamina / Config.CLIMB_MAX_STAMINA));
            float barW = 0.3f, barH = 0.025f;
            float x0 = -barW / 2f;
            float y0 = 0.9f;
            float x1 = x0 + barW;
            float y1 = y0 + barH;

            renderer2D.fillRect(cb, renderPass, sceneW, sceneH,
                    x0, y0, x1, y1, 0.08f, 0.08f, 0.08f, 0.75f);
            float fr, fg, fb;
            if (ratio > 0.5f)       { fr = 0.30f; fg = 0.85f; fb = 0.40f; }
            else if (ratio > 0.25f) { fr = 0.95f; fg = 0.75f; fb = 0.20f; }
            else                    { fr = 0.95f; fg = 0.25f; fb = 0.20f; }
            renderer2D.fillRect(cb, renderPass, sceneW, sceneH,
                    x0, y0, x0 + barW * ratio, y1, fr, fg, fb, 0.9f);
            renderer2D.outlineRect(cb, renderPass, sceneW, sceneH,
                    x0, y0, x1, y1, 1f, 1f, 1f, 0.85f);
        }

        // Crosshair — small white square at the screen centre. Shown in
        // play mode (cursor is hidden, player needs an aim reticle).
        // Matches the OpenGL drawCrosshair point but as a tiny filled
        // rect so it stays crisp at any window size.
        if (!isEditorMode) {
            // 6 px square — convert to NDC half-extents.
            float halfPxX = 3f / sceneW;
            float halfPxY = 3f / sceneH;
            renderer2D.fillRect(cb, renderPass, sceneW, sceneH,
                    -halfPxX, -halfPxY, halfPxX, halfPxY,
                    1f, 1f, 1f, 0.95f);
        }

    }

    /** True while either CTRL key is physically held. */
    private boolean ctrlHeld() {
        return glfwGetKey(glfwWindow, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS
            || glfwGetKey(glfwWindow, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS;
    }

    /** "Paint" selection: while CTRL+LMB is dragged across the scene, each
     *  hovered, not-yet-selected entity is added to the selection. The
     *  contains() guard makes it fire exactly once per object. */
    private void paintSelect(GameObject picked) {
        if (picked == null || "Sun".equals(picked.name)) return;
        if (selectedObjects.contains(picked)) return;   // already in — don't re-fire
        // Fold an existing single selection into the list so painting EXTENDS it.
        if (selectedObjects.isEmpty() && selectedEntity != null && selectedEntity != picked) {
            selectedObjects.add(selectedEntity);
        }
        selectedObjects.add(picked);
        if (explicitPivotObject == null) explicitPivotObject = picked;
        selectedEntity = (selectedObjects.size() >= 2) ? explicitPivotObject : selectedObjects.get(0);
        recordPivotState();
    }

    /** CTRL+Left-Click: extend / toggle the multi-selection. The clicked
     *  object is added (and becomes the explicit pivot) or removed. An empty
     *  result clears; a single survivor collapses back to a plain selection;
     *  2+ keep the explicit pivot driving the gizmo. */
    private void ctrlClickToggleSelect(GameObject picked) {
        if (picked == null) return;
        // Fold an existing single selection into the list so CTRL+Click
        // EXTENDS it rather than starting a fresh group.
        if (selectedObjects.isEmpty() && selectedEntity != null && selectedEntity != picked) {
            selectedObjects.add(selectedEntity);
        }
        if (selectedObjects.remove(picked)) {
            // Toggled OFF — re-home the pivot if it was the removed object.
            if (explicitPivotObject == picked) {
                explicitPivotObject = selectedObjects.isEmpty() ? null
                        : selectedObjects.get(selectedObjects.size() - 1);
            }
        } else {
            selectedObjects.add(picked);
            explicitPivotObject = picked;   // last CTRL+clicked = default pivot
        }
        // Normalize: 0 → nothing, 1 → plain single, 2+ → multi with pivot.
        if (selectedObjects.isEmpty()) {
            selectedEntity = null;
            explicitPivotObject = null;
        } else if (selectedObjects.size() == 1) {
            selectedEntity = selectedObjects.get(0);
            selectedObjects.clear();
            explicitPivotObject = null;
        } else {
            if (explicitPivotObject == null) {
                explicitPivotObject = selectedObjects.get(selectedObjects.size() - 1);
            }
            selectedEntity = explicitPivotObject;   // gizmo snaps to the pivot
        }
        recordPivotState();
    }

    /** Tear down a multi-selection (plain click elsewhere, level reload). */
    private void exitGroupSelect() {
        selectedObjects.clear();
        explicitPivotObject = null;
    }

    /** Snapshot the pivot's transform so the next frame can diff against it. */
    private void recordPivotState() {
        if (explicitPivotObject == null) return;
        Transform t = explicitPivotObject.transform;
        prevPivotX = t.x; prevPivotY = t.y; prevPivotZ = t.z;
        prevPivotRotX = t.rotX; prevPivotRotY = t.rotY; prevPivotRotZ = t.rotZ;
        prevPivotScaleX = t.scaleX; prevPivotScaleY = t.scaleY; prevPivotScaleZ = t.scaleZ;
    }

    /** Propagate the explicit pivot's per-frame transform delta to the rest of
     *  the multi-selection, relative to the pivot:
     *    - translate → every other object moves by the same delta (rigid)
     *    - rotate    → orbit each object's offset about the pivot AND add the
     *                  delta to its own local rotation
     *    - scale     → push/pull each object's offset from the pivot by the
     *                  scale factor AND multiply its own local scale
     *  The pivot is driven directly by the gizmo, so it's skipped here.
     *  Re-snapshots the pivot afterwards so each diff covers just one frame. */
    private void applyPivotDeltaAndReset() {
        if (explicitPivotObject == null || selectedObjects.size() < 2) return;
        Transform pv = explicitPivotObject.transform;

        float dx = pv.x - prevPivotX, dy = pv.y - prevPivotY, dz = pv.z - prevPivotZ;
        float drx = pv.rotX - prevPivotRotX, dry = pv.rotY - prevPivotRotY, drz = pv.rotZ - prevPivotRotZ;
        float sfx = pv.scaleX / Math.max(Math.abs(prevPivotScaleX), 1e-6f);
        float sfy = pv.scaleY / Math.max(Math.abs(prevPivotScaleY), 1e-6f);
        float sfz = pv.scaleZ / Math.max(Math.abs(prevPivotScaleZ), 1e-6f);

        // Pivot centre = its position BEFORE this frame's edit. Rotate / scale
        // gizmo drags leave the pivot's position unchanged, so this is also its
        // current position — the offsets orbit/scale about the real pivot.
        float cx = prevPivotX, cy = prevPivotY, cz = prevPivotZ;
        boolean translated = dx != 0 || dy != 0 || dz != 0;
        boolean rotated    = drx != 0 || dry != 0 || drz != 0;
        boolean scaled     = sfx != 1 || sfy != 1 || sfz != 1;

        if (translated || rotated || scaled) {
            Matrix4f rotDelta = null;
            if (rotated) {
                rotDelta = new Matrix4f()
                        .rotateX((float) Math.toRadians(drx))
                        .rotateY((float) Math.toRadians(dry))
                        .rotateZ((float) Math.toRadians(drz));
            }
            for (GameObject o : selectedObjects) {
                if (o == explicitPivotObject) continue;   // gizmo already moved the pivot
                if (translated) {
                    o.transform.x += dx; o.transform.y += dy; o.transform.z += dz;
                }
                if (scaled) {
                    float ox = o.transform.x - cx, oy = o.transform.y - cy, oz = o.transform.z - cz;
                    o.transform.x = cx + ox * sfx;
                    o.transform.y = cy + oy * sfy;
                    o.transform.z = cz + oz * sfz;
                    o.transform.scaleX *= sfx;
                    o.transform.scaleY *= sfy;
                    o.transform.scaleZ *= sfz;
                }
                if (rotated) {
                    org.joml.Vector3f offset = new org.joml.Vector3f(
                            o.transform.x - cx, o.transform.y - cy, o.transform.z - cz);
                    rotDelta.transformPosition(offset);
                    o.transform.x = cx + offset.x;
                    o.transform.y = cy + offset.y;
                    o.transform.z = cz + offset.z;
                    Matrix4f objRot = new Matrix4f()
                            .rotateX((float) Math.toRadians(o.transform.rotX))
                            .rotateY((float) Math.toRadians(o.transform.rotY))
                            .rotateZ((float) Math.toRadians(o.transform.rotZ));
                    Matrix4f combined = new Matrix4f(rotDelta).mul(objRot);
                    org.joml.Vector3f euler = new org.joml.Vector3f();
                    combined.getEulerAnglesXYZ(euler);
                    o.transform.rotX = (float) Math.toDegrees(euler.x);
                    o.transform.rotY = (float) Math.toDegrees(euler.y);
                    o.transform.rotZ = (float) Math.toDegrees(euler.z);
                }
            }
        }
        recordPivotState();
    }

    /** Cache-only mesh fetch — returns null instead of building (Inspector
     *  shouldn't block on a load if the asset isn't already warmed). */
    private VkMesh meshForOrNull(MeshRenderer mr) {
        try {
            return meshFor(mr);
        } catch (Exception e) {
            return null;
        }
    }

    /** Per-face colour picker — ported verbatim from
     *  UIManager.renderFaceColorPicker (lines 106-117). */
    private static void renderFaceColorPicker(Material mat, int faceIndex, String label) {
        float[] color = { mat.faceColors[faceIndex][0], mat.faceColors[faceIndex][1], mat.faceColors[faceIndex][2] };
        if (ImGui.colorEdit3(label, color)) {
            mat.faceColors[faceIndex][0] = color[0];
            mat.faceColors[faceIndex][1] = color[1];
            mat.faceColors[faceIndex][2] = color[2];
        }
    }

    // ------------------------------ Scene Tree ------------------------------
    // Vulkan-specific addition: lets the user pick the inspected entity until
    // we land click-in-viewport selection in the next slice.

    private void renderSceneTree() {
        ImGui.begin("Hierarchy", showHierarchy);
        ImGui.text("Entities: " + entities.size());
        ImGui.separator();
        if (ImGui.beginListBox("##entityList", -1, -1)) {
            for (int i = 0; i < entities.size(); i++) {
                GameObject g = entities.get(i);
                String label = (g.name == null || g.name.isEmpty())
                        ? "[" + i + "] (unnamed)"
                        : "[" + i + "] " + g.name;
                if (ImGui.selectable(label, g == selectedEntity)) {
                    selectedEntity = g;
                }
            }
            ImGui.endListBox();
        }
        ImGui.end();
    }

    // ------------------------------- Debug HUD ------------------------------
    // Ported from UIManager.renderDebugHud (lines 178-213). Adapted: our
    // free-fly camera state lives in camX/Y/Z and camYaw/camPitch instead
    // of scene.player.x/y/z.

    private void renderDebugHud() {
        float yaw = ((camYaw % 360f) + 360f) % 360f;
        String compass;
        if      (yaw < 22.5f || yaw >= 337.5f) compass = "N";
        else if (yaw < 67.5f)                  compass = "NE";
        else if (yaw < 112.5f)                 compass = "E";
        else if (yaw < 157.5f)                 compass = "SE";
        else if (yaw < 202.5f)                 compass = "S";
        else if (yaw < 247.5f)                 compass = "SW";
        else if (yaw < 292.5f)                 compass = "W";
        else                                   compass = "NW";

        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(camPitch);
        float fx = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float fy = (float) -Math.sin(pitchRad);
        float fz = (float) -(Math.cos(yawRad) * Math.cos(pitchRad));
        float ax = Math.abs(fx), ay = Math.abs(fy), az = Math.abs(fz);
        String facing;
        if (ax >= ay && ax >= az)      facing = fx >= 0 ? "+X (East)"  : "-X (West)";
        else if (ay >= ax && ay >= az) facing = fy >= 0 ? "+Y (Down)"  : "-Y (Up)";
        else                            facing = fz >= 0 ? "+Z (South)" : "-Z (North)";

        ImGui.begin("Debug HUD", showDebugHud);
        ImGui.text(String.format("FPS: %.0f", fpsAvg));
        ImGui.text(String.format("XYZ: %.2f / %.2f / %.2f", camX, camY, camZ));
        ImGui.text(String.format("Yaw: %.1f  (%s)", yaw, compass));
        ImGui.text(String.format("Pitch: %.1f", camPitch));
        ImGui.text("Facing: " + facing);
        ImGui.text(String.format("Sun: (%.2f, %.2f, %.2f)", sunDirX, sunDirY, sunDirZ));
        ImGui.checkbox("Show this HUD", showDebugHud);
        ImGui.checkbox("Show Hierarchy", showHierarchy);
        ImGui.checkbox("Show Profiler", showProfiler);
        ImGui.checkbox("Show Environment & Rendering", showEnvironment);
        ImGui.end();
    }

    // ------------------------------- Profiler ------------------------------
    // Ported verbatim from UIManager.renderProfiler (lines 138-174).

    private void renderProfiler() {
        ImGui.begin("Profiler", showProfiler);
        long frameNs = Profiler.getLastFrameNs();
        double frameMs = frameNs / 1_000_000.0;
        double fps = frameNs > 0 ? 1_000_000_000.0 / frameNs : 0;
        ImGui.text(String.format("Frame: %.2f ms  (%.0f fps)", frameMs, fps));
        if (Profiler.isWarmingUp()) {
            ImGui.text("Warming up... (" + Profiler.warmupRemaining() + " frames)");
        }
        if (ImGui.button("Reset max")) Profiler.resetMax();
        ImGui.sameLine();
        if (ImGui.button("Copy table")) {
            ImGui.setClipboardText(buildProfilerClipboardText(frameMs, fps));
        }
        ImGui.separator();

        if (ImGui.beginTable("zones", 4)) {
            ImGui.tableSetupColumn("Zone");
            ImGui.tableSetupColumn("avg ms");
            ImGui.tableSetupColumn("max ms");
            ImGui.tableSetupColumn("calls");
            ImGui.tableHeadersRow();
            for (Map.Entry<String, Profiler.ZoneStats> e : Profiler.getZones().entrySet()) {
                Profiler.ZoneStats s = e.getValue();
                ImGui.tableNextRow();
                ImGui.tableNextColumn(); ImGui.text(e.getKey());
                ImGui.tableNextColumn(); ImGui.text(String.format("%.3f", Profiler.avgMs(s)));
                ImGui.tableNextColumn(); ImGui.text(String.format("%.3f", Profiler.maxMs(s)));
                ImGui.tableNextColumn(); ImGui.text(String.valueOf(s.lastFrameCalls));
            }
            ImGui.endTable();
        }
        ImGui.end();
    }

    /** Markdown table for the "Copy table" button — ported verbatim from
     *  UIManager.buildProfilerClipboardText (lines 122-136). */
    private static String buildProfilerClipboardText(double frameMs, double fps) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Frame: %.2f ms  (%.0f fps)%n", frameMs, fps));
        sb.append("| Zone | avg ms | max ms | calls |\n");
        sb.append("|------|--------|--------|-------|\n");
        for (Map.Entry<String, Profiler.ZoneStats> e : Profiler.getZones().entrySet()) {
            Profiler.ZoneStats s = e.getValue();
            sb.append(String.format("| %s | %.3f | %.3f | %d |%n",
                    e.getKey(), Profiler.avgMs(s), Profiler.maxMs(s), s.lastFrameCalls));
        }
        return sb.toString();
    }

    // ----------------------------- Spawn helpers ----------------------------
    // Ported from UIManager (spawnModelInFront, spawnSpawnPointInFront).
    // Adapted for Vulkan:
    //   - Use addComponentNoStart so MeshRenderer.start() doesn't call
    //     into the OpenGL AssetPool (we have no GL context).
    //   - Eagerly populate the Vk caches via meshFor / descriptorSetFor so
    //     the next render-loop frame doesn't stutter on a synchronous load.
    //   - Position is computed from the camera's pose since there's no
    //     Player yet (Phase 15+).

    private static boolean isModelFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".glb") || n.endsWith(".gltf") || n.endsWith(".obj") || n.endsWith(".fbx");
    }

    private float[] cameraForwardOffset() {
        float yawRad   = (float) Math.toRadians(camYaw);
        float pitchRad = (float) Math.toRadians(camPitch);
        float dx = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float dy = (float) (-Math.sin(pitchRad));
        float dz = (float) (-Math.cos(yawRad) * Math.cos(pitchRad));
        return new float[] { camX + dx * 3.0f, camY + dy * 3.0f, camZ + dz * 3.0f };
    }

    private void warmEntity(GameObject g) {
        MeshRenderer mr = g.getComponent(MeshRenderer.class);
        if (mr == null) return;
        applyMeshRendererDefaults(mr);
        if (isMarker(mr)) return;
        try {
            VkMesh m = meshFor(mr);
            populateVkLocalGeometry(mr, m);
            descriptorSetFor(mr);
        } catch (Exception e) {
            Logger.error("Failed to warm assets for spawned entity " + g.name, e);
        }
    }

    /** Copy CPU-side positions / indices / bounds from the loaded VkMesh
     *  onto the entity's MeshRenderer so the (renderer-agnostic)
     *  MeshCollider + GameObject.worldBounds see real geometry instead
     *  of the ±0.5 fallback. Same shared arrays — cheap. */
    private static void populateVkLocalGeometry(MeshRenderer mr, VkMesh m) {
        if (m == null) return;
        mr.vkLocalPositions = m.localPositions();
        mr.vkLocalIndices   = m.localIndices();
        float minX = m.minX(), minY = m.minY(), minZ = m.minZ();
        float maxX = m.maxX(), maxY = m.maxY(), maxZ = m.maxZ();
        mr.vkLocalBounds = new float[]{ minX, minY, minZ, maxX, maxY, maxZ };
        // Sub-phase G — Animator.recompute() reads MeshRenderer.skeleton().
        if (m.isSkinned()) mr.vkSkeleton = m.skeleton();
    }

    private void spawnModelInFront(String modelFileName) {
        float[] p = cameraForwardOffset();
        // Snap to integer grid on all axes so editor-spawned entities
        // line up exactly with play-mode-placed cubes (which also use
        // Math.round in placeCubeFromCamera). Previous formula
        // `floor(y+1) - 0.5` biased Y to integer+0.5 — placed cubes
        // ended up 0.5 m higher than play-placed ones.
        GameObject g = new GameObject((float) Math.round(p[0]),
                                       (float) Math.round(p[1]),
                                       (float) Math.round(p[2]));
        String displayName = modelFileName.startsWith(Config.CHARACTER_PATH_PREFIX)
                ? modelFileName.substring(Config.CHARACTER_PATH_PREFIX.length())
                : modelFileName;
        g.name = displayName.replaceAll("\\.(glb|gltf|obj|fbx)$", "");
        MeshRenderer mr = new MeshRenderer();
        mr.modelPath = modelFileName;
        // Auto-derive the texture filename — characters/Hazmat.glb →
        // Hazmat.png in src/main/resources/textures. Mirrors the
        // MeshRenderer.start() fallback (lines 92-105) which isn't run
        // in the Vulkan path (addComponentNoStart). If the .png doesn't
        // exist on disk the descriptor loader will skip the entity, but
        // characters/* models all ship with a matching PNG.
        String autoTex = displayName.replaceAll("\\.(glb|gltf|obj|fbx)$", ".png");
        if (textureExistsOnClasspath(autoTex)) mr.texturePath = autoTex;
        g.addComponentNoStart(mr);
        entities.add(g);
        warmEntity(g);
        Logger.info("Spawned model: " + modelFileName + " (texture: " + mr.texturePath + ")");
    }

    /** Drag-and-drop handler for the Character Editor (Phase 2): build an
     *  ISOLATED preview entity from a model path and warm its GPU assets on
     *  the UI thread (so drawFrame is a pure cache hit). NOT added to the main
     *  level's `entities` list — it lives only in the Character Editor pass.
     *  Mirrors {@link #spawnModelInFront}'s MeshRenderer + texture setup. */
    private void loadPreviewCharacter(String modelFileName) {
        GameObject g = new GameObject(0, 0, 0);   // at the origin, isolated
        String displayName = modelFileName.startsWith(Config.CHARACTER_PATH_PREFIX)
                ? modelFileName.substring(Config.CHARACTER_PATH_PREFIX.length())
                : modelFileName;
        g.name = displayName.replaceAll("\\.(glb|gltf|obj|fbx)$", "");
        MeshRenderer mr = new MeshRenderer();
        mr.modelPath = modelFileName;
        String autoTex = displayName.replaceAll("\\.(glb|gltf|obj|fbx)$", ".png");
        if (textureExistsOnClasspath(autoTex)) mr.texturePath = autoTex;
        g.addComponentNoStart(mr);
        // Phase 3A: every dropped character gets a locomotion data component,
        // edited live via the Character Editor sliders.
        g.addComponentNoStart(new CharacterLocomotionComponent());
        warmEntity(g);   // applyDefaults + meshFor + populateVkLocalGeometry + descriptorSetFor
        previewCharacter = g;
        // Phase 6B — reset to the default isometric framing for the new model.
        previewCameraYaw = 45f;
        previewCameraPitch = 20f;
        previewCameraDistance = 3.0f;
        previewPanOffset.set(0f, 1f, 0f);   // frame the chest, not the feet
        Logger.info("Character Editor: loaded preview '" + modelFileName + "'");
    }

    /** Phase 5F — descriptor set for the static reference cube (test.png).
     *  Lazily builds a throwaway MeshRenderer so it reuses the normal
     *  descriptor cache (dsKey "tex:test.png"). */
    private MeshRenderer refCubeMr;
    private long referenceCubeDescriptorSet() {
        if (refCubeMr == null) {
            refCubeMr = new MeshRenderer();
            refCubeMr.modelPath   = "";          // procedural cube
            refCubeMr.texturePath = "test.png";
        }
        return descriptorSetFor(refCubeMr);
    }

    /** Phase 5F — apply a dropped texture to the preview character (Character
     *  Editor texture target). Same path-keyed swap the Inspector uses. */
    private void swapPreviewTexture(String texFile) {
        if (previewCharacter == null) return;
        MeshRenderer mr = previewCharacter.getComponent(MeshRenderer.class);
        if (mr == null) return;
        mr.texturePath = texFile;
        descriptorSetFor(mr);
    }

    /** Phase 5F — uniform Base Scale. Sets scale.x/y/z together and NOTHING
     *  else: the automatic feet-pivot Y compensation was removed (it pushed the
     *  character up and out of the preview frame, defeating the reference cube).
     *  The user adjusts Base Position manually if the model clips the floor.
     *  Still NaN-hardened so a corrupt scale can't poison the view matrix. */
    private void applyUniformPreviewScale(float newScale) {
        if (previewCharacter == null) return;
        if (!Float.isFinite(newScale)) newScale = 1f;
        newScale = Math.max(0.01f, newScale);
        Transform t = previewCharacter.transform;
        t.scaleX = t.scaleY = t.scaleZ = newScale;   // position untouched
    }

    /** Phase 5E — orbit the dedicated PREVIEW CAMERA (not the character). LMB
     *  drag yaws/pitches the camera around the character; the character's Base
     *  Transform is never touched (it controls only how the avatar spawns).
     *  {@code delta} = {dxPixels, dyPixels}. */
    private float previewCameraYaw   = 45f;   // isometric 3/4 framing default (Phase 6B)
    private float previewCameraPitch = 20f;
    private void orbitPreviewCamera(float[] delta) {
        // Inverted X (Issue 1): drag-left should swing the camera left, so the
        // model appears to rotate with the cursor (grab-the-turntable feel).
        previewCameraYaw   -= delta[0] * 0.4f;
        previewCameraPitch += delta[1] * 0.4f;
        if (previewCameraPitch >  85f) previewCameraPitch =  85f;
        if (previewCameraPitch < -85f) previewCameraPitch = -85f;
    }

    /** Phase 5B — CTRL+scroll zoom for the preview camera. Positive scroll
     *  (wheel up) moves the orbit eye closer to the character; clamped to a
     *  sane [0.5, 10] range. Consumed in {@link #recordCharacterPreview}. */
    private float previewCameraDistance = 3.0f;   // Phase 6B default framing
    private void zoomPreviewCamera(float scroll) {
        previewCameraDistance -= scroll * 0.3f;
        if (previewCameraDistance < 0.5f)  previewCameraDistance = 0.5f;
        if (previewCameraDistance > 10.0f) previewCameraDistance = 10.0f;
    }

    /** Phase 5C/6B — RMB+drag pans the preview camera. The pan now moves along
     *  the camera's LOCAL Right / Up vectors (Issue 2): with a world-axis pan,
     *  a 90°-rotated view made left/right drag move forward/back. Accumulated as
     *  a world-space offset added to the orbit target; clamped per-axis to ±2 m. */
    private final org.joml.Vector3f previewPanOffset = new org.joml.Vector3f(0f, 1f, 0f);
    private void panPreviewCamera(float[] delta) {
        float yr = (float) Math.toRadians(previewCameraYaw);
        float pr = (float) Math.toRadians(previewCameraPitch);
        // dir = target→eye (matches recordCharacterPreview); forward = eye→target.
        org.joml.Vector3f fwd = new org.joml.Vector3f(
                (float) (Math.sin(yr) * Math.cos(pr)),
                (float) Math.sin(pr),
                (float) (Math.cos(yr) * Math.cos(pr))).negate();
        org.joml.Vector3f right = new org.joml.Vector3f(fwd).cross(0f, 1f, 0f);
        if (right.lengthSquared() < 1e-6f) right.set(1f, 0f, 0f);
        right.normalize();
        org.joml.Vector3f up = new org.joml.Vector3f(right).cross(fwd).normalize();
        float sx = -delta[0] * 0.01f;   // drag right → content shifts right → target left
        float sy =  delta[1] * 0.01f;   // drag down (screen +Y) → target down
        previewPanOffset.add(right.mul(sx)).add(up.mul(sy));
        previewPanOffset.x = Math.max(-2f, Math.min(2f, previewPanOffset.x));
        previewPanOffset.y = Math.max(-2f, Math.min(2f, previewPanOffset.y));
        previewPanOffset.z = Math.max(-2f, Math.min(2f, previewPanOffset.z));
    }

    /** Phase 4 — "Set as Active Player": clone the isolated preview character
     *  into the live world AND bind its locomotion component to the shared
     *  Player controller, so the Character Editor sliders tune the active
     *  player's physics live.
     *
     *  Engine-architecture note (Rule #0 — implement the intent against real
     *  code): our Player IS the camera + capsule, not a scene entity, so there
     *  is no "player entity" to swap. The faithful mapping is: (a) drop a
     *  visible avatar clone into `entities` so the character exists in the
     *  world, and (b) point `player.locomotion` at the LIVE preview component
     *  the sliders edit. The avatar is flagged headless (hiddenSubmeshIndex=0)
     *  so it doesn't occlude the first-person camera in play mode (Phase 3B).
     *  A re-press removes the previous avatar (no duplicates). */
    private void setActivePlayer() {
        if (previewCharacter == null) {
            Logger.warn("Set as Active Player: no preview character loaded.");
            return;
        }
        CharacterLocomotionComponent loco =
                previewCharacter.getComponent(CharacterLocomotionComponent.class);

        // Remove the previously-spawned avatar to prevent duplicates.
        if (activePlayerEntity != null) {
            entities.remove(activePlayerEntity);
            if (selectedEntity == activePlayerEntity) selectedEntity = null;
            activePlayerEntity = null;
        }

        // Deep-copy the preview into the world (so later preview edits don't
        // mutate the spawned avatar's serialized data).
        GameObject avatar = LevelLoader.deepCopy(previewCharacter, true);
        avatar.name = previewCharacter.name + " (Player)";
        float[] feet = spawnFeetPosition();
        avatar.transform.x = feet[0];
        avatar.transform.y = feet[1];
        avatar.transform.z = feet[2];
        // Emergency NaN scrub (Phase 5G): never let a non-finite transform reach
        // the avatar/player — a NaN here propagates straight into the view
        // matrix and freezes/tears the whole frame.
        sanitizeTransform(avatar.transform);
        // Phase 6C: do NOT force hiddenSubmeshIndex here — the clone keeps the
        // exact value the user set via "Hide Avatar in First-Person" so they can
        // make the body visible for animation testing.
        // Issue 1 (stuck in floor): the avatar is a SOLID character mesh that
        // sits at the player's feet and rides with the camera — without this
        // the player collides with / stands on its own body at spawn and can't
        // resolve out (fly+fall was the only escape). Walkthrough keeps it out
        // of World.getNearbyEntities' collision grid entirely.
        avatar.addComponentNoStart(new WalkthroughComponent());
        entities.add(avatar);
        warmEntity(avatar);
        activePlayerEntity = avatar;
        // Phase 6A: the avatar needs an Animator for the locomotion state
        // machine to drive; create one if the preview didn't have it. Reset the
        // state so the machine re-plays from scratch on the next play frame.
        ensureEntityAnimator(avatar);
        currentLocoState = -1;

        // Bind the LIVE preview component to the player so the sliders keep
        // driving its physics (the feature's core ask). Apply the capsule
        // immediately too; Player.update re-reads it every frame.
        player.locomotion = loco;
        if (loco != null) {
            player.capsuleRadius = loco.collisionRadius;
            player.capsuleHeight = loco.collisionHalfHeight * 2.0f;
        }
        Logger.info("Set as Active Player: '" + avatar.name
                + "' spawned + bound to the player controller.");
    }

    /** Replace any NaN/Inf transform field with a safe default so a corrupt
     *  value can't poison the view matrix (Phase 5G emergency guard). */
    private static void sanitizeTransform(Transform t) {
        if (!Float.isFinite(t.x)) t.x = 0f;
        if (!Float.isFinite(t.y)) t.y = 0f;
        if (!Float.isFinite(t.z)) t.z = 0f;
        if (!Float.isFinite(t.rotX)) t.rotX = 0f;
        if (!Float.isFinite(t.rotY)) t.rotY = 0f;
        if (!Float.isFinite(t.rotZ)) t.rotZ = 0f;
        if (!Float.isFinite(t.scaleX) || t.scaleX == 0f) t.scaleX = 1f;
        if (!Float.isFinite(t.scaleY) || t.scaleY == 0f) t.scaleY = 1f;
        if (!Float.isFinite(t.scaleZ) || t.scaleZ == 0f) t.scaleZ = 1f;
    }

    /** Issues 2 & 3 — keep the spawned avatar locked to the live Player every
     *  play-mode frame (walking AND flying), so the 3D body follows the camera
     *  instead of being left behind at the spawn point, and exiting to the
     *  editor leaves the avatar exactly where the player stopped.
     *
     *  Player.y is the EYE position; we subtract eyeHeight() so the model's
     *  feet sit on the ground while the camera stays at eye level.
     *
     *  Yaw (Phase 4B/5): the model matrix applies rotateY(+rotY) while the
     *  view/light matrices use rotateY(-yaw) (see CLAUDE.md conventions +
     *  modelMatrixFromTransform). The bare sign-flip aligned the AXES but left
     *  standard GLTF models (local +Z forward) facing the camera. The extra
     *  180° turns the model's back to the camera so it faces where the player
     *  looks: rotY = 180 - yaw (entry inverts it: yaw = 180 - rotY). */
    private void syncActivePlayerAvatar() {
        if (activePlayerEntity == null) return;
        Transform t = activePlayerEntity.transform;
        t.x = player.x;
        t.y = player.y - player.eyeHeight();
        t.z = player.z;
        t.rotY = 180f - player.yaw;
    }

    /** Feet position of the active Spawn Point (SpawnPointComponent or the
     *  legacy SPAWN_POINT_KEY marker), else the world origin. */
    private float[] spawnFeetPosition() {
        for (GameObject obj : entities) {
            MeshRenderer mr = obj.getComponent(MeshRenderer.class);
            boolean isSpawn = obj.getComponent(SpawnPointComponent.class) != null
                    || (mr != null && World.SPAWN_POINT_KEY.equals(mr.modelPath));
            if (isSpawn) return new float[]{ obj.transform.x, obj.transform.y, obj.transform.z };
        }
        return new float[]{ 0f, 0f, 0f };
    }

    /** Phase 2 — record the world from the active player's camera into the Game
     *  viewport target. Geometry ONLY (skybox, static models, skinned, cube
     *  batch, sun) — no gizmos / outlines / lines / markers / debug — and the
     *  active player's own mesh is skipped so the first-person camera doesn't
     *  clip the body. Mirrors the scene color pass's geometry block but with the
     *  avatar's camera and the player-mesh skip. */
    private void recordGamePass(VkCommandBuffer cb, MemoryStack stack, Matrix4f lightSpace) {
        if (activePlayerEntity == null) return;
        int gw = gameTarget.width(), gh = gameTarget.height();
        setViewportAndScissor(cb, stack, 0, gh, gw, -gh, gw, gh);

        // Camera = the avatar's eye. yaw = 180 - rotY (inverse of the sync
        // mapping in syncActivePlayerAvatar); pitch 0 (avatar stores no pitch).
        Transform at = activePlayerEntity.transform;
        CharacterLocomotionComponent loco =
                activePlayerEntity.getComponent(CharacterLocomotionComponent.class);
        float eyeH = (loco != null) ? loco.cameraHeightOffset : 1.6f;
        float gx = at.x, gy = at.y + eyeH, gz = at.z;
        float gYaw = 180f - at.rotY;

        float aspect = gw / (float) gh;
        Matrix4f proj = new Matrix4f().setPerspective(
                (float) Math.toRadians(Config.FOV), aspect, 0.1f, 200.0f, true);
        Matrix4f viewRotOnly = new Matrix4f().rotateY((float) Math.toRadians(gYaw));
        Matrix4f view = new Matrix4f(viewRotOnly).translate(-gx, -gy, -gz);
        Matrix4f viewProj = new Matrix4f(proj).mul(view);

        // ---- Skybox.
        Matrix4f skyMvp = new Matrix4f(proj).mul(viewRotOnly).scale(100f);
        FloatBuffer skyPC = stack.mallocFloat(16);
        skyMvp.get(skyPC);
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, skyboxPipeline);
        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                skyboxPipelineLayout, 0, stack.longs(skyboxDescriptorSet), null);
        skyboxMesh.bind(cb);
        vkCmdPushConstants(cb, skyboxPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, skyPC);
        vkCmdDrawIndexed(cb, skyboxMesh.indexCount(), 1, 0, 0, 0);

        // ---- Collect procedural cubes (player avatar isn't a cube, but skip it).
        cubeBatch.clear();
        for (GameObject g : entities) {
            if (g == activePlayerEntity) continue;
            MeshRenderer mr = g.getComponent(MeshRenderer.class);
            if (mr == null || isMarker(mr) || hiddenAtRuntime(g)) continue;
            if (!isInstanceableCube(mr)) continue;
            long ds = descriptorSetFor(mr);
            if (ds == 0L) continue;
            cubeBatch.submit(g, mr, ds);
        }

        // ---- Static (non-cube, non-skinned) models.
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, modelPipeline);
        long boundDs = 0; VkMesh boundMesh = null;
        for (GameObject g : entities) {
            if (g == activePlayerEntity) continue;   // first-person: hide own body
            MeshRenderer mr = g.getComponent(MeshRenderer.class);
            if (mr == null || isMarker(mr) || hiddenAtRuntime(g)) continue;
            if (isInstanceableCube(mr)) continue;
            VkMesh mesh = meshFor(mr);
            if (mesh == null || mesh.isSkinned()) continue;
            long ds = descriptorSetFor(mr);
            if (ds == 0L) continue;
            if (ds != boundDs) {
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        modelPipelineLayout, 0, stack.longs(ds), null);
                boundDs = ds;
            }
            if (mesh != boundMesh) { mesh.bind(cb); boundMesh = mesh; }
            boolean triplanar = Boolean.TRUE.equals(mr.material.useWorldSpaceTiling);
            float tr, tg, tb;
            if ("Sun".equals(g.name)) { tr = 1f; tg = 1f; tb = 0f; }
            else { tr = mr.material.r; tg = mr.material.g; tb = mr.material.b; }
            recordModelDraw(cb, mesh, modelMatrixFromTransform(g.transform), viewProj, lightSpace,
                    triplanar ? 1f : 0f, mr.material.textureScale, tr, tg, tb);
        }

        // ---- Skinned characters.
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, skinnedModelPipeline);
        boundDs = 0; boundMesh = null;
        for (GameObject g : entities) {
            if (g == activePlayerEntity) continue;
            MeshRenderer mr = g.getComponent(MeshRenderer.class);
            if (mr == null || isMarker(mr) || hiddenAtRuntime(g)) continue;
            VkMesh mesh = meshFor(mr);
            if (mesh == null || !mesh.isSkinned()) continue;
            long ds = descriptorSetFor(mr);
            if (ds == 0L) continue;
            if (ds != boundDs) {
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        skinnedModelPipelineLayout, 0, stack.longs(ds), null);
                boundDs = ds;
            }
            Animator a = g.getComponent(Animator.class);
            long boneSet = (a != null && a.finalBoneMatrices != null)
                    ? skinnedRuntime.updateAndGetSet(a) : identityBoneSet();
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    skinnedModelPipelineLayout, 1, stack.longs(boneSet), null);
            if (mesh != boundMesh) { mesh.bindWithSkinning(cb); boundMesh = mesh; }
            boolean triplanar = Boolean.TRUE.equals(mr.material.useWorldSpaceTiling);
            recordSkinnedDraw(cb, mesh, modelMatrixFromTransform(g.transform), viewProj, lightSpace,
                    triplanar ? 1f : 0f, mr.material.textureScale, mr.material.r, mr.material.g, mr.material.b);
        }

        // ---- Sun cube (flat yellow).
        for (GameObject g : entities) {
            if (!"Sun".equals(g.name)) continue;
            Matrix4f sunMvp = new Matrix4f(viewProj).mul(modelMatrixFromTransform(g.transform));
            lineRenderer.drawMesh(cb, ensureCubeMesh(), sunMvp, 1f, 1f, 0f);
            break;
        }

        // ---- Cube batch — one draw per material group.
        if (cubeBatch.instanceCount() > 0) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, cubePipeline);
            ensureCubeMesh().bind(cb);
            try (MemoryStack stk = MemoryStack.stackPush()) {
                FloatBuffer pcF = stk.mallocFloat(CUBE_PC_FLOATS);
                pcF.put(0, 0f); pcF.put(1, 1f); pcF.put(2, 0f); pcF.put(3, 0f);
                viewProj.get(4, pcF);
                lightSpace.get(20, pcF);
                pcF.put(36, sunDirX); pcF.put(37, sunDirY); pcF.put(38, sunDirZ); pcF.put(39, sunIntensity);
                vkCmdPushConstants(cb, cubePipelineLayout,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pcF);
            }
            cubeBatch.flush(cb, cubePipelineLayout);
        }
    }

    /** Draw the isolated {@link #previewCharacter} into the Character Editor's
     *  offscreen pass (PASS 2b). Binds the static or skinned model pipeline,
     *  the entity's texture set (+ bone set for skinned), and pushes a preview
     *  camera MVP that orbits a character at the origin. Assets are pre-warmed
     *  in {@link #loadPreviewCharacter}, so meshFor/descriptorSetFor here are
     *  cache hits. Caller has already begun the character render pass. */
    private void recordCharacterPreview(VkCommandBuffer cb, MemoryStack stack, Matrix4f lightSpace) {
        if (previewCharacter == null) return;
        MeshRenderer mr = previewCharacter.getComponent(MeshRenderer.class);
        if (mr == null) return;
        VkMesh mesh = meshFor(mr);
        if (mesh == null) return;
        long ds = descriptorSetFor(mr);
        if (ds == 0L) return;

        int cw = characterTarget.width(), ch = characterTarget.height();
        // Negative-height viewport → GL-style up-Y (same flip as the scene pass).
        setViewportAndScissor(cb, stack, 0, ch, cw, -ch, cw, ch);

        // Dedicated preview camera: a fixed 3/4 orbit framing a ~2 m character
        // standing at the origin. lookAt + the negative-height viewport keep it
        // right-side up, matching the main scene's convention.
        float aspect = cw / (float) Math.max(1, ch);
        Matrix4f proj = new Matrix4f().setPerspective(
                (float) Math.toRadians(45.0), aspect, 0.05f, 100f, true);
        // Phase 5E/5G — dedicated preview camera. Target is anchored to the
        // FIXED WORLD ORIGIN (+ RMB pan), NOT the character transform: otherwise
        // the uniform-scale pivot (which raises Base Position Y) drags the
        // camera up and makes the static reference cube appear to slide down.
        // Anchoring here keeps the cube — and the character's feet at y=0 — rock
        // steady while Base Scale changes. Eye orbits the target by the camera
        // yaw/pitch (LMB drag) at the zoom distance (CTRL+scroll).
        org.joml.Vector3f target = new org.joml.Vector3f(0f, 1.0f, 0f).add(previewPanOffset);
        float yr = (float) Math.toRadians(previewCameraYaw);
        float pr = (float) Math.toRadians(previewCameraPitch);
        org.joml.Vector3f dir = new org.joml.Vector3f(
                (float) (Math.sin(yr) * Math.cos(pr)),
                (float) Math.sin(pr),
                (float) (Math.cos(yr) * Math.cos(pr)));
        org.joml.Vector3f eye = new org.joml.Vector3f(dir).mul(previewCameraDistance).add(target);
        Matrix4f view = new Matrix4f().lookAt(eye.x, eye.y, eye.z,
                target.x, target.y, target.z, 0f, 1f, 0f);
        Matrix4f viewProj = proj.mul(view);
        Matrix4f model = modelMatrixFromTransform(previewCharacter.transform);
        boolean triplanar = Boolean.TRUE.equals(mr.material.useWorldSpaceTiling);
        float tr = mr.material.r, tg = mr.material.g, tb = mr.material.b;

        if (mesh.isSkinned()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, skinnedModelPipeline);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    skinnedModelPipelineLayout, 0, stack.longs(ds), null);
            Animator a = previewCharacter.getComponent(Animator.class);
            long boneSet = (a != null && a.finalBoneMatrices != null)
                    ? skinnedRuntime.updateAndGetSet(a) : identityBoneSet();
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    skinnedModelPipelineLayout, 1, stack.longs(boneSet), null);
            mesh.bindWithSkinning(cb);
            recordSkinnedDraw(cb, mesh, model, viewProj, lightSpace,
                    triplanar ? 1f : 0f, mr.material.textureScale, tr, tg, tb);
        } else {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, modelPipeline);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    modelPipelineLayout, 0, stack.longs(ds), null);
            mesh.bind(cb);
            recordModelDraw(cb, mesh, model, viewProj, lightSpace,
                    triplanar ? 1f : 0f, mr.material.textureScale, tr, tg, tb);
        }

        // ---- Phase 5F — static 1×1×1 size-reference cube under the character
        // (test.png), drawn DIRECTLY here (raw draw call, NOT an entity) so it
        // never clones into the world when "Set as Active Player" runs. Sits at
        // (0, -0.5, 0) → spans y=-1..0, flush beneath the character's feet.
        long refDs = referenceCubeDescriptorSet();
        if (refDs != 0L) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, modelPipeline);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    modelPipelineLayout, 0, stack.longs(refDs), null);
            VkMesh refCube = ensureCubeMesh();
            refCube.bind(cb);
            Matrix4f refModel = new Matrix4f().translate(0f, -0.5f, 0f);
            recordModelDraw(cb, refCube, refModel, viewProj, lightSpace, 1f, 1f, 1f, 1f, 1f);
        }

        // ---- Phase 5 visual debugging overlays (line renderer, depth-off) ----
        // VkLineRenderer's pipeline was built against sceneTarget's render pass,
        // which is render-pass-COMPATIBLE with characterTarget (both
        // VkOffscreenTarget, identical attachment formats), so it records here.
        CharacterLocomotionComponent loco =
                previewCharacter.getComponent(CharacterLocomotionComponent.class);
        // Phase 5C — red eye-height marker: a small 3-axis cross at the
        // camera Y (cameraHeightOffset) so the user sees where the in-game
        // viewport sits. Always drawn (independent of the debug toggles).
        if (loco != null) {
            Transform pt0 = previewCharacter.transform;
            float ex = pt0.x, ey = pt0.y + loco.cameraHeightOffset, ez = pt0.z, s = 0.05f;
            float[] cross = {
                ex - s, ey, ez,  ex + s, ey, ez,
                ex, ey - s, ez,  ex, ey + s, ez,
                ex, ey, ez - s,  ex, ey, ez + s,
            };
            lineRenderer.drawLines(cb, cross, viewProj, 1.0f, 0.1f, 0.1f);
        }
        if (characterEditor.isShowCollision() && loco != null) {
            // Axis-aligned capsule from the loco dimensions, in WORLD space
            // (verts already absolute → MVP is just viewProj). Feet at the
            // preview transform origin; height = collisionHalfHeight * 2.
            Transform pt = previewCharacter.transform;
            float[] caps = buildCapsuleLines(pt.x, pt.y + loco.collisionOffsetY, pt.z,
                    loco.collisionRadius, loco.collisionHalfHeight * 2f);
            lineRenderer.drawLines(cb, caps, viewProj, 0.2f, 1.0f, 0.4f);
        }
        if (characterEditor.isShowBones() && mesh.isSkinned()) {
            Skeleton skel = mr.skeleton();
            if (skel != null) {
                // Animated pose if the Animator has run (debugBoneTransforms =
                // per-bone GLOBAL transforms); else the bind-pose T-skeleton.
                Animator a = previewCharacter.getComponent(Animator.class);
                Matrix4f[] dbt = (a != null && a.debugBoneTransforms != null)
                        ? a.debugBoneTransforms : null;
                float[][] lines = skel.gatherDebugLines(dbt);
                // Bone/node lines are in object-local space → compose the model
                // matrix so they follow the preview's rotation.
                Matrix4f boneMvp = new Matrix4f(viewProj).mul(model);
                if (lines[0].length > 0) lineRenderer.drawLines(cb, lines[0], boneMvp, 1.0f, 0.85f, 0.2f);
                if (lines[1].length > 0) lineRenderer.drawLines(cb, lines[1], boneMvp, 0.3f, 0.6f, 1.0f);
            }
        }
    }

    /** Build a wireframe capsule (LINE_LIST, {@code x0y0z0 x1y1z1} segments) in
     *  WORLD space: two end-cap hemispheres joined by a cylinder, feet at
     *  {@code (cx, feetY, cz)}. Used by the Character Editor's "Show Collision
     *  Bounds" overlay from the locomotion radius / height. */
    private static float[] buildCapsuleLines(float cx, float feetY, float cz,
                                             float radius, float height) {
        radius = Math.max(0.01f, radius);
        height = Math.max(2f * radius, height);
        float botC = feetY + radius;            // bottom hemisphere centre
        float topC = feetY + height - radius;   // top hemisphere centre
        java.util.List<Float> v = new java.util.ArrayList<>();
        int seg = 16;
        capRing(v, cx, cz, radius, botC, seg);
        capRing(v, cx, cz, radius, topC, seg);
        for (int k = 0; k < 4; k++) {           // 4 vertical seam lines
            double an = k * Math.PI / 2.0;
            float px = cx + (float) Math.cos(an) * radius;
            float pz = cz + (float) Math.sin(an) * radius;
            capLine(v, px, botC, pz, px, topC, pz);
        }
        capArcs(v, cx, cz, radius, topC, +1, seg);   // top dome
        capArcs(v, cx, cz, radius, botC, -1, seg);   // bottom dome
        float[] arr = new float[v.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = v.get(i);
        return arr;
    }

    private static void capLine(java.util.List<Float> v, float ax, float ay, float az,
                                float bx, float by, float bz) {
        v.add(ax); v.add(ay); v.add(az); v.add(bx); v.add(by); v.add(bz);
    }

    private static void capRing(java.util.List<Float> v, float cx, float cz,
                                float r, float y, int seg) {
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            capLine(v, cx + (float) Math.cos(a0) * r, y, cz + (float) Math.sin(a0) * r,
                       cx + (float) Math.cos(a1) * r, y, cz + (float) Math.sin(a1) * r);
        }
    }

    // Quarter-circle dome arcs in the XY and ZY planes (both sides) from a
    // hemisphere centre out to the pole (dir = +1 up, -1 down).
    private static void capArcs(java.util.List<Float> v, float cx, float cz,
                                float r, float ringY, int dir, int seg) {
        int q = Math.max(4, seg / 4);
        for (int s = 0; s < q; s++) {
            double t0 = (Math.PI / 2) * s / q, t1 = (Math.PI / 2) * (s + 1) / q;
            float c0 = (float) Math.cos(t0) * r, c1 = (float) Math.cos(t1) * r;
            float s0 = dir * (float) Math.sin(t0) * r, s1 = dir * (float) Math.sin(t1) * r;
            capLine(v, cx + c0, ringY + s0, cz, cx + c1, ringY + s1, cz);   // +X
            capLine(v, cx - c0, ringY + s0, cz, cx - c1, ringY + s1, cz);   // -X
            capLine(v, cx, ringY + s0, cz + c0, cx, ringY + s1, cz + c1);   // +Z
            capLine(v, cx, ringY + s0, cz - c0, cx, ringY + s1, cz - c1);   // -Z
        }
    }

    /** Cheap exists-check for a texture filename in
     *  {@code src/main/resources/textures/}. Avoids triggering a load
     *  via the texture cache when only probing for the auto-derived
     *  character texture in {@link #spawnModelInFront}. */
    private static boolean textureExistsOnClasspath(String filename) {
        if (filename == null || filename.isEmpty()) return false;
        return new java.io.File("src/main/resources/textures/" + filename).exists();
    }

    /** "Character Collision" gameplay object — the standalone player-body
     *  capsule definition. Scale is the capsule (scaleX/Z = diameter,
     *  scaleY = height); position is wherever the user parks it in the
     *  level. Defaults match the engine's historical body: radius 0.3
     *  (diameter 0.6), height 2.0. */
    private void spawnPlayerBodyInFront() {
        // Only one body definition makes sense — re-select the existing
        // one instead of spawning a duplicate.
        for (GameObject g : entities) {
            MeshRenderer existing = g.getComponent(MeshRenderer.class);
            if (existing != null && World.PLAYER_BODY_KEY.equals(existing.modelPath)) {
                selectedEntity = g;
                Logger.info("Character Collision already exists — selected it");
                return;
            }
        }
        float[] p = cameraForwardOffset();
        GameObject body = new GameObject(p[0], p[1], p[2]);
        body.name = "Character Collision";
        body.transform.scaleX = 0.6f;
        body.transform.scaleY = 2.0f;
        body.transform.scaleZ = 0.6f;
        MeshRenderer mr = new MeshRenderer();
        mr.modelPath = World.PLAYER_BODY_KEY;
        mr.texturePath = "";
        body.addComponentNoStart(mr);
        entities.add(body);
        selectedEntity = body;
        Logger.info("Spawned Character Collision at " + p[0] + "," + p[1] + "," + p[2]);
    }


    private void stressSpawnCubes(int count) {
        int side = (int) Math.ceil(Math.sqrt(count));
        float spacing = 1.5f;
        float startX = camX - side * spacing * 0.5f;
        float startZ = camZ - side * spacing * 0.5f - 5f;
        int spawned = 0;
        for (int z = 0; z < side && spawned < count; z++) {
            for (int x = 0; x < side && spawned < count; x++) {
                GameObject cube = new GameObject(startX + x * spacing, 1f, startZ + z * spacing);
                cube.name = "StressCube_" + spawned;
                MeshRenderer mr = new MeshRenderer();
                mr.modelPath = "";
                mr.texturePath = "test.png";
                cube.addComponentNoStart(mr);
                entities.add(cube);
                spawned++;
            }
        }
        warmEntity(entities.get(entities.size() - 1));  // one warm call seeds the cube mesh
        Logger.info("Stress-spawned " + spawned + " cubes (grid " + side + "x" + side + ")");
    }

    /** Push (model, lightSpace, flags) and issue a single indexed draw into
     *  the shadow pipeline. Caller has bound the descriptor set for the
     *  entity's material (used by the fragment shader's foliage alpha
     *  discard). Per-call MemoryStack so heavy scenes (5000+ draws) don't
     *  pile up inside drawFrame's single 512 KB frame. */
    private void recordShadowDraw(VkCommandBuffer cb,
                                  VkMesh mesh, Matrix4f model, Matrix4f lightSpace,
                                  float isFoliage) {
        mesh.bind(cb);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer pc = stack.mallocFloat(SHADOW_PC_FLOATS);
            model.get(0, pc);
            lightSpace.get(16, pc);
            pc.put(32, isFoliage); pc.put(33, 0f); pc.put(34, 0f); pc.put(35, 0f);
            vkCmdPushConstants(cb, shadowPipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, pc);
            vkCmdDrawIndexed(cb, mesh.indexCount(), 1, 0, 0, 0);
        }
    }

    /** Skinned analog — caller has bound the skinned shadow pipeline + the
     *  material + bone descriptor sets + the mesh's skinning vertex buffer.
     *  Same PC layout as the static shadow path. */
    private void recordSkinnedShadowDraw(VkCommandBuffer cb,
                                          VkMesh mesh, Matrix4f model, Matrix4f lightSpace,
                                          float isFoliage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer pc = stack.mallocFloat(SHADOW_PC_FLOATS);
            model.get(0, pc);
            lightSpace.get(16, pc);
            pc.put(32, isFoliage); pc.put(33, 0f); pc.put(34, 0f); pc.put(35, 0f);
            vkCmdPushConstants(cb, skinnedShadowPipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, pc);
            vkCmdDrawIndexed(cb, mesh.indexCount(), 1, 0, 0, 0);
        }
    }

    /** Push the full 60-float PC (model, viewProj, lightSpace, material,
     *  tint, sunDir) and issue an indexed draw into the model pipeline.
     *  Caller has bound the descriptor set + mesh already. Same per-call
     *  stack-frame discipline as {@link #recordShadowDraw}. */
    private void recordModelDraw(VkCommandBuffer cb,
                                 VkMesh mesh, Matrix4f model,
                                 Matrix4f viewProj, Matrix4f lightSpace,
                                 float triplanar, float texScale,
                                 float tintR, float tintG, float tintB) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer pc = stack.mallocFloat(MODEL_PC_FLOATS);
            model.get(0, pc);
            viewProj.get(16, pc);
            lightSpace.get(32, pc);
            pc.put(48, triplanar); pc.put(49, texScale);
            pc.put(50, Config.SHADOW_NORMAL_BIAS); pc.put(51, Config.SHADOW_DEPTH_BIAS);
            pc.put(52, tintR); pc.put(53, tintG); pc.put(54, tintB); pc.put(55, 0f);
            pc.put(56, sunDirX); pc.put(57, sunDirY); pc.put(58, sunDirZ); pc.put(59, sunIntensity);
            vkCmdPushConstants(cb, modelPipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, pc);
            vkCmdDrawIndexed(cb, mesh.indexCount(), 1, 0, 0, 0);
        }
    }

    /** Marker variant — same PC layout as the model pipeline, but
     *  pushes through markerPipelineLayout (different from
     *  modelPipelineLayout even though their contents are equivalent
     *  — Vulkan requires layouts to match the bound pipeline). */
    private void recordMarkerDraw(VkCommandBuffer cb,
                                   VkMesh mesh, Matrix4f model,
                                   Matrix4f viewProj, Matrix4f lightSpace,
                                   float tintR, float tintG, float tintB) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer pc = stack.mallocFloat(MODEL_PC_FLOATS);
            model.get(0, pc);
            viewProj.get(16, pc);
            lightSpace.get(32, pc);
            pc.put(48, 0f); pc.put(49, 1f);
            pc.put(50, Config.SHADOW_NORMAL_BIAS); pc.put(51, Config.SHADOW_DEPTH_BIAS);
            pc.put(52, tintR); pc.put(53, tintG); pc.put(54, tintB); pc.put(55, 0f);
            pc.put(56, sunDirX); pc.put(57, sunDirY); pc.put(58, sunDirZ); pc.put(59, sunIntensity);
            vkCmdPushConstants(cb, markerPipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, pc);
            vkCmdDrawIndexed(cb, mesh.indexCount(), 1, 0, 0, 0);
        }
    }

    /** Skinned analog of recordModelDraw — same push constant layout,
     *  but the skinned pipeline layout (which references the bone-set
     *  layout at slot 1). */
    private void recordSkinnedDraw(VkCommandBuffer cb,
                                    VkMesh mesh, Matrix4f model,
                                    Matrix4f viewProj, Matrix4f lightSpace,
                                    float triplanar, float texScale,
                                    float tintR, float tintG, float tintB) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer pc = stack.mallocFloat(MODEL_PC_FLOATS);
            model.get(0, pc);
            viewProj.get(16, pc);
            lightSpace.get(32, pc);
            pc.put(48, triplanar); pc.put(49, texScale);
            pc.put(50, Config.SHADOW_NORMAL_BIAS); pc.put(51, Config.SHADOW_DEPTH_BIAS);
            pc.put(52, tintR); pc.put(53, tintG); pc.put(54, tintB); pc.put(55, 0f);
            pc.put(56, sunDirX); pc.put(57, sunDirY); pc.put(58, sunDirZ); pc.put(59, sunIntensity);
            vkCmdPushConstants(cb, skinnedModelPipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0, pc);
            vkCmdDrawIndexed(cb, mesh.indexCount(), 1, 0, 0, 0);
        }
    }

    // Cached identity-bones Animator wrapper for skinned entities that
    // don't have an Animator yet (load order, or user just placed it).
    // Renders the character in bind pose. Lazily allocated; reused.
    private Animator identityAnimator;
    private long identityBoneSet() {
        if (identityAnimator == null) {
            identityAnimator = new Animator();   // start() inits 100× Matrix4f identity
        }
        // updateAndGetSet uploads the matrices (all identity) and returns
        // the descriptor set. Same set is returned every frame.
        return skinnedRuntime.updateAndGetSet(identityAnimator);
    }

    private void drawFrame() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Wait for previous frame at this slot.
            vkWaitForFences(ctx.device(), swap.inFlight(currentFrame), true, Long.MAX_VALUE);

            // 2. Acquire next swapchain image.
            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquireRes = vkAcquireNextImageKHR(ctx.device(), swap.handle(), Long.MAX_VALUE,
                    swap.imageAvailable(currentFrame), VK_NULL_HANDLE, pImageIndex);
            if (acquireRes == VK_ERROR_OUT_OF_DATE_KHR) { recreateSwapchain(); return; }
            if (acquireRes != VK_SUCCESS && acquireRes != VK_SUBOPTIMAL_KHR)
                throw new RuntimeException("vkAcquireNextImageKHR: " + acquireRes);
            int imageIndex = pImageIndex.get(0);

            // Only reset the fence AFTER we know we're committing to a submit.
            vkResetFences(ctx.device(), swap.inFlight(currentFrame));

            // Reset the per-frame 2D-overlay ring-buffer cursor — every
            // fillRect/outlineRect in this frame bumps the cursor; resetting
            // here means the GPU has already drained the last frame's draws
            // (vkWaitForFences above) so we can safely reuse the bytes.
            renderer2D.beginFrame();
            // Same reset for the line renderer — its drawLines also rings.
            lineRenderer.beginFrame();

            // Resize the offscreen Scene Viewport target if the panel
            // reported a new size last frame. This needs vkDeviceWaitIdle
            // because OTHER in-flight frames may still hold the old colour
            // image as an ImGui descriptor source.
            int wantW = (int) viewportW;
            int wantH = (int) viewportH;
            if (wantW > 0 && wantH > 0
                    && (wantW != sceneTarget.width() || wantH != sceneTarget.height())) {
                vkDeviceWaitIdle(ctx.device());
                sceneTarget.resize(wantW, wantH);
                imGuiVk.rebindTexture(viewportTexId, sceneTarget.colorView(), sceneTarget.sampler());
            }

            // Same resize dance for the Character Editor target when its panel
            // is open and its content region changed (its colour image is an
            // ImGui descriptor source too).
            if (isEditorMode && characterEditor.isOpen()) {
                int cW = characterEditor.wantWidth, cH = characterEditor.wantHeight;
                if (cW > 0 && cH > 0
                        && (cW != characterTarget.width() || cH != characterTarget.height())) {
                    vkDeviceWaitIdle(ctx.device());
                    characterTarget.resize(cW, cH);
                    imGuiVk.rebindTexture(characterTexId, characterTarget.colorView(), characterTarget.sampler());
                }
            }

            // Phase 2 — same resize dance for the Game viewport target.
            if (isEditorMode && showGame.get()
                    && gameWantW > 0 && gameWantH > 0
                    && (gameWantW != gameTarget.width() || gameWantH != gameTarget.height())) {
                vkDeviceWaitIdle(ctx.device());
                gameTarget.resize(gameWantW, gameWantH);
                imGuiVk.rebindTexture(gameTexId, gameTarget.colorView(), gameTarget.sampler());
            }

            // 3. Record command buffer.
            VkCommandBuffer cb = commandBuffers[currentFrame];
            vkResetCommandBuffer(cb, 0);
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            check(vkBeginCommandBuffer(cb, bi), "vkBeginCommandBuffer");

            // Main menu mode: skip the shadow + scene passes; only the
            // swapchain ImGui composite runs. Cheap and matches
            // MainMenuScene.render which does no 3D drawing.
            if (inMainMenu) {
                VkClearValue.Buffer mClear = VkClearValue.calloc(2, stack);
                // Dark brown matches MainMenuScene.init's glClearColor (0.08, 0.06, 0.04).
                mClear.get(0).color().float32(0, 0.08f).float32(1, 0.06f).float32(2, 0.04f).float32(3, 1.0f);
                mClear.get(1).depthStencil().set(1.0f, 0);
                VkRenderPassBeginInfo mBegin = VkRenderPassBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                        .renderPass(swap.renderPass())
                        .framebuffer(swap.framebuffer(imageIndex))
                        .pClearValues(mClear);
                mBegin.renderArea().offset().set(0, 0);
                mBegin.renderArea().extent().set(swap.extentW(), swap.extentH());
                vkCmdBeginRenderPass(cb, mBegin, VK_SUBPASS_CONTENTS_INLINE);
                setViewportAndScissor(cb, stack, 0, 0,
                        swap.extentW(), swap.extentH(),
                        swap.extentW(), swap.extentH());
                imGuiVk.renderDrawData(cb);
                vkCmdEndRenderPass(cb);
                check(vkEndCommandBuffer(cb), "vkEndCommandBuffer");
                submitAndPresent(cb, imageIndex);
                currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
                return;
            }

            // Update sun direction from the level's "Sun" entity (if any).
            // Same convention as LevelEditorScene.java:322-326: the sun
            // entity's world position is treated as a direction vector;
            // we normalize it. Lighting and the shadow camera share this.
            refreshSunDirection();

            // ============== PASS 1: SHADOW DEPTH MAP ==============
            // Dynamic frustum-centered + texel-snapped directional shadow.
            // Everything below is derived from the single Config.SHADOW_DISTANCE
            // coverage control (the UI exposes only that). The box tracks the
            // camera, so "ortho size" no longer has to be cranked up to reach
            // the far world — and texel snapping stops the shadow edges from
            // shimmering as the camera moves (the main "cheap-looking" artifact
            // a fixed, unsnapped ortho box has). NOTE: this is a single-cascade
            // solution; true distance + crispness at once is CSM, deferred
            // because it requires turning lightSpace into a per-cascade UBO
            // array across every pipeline + the 240B push-constant ABI.
            float shadowDist = Config.SHADOW_DISTANCE;
            float orthoHalf  = shadowDist * 0.5f;            // box covers shadowDist²
            float camHeight  = shadowDist * 0.85f;           // pull-back ≈ old 100 @120
            float shadowFar  = shadowDist * 2.5f;            // far ≈ old 300 @120

            float sunPitch = (float) Math.toDegrees(Math.asin(sunDirY));
            float sunYaw   = (float) Math.toDegrees(Math.atan2(sunDirX, sunDirZ));

            // Light orientation only (no translation) — the basis we snap in.
            Matrix4f lightRot = new Matrix4f()
                    .rotateX((float) Math.toRadians(sunPitch))
                    .rotateY((float) Math.toRadians(-sunYaw));

            // Box centre = camera pulled back along the sun so the near plane
            // clears tall geometry (dynamic frustum centering).
            float centerX = camX + sunDirX * camHeight;
            float centerY = camY + sunDirY * camHeight;
            float centerZ = camZ + sunDirZ * camHeight;

            // Texel snap: round the centre to the shadow-map texel grid along
            // the light's screen axes, so sub-texel camera motion no longer
            // walks the depth samples → stable, non-shimmering shadow edges.
            float texelWorld = (2f * orthoHalf) / Config.SHADOW_MAP_WIDTH;
            Vector3f cLS = lightRot.transformPosition(centerX, centerY, centerZ, new Vector3f());
            cLS.x = (float) (Math.round(cLS.x / texelWorld) * texelWorld);
            cLS.y = (float) (Math.round(cLS.y / texelWorld) * texelWorld);
            // Pure-rotation inverse = transpose; bring the snapped centre back
            // to world space to build the view translation.
            Vector3f cWS = new Matrix4f(lightRot).transpose()
                    .transformPosition(cLS.x, cLS.y, cLS.z, new Vector3f());

            Matrix4f lightView = new Matrix4f(lightRot)
                    .translate(-cWS.x, -cWS.y, -cWS.z);
            Matrix4f lightProj = new Matrix4f().ortho(
                    -orthoHalf, orthoHalf, -orthoHalf, orthoHalf,
                    Config.SHADOW_NEAR, shadowFar, true);  // zZeroToOne
            Matrix4f lightSpace = new Matrix4f(lightProj).mul(lightView);

            VkClearValue.Buffer shadowClear = VkClearValue.calloc(1, stack);
            shadowClear.get(0).depthStencil().set(1.0f, 0);
            VkRenderPassBeginInfo shadowBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(shadowMap.renderPass())
                    .framebuffer(shadowMap.framebuffer())
                    .pClearValues(shadowClear);
            shadowBegin.renderArea().offset().set(0, 0);
            shadowBegin.renderArea().extent().set(shadowMap.width(), shadowMap.height());
            vkCmdBeginRenderPass(cb, shadowBegin, VK_SUBPASS_CONTENTS_INLINE);

            // Positive-height viewport — we don't y-flip here (see main-pass viewport).
            setViewportAndScissor(cb, stack, 0, 0,
                    shadowMap.width(), shadowMap.height(),
                    shadowMap.width(), shadowMap.height());

            // Static shadow pass — every non-skinned entity.
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, shadowPipeline);
            long shadowBoundSet = 0;
            for (GameObject g : entities) {
                MeshRenderer mr = g.getComponent(MeshRenderer.class);
                if (mr == null || isMarker(mr) || hiddenAtRuntime(g)) continue;
                VkMesh mesh = meshFor(mr);
                if (mesh == null) continue;
                if (mesh.isSkinned()) continue;     // handled in the skinned shadow loop below

                long ds = descriptorSetFor(mr);
                if (ds == 0L) continue;
                if (ds != shadowBoundSet) {
                    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            shadowPipelineLayout, 0, stack.longs(ds), null);
                    shadowBoundSet = ds;
                }
                float isFoliage = mr.isFoliage ? 1.0f : 0.0f;
                recordShadowDraw(cb, mesh, modelMatrixFromTransform(g.transform),
                        lightSpace, isFoliage);
            }

            // Skinned shadow pass — bone-deformed depth. Per character:
            // bind material set (0) + bone set (1), bind static + skinning
            // vertex buffers, push the shadow PC, draw.
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, skinnedShadowPipeline);
            shadowBoundSet = 0;
            for (GameObject g : entities) {
                MeshRenderer mr = g.getComponent(MeshRenderer.class);
                if (mr == null || isMarker(mr) || hiddenAtRuntime(g)) continue;
                VkMesh mesh = meshFor(mr);
                if (mesh == null || !mesh.isSkinned()) continue;

                long ds = descriptorSetFor(mr);
                if (ds == 0L) continue;
                if (ds != shadowBoundSet) {
                    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            skinnedShadowPipelineLayout, 0, stack.longs(ds), null);
                    shadowBoundSet = ds;
                }
                Animator a = g.getComponent(Animator.class);
                long boneSet = (a != null && a.finalBoneMatrices != null)
                        ? skinnedRuntime.updateAndGetSet(a)
                        : identityBoneSet();
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        skinnedShadowPipelineLayout, 1, stack.longs(boneSet), null);

                mesh.bindWithSkinning(cb);
                float isFoliage = mr.isFoliage ? 1.0f : 0.0f;
                recordSkinnedShadowDraw(cb, mesh, modelMatrixFromTransform(g.transform),
                        lightSpace, isFoliage);
            }
            vkCmdEndRenderPass(cb);

            // ============== PASS 2: SCENE COLOR PASS ==============
            // Editor mode renders into the offscreen target (sceneTarget)
            // so the Scene Viewport panel can sample it. Play mode renders
            // directly to the swapchain — no ImGui composite pass needed
            // afterwards, the scene fills the whole window.
            boolean useOffscreen = isEditorMode;
            long   sceneRP = useOffscreen ? sceneTarget.renderPass() : swap.renderPass();
            long   sceneFB = useOffscreen ? sceneTarget.framebuffer() : swap.framebuffer(imageIndex);
            int    sceneW  = useOffscreen ? sceneTarget.width()  : swap.extentW();
            int    sceneH  = useOffscreen ? sceneTarget.height() : swap.extentH();

            VkClearValue.Buffer clear = VkClearValue.calloc(2, stack);
            clear.get(0).color()
                    .float32(0, CLEAR_R).float32(1, CLEAR_G).float32(2, CLEAR_B).float32(3, 1.0f);
            clear.get(1).depthStencil().set(1.0f, 0);
            VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(sceneRP)
                    .framebuffer(sceneFB)
                    .pClearValues(clear);
            rpBegin.renderArea().offset().set(0, 0);
            rpBegin.renderArea().extent().set(sceneW, sceneH);
            vkCmdBeginRenderPass(cb, rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            // Negative-height viewport flips Y for Vulkan's down-Y NDC,
            // restoring GL-style up-is-up so the rest of the math matches.
            setViewportAndScissor(cb, stack, 0, sceneH,
                    sceneW, -sceneH,
                    sceneW,  sceneH);

            // Camera matrices once per frame.
            float aspect = sceneW / (float) sceneH;
            // FOV from player.currentFov (matches LevelEditorScene line
            // 1416). Picking MUST use the same value (Config.FOV alone is
            // wrong — Player ramps it to 90 when running, 100 when sliding).
            Matrix4f proj = new Matrix4f().setPerspective(
                    (float) Math.toRadians(player.currentFov), aspect, 0.1f, 200.0f, true);
            // View rotation order matches LevelEditorScene.java:441-444:
            //   rotateZ(roll) · rotateX(pitch) · rotateY(yaw)
            // The Z (roll) comes FIRST so the slide tilt / wall-run lean
            // is screen-space (camera rolls around the look axis).
            Matrix4f viewRotOnly = new Matrix4f()
                    .rotateZ((float) Math.toRadians(camRoll))
                    .rotateX((float) Math.toRadians(camPitch))
                    .rotateY((float) Math.toRadians(camYaw));
            Matrix4f view = new Matrix4f(viewRotOnly).translate(-camX, -camY, -camZ);
            Matrix4f viewProj = new Matrix4f(proj).mul(view);

            // ---- Skybox: scale 100 so the cube fills the visible frustum.
            // Cubemap sampling only cares about direction so the scale is arbitrary.
            Matrix4f skyMvp = new Matrix4f(proj).mul(viewRotOnly).scale(100f);
            FloatBuffer skyPC = stack.mallocFloat(16);
            skyMvp.get(skyPC);
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, skyboxPipeline);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    skyboxPipelineLayout, 0, stack.longs(skyboxDescriptorSet), null);
            skyboxMesh.bind(cb);
            vkCmdPushConstants(cb, skyboxPipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, skyPC);
            vkCmdDrawIndexed(cb, skyboxMesh.indexCount(), 1, 0, 0, 0);

            // ---- Collect procedural cubes for the instanced batch (per-face
            // colour support) — these get drawn AFTER the per-entity loop
            // via one draw call per (material, tiling, foliage) group.
            cubeBatch.clear();
            for (GameObject g : entities) {
                MeshRenderer mr = g.getComponent(MeshRenderer.class);
                if (mr == null || isMarker(mr) || hiddenAtRuntime(g)) continue;
                if (!isInstanceableCube(mr)) continue;
                long ds = descriptorSetFor(mr);
                if (ds == 0L) continue;
                cubeBatch.submit(g, mr, ds);
            }

            // ---- Bind model pipeline, then draw every NON-cube,
            // NON-skinned entity. Skinned characters go through the
            // skinned pipeline below — different vertex layout + a
            // second descriptor set holding bone matrices.
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, modelPipeline);
            long   boundDescriptorSet = 0;
            VkMesh boundMesh          = null;
            for (GameObject g : entities) {
                MeshRenderer mr = g.getComponent(MeshRenderer.class);
                if (mr == null || isMarker(mr) || hiddenAtRuntime(g)) continue;
                if (isInstanceableCube(mr)) continue;  // handled by cubeBatch
                VkMesh mesh = meshFor(mr);
                if (mesh == null) continue;
                if (mesh.isSkinned()) continue;        // handled in the skinned loop below

                long ds = descriptorSetFor(mr);
                if (ds == 0L) continue;   // texture failed to load — skip silently
                if (ds != boundDescriptorSet) {
                    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            modelPipelineLayout, 0, stack.longs(ds), null);
                    boundDescriptorSet = ds;
                }
                if (mesh != boundMesh) {
                    mesh.bind(cb);
                    boundMesh = mesh;
                }

                boolean triplanar = Boolean.TRUE.equals(mr.material.useWorldSpaceTiling);
                // Sun cube renders as a flat-yellow marker, matching the
                // editor's LineRenderer3D.drawMesh(cube, 1,1,0) at
                // LevelEditorScene.java:544. Everything else uses the
                // entity's authored Material RGB tint.
                float tr, tg, tb;
                if ("Sun".equals(g.name)) {
                    tr = 1f; tg = 1f; tb = 0f;
                } else {
                    tr = mr.material.r; tg = mr.material.g; tb = mr.material.b;
                }
                recordModelDraw(cb, mesh, modelMatrixFromTransform(g.transform),
                        viewProj, lightSpace,
                        triplanar ? 1.0f : 0.0f,
                        mr.material.textureScale,
                        tr, tg, tb);
            }

            // ---- Skinned characters — sub-phase G. Bind the skinned
            // pipeline once, then per character: bind material set (set 0),
            // upload + bind bone-matrix set (set 1), bind mesh + skinning
            // vertex buffer, push the model PC, draw.
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, skinnedModelPipeline);
            boundDescriptorSet = 0;
            boundMesh = null;
            for (GameObject g : entities) {
                MeshRenderer mr = g.getComponent(MeshRenderer.class);
                if (mr == null || isMarker(mr) || hiddenAtRuntime(g)) continue;
                VkMesh mesh = meshFor(mr);
                if (mesh == null || !mesh.isSkinned()) continue;

                long ds = descriptorSetFor(mr);
                if (ds == 0L) continue;
                if (ds != boundDescriptorSet) {
                    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            skinnedModelPipelineLayout, 0, stack.longs(ds), null);
                    boundDescriptorSet = ds;
                }

                // Upload this entity's current bone matrices and bind the
                // resulting descriptor set at slot 1. If the entity has no
                // Animator yet (T-pose), we still need *some* bone set
                // bound — fall back to a transient identity Animator.
                Animator a = g.getComponent(Animator.class);
                long boneSet = (a != null && a.finalBoneMatrices != null)
                        ? skinnedRuntime.updateAndGetSet(a)
                        : identityBoneSet();
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        skinnedModelPipelineLayout, 1, stack.longs(boneSet), null);

                if (mesh != boundMesh) {
                    mesh.bindWithSkinning(cb);
                    boundMesh = mesh;
                }

                boolean triplanar = Boolean.TRUE.equals(mr.material.useWorldSpaceTiling);
                float tr = mr.material.r, tg = mr.material.g, tb = mr.material.b;
                recordSkinnedDraw(cb, mesh, modelMatrixFromTransform(g.transform),
                        viewProj, lightSpace,
                        triplanar ? 1.0f : 0.0f,
                        mr.material.textureScale,
                        tr, tg, tb);
            }

            // ---- Sun cube — drawn as solid yellow via the line renderer's
            // flat-color mesh path. Matches LevelEditorScene.java:544
            // (LineRenderer3D.drawMesh with RGB (1,1,0), no texture). The
            // cube batch deliberately skips Sun for this reason.
            for (GameObject g : entities) {
                if (!"Sun".equals(g.name)) continue;
                Matrix4f sunModel = modelMatrixFromTransform(g.transform);
                Matrix4f sunMvp = new Matrix4f(viewProj).mul(sunModel);
                lineRenderer.drawMesh(cb, ensureCubeMesh(), sunMvp, 1f, 1f, 0f);
                break;
            }

            // ---- Cube batch — one draw call per material group.
            if (cubeBatch.instanceCount() > 0) {
                vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, cubePipeline);
                // Bind the cube mesh (binding 0). cubeBatch binds binding 1
                // (the per-instance buffer) and pushes per-group state.
                VkMesh cubeMesh = ensureCubeMesh();
                cubeMesh.bind(cb);
                // Push per-frame data once (viewProj + lightSpace + sunDir).
                try (MemoryStack stk = MemoryStack.stackPush()) {
                    FloatBuffer pcF = stk.mallocFloat(CUBE_PC_FLOATS);
                    // material slot (per-group) gets filled by cubeBatch.flush
                    // before each group draw, so leave it zeroed for now.
                    pcF.put(0, 0f); pcF.put(1, 1f); pcF.put(2, 0f); pcF.put(3, 0f);
                    viewProj.get(4, pcF);
                    lightSpace.get(20, pcF);
                    pcF.put(36, sunDirX); pcF.put(37, sunDirY); pcF.put(38, sunDirZ); pcF.put(39, sunIntensity);
                    vkCmdPushConstants(cb, cubePipelineLayout,
                            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                            0, pcF);
                }
                cubeBatch.flush(cb, cubePipelineLayout);
            }

            // ---- Marker billboards — drawn LAST (after cube batch) so
            // they paint over any geometry in the scene. The pipeline
            // has depth test OFF + depth write OFF, so the sprite is
            // always visible regardless of what's between the camera
            // and it. The billboard matrix translates the unit quad to
            // the entity and cancels the view rotation; it does NOT
            // apply the entity's scale, so the sprite stays visually
            // 1m × 1m no matter how big the selectable hull is.
            // Visible in editor mode AND play+fly.
            if (isEditorMode || player.isFlying) {
                vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, markerPipeline);
                long mBoundSet = 0;
                for (GameObject g : entities) {
                    MeshRenderer mr = g.getComponent(MeshRenderer.class);
                    if (mr == null || !isMarker(mr)) continue;
                    VkMesh quad = meshFor(mr);
                    if (quad == null) continue;
                    long ds = descriptorSetFor(mr);
                    if (ds == 0L) continue;
                    if (ds != mBoundSet) {
                        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                markerPipelineLayout, 0, stack.longs(ds), null);
                        mBoundSet = ds;
                    }
                    quad.bind(cb);

                    org.joml.Matrix3f vRot3 = new org.joml.Matrix3f();
                    view.get3x3(vRot3);
                    vRot3.transpose();
                    Matrix4f model = new Matrix4f()
                            .translation(g.transform.x, g.transform.y, g.transform.z)
                            .mul(new Matrix4f().set(vRot3));
                    recordMarkerDraw(cb, quad, model, viewProj, lightSpace,
                            mr.material.r, mr.material.g, mr.material.b);
                }

                // Character Collision capsule (editor + play-fly, same
                // visibility as the markers). Green wireframe — this IS
                // the collider physics uses, so what you see is exactly
                // what collides. Centred on the entity's transform so
                // the gizmo sits at the capsule's middle.
                for (GameObject g : entities) {
                    MeshRenderer mr = g.getComponent(MeshRenderer.class);
                    if (mr == null || !World.PLAYER_BODY_KEY.equals(mr.modelPath)) continue;
                    float capH = Math.max(0.5f, g.transform.scaleY);
                    drawCapsuleWireframe(cb, viewProj,
                            g.transform.x, g.transform.y - capH * 0.5f, g.transform.z,
                            Math.max(0.1f, g.transform.scaleX * 0.5f), capH,
                            0.3f, 0.85f, 0.4f);
                }

                // Phase 5B — mirror the Character Editor's debug toggles onto
                // the active-player avatar in the main Scene Viewport (editor
                // only). Same visualizers (green capsule + skeleton) as PASS 2b.
                if (isEditorMode && activePlayerEntity != null) {
                    Transform at = activePlayerEntity.transform;
                    if (characterEditor.isShowCollision()) {
                        CharacterLocomotionComponent loco =
                                activePlayerEntity.getComponent(CharacterLocomotionComponent.class);
                        if (loco != null) {
                            float[] caps = buildCapsuleLines(at.x, at.y + loco.collisionOffsetY, at.z,
                                    loco.collisionRadius, loco.collisionHalfHeight * 2f);
                            lineRenderer.drawLines(cb, caps, viewProj, 0.2f, 1.0f, 0.4f);
                        }
                    }
                    if (characterEditor.isShowBones()) {
                        MeshRenderer amr = activePlayerEntity.getComponent(MeshRenderer.class);
                        VkMesh amesh = (amr != null) ? meshCache.get(meshKey(amr)) : null;
                        if (amr != null && amesh != null && amesh.isSkinned() && amr.skeleton() != null) {
                            Animator a = activePlayerEntity.getComponent(Animator.class);
                            Matrix4f[] dbt = (a != null && a.debugBoneTransforms != null)
                                    ? a.debugBoneTransforms : null;
                            float[][] lines = amr.skeleton().gatherDebugLines(dbt);
                            Matrix4f boneMvp = new Matrix4f(viewProj).mul(modelMatrixFromTransform(at));
                            if (lines[0].length > 0) lineRenderer.drawLines(cb, lines[0], boneMvp, 1.0f, 0.85f, 0.2f);
                            if (lines[1].length > 0) lineRenderer.drawLines(cb, lines[1], boneMvp, 0.3f, 0.6f, 1.0f);
                        }
                    }
                }
            }

            // Draw order matters: SELECTION first, HOVER second.
            // Both outline passes share the same stencil ref value (1),
            // so the entity drawn second's mark pollutes the first's
            // outline test. By drawing selection first, the selection's
            // outline emits its colour pixels BEFORE the hover mark
            // contaminates the stencil. The hover then traces its own
            // edges over what's left. Without this swap, hovering the
            // floor (huge silhouette) would mark stencil=1 across the
            // entire screen below the horizon and suppress the
            // selection outline of whatever's standing on the floor.

            // ---- Selection outline (bright orange stencil silhouette)
            // + gizmo. Markers get a unit-cube hull wireframe in orange
            // (matches their hover wireframe — they share the same
            // selection volume); Sun + meshes-not-loaded fall back to
            // the AABB renderer.
            if (isEditorMode && selectedEntity != null) {
                MeshRenderer selMr = selectedEntity.getComponent(MeshRenderer.class);
                VkMesh selMesh = (selMr != null) ? meshCache.get(meshKey(selMr)) : null;
                if (selMr != null && isMarker(selMr)) {
                    drawMarkerHull(cb, viewProj, selectedEntity, 1.0f, 0.55f, 0f);
                } else if (selMesh != null && selMr != null) {
                    outlineRenderer.drawOutline(cb, viewProj, selectedEntity, selMesh,
                            1.0f, 0.5f, 0f, 0.005f, descriptorSetFor(selMr), selMr.isFoliage);
                } else {
                    selectionRenderer.drawSelection(cb, viewProj, selectedEntity, selMesh);
                }
                boolean isSun = "Sun".equals(selectedEntity.name);
                // CTRL in Scale mode switches the handles to the one-sided
                // "extrude" visual (cross-plate tips).
                boolean extrudeScale = selectedEntity.transformMode == 2 && ctrlHeld();
                gizmo.render(cb, viewProj, selectedEntity, isSun,
                        draggingAxis, isHoveringX, isHoveringY, isHoveringZ, isHoveringCenter,
                        extrudeScale, gizmoHoverAxis);

                // ---- Skeleton bone debug overlay (port of
                // LevelEditorScene.java:600-611). Green/red bone segments +
                // blue node segments, drawn through the line renderer.
                if (showBones.get() && selMesh != null && selMesh.isSkinned()
                        && selMesh.skeleton() != null) {
                    Animator animComp = selectedEntity.getComponent(Animator.class);
                    boolean animated = animComp != null && animComp.currentAnimation != null
                            && animComp.debugBoneTransforms != null;
                    float[][] lines = selMesh.skeleton().gatherDebugLines(
                            animated ? animComp.debugBoneTransforms : null);
                    Matrix4f boneMvp = new Matrix4f(viewProj)
                            .translate(selectedEntity.transform.x, selectedEntity.transform.y, selectedEntity.transform.z)
                            .rotateX((float) Math.toRadians(selectedEntity.transform.rotX))
                            .rotateY((float) Math.toRadians(selectedEntity.transform.rotY))
                            .rotateZ((float) Math.toRadians(selectedEntity.transform.rotZ))
                            .scale(selectedEntity.transform.scaleX, selectedEntity.transform.scaleY, selectedEntity.transform.scaleZ);
                    if (lines[0].length > 0) {
                        if (animated) lineRenderer.drawLines(cb, lines[0], boneMvp, 1.0f, 0.2f, 0.2f);
                        else          lineRenderer.drawLines(cb, lines[0], boneMvp, 0.2f, 1.0f, 0.2f);
                    }
                    if (lines[1].length > 0) lineRenderer.drawLines(cb, lines[1], boneMvp, 0.2f, 0.6f, 0.8f);
                }
            }
            // ---- Hover highlight (dim orange stencil silhouette).
            // Drawn AFTER selection so its mark can't suppress selection's
            // outline. Skipped when hover == selection (selection already
            // wins). Markers get the unit-cube hull in dim orange.
            // Also fires in play+fly so the player can see what LMB will
            // delete / RMB will place against. Markers are editor-only
            // (their wireframe is editor-only too — skipped in play).
            // Show the dim orange silhouette in editor mode (any cursor-
            // over-viewport hit) and in play mode (whatever the crosshair
            // ray hit). Markers are editor-only — handled in the inner
            // branch below.
            boolean showHover = highlighted != null && highlighted != selectedEntity;
            if (showHover) {
                MeshRenderer hMr = highlighted.getComponent(MeshRenderer.class);
                VkMesh hMesh = (hMr != null) ? meshCache.get(meshKey(hMr)) : null;
                if (hMr != null && isMarker(hMr)) {
                    // Editor or play+fly — same modes where markers
                    // render at all. In pure play mode (no fly), markers
                    // are invisible so we skip the wireframe too.
                    if (isEditorMode || player.isFlying) {
                        drawMarkerHull(cb, viewProj, highlighted, 0.55f, 0.30f, 0.05f);
                    }
                } else if (hMesh != null && hMr != null) {
                    outlineRenderer.drawOutline(cb, viewProj, highlighted, hMesh,
                            0.4f, 0.2f, 0f, 0.003f, descriptorSetFor(hMr), hMr.isFoliage);
                } else {
                    selectionRenderer.drawHighlight(cb, viewProj, highlighted, hMesh);
                }
            }

            // ---- Multi-selected entities (box-select result). Same orange
            // silhouette as the primary selection. Drawn last so the
            // currently-active selection still pops above the rest.
            if (isEditorMode && !selectedObjects.isEmpty()) {
                for (GameObject g : selectedObjects) {
                    if (g == selectedEntity) continue;
                    MeshRenderer mr = g.getComponent(MeshRenderer.class);
                    VkMesh m = (mr != null) ? meshCache.get(meshKey(mr)) : null;
                    if (mr != null && isMarker(mr)) {
                        drawMarkerHull(cb, viewProj, g, 0.9f, 0.45f, 0f);
                    } else if (m != null && mr != null) {
                        outlineRenderer.drawOutline(cb, viewProj, g, m,
                                0.9f, 0.45f, 0f, 0.004f, descriptorSetFor(mr), mr.isFoliage);
                    } else {
                        selectionRenderer.drawHighlight(cb, viewProj, g, m);
                    }
                }
            }

            // ---- 2D overlay (sub-phase H): stamina bar in play mode +
            // box-select rectangle in editor mode. Both go through
            // VkRenderer2D against the active scene render pass.
            renderTwoDOverlay(cb, sceneRP, sceneW, sceneH);

            vkCmdEndRenderPass(cb);

            // ============== PASS 2b: CHARACTER EDITOR (editor only) ==============
            // Render into the dedicated character offscreen target. Phase 1
            // only clears it to a dark blueprint grey so the panel shows a
            // blank preview; models / posing land in later phases. The
            // target's render-pass dependencies make this write visible to the
            // ImGui composite that samples it in PASS 3.
            if (useOffscreen && characterEditor.isOpen()) {
                VkClearValue.Buffer charClear = VkClearValue.calloc(2, stack);
                charClear.get(0).color()
                        .float32(0, CHAR_CLEAR_R).float32(1, CHAR_CLEAR_G).float32(2, CHAR_CLEAR_B).float32(3, 1.0f);
                charClear.get(1).depthStencil().set(1.0f, 0);
                VkRenderPassBeginInfo charBegin = VkRenderPassBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                        .renderPass(characterTarget.renderPass())
                        .framebuffer(characterTarget.framebuffer())
                        .pClearValues(charClear);
                charBegin.renderArea().offset().set(0, 0);
                charBegin.renderArea().extent().set(characterTarget.width(), characterTarget.height());
                vkCmdBeginRenderPass(cb, charBegin, VK_SUBPASS_CONTENTS_INLINE);
                // Phase 2: draw the isolated preview character (if one was
                // dropped in). Reuses the current frame's shadow lightSpace.
                recordCharacterPreview(cb, stack, lightSpace);
                vkCmdEndRenderPass(cb);
            }

            // ============== PASS 2c: GAME VIEWPORT (editor only) ==============
            // Render the world from the active player's camera into gameTarget —
            // NO gizmos / lines / outlines / debug, and the player's own mesh
            // skipped (first-person). Sampled by the "Game" ImGui panel.
            if (useOffscreen && showGame.get() && activePlayerEntity != null
                    && GameStateManager.isPlaying()) {
                VkClearValue.Buffer gClear = VkClearValue.calloc(2, stack);
                gClear.get(0).color()
                        .float32(0, CLEAR_R).float32(1, CLEAR_G).float32(2, CLEAR_B).float32(3, 1.0f);
                gClear.get(1).depthStencil().set(1.0f, 0);
                VkRenderPassBeginInfo gBegin = VkRenderPassBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                        .renderPass(gameTarget.renderPass())
                        .framebuffer(gameTarget.framebuffer())
                        .pClearValues(gClear);
                gBegin.renderArea().offset().set(0, 0);
                gBegin.renderArea().extent().set(gameTarget.width(), gameTarget.height());
                vkCmdBeginRenderPass(cb, gBegin, VK_SUBPASS_CONTENTS_INLINE);
                recordGamePass(cb, stack, lightSpace);
                vkCmdEndRenderPass(cb);
            }

            // ============== PASS 3: SWAPCHAIN COMPOSITE (editor only) ==============
            // In editor mode, the scene went into the offscreen target; we
            // need a swap pass to clear + composite the ImGui panels
            // (including the Scene Viewport panel which samples sceneTarget).
            // In play mode the scene went straight to swap already, so this
            // pass is skipped.
            if (useOffscreen) {
                VkClearValue.Buffer swapClear = VkClearValue.calloc(2, stack);
                swapClear.get(0).color().float32(0, 0.10f).float32(1, 0.10f).float32(2, 0.10f).float32(3, 1.0f);
                swapClear.get(1).depthStencil().set(1.0f, 0);
                VkRenderPassBeginInfo swapBegin = VkRenderPassBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                        .renderPass(swap.renderPass())
                        .framebuffer(swap.framebuffer(imageIndex))
                        .pClearValues(swapClear);
                swapBegin.renderArea().offset().set(0, 0);
                swapBegin.renderArea().extent().set(swap.extentW(), swap.extentH());
                vkCmdBeginRenderPass(cb, swapBegin, VK_SUBPASS_CONTENTS_INLINE);

                // Full-window viewport (positive height — ImGui drives its own
                // ortho via push constants, no flip needed for UI rendering).
                setViewportAndScissor(cb, stack, 0, 0,
                        swap.extentW(), swap.extentH(),
                        swap.extentW(), swap.extentH());

                imGuiVk.renderDrawData(cb);

                vkCmdEndRenderPass(cb);
            }
            check(vkEndCommandBuffer(cb), "vkEndCommandBuffer");

            submitAndPresent(cb, imageIndex);
        }
        currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
    }

    /** Submit the recorded command buffer and present its image. The
     *  signaled renderFinished semaphore is keyed off the ACQUIRED image
     *  index, not the in-flight slot — see VkSwapchain javadoc. */
    /** Tear down and rebuild the main swapchain after a resize / display-mode
     *  change. The scene renders to an offscreen target, so the swapchain only
     *  feeds the final ImGui composite; its render pass keeps the same colour
     *  format, so every pipeline built against it stays render-pass-compatible
     *  and needs no rebuild. Waits for the GPU to go idle first (CRITICAL —
     *  destroying in-flight swapchain images trips the validation layers). */
    private void recreateSwapchain() {
        // Pause while minimized (0×0 framebuffer); resume when restored.
        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(glfwWindow, w, h);
        while ((w[0] == 0 || h[0] == 0) && !glfwWindowShouldClose(glfwWindow)) {
            glfwWaitEvents();
            glfwGetFramebufferSize(glfwWindow, w, h);
        }
        vkDeviceWaitIdle(ctx.device());
        swap.destroy();
        swap = new VkSwapchain(ctx, glfwWindow, MAX_FRAMES_IN_FLIGHT);
        currentFrame = 0;
        framebufferResized = false;
        Logger.info("[VK] Swapchain recreated (" + swap.extentW() + "x" + swap.extentH() + ")");
    }

    /** Persist editor panel open/closed flags so a panel the user closed stays
     *  closed across restarts (imgui.ini saves geometry, not the app-owned
     *  open booleans). Lightweight JSON map, written on shutdown. */
    private void saveEditorPrefs() {
        java.util.Map<String, Boolean> m = new java.util.LinkedHashMap<>();
        m.put("showHierarchy", showHierarchy.get());
        m.put("showInspector", showInspector.get());
        m.put("showAssetBrowser", showAssetBrowser.get());
        m.put("showGameplayObjects", showGameplayObjects.get());
        m.put("showEnvironment", showEnvironment.get());
        m.put("showProfiler", showProfiler.get());
        m.put("showDebugHud", showDebugHud.get());
        try (java.io.Writer wr = new java.io.FileWriter(EDITOR_PREFS_PATH)) {
            new com.google.gson.Gson().toJson(m, wr);
        } catch (Exception e) {
            Logger.warn("[VK] Could not save editor prefs: " + e.getMessage());
        }
    }

    /** Load the persisted panel flags (if present) so the ImBoolean defaults
     *  match the user's last session instead of always booting "open". */
    private void loadEditorPrefs() {
        java.io.File f = new java.io.File(EDITOR_PREFS_PATH);
        if (!f.exists()) return;
        try (java.io.Reader rd = new java.io.FileReader(f)) {
            java.lang.reflect.Type t =
                    new com.google.gson.reflect.TypeToken<java.util.Map<String, Boolean>>() {}.getType();
            java.util.Map<String, Boolean> m = new com.google.gson.Gson().fromJson(rd, t);
            if (m == null) return;
            applyPref(m, "showHierarchy", showHierarchy);
            applyPref(m, "showInspector", showInspector);
            applyPref(m, "showAssetBrowser", showAssetBrowser);
            applyPref(m, "showGameplayObjects", showGameplayObjects);
            applyPref(m, "showEnvironment", showEnvironment);
            applyPref(m, "showProfiler", showProfiler);
            applyPref(m, "showDebugHud", showDebugHud);
        } catch (Exception e) {
            Logger.warn("[VK] Could not load editor prefs: " + e.getMessage());
        }
    }

    private static void applyPref(java.util.Map<String, Boolean> m, String key,
                                  imgui.type.ImBoolean target) {
        Boolean v = m.get(key);
        if (v != null) target.set(v);
    }

    /** MAIN frame submit + present — strictly isolated sync: waits ONLY the
     *  main swapchain's imageAvailable, signals ONLY its renderFinished, and
     *  uses ONLY the main inFlight fence. Detached viewports are submitted +
     *  presented independently (each with its OWN fence) by the platform pump
     *  in mainLoop, AFTER this returns. */
    private void submitAndPresent(VkCommandBuffer cb, int imageIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(swap.imageAvailable(currentFrame)))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(cb.address()))
                    .pSignalSemaphores(stack.longs(swap.renderFinished(imageIndex)));
            check(vkQueueSubmit(ctx.graphicsQueue(), submit, swap.inFlight(currentFrame)),
                    "vkQueueSubmit");

            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(swap.renderFinished(imageIndex)))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swap.handle()))
                    .pImageIndices(stack.ints(imageIndex));
            int presentRes = vkQueuePresentKHR(ctx.presentQueue(), present);
            if (presentRes == VK_ERROR_OUT_OF_DATE_KHR || presentRes == VK_SUBOPTIMAL_KHR
                    || framebufferResized) {
                recreateSwapchain();
            } else if (presentRes != VK_SUCCESS) {
                throw new RuntimeException("vkQueuePresentKHR: " + presentRes);
            }
        }
    }

    /** Set dynamic viewport + scissor in one shot. The viewport y+height pair
     *  can encode Vulkan's negative-height-viewport y-flip trick (used by the
     *  main pass) or be regular positive (used by the shadow pass). */
    private void setViewportAndScissor(VkCommandBuffer cb, MemoryStack stack,
                                       float vpX, float vpY, float vpW, float vpH,
                                       int scW, int scH) {
        VkViewport.Buffer vp = VkViewport.calloc(1, stack);
        vp.get(0).x(vpX).y(vpY).width(vpW).height(vpH).minDepth(0f).maxDepth(1f);
        vkCmdSetViewport(cb, 0, vp);
        VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
        sc.get(0).offset().set(0, 0);
        sc.get(0).extent().set(scW, scH);
        vkCmdSetScissor(cb, 0, sc);
    }

    // =============================================================
    //                          Cleanup
    // =============================================================

    private void cleanup() {
        Logger.info("[VK] Shutting down");
        // Phase 3A — close the IPC socket.
        if (netSync != null) netSync.close();
        // Kill any standalone game processes we spawned so closing the editor
        // closes its child game windows too.
        for (Process p : childProcesses) {
            if (p.isAlive()) p.destroy();
        }
        childProcesses.clear();
        // Persist editor panel open/closed state before tearing down.
        saveEditorPrefs();
        if (ctx != null && ctx.device() != null) {
            // CRITICAL: wait for the GPU before destroying anything.
            vkDeviceWaitIdle(ctx.device());

            // Pipelines.
            if (skyboxPipeline       != 0) vkDestroyPipeline(ctx.device(), skyboxPipeline, null);
            if (skyboxPipelineLayout != 0) vkDestroyPipelineLayout(ctx.device(), skyboxPipelineLayout, null);
            if (shadowPipeline       != 0) vkDestroyPipeline(ctx.device(), shadowPipeline, null);
            if (shadowPipelineLayout != 0) vkDestroyPipelineLayout(ctx.device(), shadowPipelineLayout, null);
            if (cubeBatch            != null) cubeBatch.destroy();
            if (renderer2D           != null) renderer2D.destroy();
            if (cubePipeline         != 0) vkDestroyPipeline(ctx.device(), cubePipeline, null);
            if (cubePipelineLayout   != 0) vkDestroyPipelineLayout(ctx.device(), cubePipelineLayout, null);
            if (markerPipeline             != 0) vkDestroyPipeline(ctx.device(), markerPipeline, null);
            if (markerPipelineLayout       != 0) vkDestroyPipelineLayout(ctx.device(), markerPipelineLayout, null);
            if (skinnedShadowPipeline       != 0) vkDestroyPipeline(ctx.device(), skinnedShadowPipeline, null);
            if (skinnedShadowPipelineLayout != 0) vkDestroyPipelineLayout(ctx.device(), skinnedShadowPipelineLayout, null);
            if (skinnedModelPipeline       != 0) vkDestroyPipeline(ctx.device(), skinnedModelPipeline, null);
            if (skinnedModelPipelineLayout != 0) vkDestroyPipelineLayout(ctx.device(), skinnedModelPipelineLayout, null);
            if (skinnedRuntime       != null) skinnedRuntime.destroy();
            if (modelPipeline        != 0) vkDestroyPipeline(ctx.device(), modelPipeline, null);
            if (modelPipelineLayout  != 0) vkDestroyPipelineLayout(ctx.device(), modelPipelineLayout, null);

            // Descriptor pools + layouts.
            if (skyboxDescriptorPool       != 0) vkDestroyDescriptorPool(ctx.device(), skyboxDescriptorPool, null);
            if (skyboxDescriptorSetLayout  != 0) vkDestroyDescriptorSetLayout(ctx.device(), skyboxDescriptorSetLayout, null);
            if (descriptorPool             != 0) vkDestroyDescriptorPool(ctx.device(), descriptorPool, null);
            if (descriptorSetLayout        != 0) vkDestroyDescriptorSetLayout(ctx.device(), descriptorSetLayout, null);

            // Scene assets.
            if (skyboxCubemap != null) skyboxCubemap.destroy(ctx.device());
            if (skyboxMesh    != null) skyboxMesh.destroy(ctx.device());
            // Level-driven cached meshes + textures. Texture cache may
            // overlap with embeddedTextureByModel (same VkTexture instance
            // reachable both ways) — de-dupe to avoid double-destroy.
            java.util.IdentityHashMap<VkTexture, Boolean> destroyedTex = new java.util.IdentityHashMap<>();
            for (VkTexture t : textureCache.values())
                if (t != null && !destroyedTex.containsKey(t)) { t.destroy(ctx.device()); destroyedTex.put(t, true); }
            for (VkTexture t : embeddedTextureByModel.values())
                if (t != null && !destroyedTex.containsKey(t)) { t.destroy(ctx.device()); destroyedTex.put(t, true); }
            for (VkMesh m : meshCache.values()) if (m != null) m.destroy(ctx.device());

            // ImGui Vulkan backend. Tear down the multi-viewport renderer
            // FIRST (it waits idle, then frees every PerViewport's swapchain /
            // surface / framebuffers / sync + the shared render pass) so no
            // secondary presentation resources outlive the device. Its map is
            // cleared, so any late Renderer_DestroyWindow callback fired by
            // imGuiGlfw.shutdown() / destroyContext() is a safe no-op.
            if (viewportRenderer != null) viewportRenderer.destroy();
            if (imGuiVk   != null) imGuiVk.destroy();
            // imGuiGlfw.shutdown() drives ImGui.destroyPlatformWindows(), which
            // invokes our Platform_DestroyWindow (glfwDestroyWindow of each
            // child) AFTER the renderer freed their surfaces above — correct
            // order. platformBackend.destroy() then mops up any leftover child
            // windows (defensive; never touches the main window).
            if (imGuiGlfw != null) imGuiGlfw.shutdown();
            ImGui.destroyContext();
            if (platformBackend != null) platformBackend.destroy();

            // Line renderer / gizmo / selection / outline (own their pipelines).
            if (outlineRenderer != null) outlineRenderer.destroy();
            if (lineRenderer != null) lineRenderer.destroy();
            // Offscreen Scene Viewport target.
            if (shadowMapImGuiView != 0)
                vkDestroyImageView(ctx.device(), shadowMapImGuiView, null);
            if (sceneTarget != null) sceneTarget.destroy();
            if (characterTarget != null) characterTarget.destroy();
            if (gameTarget != null) gameTarget.destroy();

            // Shadow map.
            if (shadowMap != null) shadowMap.destroy(ctx.device());

            // Swapchain bundle (framebuffers, render pass, depth, sync, swapchain).
            if (swap != null) swap.destroy();

            // Command buffers are freed when the pool is destroyed by ctx.destroy().
        }
        if (ctx != null) ctx.destroy();

        if (glfwWindow != NULL) {
            glfwDestroyWindow(glfwWindow);
            glfwWindow = NULL;
        }
        glfwTerminate();
        Logger.info("[VK] Goodbye");
    }

    private static float[] normalize3(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[] { x / len, y / len, z / len };
    }

    /** Project a 2D mouse delta onto the screen-space direction of a
     *  world axis. Ported verbatim from LevelEditorScene.axisScreenDelta
     *  (lines 937-956). Returns a signed scalar = how much the user
     *  dragged "along" the axis on screen, given the camera pose. */
    private float axisScreenDelta(int axis, double deltaX, double deltaY) {
        float yawRad   = (float) Math.toRadians(player.yaw);
        float pitchRad = (float) Math.toRadians(player.pitch);
        float cy = (float) Math.cos(yawRad),   sy = (float) Math.sin(yawRad);
        float cp = (float) Math.cos(pitchRad), sp = (float) Math.sin(pitchRad);
        float sxProj, syProj;
        if (axis == 0)      { sxProj = cy;       syProj = sy * sp; }     // world X
        else if (axis == 1) { sxProj = 0f;       syProj = cp; }          // world Y
        else                { sxProj = sy;       syProj = -cy * sp; }    // world Z
        return (float) deltaX * sxProj + (float) deltaY * syProj;
    }

    /** Generalises {@link #axisScreenDelta} to an arbitrary WORLD-space
     *  direction: projects the 2D mouse delta onto the screen-space direction
     *  of (dx,dy,dz). Used for LOCAL-axis scale dragging on rotated objects —
     *  the screen projection is linear in the direction, so this is just the
     *  per-axis projections of axisScreenDelta combined by (dx,dy,dz). */
    private float screenDeltaAlong(float dx, float dy, float dz, double deltaX, double deltaY) {
        float yawRad   = (float) Math.toRadians(player.yaw);
        float pitchRad = (float) Math.toRadians(player.pitch);
        float cy = (float) Math.cos(yawRad),   sy = (float) Math.sin(yawRad);
        float cp = (float) Math.cos(pitchRad), sp = (float) Math.sin(pitchRad);
        float sxProj = dx * cy + dz * sy;
        float syProj = dx * (sy * sp) + dy * cp + dz * (-cy * sp);
        return (float) deltaX * sxProj + (float) deltaY * syProj;
    }

    /** Add {@code drag} to the active axis's raw scale accumulator
     *  (clamp-aware) and return the ACTUALLY-applied delta (for the one-sided
     *  position shift). axis: 0=X, 1=Y, 2=Z. */
    private float applyScaleAxis(int axis, float drag) {
        if (axis == 0) { float old = rawScaleX; rawScaleX = Math.max(Config.GIZMO_SCALE_MIN, rawScaleX + drag); return rawScaleX - old; }
        if (axis == 1) { float old = rawScaleY; rawScaleY = Math.max(Config.GIZMO_SCALE_MIN, rawScaleY + drag); return rawScaleY - old; }
        float old = rawScaleZ; rawScaleZ = Math.max(Config.GIZMO_SCALE_MIN, rawScaleZ + drag); return rawScaleZ - old;
    }

    /** Free-move drag (Move mode + centre cube) — the entity follows the
     *  mouse cursor in 3D. Cast a ray from the camera through the cursor,
     *  find the intersection with a plane through the entity perpendicular
     *  to the camera-forward at the ORIGINAL camera-to-entity distance
     *  ({@code freeMoveDistance}, snapshotted on drag start). That gives
     *  the new world position. Holding Shift snaps each axis to 0.5 units
     *  so movement lands on a .000 / .500 grid. */
    private void freeMoveFollowMouse(double localX, double localY, boolean isShift) {
        if (selectedEntity == null) return;
        float[] ray = mouseRayFromViewport(localX, localY);
        float[] fwd = cameraLookDirection();
        // Ray-plane: t = (planeD - origin · n) / (ray · n)
        //   plane normal = fwd, plane point = camPos + fwd * freeMoveDistance.
        //   planeD = (camPos + fwd * D) · fwd = (camPos · fwd) + D.
        float denom = ray[0] * fwd[0] + ray[1] * fwd[1] + ray[2] * fwd[2];
        if (Math.abs(denom) < 1e-5f) return;  // ray ⊥ to camera plane → ignore
        float camDotFwd = camX * fwd[0] + camY * fwd[1] + camZ * fwd[2];
        float planeD    = camDotFwd + freeMoveDistance;
        float t = (planeD - camDotFwd) / denom;
        if (t <= 0) return;
        float wx = camX + ray[0] * t;
        float wy = camY + ray[1] * t;
        float wz = camZ + ray[2] * t;
        if (isShift) {
            // Snap each axis to the nearest 0.5 — values land on .000 or .500.
            wx = Math.round(wx * 2f) / 2f;
            wy = Math.round(wy * 2f) / 2f;
            wz = Math.round(wz * 2f) / 2f;
        }
        selectedEntity.transform.x = wx;
        selectedEntity.transform.y = wy;
        selectedEntity.transform.z = wz;
        rawDragX = wx; rawDragY = wy; rawDragZ = wz;
    }

    /** Apply one frame of gizmo drag to {@code selectedEntity}'s transform.
     *  Ported verbatim from LevelEditorScene.updateTransformDrag (lines
     *  958-1018). Move (mode 0) uses Shift-snap to grid, rotate (mode 1)
     *  composes world-axis delta with object orientation via matrix
     *  multiplication, scale (mode 2) adds along the axis (or all three
     *  when the centre cube is dragged). */
    private void updateTransformDrag(double deltaY, double deltaX, float sensitivity, boolean isShift) {
        if (selectedEntity == null) return;
        // Phase 5E — physical precision: holding Right-Click while LMB-dragging
        // a gizmo drops the mouse delta to 5% for fine translate/scale control.
        // (The gizmo branch in the cursor callback returns before the camera
        // pan/look code, so RMB here never moves the camera.) SHIFT-snap below
        // still operates on the resulting accumulated value.
        if (ImGui.getIO().getMouseDown(1)) { deltaX *= 0.05; deltaY *= 0.05; }
        if (selectedEntity.transformMode == 0) {
            if (draggingAxis >= 0 && draggingAxis <= 2) {
                float drag = axisScreenDelta(draggingAxis, deltaX, deltaY) * sensitivity;
                // Shift = snap to 0.5 grid (so values are .000 or .500).
                // All three axes use the same snap formula now; previously
                // X/Z snapped to integers and Y to half-integers, which
                // gave a "+1.000 jump" feel instead of clean 0.5 steps.
                if (draggingAxis == 0) {
                    rawDragX += drag;
                    selectedEntity.transform.x = isShift ? snapHalf(rawDragX) : rawDragX;
                } else if (draggingAxis == 1) {
                    rawDragY += drag;
                    selectedEntity.transform.y = isShift ? snapHalf(rawDragY) : rawDragY;
                } else {
                    rawDragZ += drag;
                    selectedEntity.transform.z = isShift ? snapHalf(rawDragZ) : rawDragZ;
                }
            } else if (draggingAxis == 3) {
                // Free 3D move (Blender-style grab) — handled in the
                // mouse-move callback via freeMoveFollowMouse(), not here.
                // updateTransformDrag is given (deltaX, deltaY) deltas
                // which don't reconstruct the world position; free-move
                // uses the cursor's absolute world ray instead.
            }
        } else if (selectedEntity.transformMode == 1) {
            // ROTATE — accumulate the angle into `rotDragAccum` (raw,
            // unsnapped) and recompose the entity's rotation EACH FRAME
            // from the pre-drag pose. Pre-drag snapshot means we never
            // sample the entity's already-snapped values, which is what
            // was causing the sticky / dead-zone feel: with snap-then-
            // recompose, a tiny per-frame delta would round back to the
            // same 15° step and the entity wouldn't budge until you'd
            // dragged ~15° in one go.
            float dAngle;
            if (draggingAxis == 0)       dAngle = (float) (deltaY * Config.GIZMO_ROTATION_SPEED);
            else if (draggingAxis == 1)  dAngle = (float) (deltaX * Config.GIZMO_ROTATION_SPEED);
            else if (draggingAxis == 2)  dAngle = (float) (deltaX * Config.GIZMO_ROTATION_SPEED);
            else                         dAngle = 0f;
            if (dAngle != 0) {
                rotDragAccum += dAngle;
                float applied = isShift ? snapAngle15(rotDragAccum) : rotDragAccum;
                Matrix4f delta = new Matrix4f();
                if (draggingAxis == 0)      delta.rotateX((float) Math.toRadians(applied));
                else if (draggingAxis == 1) delta.rotateY((float) Math.toRadians(applied));
                else                        delta.rotateZ((float) Math.toRadians(applied));
                Matrix4f preDragMat = new Matrix4f()
                        .rotateX((float) Math.toRadians(preDragRotX))
                        .rotateY((float) Math.toRadians(preDragRotY))
                        .rotateZ((float) Math.toRadians(preDragRotZ));
                Matrix4f combined = new Matrix4f(delta).mul(preDragMat);
                org.joml.Vector3f euler = new org.joml.Vector3f();
                combined.getEulerAnglesXYZ(euler);
                selectedEntity.transform.rotX = (float) Math.toDegrees(euler.x);
                selectedEntity.transform.rotY = (float) Math.toDegrees(euler.y);
                selectedEntity.transform.rotZ = (float) Math.toDegrees(euler.z);
            }
        } else if (selectedEntity.transformMode == 2) {
            // SCALE — same trick as rotate. Maintain rawScaleX/Y/Z as
            // the unsnapped accumulator; the entity's transform.scale*
            // is only written from the raw value (optionally snapped).
            // Without this, shift-snap sticks: write 1.0, drag 0.01,
            // snap rounds back to 1.0, etc.
            boolean extrude = ctrlHeld();   // CTRL → one-sided (extrude) scale
            float scaleSpeed = sensitivity * Config.GIZMO_SCALE_SPEED;
            if (draggingAxis == 3) {
                float sAmt = (float) (deltaX + deltaY) * scaleSpeed;
                rawScaleX += sAmt;
                rawScaleY += sAmt;
                rawScaleZ += sAmt;
            } else if (draggingAxis >= 0 && draggingAxis <= 2) {
                // Project the mouse drag onto the object's LOCAL axis (world
                // direction), so scaling a rotated object stretches it along
                // its own axes instead of the global ones.
                float[] d = selectedEntity.transform.localAxisDir(draggingAxis);
                float drag = screenDeltaAlong(d[0], d[1], d[2], deltaX, deltaY) * scaleSpeed;
                float applied = applyScaleAxis(draggingAxis, drag);
                // One-sided extrude: shift position ALONG THE LOCAL AXIS by half
                // the applied delta so the OPPOSITE local face stays anchored
                // (not a diagonal slide in global space).
                if (extrude) {
                    selectedEntity.transform.x += d[0] * applied * 0.5f;
                    selectedEntity.transform.y += d[1] * applied * 0.5f;
                    selectedEntity.transform.z += d[2] * applied * 0.5f;
                }
            } else if (draggingAxis >= 4 && draggingAxis <= 6) {
                // NEGATIVE local-face extrude handle (6-way gizmo). The handle
                // points along -localAxis: measure the drag along -d (so pulling
                // outward INCREASES scale) and shift position along -d so the
                // +local face stays anchored. Always one-sided.
                int ax = draggingAxis - 4;
                float[] d = selectedEntity.transform.localAxisDir(ax);
                float drag = -screenDeltaAlong(d[0], d[1], d[2], deltaX, deltaY) * scaleSpeed;
                float applied = applyScaleAxis(ax, drag);
                selectedEntity.transform.x -= d[0] * applied * 0.5f;
                selectedEntity.transform.y -= d[1] * applied * 0.5f;
                selectedEntity.transform.z -= d[2] * applied * 0.5f;
            }
            // Clamp raw above the minimum so we don't accumulate
            // forever in the negatives (covers the centre-cube path).
            if (rawScaleX < Config.GIZMO_SCALE_MIN) rawScaleX = Config.GIZMO_SCALE_MIN;
            if (rawScaleY < Config.GIZMO_SCALE_MIN) rawScaleY = Config.GIZMO_SCALE_MIN;
            if (rawScaleZ < Config.GIZMO_SCALE_MIN) rawScaleZ = Config.GIZMO_SCALE_MIN;
            // SHIFT-snap ONLY the active dragged axis — the inactive axes keep
            // their exact value so single-axis snap can't wreck proportions.
            // Negative handles (4/5/6) map to the same X/Y/Z; centre cube (3)
            // snaps all three together.
            int snapAxis = (draggingAxis >= 4 && draggingAxis <= 6) ? draggingAxis - 4 : draggingAxis;
            if (isShift && snapAxis == 0) {
                selectedEntity.transform.scaleX = Math.max(Config.GIZMO_SCALE_MIN, snapHalf(rawScaleX));
                selectedEntity.transform.scaleY = rawScaleY;
                selectedEntity.transform.scaleZ = rawScaleZ;
            } else if (isShift && snapAxis == 1) {
                selectedEntity.transform.scaleX = rawScaleX;
                selectedEntity.transform.scaleY = Math.max(Config.GIZMO_SCALE_MIN, snapHalf(rawScaleY));
                selectedEntity.transform.scaleZ = rawScaleZ;
            } else if (isShift && snapAxis == 2) {
                selectedEntity.transform.scaleX = rawScaleX;
                selectedEntity.transform.scaleY = rawScaleY;
                selectedEntity.transform.scaleZ = Math.max(Config.GIZMO_SCALE_MIN, snapHalf(rawScaleZ));
            } else if (isShift) {
                selectedEntity.transform.scaleX = Math.max(Config.GIZMO_SCALE_MIN, snapHalf(rawScaleX));
                selectedEntity.transform.scaleY = Math.max(Config.GIZMO_SCALE_MIN, snapHalf(rawScaleY));
                selectedEntity.transform.scaleZ = Math.max(Config.GIZMO_SCALE_MIN, snapHalf(rawScaleZ));
            } else {
                selectedEntity.transform.scaleX = rawScaleX;
                selectedEntity.transform.scaleY = rawScaleY;
                selectedEntity.transform.scaleZ = rawScaleZ;
            }
        }
    }

    /** Rotation drag via ray-plane intersection. The selected ring defines a
     *  plane through the object's origin whose normal is the ring's world axis.
     *  The cursor ray is intersected with that plane; the angle of the hit
     *  point around the axis maps DIRECTLY to rotation, so there are no
     *  screen-delta dead zones and no camera-dependent inversion. Per-frame
     *  angle increments accumulate into {@code rotDragAccum}; the entity's
     *  rotation is recomposed from the pre-drag pose each frame.
     *
     *  Fallback: when the camera is nearly edge-on to the plane (ray almost
     *  parallel → |ray·normal| ~ 0), the intersection is numerically unstable,
     *  so we drag by screen-space tangent delta instead and re-anchor the plane
     *  angle for when the view rotates back to a stable angle. */
    private void updateRotationDrag(double localX, double localY, boolean isShift) {
        if (selectedEntity == null) return;

        // The gizmo is world-axis-aligned, so the ring normal is a unit axis,
        // and (u, v) is a fixed in-plane orthonormal basis (v = normal × u).
        float nx, ny, nz, ux, uy, uz, vx, vy, vz;
        if (draggingAxis == 0)      { nx=1;ny=0;nz=0;  ux=0;uy=1;uz=0;  vx=0;vy=0;vz=1; }
        else if (draggingAxis == 1) { nx=0;ny=1;nz=0;  ux=0;uy=0;uz=1;  vx=1;vy=0;vz=0; }
        else                        { nx=0;ny=0;nz=1;  ux=1;uy=0;uz=0;  vx=0;vy=1;vz=0; }

        float[] dir = mouseRayFromViewport(localX, localY);   // world-space ray dir
        float ox = player.x, oy = player.y, oz = player.z;     // ray origin = camera (matches picking)
        float denom = dir[0] * nx + dir[1] * ny + dir[2] * nz;

        // Edge-on fallback → screen-space tangent drag.
        if (Math.abs(denom) < 0.08f) {
            double dX = localX - lastDragMouseX;
            double dY = lastDragMouseY - localY;
            float dAngle = (draggingAxis == 0)
                    ? (float) (dY * Config.GIZMO_ROTATION_SPEED)
                    : (float) (dX * Config.GIZMO_ROTATION_SPEED);
            rotDragAccum += dAngle;
            rotDragHasStart = false;   // re-anchor the plane angle once stable again
            applyAccumulatedRotation(isShift);
            return;
        }

        float t = ((selectedEntity.transform.x - ox) * nx
                 + (selectedEntity.transform.y - oy) * ny
                 + (selectedEntity.transform.z - oz) * nz) / denom;
        float hx = ox + dir[0] * t, hy = oy + dir[1] * t, hz = oz + dir[2] * t;
        float wx = hx - selectedEntity.transform.x;   // origin → hit (lies in the plane)
        float wy = hy - selectedEntity.transform.y;
        float wz = hz - selectedEntity.transform.z;

        float angle = (float) Math.toDegrees(Math.atan2(
                wx * vx + wy * vy + wz * vz,
                wx * ux + wy * uy + wz * uz));

        if (!rotDragHasStart) {
            rotDragPrevAngle = angle;   // anchor; no rotation applied this frame
            rotDragHasStart = true;
            applyAccumulatedRotation(isShift);
            return;
        }
        float inc = angle - rotDragPrevAngle;
        while (inc >  180f) inc -= 360f;   // unwrap so crossing ±180° is continuous
        while (inc < -180f) inc += 360f;
        rotDragAccum += inc;
        rotDragPrevAngle = angle;
        applyAccumulatedRotation(isShift);
    }

    /** Recompose the entity's rotation from the pre-drag pose plus the total
     *  accumulated drag angle about the active world axis (optionally 15°
     *  snapped). Shared by the ray-plane path and the edge-on fallback. */
    private void applyAccumulatedRotation(boolean isShift) {
        float applied = isShift ? snapAngle15(rotDragAccum) : rotDragAccum;
        Matrix4f delta = new Matrix4f();
        if (draggingAxis == 0)      delta.rotateX((float) Math.toRadians(applied));
        else if (draggingAxis == 1) delta.rotateY((float) Math.toRadians(applied));
        else                        delta.rotateZ((float) Math.toRadians(applied));
        Matrix4f preDragMat = new Matrix4f()
                .rotateX((float) Math.toRadians(preDragRotX))
                .rotateY((float) Math.toRadians(preDragRotY))
                .rotateZ((float) Math.toRadians(preDragRotZ));
        Matrix4f combined = new Matrix4f(delta).mul(preDragMat);
        org.joml.Vector3f euler = new org.joml.Vector3f();
        combined.getEulerAnglesXYZ(euler);
        selectedEntity.transform.rotX = (float) Math.toDegrees(euler.x);
        selectedEntity.transform.rotY = (float) Math.toDegrees(euler.y);
        selectedEntity.transform.rotZ = (float) Math.toDegrees(euler.z);
    }

    /** Snap a value to the nearest 0.5-unit grid (…, -1.5, -1.0, -0.5, 0,
     *  0.5, 1.0, 1.5, …). Used by Move + Scale shift-snap. */
    private static float snapHalf(float v) {
        return Math.round(v * 2f) / 2f;
    }

    /** Snap an angle (degrees) to the nearest 15° step. Used by Rotate
     *  shift-snap so rotation lands on 15 / 30 / 45 / 60 / 75 / 90 / …. */
    private static float snapAngle15(float deg) {
        return Math.round(deg / 15f) * 15f;
    }

    /** Mouse ray from viewport-local (px, py) using the current camera
     *  yaw/pitch and FOV. MUST use {@code player.currentFov} (not
     *  Config.FOV) — Player ramps the FOV when running/sliding and the
     *  projection in drawFrame uses the same value. Mismatched FOV
     *  between projection and picker = cursor offset bug. */
    private float[] mouseRayFromViewport(double px, double py) {
        return RayCaster.getMouseRay((float) px, (float) py,
                (int) viewportW, (int) viewportH, player.currentFov, player.pitch, player.yaw);
    }

    /** Camera look-vector — same convention Player uses (yaw 0 = look -Z,
     *  positive pitch = look down). Returns (dx, dy, dz) unit-ish. */
    private float[] cameraLookDirection() {
        float yawR   = (float) Math.toRadians(camYaw);
        float pitchR = (float) Math.toRadians(camPitch);
        float dx = (float) (Math.sin(yawR) * Math.cos(pitchR));
        float dy = (float) (-Math.sin(pitchR));
        float dz = (float) (-Math.cos(yawR) * Math.cos(pitchR));
        return new float[] { dx, dy, dz };
    }

    /** Ray-pick from camera centre — used by play-mode-fly LMB/RMB.
     *  No range cap. Returns null if nothing hit. */
    private GameObject pickEntityFromCamera() {
        float[] ray = cameraLookDirection();
        GameObject closest = null;
        float minT = Float.MAX_VALUE;
        for (GameObject g : entities) {
            if (g == activePlayerEntity) continue;   // never pick own avatar
            MeshRenderer mr = g.getComponent(MeshRenderer.class);
            if (mr == null) continue;
            float t;
            if (isMarker(mr)) {
                // Markers are pickable in play mode too — fixes the bug
                // where LMB-on-spawn-point passed through and deleted
                // whatever was BEHIND it (floor, etc.). Use the unit-cube
                // hull (matches the editor hover wireframe).
                float hx = 0.5f * Math.abs(g.transform.scaleX);
                float hy = 0.5f * Math.abs(g.transform.scaleY);
                float hz = 0.5f * Math.abs(g.transform.scaleZ);
                t = RayCaster.rayAABB(camX, camY, camZ, ray[0], ray[1], ray[2],
                        g.transform.x - hx, g.transform.y - hy, g.transform.z - hz,
                        g.transform.x + hx, g.transform.y + hy, g.transform.z + hz);
            } else {
                t = MeshCollider.rayMesh(g, camX, camY, camZ, ray[0], ray[1], ray[2]);
            }
            if (t > 0 && t < minT) { minT = t; closest = g; }
        }
        return closest;
    }

    /** Play+fly RMB: spawn a cube. If the camera ray hits an entity,
     *  drop the cube AT the hit point (snapped to the nearest unit grid
     *  cell so cubes stack flush). If nothing hit, drop it 1 m in front. */
    private void placeCubeFromCamera() {
        float[] ray = cameraLookDirection();
        float bestT = Float.MAX_VALUE;
        for (GameObject g : entities) {
            if (g == activePlayerEntity) continue;   // never place against own avatar
            MeshRenderer mr = g.getComponent(MeshRenderer.class);
            if (mr == null || isMarker(mr)) continue;
            float t = MeshCollider.rayMesh(g, camX, camY, camZ, ray[0], ray[1], ray[2]);
            if (t > 0 && t < bestT) bestT = t;
        }
        float spawnX, spawnY, spawnZ;
        if (bestT == Float.MAX_VALUE) {
            // No model in sight — drop 1 m in front of player.
            spawnX = camX + ray[0];
            spawnY = camY + ray[1];
            spawnZ = camZ + ray[2];
        } else {
            // Step BACK one tiny epsilon along the ray so the cube
            // doesn't intersect the hit surface, then snap to integer
            // grid so neighbours align.
            float hitX = camX + ray[0] * (bestT - 0.01f);
            float hitY = camY + ray[1] * (bestT - 0.01f);
            float hitZ = camZ + ray[2] * (bestT - 0.01f);
            spawnX = Math.round(hitX);
            spawnY = Math.round(hitY);
            spawnZ = Math.round(hitZ);
        }
        GameObject g = new GameObject(spawnX, spawnY, spawnZ);
        g.name = "Block";
        MeshRenderer mr = new MeshRenderer();
        mr.modelPath = "";   // procedural cube — instanced batch route
        mr.texturePath = "br1.png";
        g.addComponentNoStart(mr);
        entities.add(g);
        warmEntity(g);
        Logger.info("Play-fly: placed cube at (" + spawnX + "," + spawnY + "," + spawnZ + ")");
    }

    /** Click-to-select — exact port of Player.getEntityAtMouse.
     *  MeshCollider.rayMesh does coarse AABB → per-triangle, using
     *  positions / indices through the renderer-agnostic helpers on
     *  MeshRenderer. Markers use a direct AABB hit-test against their
     *  unit-cube hull (the wireframe drawn in editor mode) since their
     *  actual mesh is a billboarded quad whose triangles don't match
     *  the visible selection cube. */
    private GameObject pickEntityAt(double localMouseX, double localMouseY) {
        float[] ray = mouseRayFromViewport(localMouseX, localMouseY);
        GameObject closest = null;
        float minT = Float.MAX_VALUE;
        for (GameObject g : entities) {
            // NOTE: the avatar IS selectable here (editor click-select). The
            // unpickable protection lives only in the play-mode break/place
            // raycasts (pickEntityFromCamera / placeCubeFromCamera).
            MeshRenderer mr = g.getComponent(MeshRenderer.class);
            if (mr == null) continue;
            float t;
            if (isMarker(mr)) {
                // Marker hull = unit cube centered on transform.xyz,
                // scaled by transform.scaleXYZ. Editor + play+fly mode
                // both pickable (matches where the wireframe + sprite
                // are visible).
                float hx = 0.5f * Math.abs(g.transform.scaleX);
                float hy = 0.5f * Math.abs(g.transform.scaleY);
                float hz = 0.5f * Math.abs(g.transform.scaleZ);
                t = RayCaster.rayAABB(player.x, player.y, player.z, ray[0], ray[1], ray[2],
                        g.transform.x - hx, g.transform.y - hy, g.transform.z - hz,
                        g.transform.x + hx, g.transform.y + hy, g.transform.z + hz);
            } else {
                t = MeshCollider.rayMesh(g, player.x, player.y, player.z, ray[0], ray[1], ray[2]);
            }
            if (t > 0 && t < minT) { minT = t; closest = g; }
        }
        return closest;
    }

    /** Wireframe capsule — the player-body collider visualisation.
     *  {@code feetY} sits at the capsule's bottom tip; total height
     *  {@code h}; radius {@code rad}. Built from two XZ rings (at the
     *  cylinder ends), four vertical connectors, and four half-circle
     *  cap arcs (XY + ZY planes, top + bottom). World-space verts in
     *  one drawLines call. */
    private void drawCapsuleWireframe(VkCommandBuffer cb, Matrix4f viewProj,
                                       float cx, float feetY, float cz,
                                       float rad, float h,
                                       float r, float g, float b) {
        final int SEG = 24;
        float ringBotY = feetY + rad;
        float ringTopY = Math.max(feetY + h - rad, ringBotY);

        java.util.ArrayList<Float> v = new java.util.ArrayList<>(SEG * 24);
        // Two full rings in the XZ plane.
        for (float ringY : new float[]{ ringBotY, ringTopY }) {
            for (int i = 0; i < SEG; i++) {
                double a0 = 2 * Math.PI * i / SEG;
                double a1 = 2 * Math.PI * (i + 1) / SEG;
                v.add(cx + (float) (rad * Math.cos(a0))); v.add(ringY); v.add(cz + (float) (rad * Math.sin(a0)));
                v.add(cx + (float) (rad * Math.cos(a1))); v.add(ringY); v.add(cz + (float) (rad * Math.sin(a1)));
            }
        }
        // Four vertical connectors.
        for (float[] off : new float[][]{ {rad, 0}, {-rad, 0}, {0, rad}, {0, -rad} }) {
            v.add(cx + off[0]); v.add(ringBotY); v.add(cz + off[1]);
            v.add(cx + off[0]); v.add(ringTopY); v.add(cz + off[1]);
        }
        // Cap arcs — half circles in the XY and ZY planes. sign=-1 bottom, +1 top.
        for (int cap = 0; cap < 2; cap++) {
            float centerY = (cap == 0) ? ringBotY : ringTopY;
            float sign    = (cap == 0) ? -1f : 1f;
            for (int plane = 0; plane < 2; plane++) {   // 0 = XY, 1 = ZY
                for (int i = 0; i < SEG / 2; i++) {
                    double a0 = Math.PI * i / (SEG / 2);
                    double a1 = Math.PI * (i + 1) / (SEG / 2);
                    float h0 = (float) (rad * Math.cos(a0)), y0 = centerY + sign * (float) (rad * Math.sin(a0));
                    float h1 = (float) (rad * Math.cos(a1)), y1 = centerY + sign * (float) (rad * Math.sin(a1));
                    if (plane == 0) {
                        v.add(cx + h0); v.add(y0); v.add(cz);
                        v.add(cx + h1); v.add(y1); v.add(cz);
                    } else {
                        v.add(cx); v.add(y0); v.add(cz + h0);
                        v.add(cx); v.add(y1); v.add(cz + h1);
                    }
                }
            }
        }
        float[] verts = new float[v.size()];
        for (int i = 0; i < verts.length; i++) verts[i] = v.get(i);
        lineRenderer.drawLines(cb, verts, viewProj, r, g, b);
    }

    /** Draw a single marker's unit-cube hull as a wireframe — used by
     *  both hover and selection paths. World-space verts → identity
     *  model; the colour parameters let the caller distinguish hover
     *  (dim) from selected (bright) shades. */
    private void drawMarkerHull(VkCommandBuffer cb, Matrix4f viewProj, GameObject g,
                                 float r, float gr, float b) {
        float cx = g.transform.x, cy = g.transform.y, cz = g.transform.z;
        float hx = 0.5f * Math.abs(g.transform.scaleX);
        float hy = 0.5f * Math.abs(g.transform.scaleY);
        float hz = 0.5f * Math.abs(g.transform.scaleZ);
        float x0 = cx - hx, x1 = cx + hx;
        float y0 = cy - hy, y1 = cy + hy;
        float z0 = cz - hz, z1 = cz + hz;
        float[] verts = new float[72];
        int w = 0;
        float[][] edges = {
                {x0,y0,z0, x1,y0,z0}, {x1,y0,z0, x1,y0,z1}, {x1,y0,z1, x0,y0,z1}, {x0,y0,z1, x0,y0,z0},
                {x0,y1,z0, x1,y1,z0}, {x1,y1,z0, x1,y1,z1}, {x1,y1,z1, x0,y1,z1}, {x0,y1,z1, x0,y1,z0},
                {x0,y0,z0, x0,y1,z0}, {x1,y0,z0, x1,y1,z0}, {x1,y0,z1, x1,y1,z1}, {x0,y0,z1, x0,y1,z1},
        };
        for (float[] e : edges) for (float f : e) verts[w++] = f;
        lineRenderer.drawLines(cb, verts, viewProj, r, gr, b);
    }

    /** Look up the "Sun" entity in the loaded level and treat its position
     *  as a unit direction. Cheap O(N) scan — small N — done once per
     *  frame. Falls back to the default direction if no Sun exists. */
    private void refreshSunDirection() {
        for (GameObject g : entities) {
            if ("Sun".equals(g.name)) {
                float sx = g.transform.x, sy = g.transform.y, sz = g.transform.z;
                float len = (float) Math.sqrt(sx*sx + sy*sy + sz*sz);
                if (len > 1e-4f) {
                    sunDirX = sx / len; sunDirY = sy / len; sunDirZ = sz / len;
                    return;
                }
            }
        }
        sunDirX = SUN_DIR_DEFAULT[0];
        sunDirY = SUN_DIR_DEFAULT[1];
        sunDirZ = SUN_DIR_DEFAULT[2];
    }
}
