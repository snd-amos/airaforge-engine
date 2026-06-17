package com.myengine;

import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Selection outline overlay — draws an orange wireframe box around the
 * selected entity. Trimmed port of {@link SelectionBoxRenderer}:
 *
 * <ul>
 *   <li><b>Wireframe AABB</b> — what every selected entity gets in this
 *       sub-phase. Lines drawn through {@link VkLineRenderer}.</li>
 *   <li><b>Wireframe capsule</b> on characters — <b>intentionally NOT
 *       ported</b>. User requested removal (see CLAUDE.md "Parked items").</li>
 *   <li><b>Stencil-mesh silhouette</b> — the proper orange mesh-shape
 *       outline (matches selection in the OpenGL build) — sub-phase H.
 *       It needs a stencil-enabled depth buffer + a second pipeline,
 *       which is a meaningful chunk of work and not blocking the rest
 *       of the editor.</li>
 * </ul>
 *
 * <p>Local AABB fallback is {@code ±0.5} for unit cubes / imported models
 * (matches the {@code VkMesh} convention used elsewhere) and
 * {@code ±Config.FLOOR_GRID_SIZE} in XZ for the procedural floor.
 * Tighter per-mesh bounds will land when {@link VkMesh} carries them
 * (sub-phase B follow-up).
 */
final class VkSelectionRenderer {

    private final VkLineRenderer lines;
    private final Matrix4f mvp = new Matrix4f();

    VkSelectionRenderer(VkLineRenderer lines) {
        this.lines = lines;
    }

    /** Draw the selected entity in bright orange (1, 0.5, 0) — same
     *  colour SelectionBoxRenderer uses on the OpenGL side. */
    void drawSelection(VkCommandBuffer cb, Matrix4f viewProj, GameObject selected,
                       VkMesh meshForEntity) {
        drawOutline(cb, viewProj, selected, meshForEntity, 1.0f, 0.5f, 0.0f);
    }

    /** Draw the hover-pick entity in dim orange (0.4, 0.2, 0). Same
     *  visual hint as the OpenGL editor — shows what an LMB click would
     *  land on. Skipped if the hover entity is the same as the
     *  selection (caller's responsibility). */
    void drawHighlight(VkCommandBuffer cb, Matrix4f viewProj, GameObject hovered,
                       VkMesh meshForEntity) {
        drawOutline(cb, viewProj, hovered, meshForEntity, 0.4f, 0.2f, 0.0f);
    }

    /** Shared outline draw — bounds come from VkMesh when available
     *  (real per-mesh bounds) and fall back to ±0.5 otherwise. */
    private void drawOutline(VkCommandBuffer cb, Matrix4f viewProj, GameObject selected,
                             VkMesh meshForEntity, float r, float g, float b) {
        if (selected == null) return;

        // Local bounds — real mesh bounds if available, fallback otherwise.
        float lminX = -0.5f, lmaxX = 0.5f;
        float lminY = -0.5f, lmaxY = 0.5f;
        float lminZ = -0.5f, lmaxZ = 0.5f;
        if (meshForEntity != null) {
            lminX = meshForEntity.minX(); lminY = meshForEntity.minY(); lminZ = meshForEntity.minZ();
            lmaxX = meshForEntity.maxX(); lmaxY = meshForEntity.maxY(); lmaxZ = meshForEntity.maxZ();
        }

        // World transform = the entity's full TRS.
        Matrix4f model = new Matrix4f()
                .translate(selected.transform.x, selected.transform.y, selected.transform.z)
                .rotateX((float) Math.toRadians(selected.transform.rotX))
                .rotateY((float) Math.toRadians(selected.transform.rotY))
                .rotateZ((float) Math.toRadians(selected.transform.rotZ))
                .scale(selected.transform.scaleX, selected.transform.scaleY, selected.transform.scaleZ);
        mvp.set(viewProj).mul(model);

        float[] verts = aabbWireframeVerts(lminX, lminY, lminZ, lmaxX, lmaxY, lmaxZ);
        lines.drawLines(cb, verts, mvp, r, g, b);
    }

    /** 12 edges of an AABB as 24 vertices for LINE_LIST drawing. */
    private static float[] aabbWireframeVerts(float minX, float minY, float minZ,
                                              float maxX, float maxY, float maxZ) {
        return new float[]{
                // bottom ring
                minX, minY, minZ,  maxX, minY, minZ,
                maxX, minY, minZ,  maxX, minY, maxZ,
                maxX, minY, maxZ,  minX, minY, maxZ,
                minX, minY, maxZ,  minX, minY, minZ,
                // top ring
                minX, maxY, minZ,  maxX, maxY, minZ,
                maxX, maxY, minZ,  maxX, maxY, maxZ,
                maxX, maxY, maxZ,  minX, maxY, maxZ,
                minX, maxY, maxZ,  minX, maxY, minZ,
                // vertical pillars
                minX, minY, minZ,  minX, maxY, minZ,
                maxX, minY, minZ,  maxX, maxY, minZ,
                maxX, minY, maxZ,  maxX, maxY, maxZ,
                minX, minY, maxZ,  minX, maxY, maxZ
        };
    }
}
