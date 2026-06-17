package com.myengine;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Vulkan port of {@link Gizmo}. Renders the per-axis Move / Rotate / Scale
 * gizmo for the selected entity using {@link VkLineRenderer#drawMesh}
 * (solid stretched-cube primitives) — the same approach the OpenGL build
 * uses, so the visual is identical: thick coloured shafts (Move/Scale),
 * 64-segment chord rings (Rotate), box / cube handle tips. Sun entity
 * gets Move-only.
 *
 * <p>Math + constants ported verbatim from {@code Gizmo.java}; see
 * {@code Config.GIZMO_*} for tunables.
 */
final class VkGizmo {

    private final VkLineRenderer lines;
    private final VkMesh         cube;

    /** Reused per render to avoid allocation churn. */
    private final Matrix4f tmp  = new Matrix4f();
    private final Matrix4f mvp  = new Matrix4f();
    private final Quaternionf q = new Quaternionf();

    VkGizmo(VkLineRenderer lines, VkMesh cube) {
        this.lines = lines;
        this.cube  = cube;
    }

    /** Render the gizmo for {@code selected} into {@code cb}. {@code viewProj}
     *  is the camera matrix used to build per-segment MVPs. */
    void render(VkCommandBuffer cb, Matrix4f viewProj, GameObject selected, boolean isSun,
                int draggingAxis,
                boolean isHoveringX, boolean isHoveringY, boolean isHoveringZ,
                boolean isHoveringCenter, boolean extrude, int hoverAxis) {
        if (selected == null) return;

        Matrix4f gizmoModel = new Matrix4f().translation(
                selected.transform.x, selected.transform.y, selected.transform.z);

        if (selected.transformMode == 0) {
            drawArrow(cb, viewProj, gizmoModel, Config.GIZMO_ARROW_LENGTH, 0, 0,
                    1, 0, 0, draggingAxis == 0, isHoveringX);
            drawArrow(cb, viewProj, gizmoModel, 0, Config.GIZMO_ARROW_LENGTH, 0,
                    0, 1, 0, draggingAxis == 1, isHoveringY);
            drawArrow(cb, viewProj, gizmoModel, 0, 0, Config.GIZMO_ARROW_LENGTH,
                    0, 0, 1, draggingAxis == 2, isHoveringZ);
            // Centre cube — drag-to-free-move handle (same visual idiom
            // as Scale's uniform-scale cube). Yellow while dragging,
            // bright white on hover, grey at rest.
            float r, g, b;
            if (draggingAxis == 3)      { r = 1; g = 1; b = 0; }
            else if (isHoveringCenter)  { r = 1; g = 1; b = 1; }
            else                        { r = 0.45f; g = 0.45f; b = 0.45f; }
            tmp.set(gizmoModel).scale(Config.GIZMO_SCALE_CUBE_SIZE);
            mvp.set(viewProj).mul(tmp);
            lines.drawMesh(cb, cube, mvp, r, g, b);
        } else if (selected.transformMode == 1 && !isSun) {
            drawCircle(cb, viewProj, gizmoModel, 0, 1, 0, 0, draggingAxis == 0, isHoveringX);
            drawCircle(cb, viewProj, gizmoModel, 1, 0, 1, 0, draggingAxis == 1, isHoveringY);
            drawCircle(cb, viewProj, gizmoModel, 2, 0, 0, 1, draggingAxis == 2, isHoveringZ);
        } else if (selected.transformMode == 2 && !isSun) {
            // LOCAL-space scale gizmo: rotate the handle frame to the object's
            // orientation so the handles point along its LOCAL axes (the
            // directions scale actually acts in under T·R·S). Without this the
            // world-aligned handles mismatch the local stretch and rotated
            // objects appear to shear / scale diagonally.
            gizmoModel
                    .rotateX((float) Math.toRadians(selected.transform.rotX))
                    .rotateY((float) Math.toRadians(selected.transform.rotY))
                    .rotateZ((float) Math.toRadians(selected.transform.rotZ));
            // Standard scale = 3 positive handles (shaft + cube tip). One-sided
            // "extrude" mode (CTRL held) = a 6-WAY gizmo: the same scale handle
            // rendered in all 6 directions (±X, ±Y, ±Z) — a 3D crosshair through
            // the object. Negative handles are just the axis vector inverted.
            if (extrude) {
                float L = Config.GIZMO_ARROW_LENGTH;
                drawScale(cb, viewProj, gizmoModel,  L, 0, 0, 1, 0, 0, draggingAxis == 0, hoverAxis == 0);
                drawScale(cb, viewProj, gizmoModel, -L, 0, 0, 1, 0, 0, draggingAxis == 4, hoverAxis == 4);
                drawScale(cb, viewProj, gizmoModel, 0,  L, 0, 0, 1, 0, draggingAxis == 1, hoverAxis == 1);
                drawScale(cb, viewProj, gizmoModel, 0, -L, 0, 0, 1, 0, draggingAxis == 5, hoverAxis == 5);
                drawScale(cb, viewProj, gizmoModel, 0, 0,  L, 0, 0, 1, draggingAxis == 2, hoverAxis == 2);
                drawScale(cb, viewProj, gizmoModel, 0, 0, -L, 0, 0, 1, draggingAxis == 6, hoverAxis == 6);
            } else {
                drawScale(cb, viewProj, gizmoModel, Config.GIZMO_ARROW_LENGTH, 0, 0,
                        1, 0, 0, draggingAxis == 0, isHoveringX);
                drawScale(cb, viewProj, gizmoModel, 0, Config.GIZMO_ARROW_LENGTH, 0,
                        0, 1, 0, draggingAxis == 1, isHoveringY);
                drawScale(cb, viewProj, gizmoModel, 0, 0, Config.GIZMO_ARROW_LENGTH,
                        0, 0, 1, draggingAxis == 2, isHoveringZ);
            }

            float r, g, b;
            if (draggingAxis == 3)      { r = 1; g = 1; b = 0; }
            else if (isHoveringCenter)  { r = 1; g = 1; b = 1; }
            else                        { r = 0.45f; g = 0.45f; b = 0.45f; }

            tmp.set(gizmoModel).scale(Config.GIZMO_SCALE_CUBE_SIZE);
            mvp.set(viewProj).mul(tmp);
            lines.drawMesh(cb, cube, mvp, r, g, b);
        }
    }

    // Same picking math as Gizmo.getHitAxis, but the screen-space ray
    // calculation is done by the caller (camera viewport / RayCaster).
    int getHitAxis(GameObject selected, float[] ray,
                   float originX, float originY, float originZ, boolean extrude) {
        if (selected == null) return -1;
        float ox = originX - selected.transform.x;
        float oy = originY - selected.transform.y;
        float oz = originZ - selected.transform.z;

        float bestT = Float.MAX_VALUE;
        int hitAxis = -1;

        if (selected.transformMode == 0 || selected.transformMode == 2) {
            // Pick frame: SCALE mode (2) draws a LOCAL-space gizmo (rotated to
            // the object), so rotate the ray by the INVERSE object rotation and
            // test against the same world-aligned local AABBs. MOVE mode (0)
            // keeps the world frame.
            float pox = ox, poy = oy, poz = oz;
            float pdx = ray[0], pdy = ray[1], pdz = ray[2];
            if (selected.transformMode == 2) {
                Matrix4f inv = selected.transform.rotationMatrix().transpose();   // inverse of a pure rotation
                org.joml.Vector3f o = inv.transformDirection(new org.joml.Vector3f(ox, oy, oz));
                org.joml.Vector3f d = inv.transformDirection(new org.joml.Vector3f(ray[0], ray[1], ray[2]));
                pox = o.x; poy = o.y; poz = o.z;
                pdx = d.x; pdy = d.y; pdz = d.z;
            }
            // Centre cube handle — Scale uses it for uniform scaling,
            // Move uses it for free 3D drag (Blender-style grab). Both
            // modes detect it here so the cursor highlights it on hover.
            float tC = RayCaster.rayAABB(pox, poy, poz, pdx, pdy, pdz,
                    -Config.GIZMO_CENTER_CUBE_SIZE, -Config.GIZMO_CENTER_CUBE_SIZE,
                    -Config.GIZMO_CENTER_CUBE_SIZE,
                    Config.GIZMO_CENTER_CUBE_SIZE, Config.GIZMO_CENTER_CUBE_SIZE,
                    Config.GIZMO_CENTER_CUBE_SIZE);
            if (tC > 0 && tC < bestT) { bestT = tC; hitAxis = 3; }
            float tX = RayCaster.rayAABB(pox, poy, poz, pdx, pdy, pdz,
                    0.15f, -0.06f, -0.06f, Config.GIZMO_ARROW_LENGTH + 0.1f, 0.06f, 0.06f);
            if (tX > 0 && tX < bestT) { bestT = tX; hitAxis = 0; }
            float tY = RayCaster.rayAABB(pox, poy, poz, pdx, pdy, pdz,
                    -0.06f, 0.15f, -0.06f, 0.06f, Config.GIZMO_ARROW_LENGTH + 0.1f, 0.06f);
            if (tY > 0 && tY < bestT) { bestT = tY; hitAxis = 1; }
            float tZ = RayCaster.rayAABB(pox, poy, poz, pdx, pdy, pdz,
                    -0.06f, -0.06f, 0.15f, 0.06f, 0.06f, Config.GIZMO_ARROW_LENGTH + 0.1f);
            if (tZ > 0 && tZ < bestT) { bestT = tZ; hitAxis = 2; }
            // 6-way extrude (Scale + CTRL) adds the three NEGATIVE-direction
            // handles, encoded 4=-X, 5=-Y, 6=-Z.
            if (extrude && selected.transformMode == 2) {
                float L = Config.GIZMO_ARROW_LENGTH;
                float tnX = RayCaster.rayAABB(pox, poy, poz, pdx, pdy, pdz,
                        -(L + 0.1f), -0.06f, -0.06f, -0.15f, 0.06f, 0.06f);
                if (tnX > 0 && tnX < bestT) { bestT = tnX; hitAxis = 4; }
                float tnY = RayCaster.rayAABB(pox, poy, poz, pdx, pdy, pdz,
                        -0.06f, -(L + 0.1f), -0.06f, 0.06f, -0.15f, 0.06f);
                if (tnY > 0 && tnY < bestT) { bestT = tnY; hitAxis = 5; }
                float tnZ = RayCaster.rayAABB(pox, poy, poz, pdx, pdy, pdz,
                        -0.06f, -0.06f, -(L + 0.1f), 0.06f, 0.06f, -0.15f);
                if (tnZ > 0 && tnZ < bestT) { bestT = tnZ; hitAxis = 6; }
            }
        } else if (selected.transformMode == 1) {
            // Rotation rings: plane intersection + radius-band check. The band
            // is centred on the ring radius and widened by GIZMO_ROTATION_HIT_HALF
            // so the user doesn't have to be pixel-perfect to grab a ring.
            float rad   = Config.GIZMO_ROTATION_RADIUS;
            float inner = rad - Config.GIZMO_ROTATION_HIT_HALF;
            float outer = rad + Config.GIZMO_ROTATION_HIT_HALF;
            float tX = (ray[0] != 0) ? -ox / ray[0] : -1;
            if (tX > 0 && tX < bestT) {
                float hitY = oy + ray[1] * tX;
                float hitZ = oz + ray[2] * tX;
                double r2 = Math.sqrt(hitY*hitY + hitZ*hitZ);
                if (r2 > inner && r2 < outer) { bestT = tX; hitAxis = 0; }
            }
            float tY = (ray[1] != 0) ? -oy / ray[1] : -1;
            if (tY > 0 && tY < bestT) {
                float hitX = ox + ray[0] * tY;
                float hitZ = oz + ray[2] * tY;
                double r2 = Math.sqrt(hitX*hitX + hitZ*hitZ);
                if (r2 > inner && r2 < outer) { bestT = tY; hitAxis = 1; }
            }
            float tZ = (ray[2] != 0) ? -oz / ray[2] : -1;
            if (tZ > 0 && tZ < bestT) {
                float hitX = ox + ray[0] * tZ;
                float hitY = oy + ray[1] * tZ;
                double r2 = Math.sqrt(hitX*hitX + hitY*hitY);
                if (r2 > inner && r2 < outer) { bestT = tZ; hitAxis = 2; }
            }
        }
        return hitAxis;
    }

    // ============================================================
    //                       Drawing helpers
    // ============================================================
    // Same math as Gizmo.axisTint / drawArrow / drawScaleGizmo /
    // drawGizmoCircle, but using VkLineRenderer.drawMesh instead of
    // LineRenderer3D.drawMesh.

    private static float[] axisTint(float r, float g, float b, boolean d, boolean h) {
        if (d) return new float[]{ 1f, 1f, 1f };
        if (h) return new float[]{ 0.5f + r * 0.5f, 0.5f + g * 0.5f, 0.5f + b * 0.5f };
        return new float[]{ r, g, b };
    }

    private void drawArrow(VkCommandBuffer cb, Matrix4f viewProj, Matrix4f model,
                           float ex, float ey, float ez,
                           float r, float g, float b, boolean d, boolean h) {
        float[] c = axisTint(r, g, b, d, h);
        float length = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        float t = (d || h) ? 0.10f : 0.06f;
        tmp.set(model);
        if (Math.abs(ex) > 0.5f)      tmp.translate(length / 2f, 0, 0).scale(length, t, t);
        else if (Math.abs(ey) > 0.5f) tmp.translate(0, length / 2f, 0).scale(t, length, t);
        else                          tmp.translate(0, 0, length / 2f).scale(t, t, length);
        mvp.set(viewProj).mul(tmp);
        lines.drawMesh(cb, cube, mvp, c[0], c[1], c[2]);
    }

    private void drawScale(VkCommandBuffer cb, Matrix4f viewProj, Matrix4f model,
                           float ex, float ey, float ez,
                           float r, float g, float b, boolean d, boolean h) {
        float[] c = axisTint(r, g, b, d, h);
        float length = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        float t = (d || h) ? 0.10f : 0.06f;
        // Shaft — translate by the SIGNED half-vector so negative axis vectors
        // (the 6-way extrude handles) draw toward -axis instead of +axis.
        tmp.set(model);
        if (Math.abs(ex) > 0.5f)      tmp.translate(ex / 2f, 0, 0).scale(length, t, t);
        else if (Math.abs(ey) > 0.5f) tmp.translate(0, ey / 2f, 0).scale(t, length, t);
        else                          tmp.translate(0, 0, ez / 2f).scale(t, t, length);
        mvp.set(viewProj).mul(tmp);
        lines.drawMesh(cb, cube, mvp, c[0], c[1], c[2]);
        // Cube at tip (already at the signed end-point).
        tmp.set(model).translate(ex, ey, ez).scale(0.2f);
        mvp.set(viewProj).mul(tmp);
        lines.drawMesh(cb, cube, mvp, c[0], c[1], c[2]);
    }

    private void drawCircle(VkCommandBuffer cb, Matrix4f viewProj, Matrix4f model,
                            int axis, float r, float g, float b,
                            boolean d, boolean h) {
        float[] c = axisTint(r, g, b, d, h);
        float cr = c[0], cg = c[1], cb_ = c[2];
        float rad = Config.GIZMO_ROTATION_RADIUS;
        int seg = Config.GIZMO_CIRCLE_SEGMENTS;
        float thickness = (d || h) ? 0.10f : 0.06f;

        for (int i = 0; i < seg; i++) {
            float th0 = (float) (2.0 * Math.PI * i / seg);
            float th1 = (float) (2.0 * Math.PI * ((i + 1) % seg) / seg);
            float c0 = (float) (rad * Math.cos(th0));
            float s0 = (float) (rad * Math.sin(th0));
            float c1 = (float) (rad * Math.cos(th1));
            float s1 = (float) (rad * Math.sin(th1));

            float sx, sy, sz, ex, ey, ez;
            if (axis == 1) {        // Y-ring in XZ plane
                sx = c0; sy = 0;  sz = s0;
                ex = c1; ey = 0;  ez = s1;
            } else if (axis == 0) { // X-ring in YZ plane
                sx = 0;  sy = c0; sz = s0;
                ex = 0;  ey = c1; ez = s1;
            } else {                // Z-ring in XY plane
                sx = c0; sy = s0; sz = 0;
                ex = c1; ey = s1; ez = 0;
            }

            float dx = ex - sx, dy = ey - sy, dz = ez - sz;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-5f) continue;
            float invLen = 1f / len;
            float nx = dx * invLen, ny = dy * invLen, nz = dz * invLen;
            q.identity().rotateTo(1, 0, 0, nx, ny, nz);

            tmp.set(model)
                    .translate((sx + ex) * 0.5f, (sy + ey) * 0.5f, (sz + ez) * 0.5f)
                    .rotate(q)
                    .scale(len, thickness, thickness);
            mvp.set(viewProj).mul(tmp);
            lines.drawMesh(cb, cube, mvp, cr, cg, cb_);
        }
    }
}
