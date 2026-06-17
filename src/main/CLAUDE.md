# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

Act as an expert Java Graphics and Game Engine Developer.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

# JE _Parkour V2 — Project State

A LWJGL/Java parkour engine + level editor. Player and editor share the same scene; double-tap Space toggles fly-mode that re-enables clicking to spawn/delete cubes.

## Tech stack

- Java 21, Maven (`pom.xml` at project root — cross-platform profiles for Windows/Linux/macOS Intel/macOS arm64, both LWJGL and ImGui natives).
- LWJGL 3.3.3 (OpenGL, GLFW, STB, Assimp).
- imgui-java 1.86.11 for the editor UI.
- JOML 1.10.5 for matrix math.
- GSON 2.10.1 for level save/load.

## Where things live

```
src/main/java/com/myengine/         all engine code
src/main/resources/
  models/        static .glb / .gltf / .obj / .fbx (props)
  characters/    character models (Hazmat.glb) — get a capsule collider
  textures/      .png textures (looked up by filename)
  shaders/       default.{vert,frag}, depth.{vert,frag}, outline.{vert,frag}
  animations/    animation overrides (animation_trims.json)

level.json              currently-loaded level (saved via editor)
level.backup.json       prior save snapshot
animation_trims.json    per-animation default offset/tilt trims
```

The asset browser lists `models/` and `characters/` separately; spawning from `characters/` prefixes the modelPath with `characters/` so `MeshRenderer.isCharacter()` returns true.

## Window

`Window.java` opens a **normal decorated window started MAXIMIZED** (title bar, min/max/X buttons all work). `glfwGetWindowSize` reads the real size after maximization so scenes/viewport get correct dimensions. Esc returns to the main menu.

## Scene structure

- `MainMenuScene` — title screen.
- `LevelEditorScene` — main scene. Toggles between **editor mode** (Inspector + Asset Browser + Gizmo) and **play mode** (FPS controls). Double-tap Space toggles fly. In play mode, place/delete blocks only works in fly.
- `GameStateManager.changeScene(...)` to switch.

## Player movement (the hard-won part)

`Player.java` runs an FSM (`State.IDLE / WALKING / RUNNING / IN_AIR / WALL_RUNNING / LEDGE_GRABBING / SLIDING / CLIMBING`). Movement is **dt-based** in real m/s units. Gravity = 25 m/s².

The **collision/ground model** (current, after many iterations):

1. **Horizontal phase — body AABB wall collision.** `checkWallCollision` uses an AABB starting at `feetY + stepHeight` (= knee) up to head, NOT feet-to-head. Calls `MeshCollider.intersectsAABBSteep` which only counts triangles whose `|normal.y| < WALKABLE_NORMAL_Y (0.707)`. Walkable triangles are ignored. Effect:
   - Walls (steep) block.
   - Obstacles ≤ `stepHeight` (1.05m) tall don't reach the body → don't block → you walk onto them and the vertical phase lifts you.
   - The steep base faces of rotated cubes (which would otherwise freeze you) are below the knee → ignored.
2. **Vertical phase — pure downward raycast.** `findGroundBelow` casts 5 rays (center + 4 footprint edges) from `feetY + stepHeight + 0.1` down through `stepHeight * 4`. **Prefers the highest WALKABLE hit** (`|normal.y| ≥ 0.707`) over steep ones — so at a slab's edge, a stray ray hitting the steep end-face doesn't make you fall in. Snap rules:
   - `futureFeet ≤ groundY` → land on it (handles arriving from a fall).
   - `wasGrounded && |feetY − groundY| ≤ stepHeight` → follow the surface (step up or walk down a slope).
   - Otherwise → free-fall (`y += yVel * fdt`).
3. **Snap epsilon.** `y = groundY + feetOffset + 0.005` — small float-above so the AABB bottom never sits exactly coincident with a face (SAT was flickering at exact boundaries).
4. **45° walkable threshold** in `Config.WALKABLE_NORMAL_Y = 0.707f` — raise it (toward 1.0) to be stricter, lower (toward 0) to allow steeper ramps.

**Known characteristics:**
- Rotated cubes act as ramps on gentle faces, walls on steep faces.
- 1×1×1 cube stairs auto-climb (no explicit step-up code — body AABB exempts them).
- Walking off a tall ledge → smooth free-fall with horizontal momentum preserved.
- Walking off a small drop (≤ stepHeight) → snaps down to lower ground.

**Rotated AABB:** `GameObject.worldBounds()` computes a rotation-aware world AABB from the 8 transformed corners (scale → Rz → Ry → Rx → translate), cached against the 9 transform fields. All `getMin*/getMax*` getters delegate to it. Pass-through on rotated blocks and ramp edge fall-throughs are gone.

## Characters (capsule collision)

Any model in `characters/` is treated as a character:
- Loads via `ModelLoader.loadModel("characters/<name>.glb")` — the path prefix routes to `Config.CHARACTERS_PATH`.
- `MeshCollider.intersectsAABB` uses `characterCapsule()` — radius = `min(width, depth) / 2` (T-pose arms don't inflate it), height = mesh Y-range.
- When *selected* in editor, `SelectionBoxRenderer` draws a wireframe capsule outline (Unity-style: top/bottom rings + 2 vertical great circles + 4 seam lines, depth test off so it's visible through the model). Hovering still shows the mesh silhouette outline.

Wireframe capsule helper: `RenderUtils.drawWireframeCapsule`. Same helper draws the *player's own* collision capsule in editor mode (`LevelEditorScene.drawPlayerCapsule()`).

## Mesh raycasting and collision

`MeshCollider.java` is the central per-triangle test code:
- `rayMesh(obj, origin, dir)` — closest ray-triangle hit, returns t.
- `rayMeshNormal(obj, origin, dir)` — same plus surface normal (flipped toward the ray origin for consistent direction regardless of mesh winding).
- `intersectsAABB(obj, min, max)` — AABB-vs-mesh SAT (Akenine-Möller triangle-box overlap). Used for character capsule picking.
- `intersectsAABBSteep(obj, min, max, walkableY)` — same SAT but only counts triangles with `|n.y| < walkableY`. The wall-collision primitive.
- `computeWorldPositions(obj, mr)` — transforms mesh vertices to world space (scale → Rz → Ry → Rx → translate, plus bone skinning for animated meshes via `Animator.matricesBuffer`).

Skinning math mirrors the GPU shader (column-major mat4 in `FloatBuffer`).

## Rendering

- `default.vert/.frag` — main pass with shadow mapping (single directional light from `sunBlock`), bone skinning, textured + color-tinted output. Supports world-space (triplanar) texture tiling via `useWorldSpaceTiling` uniform — fragment shader samples the diffuse three times (YZ/XZ/XY planes), blends by `abs(WorldNormal)^4` partition of unity, repeats at `textureScale` per world unit.
- `depth.vert/.frag` — shadow-map pass (skinning-aware). Alpha-discards foliage fragments (`useTexture` uniform) so the leaf tree casts a tree-shaped shadow rather than a quad-shaped one.
- `outline.vert/.frag` — selection silhouette: vertex shader pushes verts along `aSmoothNormal` in **screen space** (constant pixel width regardless of distance), back-face-cull only, flat color. Combined with smooth normals (computed on mesh load) so corners don't break apart. Frag shader alpha-discards foliage (`useTexture` uniform) so the leaf tree outlines its leaf shapes, not the leaf-quad rectangles.
- Cubes (procedural, `modelPath == ""`) auto-assign the shared `World.CUBE_MESH_KEY` mesh in `MeshRenderer.start()` so they participate in the same mesh-aware collision/picking as imported models.
- Procedural Floor: `World.FLOOR_MESH_KEY`, a flat quad at y=0, seeded if absent.

## Selection outline behavior (per type)

`SelectionBoxRenderer.drawWireframeOnly` branches:
- Paper-thin meshes (any dim < 0.001) and billboards (spawn point, win zone) → AABB outline. The stencil silhouette would render but its smooth-normal screen-space push collapses to zero when the camera looks along a flat quad's normal, so the halo becomes invisible — AABB is the only thing that survives.
- Characters when *selected* → wireframe capsule.
- Everything else (procedural cubes, imported props, foliage, characters when *hovered*) → **stencil-based silhouette** in `drawMeshOutline`:
  1. Pass A — write `1` into the stencil buffer wherever the un-inflated mesh covers. Color and depth writes off.
  2. Pass B — render an inflated copy of the mesh (the existing `outline.vert` screen-space normal push) only where stencil ≠ 1. Depth test off so the halo paints over anything behind the original.

  Works correctly for axis-aligned cubes (the old inverted-hull `cull_front` technique couldn't — back faces never reached the silhouette edges, so top/bottom dropped out at most camera pitches). Alpha-discard via `MeshRenderer.isFoliage` keeps leaf outlines following the leaf shape. Requires the main framebuffer to have a stencil attachment — see `Framebuffer.java` (`GL_DEPTH24_STENCIL8` on the depth renderbuffer).

## Level format

GSON-serialized list of `GameObject` (uuid, name, transformMode, transform, components). Loaded by `LevelLoader.loadLevel(path)`. Components deserialized polymorphically via `ComponentRegistry`. `Mesh`/`textureID` are transient — re-resolved in `MeshRenderer.start()` from `modelPath`/`texturePath`.

Texture auto-derivation: when a `MeshRenderer` has `modelPath` but `texturePath == "test.png"` (default), it tries `<base>.png` in `textures/` (stripping `characters/` prefix if present). For `Hazmat.glb` this loads `textures/Hazmat.png`.

Multi-submesh GLB/FBX exports: `ModelLoader` merges all `AIMesh` entries into one Mesh when the first mesh isn't skinned (fixed the case where `old_computer.glb`'s tower was unpickable because only the monitor mesh was loaded).

## MeshRenderer / Material flags

- `MeshRenderer.isFoliage` — auto-set to `true` in `start()` when `modelPath` contains "leaf" (currently just the leaf tree). Drives alpha-discard in both the outline pass and the shadow pass so transparency of the diffuse texture defines the silhouette and the shadow shape. Other models stay opaque-shaped.
- `Material.useWorldSpaceTiling` — auto-set to `true` for procedural cubes and the floor (modelPath empty or matches `World.CUBE_MESH_KEY` / `FLOOR_MESH_KEY`). Triplanar tile the diffuse texture in world units instead of using stored UVs. Inspector lets you flip it per-object.
- `Material.textureScale` — repeats per world unit when `useWorldSpaceTiling` is on. Inspector slider, default 1.0.

## Texture size cap

`Texture.loadTexture` and `Texture.uploadImage` downscale any source larger than `Config.MAX_TEXTURE_SIZE` (currently 1024) with bilinear filtering. Prevents accidentally huge source PNGs (e.g. a 4725×4725 billboard) from eating ~90 MB of VRAM each. Logged at DEBUG when triggered.

## Debug HUD

Minecraft-F3-style overlay in the editor: XYZ position, yaw + compass (N/E/S/W), pitch, dominant facing axis. Toggle with **F3** key or the checkbox inside the panel. Lives in `UIManager.renderDebugHud` and reads `scene.player` for state.

## Other systems

- **Climb (E):** Press E facing a wall whose top is > 3m above feet — enter `State.CLIMBING`. Stamina = 8m drained linearly (slower as it depletes). Auto-mount when within 0.5m of the wall top. Stamina bar drawn top-center.
- **Ledge grab (R):** In `IN_AIR` near a wall edge within ±0.5m of `y` — `LEDGE_GRABBING`. A/D shimmies, Space mounts up, S drops.
- **Wall-running:** W+A or W+D in air; side-check uses `intersectsAABBSteep` so ramps don't trigger spurious wall-runs.
- **Sliding (C):** Friction → ice-like; collision uses shorter feet offset (0.8 vs 1.8); FOV bumps to 100.
- **FOV:** Lerps with state — 70 idle/walk, 90 run/wall-run, 100 slide.

## Editor specifics

- Gizmo (`Gizmo.java`) — translate/rotate/scale handles, always world-axis-aligned. Multi-selection uses a transient proxy `GameObject` at the group center; per-frame `applyGroupDeltaAndReset` diffs the proxy's transform vs `prevProxy*` and applies the delta to each selected object. **Important:** the proxy's rotation/scale are NOT reset to identity each frame (that was a bug — Inspector showed 0/1 forever) — they accumulate, the diff still works.
- Inspector wraps every textbox edit in an `UndoRedoManager` command (Ctrl+Z / Ctrl+Y).
- Box-select: right-click drag in the viewport.
- Procedural cubes get the AABB outline tight against the mesh extents (1mm pad — sub-pixel, no visible gap).
- Play-mode UI gating: when NOT flying, the highlight raycast and click handlers are disabled, so the player doesn't accidentally place/delete blocks while playing.

## Cross-platform / Mac

`pom.xml` uses Maven profiles that auto-select natives by OS+arch. Mac users need `-XstartOnFirstThread` JVM arg in IntelliJ's run config (GLFW requires it).

**Big caveat:** the engine uses both modern shaders (`#version 130`) AND fixed-function OpenGL (`glBegin/glEnd`, `glTranslatef`, `GL_LIGHTING`, etc.). On macOS, the compatibility profile is capped at OpenGL 2.1 — `#version 130` shaders won't compile. To actually *run* on Mac, all fixed-function calls need porting to VBO+shader. That's a focused refactor (Gizmo, SelectionBoxRenderer wireframe, crosshair, stamina bar, skybox, floor grid, viewmodel, sun cube, box-select overlay). Estimated cost ~$30–80 of API usage over 2–4 sessions. **Status: not done.** Build/run on Windows for now.

## Done so far (highlights)

- Climbing mechanic + stamina bar.
- Ledge grab + shimmy + mount.
- Wall-running, sliding, head bob, FOV blending.
- Multi-select / box-select / group gizmo (with persistent rotation/scale in Inspector).
- Mesh-aware raycasting (clicking the visible model, not just its AABB).
- Inverted-hull silhouette outlines (slim, screen-space pixel width, smooth-normal corners).
- Capsule character collider with Unity-style wireframe when selected.
- OBJ + FBX import (assimp does the heavy lifting, multi-submesh merging for static models).
- Procedural cubes use the shared cube mesh → rotated cubes get correct mesh collision.
- macOS-aware `pom.xml` (Mac runtime still blocked on fixed-function OpenGL — see caveat above).
- Editor opens maximized in a normal decorated window (minimize/X work normally).
- Rotation-aware `GameObject.worldBounds()` (cached) so rotated/scaled blocks raycast correctly.
- Terminal cleanup: `__`-prefixed mesh keys skip file-load fallback, rig log compressed to one INFO line, texture size cap with downscale at load, framebuffer reallocates GL handles in place instead of tearing down on resize.
- Camera-aware gizmo translate/scale: mouse delta projects onto each world axis's screen-space direction so dragging works regardless of yaw/pitch.
- Foliage shadow + outline: `MeshRenderer.isFoliage` drives alpha-discard in `depth.frag` (tree casts tree-shape shadow) and `outline.frag` (leaf outline follows leaf shape).
- World-space (triplanar) texture tiling per material, auto-on for procedural geometry; Inspector toggle + scale slider.
- F3-style Debug HUD with coords + compass (toggle: **F3** key).

## Next up

Large-map performance — most of this is in flight:
1. ✅ Profiler — per-frame timing zones surfaced in ImGui.
2. ✅ Instanced rendering for procedural cubes (`CubeBatch`, `default_instanced.{vert,frag}`, `depth_instanced.vert`). 15k cubes went from ~12 fps to playable. One draw call per (texture+tiling+scale) group per pass.
3. Spatial partition (octree or loose grid) replacing the 3x3 cell scan in `World.getNearbyEntities`. Mostly for collision/raycast speed at scale (Main pass is now cheap; broad-phase is the next bottleneck).
4. Frustum culling against the partition — cull cubes that are off-screen *before* uploading to the instance buffer.
5. Texture atlas + material batching for imported props (cubes are already batched; the truck/tree/etc. still draw individually).
6. LOD + async streaming (only if still needed).

## Build & run

The IntelliJ run config is the primary way the user launches the game.
For automated/in-session compile checks, use one of:

- `.\compile.ps1` from the project root — compiles every source file under
  `src/main/java/com/myengine` against the cached Maven classpath.
- `.\compile.ps1 GameObject.java SelectionBoxRenderer.java` — compiles just
  the listed files (resolved against `src/main/java/com/myengine` if not an
  absolute path). Faster when iterating on a couple of files.
- `.\mvn.cmd -q compile` from the project root — full Maven compile via the
  IntelliJ-bundled Maven 3.9.11. `mvn.cmd` is a tiny shim at the project
  root that sets `JAVA_HOME` and forwards to IntelliJ's `mvn`. Slower than
  `compile.ps1` but a real Maven invocation, useful for dependency or
  plugin debugging.

Output goes to `target\classes` (same place IntelliJ builds to), so a
subsequent IntelliJ run picks the change up. If you edit `compile.ps1` or
`mvn.cmd`, the JDK / IntelliJ install paths are hard-coded near the top
and need updating on a move/reinstall.

## Scratchpad

`notes/NOTES.md` is the loose investigation notepad. Use it for in-progress
findings that don't yet belong in CLAUDE.md. Promote mature notes to the
right section here and delete from the scratchpad.

## Core-profile port (in progress)

Migration of every fixed-function GL call to VAO/VBO/shader equivalents so
the engine can run on macOS (Core profile only — Compat caps at GL 2.1
where `#version 130` shaders won't compile). Linux and Windows already
work; this is what unblocks macOS.

Strategy: incremental. Old fixed-function code coexists with new VAO-based
code during the transition (Windows Compat profile supports both). The
final phase flips the GLFW context hint to Core + forward-compat AND
updates the shaders to drop `gl_ModelViewProjectionMatrix` for an explicit
`uniform mat4 mvp`.

**Phase 1 — Foundation + wireframes (✅ done):**
- `LineRenderer3D` (singleton, lazy-init) — VAO + VBO + `line.vert/frag`.
  Single `drawLines(verts, r, g, b)` entry point. Replaces every
  `glBegin(GL_LINES)` pattern in the engine. Vertex shader still reads
  `gl_ModelViewProjectionMatrix` so callers' `glPushMatrix/Translate/...`
  sequences keep working unchanged during the transition.
- `RenderUtils.drawWireframeAABB` and `drawWireframeCapsule` ported to
  `LineRenderer3D`. New signatures take RGB explicitly (no more
  `glColor3f` setup at call site).
- Callers updated: `SelectionBoxRenderer` AABB + capsule branches,
  `LevelEditorScene.drawPlayerCapsule`.

**Phase 2 — Other immediate-mode users (✅ done):**
- `Skeleton.drawDebug` / `drawAnimatedDebug` ported to LineRenderer3D.
  Software matrix-stack via JOML (`Matrix4f.transformPosition`) replaces
  the old `glPushMatrix`/`glMultMatrixf` recursion.
- `Gizmo` arrows / scale handles / rotation circles ported. Solid scale-
  handle cubes go through a new `LineRenderer3D.drawMesh(Mesh, r,g,b)`
  helper that reuses the line shader on the shared `CUBE_MESH_KEY` mesh.
- `Renderer2D` — VAO + 2D shader (NDC input, vec4 colour uniform). Drives
  the crosshair, stamina bar and box-select rectangle. Sidesteps the GL
  matrix stack entirely (no more `glOrtho` / `glLoadIdentity` overlay
  pattern).
- Sun cube — previously rendered with no shader bound (fixed-function
  fallback); now uses `LineRenderer3D.drawMesh`. Was the only call site
  that would have silently produced nothing in a Core context.
- `World.drawSkybox` — new `skybox.vert/frag` (sampler-cube fragment
  shader), reuses `CUBE_MESH_KEY` scaled to ±50 via the matrix stack.

**Phase 3 — Matrix-stack code (✅ done):**
- Shaders updated to take explicit `viewMatrix` / `projMatrix` /
  `modelMatrix` uniforms instead of `gl_ModelViewProjectionMatrix`:
  `default.vert`, `outline.vert`, `depth.vert`. The instanced and
  Renderer2D shaders already used explicit uniforms.
- `line.vert` and `skybox.vert` take a single `mvp` uniform.
- `RenderState` (new) — static holder for the current frame's view +
  projection (both JOML and FloatBuffer mirrors). Populated by
  LevelEditorScene at the start of each pass; consumed by Selection
  outline, Skeleton debug, Gizmo, RenderUtils wireframes, LineRenderer3D,
  and the skybox.
- `LineRenderer3D.drawLines / drawMesh` take a `Matrix4f model` — the line
  shader reads MVP = projection * view * model from a uniform.
- `RenderUtils.drawWireframeAABB / drawWireframeCapsule` take `Matrix4f
  model` and pass through.
- `Skeleton.drawDebug / drawAnimatedDebug` take `Matrix4f model` (the host
  entity's world transform).
- `Gizmo` composes its own translation-only model matrix internally;
  helpers take `Matrix4f` and post-multiply local translates/scales.
- `LevelEditorScene` — shadow and main pass setups now build matrices in
  JOML (`Matrix4f.ortho / .perspective / .rotate / .translate`). Per-
  entity model matrix built via `RenderState.modelMatrix(transform, out)`.
  Billboards (spawn / win markers) cancel the view rotation in software
  by post-multiplying the inverse 3x3 of the view rotation.
- `World.drawSkybox(int, Matrix4f projection, Matrix4f viewRotOnly)` —
  composes MVP itself, no matrix stack.
- All `glPushMatrix / glPopMatrix / glLoadIdentity / glMatrixMode /
  glOrtho / glFrustum / glRotatef / glTranslatef / glScalef /
  glMultMatrixf / glLoadMatrixf / glPushAttrib / glPopAttrib` calls
  removed from `LevelEditorScene`, `Gizmo`, `Skeleton`,
  `SelectionBoxRenderer`, `RenderUtils`, `World`.
- Fixed-function light state (`GL_LIGHT0`, `glLightModelfv`,
  `glColorMaterial`, `glEnable(GL_LIGHTING)` / `GL_TEXTURE_2D`)
  removed — lighting was already a shader uniform, the legacy state
  was dead code.

**Phase 4 — GLFW Core profile flip (✅ done):**
- Window.init requests `GLFW_CONTEXT_VERSION_MAJOR=3, MINOR=3`,
  `GLFW_OPENGL_PROFILE=GLFW_OPENGL_CORE_PROFILE`,
  `GLFW_OPENGL_FORWARD_COMPAT=GL_TRUE`. Windows now runs Core too — the
  port's clean enough that there's no value in keeping a fork.
- All 15 shader headers bumped from `#version 130` to `#version 150`
  (the minimum Core profile supports — GLSL 130 is rejected by Core).
- ImGui's GL3 backend re-initialised with `#version 150`.
- Mac users still need `-XstartOnFirstThread` JVM arg in their run
  config (GLFW main-thread requirement, separate from Core profile).

**Open caveat on macOS — `glLineWidth > 1`:**
Apple's Core profile silently clamps line width to 1.0. On macOS every
outline/wireframe in the engine (selection AABB, capsule, gizmo arrows,
gizmo rings, skeleton bones, player capsule, box-select rectangle) draws
1 pixel thick instead of the 1.5-10.0 we set on Windows. Cosmetic only.
Proper fix is to render lines as thick quads in screen space via a
geometry shader or per-line vertex expansion. Parked.

## Open bugs (note-as-you-find policy)

When a bug is discovered mid-session, log a one-line entry here with what triggers it and the suspected cause. Don't try to fix in line unless the user asks — this avoids scope creep. Address the list in dedicated sessions.

- ⚠️ **macOS GL Core-profile GPU stall: any non-trivial set of file-loaded textures + the editor's render load reliably hangs `glfwSwapBuffers`** (after frame 1's render completes cleanly). Investigated for 5+ hours across many bisection rounds: per-entity, per-texture-size, per-pass. None isolated a single offender. The previous fix-suspects (marker textures at 1024, empty sampler unit 0) turned out not to be the full story; capping every 2D texture to 256×256 and every cubemap face to 256×256 still hangs the editor as soon as real PNG data is uploaded. Magenta-fallback / 1×1-fallback textures **do** work — the editor runs for minutes responsive. So the bug is somewhere in the interaction between actual sampled texture content and the driver's swap path, not in upload size or sampler completeness alone. **Workaround currently in place:** `Texture.MACOS_DISABLE_FILE_TEXTURES` is auto-true on macOS (via `os.name` check). All file texture loads return null → caller falls back to magenta. Embedded GLB textures (uploaded via `Texture.uploadImage`) are NOT blocked — they're smaller and have never reproduced the hang. **Effect on Mac:** editor functional, models render correctly (their embedded textures show), file-loaded textures (test.png, br1.png, etc.) and skybox appear magenta. Toggle the flag off in `Texture.java` to re-investigate. **Likely real fix:** replace AWT/ImageIO loader with `lwjgl-stb` (`stbi_load`) — STB writes raw pixel data without the BufferedImage round-trip and is the standard loader most LWJGL apps use. Worth a dedicated session.
- ✅ **macOS GPU swap stall from oversized marker textures (`spawn.png`, `win.png`)** — both ship as 4725×4725 PNGs from the source pack. `Texture.capSize` downscaled them to `Config.MAX_TEXTURE_SIZE = 1024`. The resulting 1024×1024 RGBA upload somehow puts Apple's Core-profile GL driver into a bad state: frame 1's render completes cleanly through every stage (we instrumented every boundary), but the immediately-following `glfwSwapBuffers` hangs the GPU forever. ESC eventually transitions scenes (input keeps flowing) but the screen never updates. Sometimes preceded by the Apple console warning `unit 0 GLD_TEXTURE_INDEX_2D is unloadable and bound to sampler type (Float)` — but that warning is **benign** on its own; it only correlates with the hang here. We bisected a 206-entity level down to the two marker entities, then confirmed: removing only those textures' GPU upload (skipping `getTexture` for `spawn.png`/`win.png`) made the editor work; restoring them brought the hang back. **Fix:** cap `spawn.png`/`win.png` at **256×256** specifically in `Texture.capSize`. Markers are tiny viewport indicators; 256 is plenty visually and the smaller upload is fine on all drivers. Other textures keep their 1024 cap. Root cause of WHY 1024 specifically breaks these two textures (and not, say, `test.png` at 512) is still unknown — could be the AWT bilinear-downscale path producing pixel data Apple's driver mishandles, could be a heap pressure issue at decode time (4725² → 88 MB BufferedImage briefly), could be the alpha distribution. Worth replacing the AWT-based loader with `lwjgl-stb` as a follow-up.
- ⚠️ **Windows `GL[after-overlays] error 0x501` (GL_INVALID_VALUE)** — surfaced during the macOS bisection. The error fires during the Selection + overlays block (after `main-pass`, before `imgui-render`) on Windows. The diagnostic instrumentation has been removed for now, but this is a real bug that's been silently happening. Next step: re-add a single `glGetError` checkpoint around each overlay sub-call (stencil clear, `SelectionBoxRenderer.draw*`, `drawPlayerCapsule`, `drawCrosshair`, `drawStaminaBar`) to narrow it. Possibly a `glLineWidth` we missed in the GLCompat refactor or a stencil-mask interaction.
- ✅ **macOS Core profile GPU stall: empty `sampler2D` at texture unit 0** — Apple's GL driver validates every shader sampler at draw time, including ones the shader never actually fetches because of a runtime conditional like `if (useTexture == 1)`. Windows/Linux silently tolerate samplers with no bound texture if they're not sampled; macOS logs `unit 0 GLD_TEXTURE_INDEX_2D is unloadable and bound to sampler type (Float) - using zero texture because texture unloadable` and — when it keeps happening every frame — stalls the GPU on `glfwSwapBuffers`. Symptoms: editor renders one frame then freezes; ESC eventually transitions scenes but the screen never updates. The shadow-pass per-entity loop (only bound a texture for foliage's alpha-discard branch, left unit 0 empty for everything else) and `CubeBatch.flush` (only bound a texture when the group had `textureID > 0`, skipped for un-textured cubes) both produced this. **Fix:** at the top of both shadow and main passes, bind `cubeTexture` (always-loaded `test.png`) to unit 0 as a defensive fallback (`LevelEditorScene.render`, two new `glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, cubeTexture)` blocks). Per-entity / per-group code below still overrides as needed; the point is unit 0 is never "empty" when a draw fires. Also added a defensive `glBindVertexArray(0); glBindBuffer(GL_ARRAY_BUFFER, 0); glUseProgram(0); glFlush()` immediately before `glfwSwapBuffers` in `Window.run` so any leftover state doesn't trip macOS's pre-swap validation. The Apple "unloadable" warning still logs once at startup but is now confirmed benign — the GPU no longer stalls on it.
- ✅ **macOS Core profile stall: `glLineWidth(x != 1.0)` raises GL_INVALID_VALUE** — wide lines were deprecated in GL 3.0 and effectively removed in Core. Windows/Linux drivers silently tolerate it; Apple's driver strictly enforces it AND degrades the context afterwards, so the next frame never renders (editor goes gray, play mode freezes on frame 1, ESC eventually fires once but no more heartbeats). Symptom in console: `[ERROR] GL[after-playerCapsule] error 0x501`. **Fix:** new `GLCompat.lineWidth(float)` shim that queries `GL_ALIASED_LINE_WIDTH_RANGE` once and clamps; all call sites (`Gizmo`, `SelectionBoxRenderer`, `Skeleton`, `LevelEditorScene.drawPlayerCapsule`) routed through it. macOS gets thin lines, Windows/Linux still get wide ones. Diagnostic instrumentation (R/G/B background pulse + per-stage `glDiag` checkpoints + frame-heartbeat) was kept in `Window.run` / `LevelEditorScene.render` while hunting the bug; remove once macOS confirms.
- ✅ **ImGui callback chain corruption across scene transitions** — `imgui-java 1.89.0`'s `ImGuiImplGlfw.shutdown()` nulls its internal `data` but does NOT pull its callback off the GLFW window. When a second scene called `imGuiGlfw.init(window, true)`, it chained to the dead callback; pressing ESC (or any key) into the dead instance crashed with `NPE: this.data is null at ImGuiImplGlfw.keyCallback:677`. On macOS the dead poll-events crash silently killed input — symptom was play mode frozen on frame 1, editor a gray window. **Fix:** hoisted ImGui (context + Glfw/GL3 impls) to `Window` for the app lifetime. `MainMenuScene` and `UIManager` no longer create/destroy their own — they use `Window.imGuiGlfw/imGuiGl3` instead.

## Foliage collision (open bug)

Trees are alpha-cutout: trunk = opaque triangles, leaves = transparent triangles on flat quads. The `MeshRenderer.isFoliage` flag covers the whole mesh, so flagging it walkthrough also makes the trunk walkthrough — that's wrong. Walkthrough behaviour is **reverted** until the proper fix lands.

Proper fix options (pick one when this comes back up):
- **Per-triangle alpha pre-classification** at mesh load: sample the diffuse texture at each triangle's UV centroid, build a `boolean[] triangleSolid` on the Mesh, skip non-solid triangles in the MeshCollider loops. Cost: one-time cost at load, O(N) bool check per triangle in collision (cheap). Probably the right answer.
- **Split the GLB into trunk + leaves submeshes** at import. Cleaner data model, but needs Blender authoring.

## Future / parking lot

- macOS porting (full fixed-function → Core profile refactor).
- **Vulkan migration** (user's eventual target). After Core-profile port stabilises so we're not refactoring twice.
- **Stylised renderer goals** — keep the low-poly silhouette but layer ray-traced (or screen-space-traced) shadows + bouncing RGB lights for a R.E.P.O-style mood. Requires: a real lighting pass (deferred or forward+), per-light shadow maps or RT, post-process bloom + tonemap. Far-future, after Vulkan.
- ✅ **Shadow map follows the player** (was: fixed on world origin → blocks on a second floor lost lighting). Light view translation now targets `player.x/y/z + sunDir*SHADOW_CAM_HEIGHT`, so the ortho box rides with the player. Three sliders in the Shadow Map Debug panel: `Ortho half-size` (10–300, default 60 → 120×120m coverage at 17 texels/m on a 2048² map), `Cam height (along sun)` (default 100), `Shadow far` (default 300). Still missing: a wireframe overlay of the shadow frustum in the main viewport, and cascaded shadow maps (CSM) so very large maps stay sharp.
- **Character internal-flight collision push-out**: in fly mode the player phases through entity meshes (Hazmat in particular). On exit-fly we should resolve any AABB overlap by pushing the player out along the shortest axis before re-enabling physics.
- ✅ Stencil-based selection outline — cubes and curved meshes alike get a true single-line silhouette. Framebuffer attachment is now `GL_DEPTH24_STENCIL8`.
- True per-bone character collision (currently capsule is good enough).
- BVH or voxel acceleration for very large meshes (current per-triangle is fine for hundreds of triangles; would slow on tens of thousands).
- Per-pose animated character collision (currently uses bind pose — fine for static-pose characters).
- Animation clip selector for FBX imports (currently reads only the first clip).
- Rotated mesh capsule collision (character capsule code currently ignores rotation; fine for upright characters).
- Live-resize handling for the maximized window if the user un-maximizes mid-session (scenes were sized at startup).
