# Session Archive — 2026-06-12/13

Record of everything done in one long context window, so it survives a reset.
All changes compile (`.\compile.ps1`, 73 files, exit 0). Verification was
**compile-only + user playtests** — the Vulkan build (`VulkanMain`) cannot be
run in the assistant's environment (no GPU/display), so collision work relied
on the user running it and pasting console logs.

> NOTE: the same context window also built a *separate* project — a Java Swing
> image editor at `C:\Users\soundye\Desktop\IMGEDIT` (com.amos_robert.imageprocessor,
> packaged to an .exe). That is unrelated to this engine and is finished.

---

## THE #1 LESSON: there are TWO parallel builds

- **OpenGL build**: `Main` → `LevelEditorScene` (+ `UIManager`, `ModelLoader`,
  `SelectionBoxRenderer`, `Skeleton.drawDebug`, etc.).
- **Vulkan build**: `VulkanMain` — its OWN input callback, ImGui editor UI,
  component palette/inspector, mesh loader (`VkMeshLoader`/`VkMesh`), outline
  renderer (`VkOutlineRenderer`), line renderer (`VkLineRenderer`).

**The user runs the VULKAN build.** Early in the session, features were
implemented in OpenGL-only files and "didn't appear" for the user. Every
gameplay/editor feature must be ported to BOTH, or at least to `VulkanMain`.
`Player.java`, `World.java`, `MeshCollider.java`, `LevelLoader.java`,
`Component`/`ComponentRegistry`, `GameStateManager` are SHARED.

---

## Features added

### Copy / Paste (Ctrl+C / Ctrl+V) — editor
- `LevelLoader.deepCopy(GameObject)` and `deepCopy(GameObject, boolean skipStart)`
  — JSON round-trip clone (transient fields excluded). `skipStart=true` is the
  Vulkan path (no GL context → `MeshRenderer.start()` would crash on the GL
  AssetPool); the caller warms the Vk caches itself via `warmEntity`.
- OpenGL: `LevelEditorScene.onKeyPress` Ctrl+C/V branches (`clipboardGameObject`).
- Vulkan: `VulkanMain` key callback Ctrl+C/V branches (`clipboardEntity`,
  `deepCopy(..., true)` + `warmEntity`). Paste = new UUID + 0.5 X offset.
- imgui-capture allowlist extended so Ctrl+C/V pass through text-input gating.

### AABB pad for paper-thin / planar meshes (leaf tree)
- Degenerate axis (`max-min < EPSILON 0.01`) padded by `PAD 0.05`.
- OpenGL: `ModelLoader` (after extents, before `new Mesh`).
- Vulkan: `VkMesh` constructor (the Vk AABB-calculation point).

### WinZoneComponent
- New `WinZoneComponent extends Component` (tag). Registered in
  `ComponentRegistry` as `"WinZone"`.
- `GameStateManager.requestWin()` / `consumeWinRequest()` (single flag).
- `checkWinZones()` (both builds) now triggers on the component OR the legacy
  `WIN_ZONE_KEY` marker; calls `GameStateManager.requestWin()`.
- Vulkan: added `componentDragSource("WinZone")` to the palette + `WinZone`
  to the inspector's attached-component display list.

### Removed legacy hardcoded floor + marker spawn buttons
- Deleted `World.FLOOR_MESH_KEY` ("__floor_plane") + its mesh registration.
- Deleted the auto-floor seed in `LevelEditorScene.init`.
- Deleted `VulkanMain.buildFloorMesh`, the floor branch in `meshKey`/loader,
  the floor AABB-thickening (`minY=-16`) in `warmEntity`, `spawnFloorInFront`,
  `FLOOR_TEXTURE_SCALE_DEFAULT`, and the "Spawn: Floor/Win Zone/Spawn Point"
  buttons (both `UIManager` and `VulkanMain`). Floors are now user-built scaled
  cubes; spawn-point/win-zone are component-drag only.
- `LevelLoader.dropLegacyFloor(...)` migration strips any leftover
  `modelPath == "__floor_plane"` entity at load (both `loadLevel` +
  `loadLevelData`), so old saves auto-clean. `Config.FLOOR_GRID_SIZE` left in
  place (still referenced by a Vk comment; harmless).

### Vulkan foliage selection outline (leaf-shaped, not quad-shaped)
- `VkOutlineRenderer.OUTLINE_FRAG` *claimed* to alpha-discard but only output
  solid colour → leaf silhouette outlined the rectangular leaf quads.
- Fix: static outline pipelines now bind the entity's material descriptor set
  (`set=0,binding=0` diffuse) and the frag discards `alpha < 0.1` when
  `flags.x==1` (foliage). The stencil **mark** pass discards too, so the
  silhouette region follows the leaf shape.
- Skinned outline pipelines use a separate `OUTLINE_FRAG_SOLID` (characters
  bind the bone UBO at set 0, can't also sample a texture there; never foliage).
- PC bumped 36 → 40 floats (added `vec4 flags`). Constructor takes
  `modelSetLayout`. The 3 `drawOutline` call sites pass `descriptorSetFor(mr)`
  + `mr.isFoliage`. `mr.isFoliage` is set in `VulkanMain.applyMeshRendererDefaults`.

### Vulkan "Show Bones" skeleton debug
- `Skeleton.gatherDebugLines(Matrix4f[] debugBoneTransforms)` — GL-free, reuses
  the existing gather logic, returns `[boneLines, nodeLines]`.
- `VulkanMain`: `showBones` ImBoolean + checkbox in the Animator inspector
  section (under "Skeleton: N bones"); render-loop block draws the lines via
  `VkLineRenderer` (green bones T-pose / red bones animated / blue node links),
  mvp = `viewProj * model`. Reads `Animator.debugBoneTransforms` when animating.

---

## Player movement / collision saga (the big one)

Debugged with a temporary G-key console diagnostic (`[COLLIDE] branch=... walkHits
... steepHits ... feetY ... walkY ... steepY ...`) added to `Player.update`,
**now fully stripped**. Data-driven debugging was essential — every initial
hypothesis was wrong until the logs showed the real cause.

### 1. Ctrl+C also descended in fly mode
- `Player.java`: fly-down gated `keys[GLFW_KEY_C] && !keys[GLFW_KEY_LEFT_CONTROL]`.

### 2. Steep-ramp (>45°, e.g. −141°) jitter (FALL/STEEP every frame)
- Root cause (from logs): the STEEP branch clamps feet to `steepY + 0.005`
  (5mm ABOVE the surface), but its entry guard required feet to be sinking
  BELOW (`futureFeet <= steepY`). After each clamp the feet sit just above →
  next frame guard fails → FALL → drop 4mm → STEEP → clamp up. 4mm bounce.
- Fix: hysteresis clause —
  `onSteep = haveSteep && steepY <= feetY+0.1 && (futureFeet <= steepY || feetY <= steepY+0.06)`.
  Keeps fast-fall tunnel protection AND stays engaged while resting on the face.

### 3. Walkable ramp (≤45°, e.g. −135°) clip-through on sprint-jump
- Root cause: the rising phase (`yVel > 0`) did ZERO collision (`y += yVel*fdt`).
  Sprint-jumping a 45° ramp at ~13 m/s: the ramp surface climbs as fast as the
  jump, so the feet stay UNDER the ramp the whole arc → clip straight through →
  overshoot the top → free-fall to the floor.
- Fix: in the RISE branch, probe for a walkable surface; if `feetY < walkY`
  (penetrating), snap `y = walkY + feetOffset + 0.005` (ride the surface up).
  A jump that truly clears the ramp keeps feet above walkY → no-op. Scoped to
  walkable hits, so steep ramps are untouched.

### 4. "Stuck INSIDE the slab" on steep TILTED CUBES (the hard one)
- Root cause: the ground probe is a single infinitely-thin ray at the capsule
  CENTER. The slab cross-section (~1 unit) is comparable to the capsule radius
  (~0.3), so the center-ray punches through the near/outer face and locks onto
  the FAR/inner face → the whole capsule ends up inside the box.
- Thickening Scale Z did NOT fix it (made it more visible) — the issue is the
  center being inside, not slab thickness.
- Naive closest-point sphere depenetration would push out the FAR face when the
  center is inside a thin slab (the documented teleport-through bug that got the
  old `sphereContact` removed). Avoided.
- REAL FIX — Oriented-Box (OBB) push-out, exact + bounded for cubes:
  - `MeshCollider.pushOutOfCube(cube, wx, wy, wz, radius)` — transforms the
    point into the cube's LOCAL unit-box space (inverse of worldBounds'
    scale→Rz→Ry→Rx→translate), tests `±(0.5 + radius/scale)` per axis, pushes
    out the MIN-penetration face (always the nearest → can't teleport through).
  - `Player.resolveCubePenetration(world)` — called after the vertical phase;
    samples 3 spheres along the spine (feet/mid/head) against nearby cubes,
    applies the largest push, cancels the into-face velocity component (keeps
    the along-face slide).
  - **Scoped to ROTATED cubes only** (`isAxisAligned` via `isMultipleOf90` on
    rotX/Y/Z). Axis-aligned cubes (floors, walls, curbs, steps) are SKIPPED so
    normal cube collision / step-up is untouched.

### Net result
- ≤45° ramps: run / jump / walk-down all solid.
- >45° ramps: slide down correctly (feet physics right) AND OBB push-out keeps
  the body out of tilted cubes. User confirmed "PERFECT".
- Normal axis-aligned cubes: unchanged (push-out skips them).

---

## Open / parked

- OBB push-out only covers procedural CUBES (the ramp case). Tilted *imported
  meshes* used as ramps would still rely on the ray probe (not addressed; no
  current content needs it).
- `Config.FLOOR_GRID_SIZE` now unused on the OpenGL side (kept for a Vk comment
  ref). Remove in a future cleanup if desired.
- macOS still blocked on the file-texture GPU stall (see CLAUDE.md Open bugs) —
  untouched this session.
