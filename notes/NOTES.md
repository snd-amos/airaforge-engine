# Scratchpad

Loose investigation notes that don't yet belong in CLAUDE.md. Either of us
can append here mid-session without polluting the project doc.

When a note matures into a stable fact about the engine, move it to the
appropriate section of `src/main/CLAUDE.md` and delete it from here.

When a note describes a bug, move it to the "Open bugs" list in CLAUDE.md.

---

<!-- Append new entries below this line. Date + 1-line summary is enough. -->

- 2026-06-13: **Full session archive → `notes/SESSION_ARCHIVE_2026-06-13.md`.** Copy/paste (Ctrl+C/V), planar-mesh AABB pad, WinZoneComponent, legacy `__floor_plane` removal, Vulkan foliage outline alpha-discard, Vulkan "Show Bones", and the whole ramp-collision saga (steep jitter hysteresis, walkable jump clip-through, and the OBB push-out `MeshCollider.pushOutOfCube` for tilted cubes). KEY reminder in there: **the user runs the VULKAN build (`VulkanMain`)** — features must reach it, not just the OpenGL `LevelEditorScene`.
- 2026-06-13: **Ramp collision facts (now stable).** Walkable cutoff is exactly 45° (`Config.WALKABLE_NORMAL_Y = 0.707`); −135° = walkable, −136°+ = steep. STEEP branch has a hysteresis clause (`feetY <= steepY + 0.06`) to stop FALL/STEEP jitter. RISE phase snaps onto a penetrated walkable surface (stops sprint-jump clip-through). Tilted CUBES get OBB depenetration (`pushOutOfCube` + `Player.resolveCubePenetration`), scoped to non-axis-aligned cubes only.

- 2026-05-26: **`glClear(GL_STENCIL_BUFFER_BIT)` is gated by `glStencilMask`.** Pass B set mask to 0x00 and never restored it; next frame's clear was a no-op so stale marks survived and Pass B refused to paint where they sat. Always restore stencil mask to 0xFF before clear AND at end of any pass that writes 0x00. Repro symptom was "selection disappears after hover" with tab-out/in temporarily fixing it (driver restored mask on context reattach).

- 2026-05-26: `outline.vert` inflation switched from smooth-normal-projection to radial-from-screen-center. Assumes mesh local origin ≈ visual centre; would skew on heavily off-centre imports. If a future model imports with its origin at an edge or end and the outline looks lopsided, add a per-mesh `centerOffset` uniform (or compute mesh centroid at load time, store on `Mesh`).
