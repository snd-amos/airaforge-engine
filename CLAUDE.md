# JE Parkour V2 — Vulkan Port Plan

This file is **the source of truth for the Vulkan port effort.** Read it at
the start of every session before touching code. Update the "Status" column
when you complete or revise a sub-phase. Never delete an OpenGL file — they
stay alongside the Vulkan ones until everything is verified working.

## Rule #1 — port, don't reinvent

When porting a feature from the OpenGL engine to the Vulkan renderer:

1. **Read the original file end-to-end first.** Don't skim. Don't guess at
   the API.
2. **Port verbatim** — exact constants (e.g. `Config.MOUSE_LOOK_SENSITIVITY`),
   exact math (view matrix is `rotateX(pitch) · rotateY(yaw) · translate(-cam)`,
   not the other order), exact keybinds (P toggles play/editor, B arms
   box-select, Ctrl+Z undo, X delete).
3. **Annotate every Vulkan-specific divergence** in code comments with a
   reference to the OpenGL line being replaced.
4. **Compile + run after each sub-phase.** No merging two sub-phases into
   one untested commit.

Failures from past sessions to NOT repeat:
- Invented "Right-Alt to free cursor" instead of reading `LevelEditorScene.java:802` (P toggle).
- Forgot `ImGuiConfigFlags.DockingEnable` even though it's the very next line in `Window.java:149`.
- Wrote `MODELS_DIR + "characters/Hazmat.glb"` instead of using `Config.CHARACTERS_PATH`.
- Allocated push-constant FloatBuffers in the caller's `MemoryStack` frame, causing stack overflow at 5000+ draws.
- Set `setIniFilename(null)`, killing layout persistence — opposite of the OpenGL build.

## Rule #0 — prompting & workflow protocol

How prompts arrive and how you must respond:

- **Prompts use strict XML tags.** Expect `<role>`, `<strict_rules>`, `<bug_report>`,
  `<feature_request>`, `<technical_direction>`, `<task>`. Read every tag —
  `<technical_direction>` is the spec, `<strict_rules>` is non-negotiable.
- **ALWAYS edit files with your tools. NEVER print raw code blocks for the
  user to copy/paste** — this is the highest-priority rule, above everything
  except not breaking the build. The only exception is when the prompt
  explicitly asks for a snippet/explanation instead of an edit.
- **Compile after every change** via `compile.ps1` (or `.\mvn.cmd -q compile`);
  report the result. Never hand back code you haven't compiled.
- **The technical_direction may name a mechanism that doesn't match our code**
  (e.g. "Gson `RuntimeTypeAdapterFactory`" when we actually use a custom
  `JsonDeserializer` + `ComponentRegistry`). Implement the INTENT against the
  real code; note the divergence in your report. Don't invent the named API.
- **Decide, don't break.** When two integration problems run deep (e.g. ImGui
  multi-viewport: present jitter + global-coord input desync), prefer the
  safety valve the prompt offers (fall back, keep infra dormant behind a
  flag) over a risky half-rewrite. Report the decision + why.
- This file exists to survive context-window resets: when context fills, the
  terse notes below are how the next session knows where we are. Keep the
  Status / Working notes current.

## Architecture (end state)

```
Engine code (API-agnostic) — keep as-is, share with both renderers:
  Config, Player, World, Level, LevelLoader, MeshRenderer, Material,
  Transform, Component, GameObject, ComponentRegistry, AssetPool*,
  Profiler, Logger, Animation/Animator/Skeleton/BoneAnim/BoneInfo,
  NodeData, AnimationLoader, AnimationDefaults, AnimationKeys,
  MeshCollider*, FrustumCuller, RayCaster, Command, TransformCommand,
  DeleteObjectCommand, UndoRedoManager, Gizmo, RenderState, GLCompat,
  GameStateManager, Scene, MainMenuScene, LevelEditorScene*

  * = currently GL-coupled, needs decoupling work.

OpenGL renderer (kept alongside until Vulkan is at parity, then deleted in
final phase):
  Window, Main, Shader, Mesh, Texture, ModelLoader, ShadowMapFBO,
  Framebuffer, Renderer2D, LineRenderer3D, SelectionBoxRenderer,
  CubeBatch, UIManager

Vulkan renderer (Vk* prefix):
  VkContext      — instance/debug/surface/device/queues/command pool
  VkSwapchain    — swapchain + framebuffers + main render pass + sync
  VkPipelineBuilder — fluent builder collapsing pipeline boilerplate
  VkMesh         — vertex + index buffers
  VkTexture      — 2D + cubemap textures via STB
  VkMeshLoader   — Assimp → VkMesh (with embedded texture extraction)
  VkShadowMap    — depth target + render pass + sampler
  VkImGuiRenderer — Dear ImGui Vulkan backend (hand-rolled; SpaiR ships only GL3)
  VulkanMain     — window, camera, scene loop, drawFrame

  Sub-phase A adds: VkOffscreenTarget, multi-texture ImGui support, scene
                    viewport texture, shadow-map-debug texture
  Sub-phase B adds: VkLineRenderer (line pipeline + buffer)
```

## Conventions copied verbatim from the OpenGL engine

These must NOT drift between renderers. Grep the OpenGL source before
inventing anything.

| Convention | OpenGL source | Vulkan location |
|---|---|---|
| Mouse-look sensitivity | `Config.MOUSE_LOOK_SENSITIVITY = 0.1f` | same constant |
| Pitch clamp | `Config.PITCH_CLAMP = 89f` | same |
| Player walk speed | `Config.PLAYER_SPEED = 0.05f` at 120 fps | `PLAYER_SPEED * 120` per second |
| Sprint multiplier | `Config.PLAYER_FLY_SPEED_MULTIPLIER` | same |
| Yaw / pitch units | degrees, NOT radians | same |
| View matrix | `rotateX(pitch) · rotateY(yaw) · translate(-camPos)` | `LevelEditorScene.java:441-448` |
| Player.update mouselook | `yaw += dx * sens; pitch += dy * sens; clamp` | `LevelEditorScene.java:921-924` |
| Forward / right (yaw-only) | `(sin yaw, 0, -cos yaw)` / `(cos yaw, 0, sin yaw)` | `Player.java:62-63` |
| Sun "direction" | `normalize(sunBlock.transform.xyz)` | `LevelEditorScene.java:322-326` |
| Sun light shadow camera | `rotateX(pitch) · rotateY(-yaw) · translate(-cam)` where camPos = player + sunDir · CAM_HEIGHT | `LevelEditorScene.java:333-340` |
| Shadow ortho box | `Config.SHADOW_ORTHO_SIZE` | same |
| Shadow PCF | 3×3 taps with `bias = max(0.001 * (1 - n·l), 0.0002)` | `default.frag:18-39` |
| Triplanar tiling | sample yz/xz/xy planes blended by `pow(abs(n), 4)` normalised | `default.frag:46-60` |
| Foliage alpha cutoff | `if (a < 0.1) discard;` | `default.frag:66`, `depth.frag:13-16` |
| Floor scale default | `0.315` (the value in level.json's Floor entity) | — |
| Sun cube colour | yellow `(1, 1, 0)` flat | `LevelEditorScene.java:544` |
| Model matrix | `translate · Rx · Ry · Rz · scale` | `RenderState.java:47-54` |
| Toggle play/editor | **P** key | `LevelEditorScene.java:802-816` |
| Editor cursor mode | `GLFW_CURSOR_NORMAL` | `LevelEditorScene.java:806` |
| Play cursor mode | `GLFW_CURSOR_DISABLED` | `LevelEditorScene.java:809` |
| Box-select arm | **B** key | `LevelEditorScene.java:818` |
| Undo / Redo | Ctrl+Z / Ctrl+Y | `LevelEditorScene.java:832-853` |
| Delete selected | **X** key | `LevelEditorScene.java:856-863` |
| Double-tap Space | toggle `Player.isFlying` | `LevelEditorScene.java:867-876` |
| Esc | back to MainMenu | `Window.java:160-164` |

## Vulkan-specific divergences (intentional)

| Why | What's different |
|---|---|
| Vulkan NDC Z is `[0,1]` not `[-1,1]` | Don't apply the `*0.5+0.5` remap to the Z component of `projCoords` in `shadowCalc`. Use `ortho(.., zZeroToOne=true)` and `setPerspective(.., true)`. |
| Vulkan Y points DOWN in NDC | Use a negative-height viewport in the main pass to flip to GL-style up-y. Shadow pass uses positive height (doesn't matter — sampling is self-consistent). |
| Push constants visible to vertex AND fragment | `vec4 material` carries the triplanar flag (vertex doesn't care; fragment does) so we use a combined stage flag. |
| One descriptor set per material texture | Cached by texture-key (`emb:<modelPath>` or `tex:<path>`). Shadow map is binding 1 of every set since it's global. |
| Push-constant payload | 240 B (3 mat4 + 3 vec4: material/tint/sunDir). `sunDir.w` carries sun intensity. Under MoltenVK / NVIDIA / AMD / Intel desktop limits but exceeds the 128 B spec minimum. |
| Component start() | `MeshRenderer.start()` calls into the GL `AssetPool` and crashes without a GL context. Vulkan path uses `GameObject.addComponentNoStart()` and `LevelLoader.loadLevelData()` (no-start variant). |

## Global UI rule (ImGui)

- **Global UI Precision:** Whenever adding new sliders or value editors (floats,
  vectors) anywhere in the engine's ImGui, you MUST use the custom precision
  wrapper (`UIUtils.dragFloat` / `UIUtils.dragFloat3`) instead of standard
  `ImGui.sliderFloat` / `ImGui.dragFloat`. This ensures the global RMB-slowdown
  (v_speed → 0.001 while Right-Click held) and SHIFT-snapping (round to 0.5
  increments, matching the 3D gizmo) mechanics are universally applied.

## Regression vault (fixed once — DO NOT regress)

Invariants from bugs already paid for. Re-check these before touching the
related system.

- **Component serialization** — every new `Component` MUST be registered in
  `ComponentRegistry` under its **simple class name** (`Component.type =
  getClass().getSimpleName()`, e.g. `"WallRunnableComponent"`), because the
  `LevelLoader` deserializer looks components up by that exact string. Register
  the short UI name too (the Inspector drag chips use it). Miss the
  simple-name key → the component is silently dropped on save→reload (this is
  what broke WallRunnable). NB: we use a custom `JsonDeserializer` +
  `ComponentRegistry`, **not** a Gson `RuntimeTypeAdapterFactory`.
- **Gizmo rotation** — MUST use 3D **ray-to-plane intersection** (cast the
  cursor ray onto the plane through the object origin whose normal is the
  ring's world axis; map the hit angle directly), **not** 2D screen-space
  mouse deltas. Screen deltas gave dead zones + camera-dependent inversion.
  Fallback to screen-space tangent only when the camera is edge-on to the
  plane (`|ray·normal| < 0.08`). See `VulkanMain.updateRotationDrag`.
- **ImGui panel visibility persistence** — the `show*` `ImBoolean`s default to
  `true`, which overrode the user's closed panels on every boot. They are now
  loaded from `editor_prefs.json` (written in `cleanup()`). `imgui.ini` saves
  geometry/dock/collapse only — NOT the app-owned open booleans. Don't reset
  `setIniFilename(null)`.
- **Settings UI consistency** — the Main Menu runs `fontGlobalScale = 2.2`,
  the editor `1.0`, so a shared popup rendered at different scales. Fix
  (`SettingsUI.render`): `setNextWindowSize/Pos(..., ImGuiCond.Appearing)`,
  explicit `pushStyleVar(WindowPadding/ItemSpacing)`, and
  `setWindowFontScale(1 / fontGlobalScale)` to normalize. Reuse this pattern
  for any popup shared across menu + editor.
- **Swapchain recreation** — any window resize / display-mode switch
  invalidates the swapchain. Always `vkDeviceWaitIdle` → `swap.destroy()` →
  rebuild (`recreateSwapchain`), triggered by `framebufferResized` or
  `VK_ERROR_OUT_OF_DATE_KHR`/`SUBOPTIMAL` at acquire+present. The scene renders
  offscreen, so the swapchain only feeds the ImGui composite — its render pass
  keeps the same format, so pipelines stay compatible and need no rebuild.
- **Play mode is strictly gameplay** — editor tools (raycast select, place,
  delete) are gated `!isEditorMode && player.isFlying`. Walking = no object
  manipulation. Always reset `player.isFlying = false` when entering play.
- **Display settings persistence** — `DisplaySettings.java` (`windowWidth`,
  `windowHeight`, `aspectRatio`, `fullscreenMode`) ↔ `settings.json` via GSON.
  `SettingsUI` seeds its dropdowns from it on construction and `save()`s on
  Apply; `initWindow` boots at the saved resolution. CRITICAL: apply the saved
  fullscreen/borderless mode (`SettingsUI.applyToWindow`) BEFORE the GLFW
  framebuffer-size callback is installed, otherwise the boot-time monitor
  switch spuriously flags `framebufferResized` → an extra swapchain recreate.

## 🧍 Character Editor & Locomotion Roadmap

A dockable "Character Editor" ImGui window that previews + edits characters in
isolation from the main level. Three phases:

- **Phase 1 (✅ Complete)** — Vulkan **offscreen target** + ImGui viewport.
  Reused `VkOffscreenTarget` (colour + depth, `SAMPLED_BIT`,
  `SHADER_READ_ONLY_OPTIMAL`); registered with `VkImGuiRenderer.registerTexture`
  → `characterTexId`. `CharacterEditorWindow` panel samples it via
  `ImGui.image`. `drawFrame` **PASS 2b** clears it to dark blueprint grey.
  Per-frame resize mirrors the Scene Viewport.
- **Phase 2 (✅ Complete)** — **Isolated preview rendering** + **drag-and-drop**.
  A dedicated `previewCharacter` GameObject + preview camera (orbit at origin)
  that is NEVER added to the main `entities` list. PASS 2b binds the model /
  skinned pipeline and draws `previewCharacter` with the preview MVP. Asset
  Browser model buttons become ImGui drag SOURCES (payload `CHAR_MODEL` =
  model filename); the Character Editor is the drag TARGET that loads the file
  via the existing `meshFor` / `descriptorSetFor` path onto `previewCharacter`.
- **Phase 3A (✅ Complete)** — **Component integration & UI sliders**.
  `CharacterLocomotionComponent` (collisionRadius / collisionHalfHeight /
  walkSpeed / sprintSpeed / jumpVelocity / stepHeight, + `hiddenSubmeshIndex`)
  registered in `ComponentRegistry` (NOT a Gson `RuntimeTypeAdapterFactory` —
  see Regression vault) under BOTH its short + simple-class-name keys.
  Auto-attached to `previewCharacter` on drop; a "Locomotion Attributes"
  `collapsingHeader` of `sliderFloat`s below the viewport edits the live one.
- **Phase 3B (✅ Complete)** — first-person camera + Play/Pause state sync.
  P toggle Play→Editor snaps the (shared) Player camera to its final pose (no
  reset — Player IS the camera in both modes). First-person eye offset =
  `cameraHeightOffset` (drives `Player.eyeHeight()` when a loco is bound).
  Headless head-hiding: the engine MERGES submeshes into one VkMesh
  (`ModelLoader`/`VkMeshLoader`), so there is NO per-submesh draw to skip —
  `hiddenSubmeshIndex >= 0` hides the whole avatar mesh via
  `VulkanMain.hiddenAtRuntime`.
- **Phase 4 (✅ Complete)** — spawning + physics integration. "Set as Active
  Player" (`CharacterEditorWindow` button → `VulkanMain.setActivePlayer`)
  deep-copies the preview into the world (`activePlayerEntity`) and binds the
  LIVE loco component to `player.locomotion`. `Player.update` re-reads
  walkSpeed/sprintSpeed/jumpVelocity/stepHeight/collisionRadius/
  collisionHalfHeight every frame (fallback = legacy finals when unbound; the
  speed/jump/step fields were de-`final`ed). The avatar gets a
  `WalkthroughComponent` so the player never self-collides (the "stuck in
  floor" bug). `syncActivePlayerAvatar` drags the body with the camera every
  play frame (feet = `player.y - eyeHeight()`).
- **Phase 4B (✅ Complete)** — two-way Editor↔Play sync. Entering Play snaps the
  Player to `activePlayerEntity`'s editor transform (`y + eyeHeight()`); the
  "Spawn: Character Collision" menu button was removed (loco supersedes it).
## 🏛️ AAA Engine Architecture (Laura/Hazel Inspired) — ACTIVE ROADMAP

> **PIVOT.** Development has shifted from the Character Locomotion & Animation
> roadmap (now paused — Phases 6–8 below) to an **AAA editor-shell overhaul**
> modelled on the "Laura" engine (a C++ Hazel-clone,
> https://github.com/jakubg05/Laura). Do NOT touch Animation/parkour/IK logic
> until explicitly told to resume; that work is preserved below for re-entry.

- **Phase 1 (✅ COMPLETE) — Borderless Window + Custom Title Bar + Dark Theme.**
  OS decorations off (`GLFW_DECORATED = GLFW_FALSE` in `initWindow` AND in
  `SettingsUI.applyToWindow` Windowed case — stays borderless on display-mode
  switch, no double bar). Pro dark ImGui theme (`applyDarkTheme` in `initImGui`).
  Unified title bar (`buildMainMenuBar`): a decoration-less window pinned to
  (0,0) — File/Edit/Window/Settings as button+popup dropdowns (left), engine
  name (center), Minimize/Maximize/Close (right); empty-strip drag moves the OS
  window. `buildDockspace(titleBarHeight)` hosts the DockSpace below it. Global
  `fontGlobalScale` stays 1.0 everywhere; the Main Menu enlarges only its own
  window via `setWindowFontScale`. Main Menu is editor-first (Start Game
  removed): Open Level Editor / Settings / Quit.
- **Phase 2 (✅ COMPLETE) — Dual Viewports (Editor vs. Game).** "Scene" window =
  free-fly editor camera + gizmos/outlines/debug; "Game" window = `gameTarget`
  offscreen (`recordGamePass`, PASS 2c) rendered from `activePlayerEntity`'s
  camera (eye = transform + loco `cameraHeightOffset`, yaw = `180 - rotY`), NO
  debug overlays, and the player's own mesh skipped so the FP camera doesn't
  clip the body. `showGame` panel toggle in the Window menu.
- **Phase 3 (✅ COMPLETE) — Play Toolbar + Scene State Serialization.** A thin
  full-width `##Toolbar` window under the title bar with Play ▶ / Stop ■
  (themed accent on the active one). `GameStateManager.isPlaying` flag.
  `LevelLoader.toJsonString/fromJsonString` (List&lt;GameObject&gt; ↔ JSON
  string). `startPlay` snapshots `entities` → `sceneSnapshotJson`;
  `stopPlay` rebuilds `entities` from the snapshot (so play-mode edits don't
  persist), re-finds `activePlayerEntity` by the " (Player)" name suffix, and
  re-prewarms. Editor stays visible throughout (no fullscreen switch).
- **Phase 4 (⏸️ PAUSED) — Standalone Game Exporting / Packaging.** Build a
  distributable game (bundled assets + runtime, no editor). On hold while the
  Multi-Viewport architecture below is built.

### Advanced Architecture: Multi-Process Real-Time Editor (ACTIVE)

> **PIVOT — ImGui Multi-Viewports ABANDONED.** Single-threaded multi-swapchain
> presentation is fundamentally incompatible with our one-queue structure
> (persistent VUID errors, tearing, jitter — neither the unified submit nor the
> isolated per-viewport submits resolved it). The editor is now PERMANENTLY
> locked to internal docking (`MULTI_VIEWPORT = false`, `ViewportsEnable` never
> set). Instead, detached/real-time game views will be a SEPARATE OS PROCESS.

- **Phase 1 (✅ COMPLETE) — Stabilize editor + `-runtime` boot flag.** Hardcode
  `MULTI_VIEWPORT = false`. New CLI flag (`--runtime` / `-runtime` / `-game`) →
  `VulkanMain.runtimeMode`; in runtime mode the engine skips the main menu +
  editor UI and boots STRAIGHT into Play (load `level.json`,
  `GameStateManager.setPlaying(true)`, `applySpawnPointIfPresent`, cursor
  disabled), rendering fullscreen from the player/active-player camera (the
  normal `!isEditorMode` swapchain path). Editor build is unchanged.
- **Phase 2 (✅ COMPLETE) — Process Launcher.** `launchRuntimeProcess()` spawns a
  separate OS process via `ProcessBuilder([javaBin, "-cp", classpath,
  "com.myengine.VulkanMain", "--runtime"]).inheritIO()`, javaBin from
  `java.home/bin/java`, classpath from `java.class.path`. Spawned `Process`
  handles are tracked in `childProcesses` and `.destroy()`-ed in `cleanup()`
  so closing the editor closes its child game windows. UI hook: title-bar
  **Run** menu → "Launch Standalone Game". ESC in `runtimeMode` calls
  `glfwSetWindowShouldClose` instantly (no editor-menu fallback).
- **Phase 3 (🚧 IN PROGRESS) — IPC for real-time sync.** Editor ↔ Game live state
  sync over loopback UDP. Phase 3A (done): `NetworkSync` opens a non-blocking
  `DatagramChannel` — `initServer(port)` (editor, broadcasts to 127.0.0.1:port)
  / `initClient(port)` (runtime, binds + listens). Started in `initVulkan` on
  port 7777 (client when `runtimeMode`, else server), closed in `cleanup()`.
  Also fixed the standalone-game blank screen: `enterRuntimePlay` now binds
  `activePlayerEntity` by "(Player)" suffix → `CharacterLocomotionComponent` →
  "Player" name, force-calls `initPlayer()`, snaps the camera to the avatar
  when no Spawn Point exists, and syncs the `cam*` fields immediately so the
  first frame has a valid view matrix. Phase 3B (done): the standalone game
  booted to skybox-only because the asset cache was half-initialized at the
  runtime boot transition — `enterRuntimePlay` now calls `reloadLevelFromDisk()`
  first (deterministic loadLevelData → prewarm GPU upload → updateGrid) so the
  full world renders. Preview-camera pan-Y default set to 1.0 (frames the
  chest). Audited the VkPipelineLayout leak: every `.build()` site is balanced
  with a destroy (marker + skinned-pipeline destroys from Phase 2 already
  closed the gap) — no missing teardown. Everything else in the 3B request
  (F5 third-person toggle + back-camera + force-draw avatar, collisionOffsetY
  field/slider/physics/debug-viz, RMB pan on local right/up vectors, char-editor
  image height = availY*0.5, Clear Character button, per-slot anim Play buttons,
  isometric default framing) was already implemented in Phases 5–6.
  Phase 3C (todo): serialize transforms + per-frame UDP send/receive pump.
- Play-state consolidation pass: (1) `hiddenAtRuntime` rewritten to the
  single canonical rule — playView = `runtimeMode || !isEditorMode`; in a
  play view hide Invisible/SpawnPoint markers, and hide the avatar ONLY when
  `g == activePlayerEntity && !isThirdPerson && loco.hiddenSubmeshIndex >= 0`
  (was force-drawing via a stale third-person override). The Game viewport
  pass (recordGamePass) already hard-skips `activePlayerEntity` in all 3
  loops, so it's independent. (2) `renderGameWindow` now early-returns when
  `activePlayerEntity == null` — no more zombie Game panel. (3) Standalone
  runtime boot deferred: `enterRuntimePlay` no longer runs in `run()`; a
  `runtimePlayPending` latch fires it on the FIRST mainLoop frame (after
  initVulkan fully brings up device/queues/pools) so the reload + prewarm GPU
  uploads can't hit a half-ready context.

#### Archived: Vulkan Multi-Viewports (dormant code, do not delete)

> `VkViewportRenderer.java` + `VkGlfwPlatformIO.java` are kept as archive code
> (the hand-rolled `GLFW_NO_API` platform + per-window swapchain backend) but are
> fully bypassed — instantiated only inside `if (MULTI_VIEWPORT)` which is now
> permanently false. History below for reference.

- **Step 1 (current) — Custom `VkGlfwPlatformIO` (GLFW_NO_API override).**
  ✅ Present: `VkGlfwPlatformIO` implements all platform window-management
  callbacks (create / destroy / show / set+get pos / set+get size / set+get
  focus / get-minimized / set-title) via `register(ImGui.getPlatformIO())`.
  `createWindow` sets `glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)` BEFORE
  `glfwCreateWindow`, stores the handle in an id→`GLFWwindow*` map AND on
  `vp.setPlatformHandle` (so the renderer's `glfwCreateWindowSurface` + ImGui's
  input path can read it); `destroyWindow` frees callbacks + `glfwDestroyWindow`
  (never the main window). Instantiated + registered ONLY inside the
  `if (MULTI_VIEWPORT)` block in `initImGui`, so it is fully dormant by default.
- **Step 2 (✅ COMPLETE) — `VkViewportRenderer` (per-window swapchain / surface).**
  Audited: one Vulkan presentation stack per detached window
  (surface via `glfwCreateWindowSurface` on the Step-1 handle, independent
  `VkSwapchainKHR` + framebuffers + command buffers + sync). Resize tears down
  and rebuilds the swapchain to the new extent; destroy does
  `vkDeviceWaitIdle` → destroy swapchain/framebuffers/surface. Shares
  `VkImGuiRenderer`'s pipeline + descriptor layouts (the `viewportPipeline`
  variant from `setViewportRenderPass`) and a render pass with the same
  `B8G8R8A8_UNORM` colour format. Still parked behind `MULTI_VIEWPORT`.
- **Step 3 (✅ COMPLETE — audit, no fix needed) — Vulkan synchronization.**
  Per-viewport sync is fully isolated in the `PerViewport` struct (own
  `imageAvailable` + per-image `renderFinished` + `inFlight` fence) — distinct
  Vulkan objects from the main `VkSwapchain`'s sync, no collision.
  `renderWindow` order is correct: `vkWaitForFences(inFlight)` → acquire →
  (on OUT_OF_DATE return WITHOUT resetting the still-signalled fence) →
  `vkResetFences` → submit(wait=imageAvailable, signal=renderFinished[img],
  fence=inFlight); `swapBuffers` presents waiting on `renderFinished[img]`,
  resets `currentImage = -1`. Single-frame-in-flight, so the reused
  `imageAvailable` semaphore is always drained before re-acquire. The pump
  (`updatePlatformWindows`+`renderPlatformWindowsDefault`) runs AFTER the main
  present, on the one render thread → sequential shared-queue submits are safe;
  no cross-swapchain semaphore needed. The earlier black-screen JITTER was
  multi-swapchain FIFO present *contention* on the shared queue (+ global mouse-
  coord desync), an architectural Step-4 activation concern — NOT a missing
  fence/semaphore. No code change.
- **Step 4 (✅ ACTIVATED) — Activation & global-coordinate input routing.**
  `MULTI_VIEWPORT = true` → `initImGui` registers `VkGlfwPlatformIO` (platform)
  + `VkViewportRenderer` (renderer), adds `ViewportsEnable` + the
  Platform/RendererHasViewports backend flags; the end-of-frame pump
  (`updatePlatformWindows` + `renderPlatformWindowsDefault`) runs (gated on the
  now-set `ViewportsEnable` flag). **Input fix:** all viewport-relative picking
  (`pickEntityAt` / hover-pick / gizmo drag / pending LMB click / CTRL+RMB pivot)
  now derives local coords from `ImGui.getMousePosX/Y() - viewportX/Y` instead of
  `glfwGetCursorPos` — ImGui mouse pos and `viewportX` (= `getWindowPos +
  contentRegionMin`) live in the SAME space (window-local with viewports off,
  global-desktop with them on), so it's correct in both. Title-bar window-drag
  keeps `glfwGetCursorPos` (it moves the OS main window, intentionally
  window-local). NOTE: the multi-swapchain FIFO present-contention jitter on the
  shared queue is a separate architectural matter (MAILBOX / present scheduling),
  to revisit if it resurfaces at runtime.
  - **Stabilization pass:** secondary swapchains now pick MAILBOX → IMMEDIATE →
    FIFO (no more double-V-Sync FIFO contention/jitter); detached GLFW child
    windows get `GLFW_TRANSPARENT_FRAMEBUFFER = FALSE` + swapchain
    `compositeAlpha = OPAQUE` + render-pass clear alpha `1.0` (no desktop bleed).
    Offscreen-texture panels (Character Editor / Scene / Game) bind via the
    SHARED `VkImGuiRenderer.texIdToSet` + pipeline layout, so descriptors ARE
    accessible in secondary command buffers (verified) — if an offscreen panel
    still flickers when detached, the cause is cross-submission memory
    visibility (offscreen written in the main frame, read in the pump with no
    semaphore), a follow-up sync task, NOT a descriptor/layout bug.
  - **ISOLATED PER-VIEWPORT SUBMITS (current + final model).** The "unified
    submit" (one `vkQueueSubmit` waiting on multiple swapchains' `imageAvailable`)
    violated Vulkan's state machine — a single submission can't safely wait on
    multiple async monitor acquires → `Semaphore must not be currently signaled`
    deadlocks. REVERTED to fully independent submits, each protected by its OWN
    fence (the Step-1/2 design the Step-3 audit validated):
    * MAIN frame (`submitAndPresent`): one `VkSubmitInfo` waiting ONLY on the
      main `imageAvailable`, signalling ONLY the main `renderFinished`, fenced by
      the main `swap.inFlight(currentFrame)`. Submit + present immediately.
    * Each detached viewport (`VkViewportRenderer.renderWindow`, driven by the
      mainLoop pump AFTER the main present): waits THIS viewport's `inFlight`
      fence → acquire (its own `imageAvailable`) → reset THIS fence → record →
      `vkQueueSubmit` signalling its per-image `renderFinished`, fenced by its
      OWN `inFlight`. `swapBuffers` presents it (waits that `renderFinished`).
    * NO shared/cross-submission semaphores, NO global fence across swapchains,
      NO drain hack. Sync objects are strictly per-`PerViewport`. Present mode is
      MAILBOX→IMMEDIATE→FIFO and child framebuffers are opaque (no bleed).

---

## 🧍 Character Locomotion & Animation roadmap (⏸️ PAUSED — see pivot above)

- **Phase 5 (✅ COMPLETE)** — Character Editor visual debugging & interactivity.
  Yaw round-trip uses a **180° offset both ways** (`rotY = 180 - yaw` on exit,
  `yaw = 180 - rotY` on entry) so a model's local +Z forward points AWAY from
  the camera (back-to-camera), fixing the face-the-camera flip. Drag inside the
  preview `ImGui.image` rotates `previewCharacter` (X-drag→rotY, Y-drag→rotX).
  "Show Collision Bounds" / "Show Skeleton/Bones" checkboxes drive
  `VkLineRenderer` debug draws inside PASS 2b (capsule from loco radius/half-
  height in world space; bone lines from `Skeleton.gatherDebugLines` using
  `Animator.debugBoneTransforms`, or T-pose when null). `VkLineRenderer` is
  render-pass-compatible with the character target (both `VkOffscreenTarget`).
  Phase 5F/5G follow-ups: Asset Browser texture search/3-item-cap/expand +
  TEXTURE drag-drop (Inspector + preview); static 1×1×1 `test.png` reference
  cube drawn RAW in PASS 2b at `(0,-0.5,0)` (camera anchored to world origin,
  not the character, so it stays put); uniform Base Scale (scale-only, NaN-
  hardened, no auto Y-pivot); keyboard guard uses `getWantTextInput()` (not
  `getWantCaptureKeyboard()` — that froze WASD on any panel focus).
- **Phase 6 (⏸️ PAUSED) — Animation Runtime & Third-Person Camera.** Animations
  are referenced by **String file path** (`.glb`), loaded via
  `AnimationLoader.loadAnimation(path, skeleton)` (cached in `animationCache`),
  played via `Animator.play(Animation)`; a single Animator holds one clip at a
  time. Phase 6A: `CharacterLocomotionComponent` gains
  `animIdle/animWalk/animSprint/animJump` (String paths, Gson-serialized);
  Character Editor "Animations" header has 4 text+drag-drop slots (CHAR_MODEL
  payload), assigning one plays `animIdle` on the preview; play-mode
  `updateLocomotionStateMachine` picks Idle/Walk/Sprint/Jump from `player`
  velocity + grounded + Shift and calls `play()` ONLY on state change
  (`currentLocoState` guard). Preview animator is advanced in `updateCamera`
  even in editor (it's not in `entities`, so `updateAnimators` skips it).
  Phase 6B: per-slot Play buttons, `collisionOffsetY`, side-by-side Asset
  Browser panes (`beginTable`), default isometric preview framing.
  Phase 6C: avatar visibility honours the UI `hiddenSubmeshIndex` (no forced
  override on spawn); **F5** toggles `isThirdPerson` — camera pulls 3 m back
  along view-forward + 0.5 up, and `hiddenAtRuntime` force-draws the avatar.
  *Resume point:* animation blending/transitions, root motion, parkour-driven
  state additions.
- **Phase 7 (⏸️ PAUSED) — Parkour Traversal Integration.** Not started.
  Tie the existing parkour FSM states (wall-run / ledge-grab / climb / slide,
  see `Player.java`) into the animation state machine + camera. Hold.
- **Phase 8 (⏸️ PAUSED) — Inverse Kinematics.** Not started. Foot-IK / look-at /
  hand placement on the skinned rig. Hold — do not implement any IK logic yet.

## Status

### Done
- Phases 1–11: hello-window → instance/device → swapchain → render pass → triangle → mesh → texture → multi-mesh → embedded texture → camera/scene → skybox → floor/triplanar → sun shadow map + PCF + bias.
- Phase 12: load `level.json` and render every entity with its real transform/material; lazy mesh+texture cache; per-texture descriptor set; state-change dedup; alpha-cutout foliage.
- Phase 12 polish: `Material.r/g/b` tint multiplied into shader; sun direction read from "Sun" entity; sun cube forced yellow.
- Phase 13 partial (editor port, ongoing):
  - ✅ Editor / Play mode toggle on **P**, cursor mode switch, mouselook gated to play mode (`LevelEditorScene.java:802-816`)
  - ✅ ImGui docking (`DockingEnable` + `dockSpaceOverViewport(PassthruCentralNode)`)
  - ✅ ImGui callbacks chained (`installCallbacks=true`)
  - ✅ Layout persistence via default `imgui.ini`
  - ✅ Asset Browser panel (Save/Load + Spawn buttons for floor, every model in `Config.MODELS_PATH`, characters in `Config.CHARACTERS_PATH`, stress test)
  - ✅ Gameplay Objects panel (Spawn Point, Win Zone)
  - ✅ Inspector panel (name, transform mode radio, position/rotation/scale, Sun Intensity slider, MeshRenderer Texture/Model fields, Material collapsing header with tint + tiling + texture scale slider, Face Colors section with per-face pickers + Match Tint button)
  - ✅ Debug HUD (FPS, coords, yaw, pitch, compass, facing, sun, toggles for HUD/SceneTree/Profiler)
  - ✅ Profiler panel (zones table, Reset max, Copy table)
  - ✅ Scene Tree (Vulkan-specific selection panel until viewport click lands)
  - ✅ Asset failure resilience (`failedAssetKeys` skip set)
  - ✅ Per-call MemoryStack frames in `recordShadowDraw`/`recordModelDraw` so stress tests don't overflow

### Remaining sub-phases (in execution order)

| # | Name | Key files to port | Vulkan additions | Status |
|---|---|---|---|---|
| **A** | Scene Viewport + multi-texture ImGui + Shadow Map Debug + click-to-select + foliage shadow | `UIManager.renderViewport` (215-230), `UIManager.render` shadow block (70-92), `LevelEditorScene.onMouseClick` selection logic, `RayCaster`, `depth.frag` foliage | `VkOffscreenTarget`, `VkImGuiRenderer.registerTexture`, depth-pass alpha discard | ✅ Done |
| **B** | LineRenderer3D + Gizmo + SelectionBoxRenderer | `LineRenderer3D` (whole), `Gizmo`, `SelectionBoxRenderer`, `LevelEditorScene` gizmo wiring, `outline.frag`, `line.frag/vert` | `VkLineRenderer`, line/outline pipeline | ✅ Done — visual gizmo + drag-to-edit (Move/Rotate/Scale). Stencil silhouette still deferred to H; orange outline is the AABB for now. Click-to-select now uses mesh-accurate `MeshCollider.rayMesh` instead of AABB. |
| **C** | Undo / redo / delete / box-select | `UndoRedoManager`, `TransformCommand`, `DeleteObjectCommand`, `LevelEditorScene.onKeyPress` (Ctrl+Z/Y/X/B branches) | Wire into Inspector + scene | ✅ Done — undo / redo / delete plus B-key box-select (drag a rect, hits get orange outlines; primary selection still drives the gizmo). Multi-entity group-drag deferred. |
| **D** | MainMenuScene + GameStateManager + Esc-to-menu | `MainMenuScene`, `GameStateManager`, `Scene` interface | Make `VulkanMain` host scenes | ✅ Done (state-flag based, not full Scene class) |
| **E** | Player physics in Vulkan | `Player`, `MeshCollider` — needs decoupling from `Mesh.minX..maxZ` (GL-bound) | Mesh-bounds split into a POJO so collision works without GL | ✅ Done — `MeshRenderer` carries renderer-agnostic `vkLocalPositions/Indices/Bounds` populated by `VkMeshLoader` + procedural builders. `MeshCollider` + `GameObject.worldBounds` now read via helpers, work for both renderers. `Player` + `World` (no `init()`) wired into VulkanMain. Double-tap Space toggles fly. Win Zone respawn ported. |
| **F** | Instanced cubes + per-face colour rendering | `CubeBatch`, `default_instanced.vert/frag` | `VkCubeBatch`, instanced pipeline | ✅ Done — `VkCubeBatch` collects procedural cubes per frame, groups by (material, tiling, foliage, scale), pushes per-instance data to a dynamic vertex buffer at binding 1 (INSTANCE rate), one `vkCmdDrawIndexed(36, count, ...)` per group. `VkPipelineBuilder.instanceLayout(...)` added for the second binding. Per-face colour shader (location-based face pick from local normal) ported verbatim from `default_instanced.vert/frag`. Sun cube forced to yellow tint inside the batch. |
| **G** | Skinned characters + Animator panel | `Skeleton`, `Animator`, `Animation`, `BoneAnim`, `BoneInfo`, `AnimationLoader`, `AnimationDefaults`, `AnimationKeys`, `NodeData`, `default.vert` skinning path, `depth.vert` skinning path, `UIManager` animator section (533-580) | Bone matrix UBO/SSBO, skinned model shaders, Inspector animator UI | ✅ Done — color pass + shadow pass + selection outline all bone-deformed via skinned pipelines sharing one VkSkinnedRuntime UBO/descriptor per Animator. Inspector Animator panel with file load + play/pause + trim + Save Defaults. Auto-derives texture filename from glb (characters/Hazmat.glb → Hazmat.png). |
| **H** | Stencil outline (selection ring around picked entity) | `default.frag` stencil branch | Stencil-test enabled secondary pipeline | ✅ Done — `VkOutlineRenderer` does the two-pass silhouette (stencil mark + outline-test-NOT_EQUAL). Depth attachment bumped to D32S8/D24S8 for the stencil aspect. Used for both hover (dim orange, thickness 0.003) + selection (bright orange, thickness 0.005). AABB fallback retained for markers + entities without loaded mesh. |
| **Final** | Delete OpenGL renderer, share assets through unified interface | All OpenGL-only files | None | When everything else verified |

## Parked items (revisit after the sub-phases above)

User-flagged issues from earlier sessions:

- ✅ **Player ramp physics** (RESOLVED) — the ≤45° walk / >45° slide model
  landed via collide-and-slide + ray-only ground detection + camera-eased step
  smoothing. `Config.PLAYER_STEP_HEIGHT = 0.5`. See the Working notes ramp
  entries. The character wireframe-capsule debug lines were intentionally never
  ported to Vulkan (so N/A there).
- **Character internal-flight push-out** (open) — in fly mode the player phases
  through entity meshes; on exit-fly we should depenetrate along the shortest
  axis before re-enabling physics.
- **Floor scale** — `0.315`, user "not sure"; Scene Viewport exists now, so
  revisit visually whenever convenient (low priority).

## Reference snapshots

- **Pre-Vulkan engine backup**: `C:\Users\soundye\Desktop\beforevlk\` — the
  last fully-working OpenGL snapshot. When the Vulkan port's behaviour is
  uncertain, diff against the corresponding file there.
- The user provided 13 reference screenshots of the OpenGL editor (saved in
  conversation history). Editor layout target:
    Inspector (left) | Scene Viewport (center) | Asset Browser (right);
    Profiler + Shadow Map Debug bottom-left; Gameplay Objects bottom-right;
    floating Debug HUD inside the viewport.
  Gizmos: Move = solid coloured arrow shafts + cube tips; Rotate = three
  64-segment coloured rings; Scale = shafts + box tips + central white
  cube. Selected meshes get an orange **stencil-silhouette** outline (not
  just AABB). Hazmat character currently shows an orange **wireframe
  capsule** that the user wants removed (see Parked items).
- **Play-mode HUD** (`!isEditorMode`): full-screen viewport with a
  top-centre stamina/jump-charge bar drawn via `Renderer2D.fillRect`
  (`Player.java` references it for stamina display). No editor panels in
  play mode. Cursor hidden. WASD + mouselook + Player physics.

## Asset paths

- Models: `src/main/resources/models/`
- Characters: `src/main/resources/characters/` (path prefix `characters/` in level.json)
- Textures: `src/main/resources/textures/`
- Skybox cubemap: `src/main/resources/textures/{px,nx,py,ny,pz,nz}.png`
- Shaders: `src/main/resources/shaders/` (OpenGL reads from disk; Vulkan inlines GLSL as string constants in `VulkanMain.java` and compiles via shaderc)
- Level: `level.json` (project root)

## Run / build

- Main classes:
  - `com.myengine.Main` — OpenGL build (still functional, do NOT break)
  - `com.myengine.VulkanMain` — Vulkan build
- Compile helper: `compile.ps1 <file1.java> <file2.java> ...` (PowerShell)
- macOS needs `-XstartOnFirstThread` VM option
- macOS uses MoltenVK; instance creation requires `VK_KHR_portability_enumeration` extension + the corresponding flag (`VkContext.createInstance` handles this).

## Push-constant layout reference (model pipeline, 240 B / 60 floats)

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 64 | `mat4 model` | TRS for this entity |
| 64 | 64 | `mat4 viewProj` | projection × view, per frame |
| 128 | 64 | `mat4 lightSpace` | shadow projection × shadow view |
| 192 | 16 | `vec4 material` | x=triplanar 0/1, y=texScale, z/w=unused |
| 208 | 16 | `vec4 tint` | rgb=Material.r/g/b, w=unused |
| 224 | 16 | `vec4 sunDir` | xyz=normalised dir, w=sunIntensity |

Shadow pipeline PC (128 B / 32 floats): `mat4 model`, `mat4 lightSpace`.

Skybox PC (64 B / 16 floats): `mat4 mvp`.

ImGui PC (16 B / 4 floats): `vec2 scale`, `vec2 translate`.

## Working notes (session log)

Append a one-line entry whenever a sub-phase is finished or a structural
decision is made. Keep entries terse.

- Sub-phase A done: `VkOffscreenTarget` carries the scene through ImGui's
  Scene Viewport panel; `VkImGuiRenderer` now supports multiple textures
  (font + viewport + shadow map); Shadow Map Debug panel works; click-to-
  select via AABB raycast; foliage shadow alpha-discard (shadow pipeline
  now binds the model descriptor set + reads UV).
- Sub-phase B done (visible-only): `VkLineRenderer` + `VkGizmo` + `VkSelectionRenderer`
  render the per-axis Move/Rotate/Scale gizmo + an orange wireframe AABB
  outline for the selected entity. Drag-to-edit gizmo deferred to C; the
  proper stencil silhouette outline deferred to H. Hazmat wireframe
  capsule intentionally NOT ported (parked item).
- Sub-phase C done: `UndoRedoManager` wired up. Ctrl+Z / Ctrl+Y / X delete
  match `LevelEditorScene.java:831-865`. Inspector position/rotation/scale
  drags now push `TransformCommand`s on item-deactivated-after-edit.
  Box-select (B-key) deferred.
- Sub-phase D done: state-flag MainMenu (no full `Scene` class abstraction
  yet — `VulkanMain` has `inMainMenu` + `enterMainMenu/Play/Editor` helpers).
  Esc returns to menu from Editor/Play. Font scaled 2.2× at the menu.
- Sub-phase E foundation: `VkMesh` now carries computed local bounds
  (`minX..maxZ`). Selection + picking use them when the mesh is cached;
  ±0.5 fallback otherwise. Full Player physics port still pending.
- Sub-phase E full: `MeshRenderer.vkLocal{Positions,Indices,Bounds}` added,
  populated by `VkMeshLoader`/procedural builders. `GameObject.worldBounds`
  + `MeshCollider` use renderer-agnostic helpers — same code path serves
  OpenGL + Vulkan. `Player` + `World` (no `init()`) wired into `VulkanMain`.
  Mouselook drives `player.yaw/pitch`. Double-tap Space toggles fly.
  Win-Zone trigger ported. Click-to-select now uses mesh-accurate
  `MeshCollider.rayMesh` instead of AABB.
- Sub-phase B drag follow-up: gizmo drag-to-edit fully ported. LMB-press
  on a gizmo axis sets `draggingAxis`, mouse-drag invokes verbatim
  `axisScreenDelta` + `updateTransformDrag`, LMB-release commits a
  `TransformCommand` for undo. Shift-snap on Move axis, world-axis
  rotation matrix compose, axis/center scale all match the OpenGL
  editor (LevelEditorScene.java:937-1018).
- Picking bug: FOV mismatch between renderer (hardcoded 60°) and picker
  (Config.FOV=70°) caused cursor offset. Both now use
  `player.currentFov` (Player ramps to 90 when running, 100 sliding).
  Per-frame hover-pick via `pickEntityAt`; result drawn dim-orange to
  preview what LMB will click.
- Sub-phase H polish: VkRenderer2D had a buffer-aliasing bug — every
  fillRect/outlineRect wrote to offset 0 of one buffer, so a second
  call clobbered the first call's vertices before the GPU drained the
  queue (stamina bar fill landed on top of the bg geometry → both
  rects looked empty). Fixed with a per-frame bump-cursor ring buffer
  (`beginFrame()` resets, each draw writes at the current offset then
  bumps to next vec2-aligned slot).
- Marker billboards (spawn / win) now render. New `buildSpawnQuadMesh`
  + `SPAWN_POINT_KEY` mesh path, dedicated marker draw pass after the
  model loop. Model matrix = translate · (view-rotation⁻¹) · scale so
  the quad always faces the camera; visible in editor mode AND in play
  + fly (per user spec). Markers fall through the alpha-discard path
  of MODEL_FRAG so the sprite cutout works.
- Group-select pivot: 2+ hits from a box-select now build a transient
  "(Group: N objects)" GameObject at the centroid; the gizmo + Inspector
  operate on the proxy and `applyGroupDeltaAndReset()` per frame
  diffs proxy.transform vs prevProxy* to propagate translate /
  rotate-around-centre / scale-from-centre to every real entity, then
  re-anchors the proxy. Verbatim port of LevelEditorScene.applyGroupDeltaAndReset:1262-1350.
  Group undo deferred (would need a composite command).
- Play-fly LMB/RMB: LMB raycasts from camera and deletes the hit entity
  (no range cap); RMB places a procedural cube either snapped to the
  hit point (Math.round so cubes align) or 1 m in front of the player
  if nothing's in sight. Mirrors LevelEditorScene.java:1069-1081.
- Sub-phase H + C box-select done: `VkRenderer2D` ports the OpenGL
  `Renderer2D` (fillRect + outlineRect + pxX/pxY) with a position-only
  pipeline that's lazy-built per render pass. Stamina bar shows in
  play mode when `climbStamina < CLIMB_MAX_STAMINA` or actively climbing.
  Box-select: B in editor arms the mode, LMB-drag in viewport draws a
  blue rectangle, release projects each entity's AABB centre to screen
  and collects those inside the rect. Single-hit promotes to
  `selectedEntity` (full gizmo+Inspector); multi-hit fills
  `selectedObjects` and each gets an orange outline. Group-drag deferred.
- Sub-phase G follow-up: skinned shadow + skinned outline pipelines
  landed. `SKINNED_SHADOW_VERT` + `skinnedShadowPipeline` make the
  character's cast shadow follow the animation; `SKINNED_OUTLINE_VERT`
  + skinnedMark/Outline pipelines in `VkOutlineRenderer` make the
  selection silhouette deform with the bones. `VkOutlineRenderer.setSkinnedRuntime`
  wires the bone UBO layout in after init. Hazmat texture auto-derives
  from filename (characters/Hazmat.glb → textures/Hazmat.png) in
  `spawnModelInFront`. Editor shortcuts (P/X/Ctrl+Z/Y/Space) now gated
  on `ImGui.WantTextInput` so typing into Inspector text fields no
  longer hijacks the keystrokes.
- Sub-phase G basic done: `VkMesh.addSkinning` carries the per-vertex
  bone-IDs/weights buffer at binding 1; `VkMeshLoader.buildSkinnedMesh`
  extracts the `Skeleton` (BoneInfo + NodeData + globalInverse) verbatim
  from `ModelLoader.loadModel:128-169`. `VkSkinnedRuntime` owns a UBO +
  descriptor set per Animator (set 1, binding 0). New `skinnedModelPipeline`
  + `SKINNED_MODEL_VERT` runs the skinning matrix-blend loop from
  `default.vert:32-44`. `MeshRenderer.vkSkeleton` + `skeleton()` helper
  is renderer-agnostic; `Animator.recompute` now reads through it.
  Inspector Animator section ported verbatim from `UIManager.java:533-580`
  with an "Add Animator" affordance for skinned entities without one.
  Anisotropic device feature enabled in `VkContext` + sampler — kills
  the stretched-strip aliasing on thin walls at glancing angles.
  Cube fragment-shader PC layout was OUT OF SYNC with its vertex stage —
  fixed: tiling toggle + texture scale slider now actually work and
  texture stops "multiplying like crazy at distance" (was reading
  garbage from the lightSpace translation column).
- Gamma fix: whole chain switched from `*_SRGB` to `*_UNORM` formats
  (swapchain, 2D textures, cubemap, offscreen scene target). The GL build
  never enabled GL_FRAMEBUFFER_SRGB, so its lighting ran in gamma space
  and bytes hit the display raw; the Vulkan _SRGB formats added a
  linear→sRGB encode on write which washed out the whole frame. UNORM
  reproduces GL byte-for-byte. Also silenced the harmless loader warning
  about OBS/Wallpaper-Engine's duplicate VK_LAYER_WEOBS_HOOK manifests.
- Player capsule rework: `Player.capsuleRadius/Height` replace the
  hardcoded 0.3/1.8/2.0 body dims (eye = 90% of height, slide-eye 40%,
  headroom 10%). The Spawn Point's scale IS the capsule definition
  (scaleX/Z = diameter, scaleY = height) — green wireframe capsule drawn
  at every spawn point in editor/play-fly, edited via Scale gizmo or the
  Inspector "Player Capsule" fields, applied to physics on play start.
  Legacy spawn points (scale 1,1,1) auto-migrate to 0.6/2.0/0.6.
- Capsule decoupled from Spawn Point: new "Character Collision" gameplay
  object (`World.PLAYER_BODY_KEY = "__player_body"`). Standalone entity —
  scale is the capsule (X/Z diameter, Y height), position is wherever it
  sits in the editor, gizmo edits it independently of the spawn point.
  Spawn button re-selects the existing one instead of duplicating. Green
  wireframe capsule centred on its transform; Inspector "Player Capsule"
  section moved to it; physics reads dimensions from it on play start
  (position still from Spawn Point). Skipped by collision grid + meshKey
  returns null (no mesh, no sprite).
- Horizontal collide-and-slide (final wall/ramp model). Replaced the
  binary checkWallCollision-revert AND the `alreadyInside` bypass (which
  let you bulldoze into a cube by holding W) with proper collide-and-slide:
  `castSteepWallNormal` casts forward rays (3 heights knee→head × 3 lateral
  offsets across the capsule width) into steep geometry; the move loop
  strips the velocity component pointing INTO the nearest wall normal,
  keeping the tangential part (up to 3 iterations for corners). One
  mechanism covers all cases: walk-into-wall → stop/strafe, jump+W into a
  ramp → can't enter, standing on a steep ramp → uphill stripped, gravity
  downhill kept → slide down. Never penetrates (so nothing to depenetrate →
  no teleport) and never hard-reverts (so no freeze). `checkWallCollision`
  deleted (dead). Walkable surfaces skipped by the ray filter so walking
  up ≤45° ramps is unaffected.
- Ramp "stuck in wall" fix: when the feet clamp onto a steep ramp the
  TORSO overlaps the up-slope part of the ramp (it rises into the chest),
  so `checkWallCollision` (binary overlap→revert) reverted EVERY
  horizontal move including the downhill slide → frozen on the ramp, and
  with depenetration gone nothing pushed out either. Fix: compute
  `alreadyInside = checkWallCollision(current pos)` BEFORE moving; if
  already in contact with steep geometry, don't revert horizontal moves
  (let the gravity slide carry you out). A fresh wall can only be ENTERED
  when NOT already touching one, so that case still blocks — you can't
  walk through walls, but you can slide off / escape a ramp you're on.
  `intersectsAABBSteep` only counts steep faces, so walkable ramps never
  trip `alreadyInside` and keep normal wall-blocking.
- Ramp collision FINAL (ray-only, no depenetration). Reverted the
  sphere-depenetration experiment entirely — it was the teleport source
  (min-translation pushes out the SHORTEST side, so a fast jump+W past a
  thick tilted bar's midline ejects out the FAR side = teleport through;
  and edge contacts flip the push dir = jitter at -164). Deleted
  `MeshCollider.sphereContact` + `sphereVsObjAABB` + `closestPointOnTriangle`
  (all dead now). New model is purely ray-based ground detection, which
  can never teleport/tunnel (a down-ray finds the surface from above and
  places feet on top):
  * `probeSurfacesBelow` returns highest WALKABLE and highest STEEP
    surface SEPARATELY (steep carries its normal).
  * Vertical branch: walkable reachable → stand/step (grounded). Else if
    a STEEP surface is at/below the feet (`steepY <= feetY+0.1`) and the
    feet would cross it → clamp feet onto it + project velocity onto the
    slope (slide), not grounded. Else fall. The `steepY <= feetY` guard
    is what kills the phantom-slide: a steep face rising BESIDE you
    (caught by an edge ray ABOVE your feet) no longer yanks you onto it.
  * No sphere depenetration anywhere → no teleport, no jitter. Slide
    emerges from gravity + velocity projection; downhill horizontal is
    allowed by checkWallCollision, uphill is blocked.
- Ramp/cube collision clean rework (fixes: slide-shows-when-not-
  perpendicular, jump+W teleports through -136, 1m cube auto-climb/
  spawn-inside). Separated the two concerns properly:
  * WALKABLE ground (≤45°) → `findGroundBelow` (now walkable-only, returns
    just the highest walkable Y) + the one-branch ground-snap. Steep
    surfaces are NOT ground and are ignored here.
  * SOLID steep surfaces → `resolveSteepContacts` capsule depenetration
    only. Removed the vertical steep-clamp that fired on single edge rays
    (that was the spurious slide when walking near a steep face).
  * `MeshCollider.sphereContact` rewritten as proper MINIMUM-TRANSLATION
    depenetration (shortest exit). The old code special-cased "inside"
    contacts and shoved along the most-upward normal by the full depth —
    which launched the player to the top of a ramp on jump+W (the
    teleport). Shortest-exit pushes back out the way you came; can't
    teleport across the solid.
  * `Config.PLAYER_STEP_HEIGHT = 0.4` (was a hardcoded 1.05). 1m cubes are
    now walls (checkWallCollision blocks at body height) — you stop and
    must jump, instead of being auto-stepped up inside them. Curbs ≤0.4
    still step.
- Ramp tunnel fix #3 (ACTUAL root cause): `findGroundBelow` "preferred
  the highest WALKABLE hit". On the thin tilted -152° slab the player's
  footprint overhangs the slab's narrow edge, so one of the 5 probe rays
  misses the slab and hits the FLOOR far below. The walkable-preference
  then returned that distant floor instead of the steep slab directly
  under the feet — the steep-clamp branch never ran and the player
  free-fell through. Fix: findGroundBelow now returns the HIGHEST surface
  under the footprint regardless of slope (+ its normal); the vertical
  branch decides walkable→land vs steep→slide. On-slab rays win (slab top
  is higher than the floor) so the steep clamp always engages. Vertical
  branch rewritten into one clean if/else: g==null→fall, walkable &
  (cross|step-follow)→land, steep & cross→clamp-and-slide, else→fall.
  Probe range scales with fall speed (no tunnelling at any framerate).
- Ramp tunnel fix #2 ("teleports through sometimes"): the sphere-overlap
  resolver alone can MISS when a frame hitch sinks the feet deeper than
  the sphere radius past a thin slab — and the inside-contact push can
  then eject DOWNWARD (wrong side). Two changes: (1) ray-based steep
  clamp in the vertical branch — findGroundBelow now returns the full
  surface normal; when a steep surface lies within this frame's fall
  step, feet are clamped onto it and velocity is projected onto the
  slope plane (slide). Rays can't tunnel at any dt. Probe range also
  extends with fall speed. (2) Feet sphere now accepts only clearly
  UPWARD pushes (ny > 0.25) — near-horizontal keeps step-up alive,
  downward pushes (thin-slab underside) are exactly the eject bug.
- Steep-surface fix (the -136° ramp fall-through): new
  `Player.resolveSteepContacts` — 3-sphere capsule penetration resolve
  via MeshCollider.sphereContact, run after vertical movement. Walkable
  contacts (ny >= 0.707) skipped (ground-follow owns them); feet-sphere
  near-horizontal contacts (|ny| < 0.25) skipped to keep step-up alive.
  Steep ramps are now SOLID but unwalkable: push-out + into-surface
  velocity cancel + gravity = slide down, never penetrate.
- Mipmap bug: 2D textures were uploaded with `mipLevels=1` + sampler
  `MIPMAP_MODE_NEAREST`, so at distance every minified sample collapsed
  to one texel — looked like the texture was "morphing" into a checker
  pattern (user's stone-wall screenshot). Fix in `VkTexture.uploadRgba`:
  full mip chain via `vkCmdBlitImage` (linear filter), image view spans
  all levels, sampler now uses `MIPMAP_MODE_LINEAR` + `maxLod = mipLevels`
  (trilinear filtering). Image usage gains `TRANSFER_SRC_BIT` so a level
  can be blitted from to populate the next. Cubemap path left at 1 mip
  (skybox doesn't benefit).
- Step smoothing rework: collider SNAPS to walkable ground every frame
  (no body Y-lerp) — kills ramp jitter + stair stall + floating. The
  smoothness moved to a camera low-pass (`Player.stepCamOffset`, eased to
  0, added to `camY` in VulkanMain). `Config.PLAYER_STEP_HEIGHT = 0.5`.
- Shadow rework: single "Shadow Distance" control derives ortho/cam/far;
  box is texel-snapped (kills shimmer). Named Depth/Normal bias sliders fed
  via the unused `material.zw` PC slots (no PC growth). CSM assessed and
  DEFERRED (would require turning lightSpace into a per-cascade UBO array
  across every pipeline + the 240B ABI).
- Selection outline math: switched the silhouette-inflation push from a
  local-normal epsilon (catastrophic cancellation at large scale → warping)
  to a proper normal-matrix (inverse-transpose) screen-space normal push,
  scaled by clip-w for uniform pixel thickness. Both static + skinned
  `VkOutlineRenderer` verts.
- Play-mode gating + state reset: editor tools (raycast select / place /
  delete) gated `!isEditorMode && player.isFlying`; `player.isFlying` forced
  false on every play entry. See Regression vault.
- Component serialization fix: added simple-class-name aliases in
  `ComponentRegistry` so `WallRunnable`/`Invisible`/`SpawnPoint`/`WinZone`/
  `Walkthrough` survive save→reload. WallRunnable wall-run logic verified
  correct (uses renderer-agnostic `intersectsAABBSteep`); transition log added.
- Gizmo rotation → ray-to-plane (see Regression vault).
- Settings UI + window management: `SettingsUI` popup (Display Mode / Aspect /
  Resolution) reachable from Main Menu button + editor "Settings" menu; wired
  to `glfwSetWindowMonitor`/size/aspect. New `recreateSwapchain()` +
  `framebufferResized` flag survive the resize. Editor panel open/closed state
  persisted to `editor_prefs.json`. See Regression vault.
- ImGui multi-viewport (detach panels to OS windows): full backend BUILT —
  `VkViewportRenderer` (per-window swapchain) + `VkGlfwPlatformIO` (NO_API
  child windows, replacing ImGuiImplGlfw's GL-only platform half) +
  `VkImGuiRenderer.record()` generalized. Then PARKED behind
  `MULTI_VIEWPORT = false` (VulkanMain): multi-swapchain present jitter on the
  shared queue + ViewportsEnable forcing global desktop mouse coords desynced
  the raycaster. Internal docking (DockingEnable + dockSpaceOverViewport) is
  the shipping path. Flip the flag to re-arm once present-sync + coordinate
  routing are reworked.
- See PORTING_NOTES.md for full OpenGL engine walk-through + per-bug
  reproduction notes.
