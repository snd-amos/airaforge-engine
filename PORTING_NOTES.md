# JE Parkour V2 — Vulkan Port Notes

Running scratchpad for the porting effort. Three parts:

- **§1. OpenGL engine walk-through** — what each piece of the old engine
  does, so we don't re-derive it from screenshots every session.
- **§2. Vulkan-specific landmines** — pitfalls we've hit so we don't
  re-hit them.
- **§3. Bug list** — user-reported issues with reproduction notes.

Read CLAUDE.md first for the sub-phase status board. Come here for the
day-to-day detail.

The pre-Vulkan working copy lives at `C:\Users\soundye\Desktop\beforevlk\`
(diff against it when behaviour drifts).

---

## §1. OpenGL engine walk-through

### App boot — `Window.run` (`Window.java`)

1. `glfwInit` + create maximized GL 3.3 core window. Reads framebuffer
   size (not window size) so HiDPI displays are correct.
2. Hooks GLFW callbacks (key / cursor / mouse-button) that just forward
   to the active scene via `GameStateManager.currentScene`.
3. **ImGui is APP-LIFETIME, not per scene.** Created here with
   `DockingEnable` config flag + `installCallbacks=true` (ImGui chains
   on top of Window's callbacks). Per the comment, re-init from a
   second scene corrupts SpaiR's callback chain — so the context lives
   for the whole app.
4. `GameStateManager.changeScene(new MainMenuScene(...))` — first scene.
5. Main loop: 120 fps cap (`TARGET_FPS`). Each tick:
   - dt clamped to 0.1 (so a slow frame doesn't tunnel the player
     through geometry — comment at `Window.java:55-58`).
   - `Profiler.frameStart()`, then `scene.update(dt, keys)`,
     `glClear`, `scene.render()`, `glfwSwapBuffers`, `glfwPollEvents`,
     `Profiler.frameEnd()`.

### Scenes

`Scene` is a tiny abstract interface: `init / update / render / destroy
/ onMouseClick / onMouseMove / onKeyPress`. Two implementations:

**`MainMenuScene`** — single centered "Main Menu" panel with
`JEngine Parkour` header + 3 large blue buttons (Start Game / Open
Level Editor / Quit). Font scaled 2.2× in `init()`. Background colour:
dark brown `(0.08, 0.06, 0.04)`. Start Game → `LevelEditorScene(...,
startInEditorMode=false)`. Open Level Editor → same scene with
`true`.

**`LevelEditorScene`** is the workhorse. Same scene serves Editor and
Play; difference is the `isEditorMode` flag (toggled by P). Sub-systems:

- `Player player` — physics + camera state. Always runs (both modes).
- `World world` — entity list + spatial grid for collision queries.
- `UIManager uiManager` — every ImGui panel.
- `Shader defaultShader` / `depthShader` / `defaultInstancedShader` /
  `depthInstancedShader` / `outlineShader` — 5 shaders.
- `Framebuffer framebuffer` — offscreen color+depth target. Scene
  renders into this; `uiManager.render(... framebuffer.textureID)` shows
  it inside the Scene Viewport ImGui panel.
- `ShadowMapFBO shadowFBO` — sun depth target.
- `CubeBatch cubeBatch` — collects procedural cubes per frame, draws
  them in one instanced draw call.

`init()` flow (lines 110-216):
1. Shaders + framebuffers.
2. Load level → fix up Sun (dedupe, seed default at `(-10, 26, -40)`).
3. Seed default Floor entity if level lacks one.
4. `world.updateGrid()` before the first physics tick.
5. Reset player to `(0, PLAYER_START_Y, 0)`.
6. If in Play mode AND level has a Spawn Point, teleport to it.

### `update(dt, keys)` flow (lines 250-279)

1. `world.updateGrid()`.
2. `player.update(modifiedKeys, world, false, dt)` — in editor mode, if
   currently gizmo-dragging, Shift is forced false in the keys array so
   the user can hold Shift to snap-to-grid without sprinting.
3. Play mode only: `checkWinZones()`.
4. Loop every entity, call `obj.update(dt)` — drives Animator etc.
5. `applyGroupDeltaAndReset()` — multi-select group proxy propagates
   gizmo deltas to all selected objects.

### `render()` flow (lines 290-707)

**Three passes**, profiled:

**Pass 1 — Shadow pass** (`Shadow pass`, lines 301-410):
- Bind `shadowFBO`, clear depth.
- Build ortho light proj that's `Config.SHADOW_ORTHO_SIZE` wide.
- Light view = `rotateX(sunPitch) · rotateY(-sunYaw) · translate(-camPos)`
  where camPos = player + sunDir × `SHADOW_CAM_HEIGHT`. Sun follows the
  player so the shadow frustum tracks the visible scene.
- `glColorMask false`, `glDisable(GL_CULL_FACE)`,
  `glPolygonOffset(2.0, 4.0)` — depth-only pass.
- Loop entities, skip sunBlock + markers + cubes (cubes go through
  `depthInstancedShader` afterwards).
- Per-entity foliage detection: `useAlpha = mr.isFoliage && mr.textureID > 0`
  → fragment shader does alpha-discard for leaf trees.

**Pass 2 — Main pass** (`Main pass`, lines 419-616):
- If editor: bind `framebuffer` (offscreen), set viewport to viewport
  panel size, projection updated. If play: `framebuffer.unbind()`, set
  to screen size.
- View matrix: `rotateZ(player.roll) · rotateX(player.pitch) ·
  rotateY(player.yaw) · translate(-player.x, -(player.y + cameraYOffset),
  -player.z)`. Z FIRST (roll = wall-run / slide tilt).
- `world.drawSkybox(...)` — cube with sampler, depth-writes off.
- Bind `defaultShader`, upload view/proj/lightView/lightProj/sunPos/
  shadow map.
- **Hover pick** (line 485): if `uiManager.isViewportHovered` AND no
  gizmo axis is hovered → `highlighted = player.getEntityAtMouse(...)`.
  Updates every frame the cursor is over the viewport.
- Loop entities. For each:
  - Skip sun if not editor; skip cubes (instanced); skip markers if not
    editor.
  - Build model matrix via `RenderState.modelMatrix(b.transform, ...)`
    (`translate · Rx · Ry · Rz · scale`).
  - Markers (spawn / win zone) → billboard: model matrix is pure
    translation + inverse of view rotation so the quad faces the camera.
  - Sun cube: special — drawn via `LineRenderer3D.drawMesh(cube, model,
    1, 1, 0)` (flat yellow).
  - Set per-material uniforms: `colorTint = mr.material.r/g/b`,
    `useWorldSpaceTiling`, `textureScale`, `sunIntensity` (1.0 for
    markers, `scene.sunIntensity` for everyone else).
  - Skinning: if `mr.mesh.isSkinned`, upload `finalBonesMatrices` from
    Animator (or identity if no animation).
  - `glBindTexture` for diffuse, `meshToRender.render()`.
- After entities: `cubeBatch.flush(...)` for procedural cubes.

**Pass 3 — Selection + overlays** (`Selection + overlays`, lines 618-697):
- For each selected entity: silhouette mark into stencil
  (`SelectionBoxRenderer.drawSilhouetteMark`).
- Skeleton debug overlay if Show Bones is on.
- For each selected entity: stencil-based mesh outline OR wireframe AABB
  (`SelectionBoxRenderer.drawWireframeOnly`).
- For the hover-pick entity: same outline but dimmer (`(0, 0, 0)`
  thickness 0 → uses the outline shader as-is).
- Gizmo: `Gizmo.render(selectedBox, isSun, draggingAxis, isHovering*)`.
- Editor only: `drawPlayerCapsule()` — wireframe capsule around the
  player so you can see the hitbox.
- If box-dragging: `drawBoxSelectOverlay()` — 2D rectangle.
- `drawCrosshair()` and `drawStaminaBar()`.

**Pass 4 — ImGui** (`UI (ImGui)`, lines 701-706): editor only.
`framebuffer.unbind()` then `uiManager.render(this, framebufferTextureID)`.

### Picking — exact flow

Source: `Player.getEntityAtMouse` (line 566) + `MeshCollider.rayMesh`
(line 17).

```
getMouseRay(mouseX, mouseY, viewportW, viewportH, player.currentFov,
            player.pitch, player.yaw):
  nx = 2*mouseX/W - 1                 # NDC, [-1, +1]
  ny = 1 - 2*mouseY/H                 # NDC, +1 = top
  fovRad = toRadians(fov / 2)         # half-FOV in radians
  aspect = W/H
  vx = nx * aspect * tan(fovRad)
  vy = ny * tan(fovRad)
  vz = -1                             # view-space forward
  normalize (vx, vy, vz)
  # Rotate by camera pitch then yaw (inverse view rotation).
  apply pitch: rotateX(-pitch)
  apply yaw:   rotateY(-yaw)
  return world-space direction
```

CRITICAL: `fov` here is `player.currentFov` — Player ramps it to 90
when running, 100 when sliding. **The renderer's projection MUST use
the same value** or the cursor offsets. Mismatched FOV = "I clicked X
but the entity left of X got picked".

Per-frame in render():
```
if (uiManager.isViewportHovered) {
    highlighted = player.getEntityAtMouse(...);
}
```

On LMB-press inside viewport:
```
hit = Gizmo.getHitAxis(player, selectedBox, ...);
if (hit != -1) {
    draggingAxis = hit;
    rawDragX/Y/Z = selectedBox.transform.x/y/z;
} else {
    b = player.getEntityAtMouse(...);
    if (b != null) {
        exitMultiSelect();
        selectedBox = b;
    } else {
        selectedBox = null;
    }
}
```

`MeshCollider.rayMesh(obj, ox, oy, oz, dx, dy, dz)`:
1. Coarse AABB pre-filter (`obj.getMinX..maxZ` — accounts for
   transform).
2. If `mr.mesh` is null OR positions array is null → return AABB
   `t`.
3. Markers (spawn / win zone) → return AABB `t`.
4. Otherwise: `computeWorldPositions(obj, mr)` — transforms every
   vertex through TRS (+ skinning if active), then Möller-Trumbore
   ray-triangle against each tri. Returns CLOSEST hit's `t`.

So picking is **mesh-accurate**: cursor → triangle, not cursor →
bounding box. That's where "pixel-perfect from any distance" comes
from.

### Player physics — `Player.update` (`Player.java:48`)

The 600-line beast. Top-level state machine:
`IDLE / WALKING / RUNNING / IN_AIR / WALL_RUNNING / LEDGE_GRABBING /
SLIDING / CLIMBING`.

Key entrypoints I care about for the port:

- `yaw / pitch` — set externally by mouselook. Clamped to ±89°.
- `x, y, z` — eye position (feet are 1.8 below).
- `currentFov` — used by the renderer + picker. Ramped to:
  - `70.0f` idle/walking
  - `90.0f` when RUNNING or WALL_RUNNING
  - `100.0f` when SLIDING
- `cameraYOffset` — head-bob, ±0.06 sinusoidal when grounded + moving.
- `roll` — wall-run/slide tilt (rotateZ in the view matrix).
- `isFlying` — toggled by double-tap Space (LevelEditorScene:867-876).
  When true, gravity + collision skipped, WASD just translates,
  Space/C move up/down.

`World` methods Player uses:
- `world.entities` (read)
- `world.getNearbyEntities(x, z)` — spatial-grid query for collision.

### Stamina HUD (`Renderer2D.fillRect`, `drawStaminaBar` line 720)

```
if climbStamina >= MAX && not CLIMBING: hide
ratio = climbStamina / MAX
NDC box: x in [-0.15, 0.15], y in [0.9, 0.925]
Background: fillRect dark grey alpha 0.75
Fill bar:   green > 50%, yellow > 25%, red below
Outline:    white alpha 0.85
```

All `Renderer2D.fillRect / outlineRect` calls in NDC. Needs a Vulkan
2D renderer port to display.

### UIManager — every panel

`UIManager.render(scene, framebufferTextureID)` (line 58) calls:
- `dockSpaceOverViewport(getMainViewport())`
- `renderViewport(framebufferTextureID)` — Scene Viewport panel
- `renderAssetBrowser(scene)` — Save/Load + spawn buttons
- `renderGameplayObjects(scene)` — Spawn Point / Win Zone
- `renderInspector(scene)` — selected entity properties
- `Shadow Map Debug` panel inline
- conditional `renderDebugHud(scene)` — XYZ, yaw, pitch, compass
- conditional `renderProfiler()` — zones table

`renderInspector` is the longest panel — name, transform mode radio,
position/rotation/scale (with undo recording on
`isItemDeactivatedAfterEdit`), MeshRenderer (texture + model + foliage
flag), Animator (file load, play/pause, animation trim), Material
(color tint, world-space tiling + scale slider, per-face colors).

---

## §2. Vulkan-specific landmines (already hit, don't repeat)

- **`WantCaptureMouse` is ALWAYS true** while hovering the Scene
  Viewport panel — the panel IS an ImGui window. Gating LMB on it drops
  every viewport click. Use `isViewportHovered` (from
  `ImGui.isWindowHovered()` inside the panel) instead.
- **`WantCaptureKeyboard`** is true any time a panel can receive keys.
  `WantTextInput` is the right gate for "don't fight inputText typing".
- **ImGui.image UV flip**: in Vulkan the offscreen color image already
  has top-left origin (because the scene render uses a negative-height
  viewport). Sample with `(0,0)-(1,1)`, NOT `(0,1)-(1,0)` like OpenGL.
- **Render-pass compatibility**: validation layers (newer builds)
  compare full subpass dependency stage/access masks, not just counts.
  Mismatched stage masks → VUID-vkCmdDrawIndexed-renderPass-02684.
  Keep them byte-identical between swap and offscreen render passes.
- **Dynamic ImGui vertex/index buffer growth** must `vkDeviceWaitIdle`
  before destroying the old buffer — in-flight command buffers still
  reference it.
- **240 B push constants** exceed Vulkan's 128 B minimum guarantee but
  are fine on every desktop driver we target. If spec-minimum hardware
  becomes a concern, move per-frame data into a UBO.
- **FOV mismatch between renderer + picker** = cursor offset bug.
  Renderer's `setPerspective` and picker's `RayCaster.getMouseRay`
  MUST use the same `player.currentFov`. Don't hardcode 60 in one and
  use `Config.FOV = 70` in the other.
- **`MeshRenderer.start()`** calls into the GL `AssetPool` and crashes
  without a GL context. The Vulkan load path uses
  `GameObject.addComponentNoStart` + `LevelLoader.loadLevelData` (the
  no-start variant). Post-load we run `applyMeshRendererDefaults` and
  `populateVkLocalGeometry` to fill in what `start()` would have set.

---

## §3. Bug list — user-reported

(Add a date when reported, strike through when resolved.)

- **2026-05-29 — FOV mismatch causes "cursor selects wrong block"** —
  renderer was projecting with hardcoded 60°, picker with `Config.FOV
  = 70°`. ✅ FIXED — both now use `player.currentFov`.
- **2026-05-29 — No hover highlight on Scene Viewport** — ✅ FIXED
  (`highlighted` field refreshed every frame the cursor is in the
  viewport, drawn dim-orange).
- **2026-05-29 — "Sometimes I try to select them but they're not
  getting selected"** — Likely same root cause as the FOV bug
  (cursor offset put the ray outside the entity). Re-test after FOV
  fix.
- **2026-05-29 — Climb stamina bar / jump-charge HUD missing** —
  needs Vulkan 2D-renderer port. Open.
- **2026-05-29 — Floor selected when clicking near the horizon** —
  mathematically correct (ray hits floor far away). With hover
  highlight in place, user can see this before clicking. Not really
  a bug; future polish could limit max pick distance.

---

## §4. What I'm doing across sessions (status quick-glance)

Updated whenever a sub-phase ships. Full board in CLAUDE.md.

- Phases 1-13 done.
- Sub-phases A, B, C, D, E done; B drag follow-up done.
- **H (stencil silhouette outline) DONE** — `VkOutlineRenderer`,
  two-pass mark-then-outline. Depth attachment now D32S8/D24S8 so the
  stencil aspect is available. `VkPipelineBuilder.stencilMark()` /
  `stencilTestNotEqual()` configure the two passes. Used for both
  hover (dim orange, thickness 0.003) and selection (bright orange,
  thickness 0.005). AABB fallback retained for markers and entities
  whose mesh hasn't loaded.
- **F (instanced cubes + per-face) DONE** — `VkCubeBatch` per-frame
  collector + grouped flush. `VkPipelineBuilder.instanceLayout(...)`
  added for the second per-instance vertex binding. Shaders
  `CUBE_INSTANCED_VERT/FRAG` ported from `default_instanced.*` — the
  vertex shader picks one of six face colours from the local-space
  normal direction. Sun cube special-cased to yellow tint inside the
  batch. Cubes still go through the per-entity SHADOW pass — only
  the main colour pass is instanced (shadow could be too if perf
  warrants).
- G (skinned characters + Animator panel), C box-select follow-up,
  Renderer2D 2D HUD — NOT STARTED.

### Notes for the next session

- **F (instanced cubes)** — Read `CubeBatch.java` end-to-end first. The
  per-instance layout is 34 floats (mat4 model + 6 vec3 face colours).
  In Vulkan that's a second vertex binding with `inputRate = INSTANCE`,
  divisor = 1. Will need `VkPipelineBuilder.instanceLayout(...)` or
  similar. Two shaders to port: `default_instanced.vert/frag` (main
  pass) + `depth_instanced.vert` (shadow pass — same `depth.frag`).
- **G (skinning)** — Largest of the remaining. Needs:
  - Bone matrix UBO (one per skinned entity OR shared SSBO indexed by
    entity)
  - Skinned model vertex shader (reads boneIds + weights as vertex
    attribs; `Mesh.boneIds` + `Mesh.weights` are int[]/float[] arrays
    in the OpenGL Mesh class)
  - Per-frame: Animator.update writes `finalBoneMatrices` to its
    `matricesBuffer` — port into Vulkan UBO upload path
  - Inspector Animator panel — animation file load button, play/pause,
    trim XYZ + tilt XYZ + "Save Trim as Default", current time / duration
- **Renderer2D HUD** — `Renderer2D.fillRect(x0, y0, x1, y1, r, g, b, a)`
  takes NDC coords. Used by `drawStaminaBar` (climb stamina) +
  `drawCrosshair` (play mode). Simple pipeline: 2D vertex (vec2 pos) +
  uniform colour. Could share VkLineRenderer's plumbing.
