package com.myengine;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

class Gizmo {

    // The gizmo is always world-axis-aligned, so its model matrix is just
    // a pure translation to the selected entity. Inner helpers compose
    // additional local transforms (e.g. translate to arrow tip + scale)
    // by post-multiplying.
    public static void render(GameObject selectedBox, boolean isSun, int draggingAxis,
                              boolean isHoveringX, boolean isHoveringY, boolean isHoveringZ,
                              boolean isHoveringCenter) {
        glDisable(GL_DEPTH_TEST);

        Matrix4f gizmoModel = new Matrix4f().translation(
                selectedBox.transform.x, selectedBox.transform.y, selectedBox.transform.z);

        if (selectedBox.transformMode == 0) {
            drawArrow(gizmoModel, Config.GIZMO_ARROW_LENGTH, 0, 0, 1, 0, 0, draggingAxis == 0, isHoveringX);
            drawArrow(gizmoModel, 0, Config.GIZMO_ARROW_LENGTH, 0, 0, 1, 0, draggingAxis == 1, isHoveringY);
            drawArrow(gizmoModel, 0, 0, Config.GIZMO_ARROW_LENGTH, 0, 0, 1, draggingAxis == 2, isHoveringZ);
        } else if (selectedBox.transformMode == 1 && !isSun) {
            drawGizmoCircle(gizmoModel, 0, 1, 0, 0, draggingAxis == 0, isHoveringX);
            drawGizmoCircle(gizmoModel, 1, 0, 1, 0, draggingAxis == 1, isHoveringY);
            drawGizmoCircle(gizmoModel, 2, 0, 0, 1, draggingAxis == 2, isHoveringZ);
        } else if (selectedBox.transformMode == 2 && !isSun) {
            drawScaleGizmo(gizmoModel, Config.GIZMO_ARROW_LENGTH, 0, 0, 1, 0, 0, draggingAxis == 0, isHoveringX);
            drawScaleGizmo(gizmoModel, 0, Config.GIZMO_ARROW_LENGTH, 0, 0, 1, 0, draggingAxis == 1, isHoveringY);
            drawScaleGizmo(gizmoModel, 0, 0, Config.GIZMO_ARROW_LENGTH, 0, 0, 1, draggingAxis == 2, isHoveringZ);

            float r, g, b;
            if (draggingAxis == 3)      { r = 1; g = 1; b = 0; }
            else if (isHoveringCenter)  { r = 1; g = 1; b = 1; }
            else                        { r = 0.7f; g = 0.7f; b = 0.7f; }

            // Centre cube: translate then uniform-scale (in local space).
            Matrix4f centerModel = new Matrix4f(gizmoModel)
                    .scale(Config.GIZMO_SCALE_CUBE_SIZE);
            drawGizmoSolidCube(centerModel, r, g, b);
        }

        glEnable(GL_DEPTH_TEST);
        GLCompat.lineWidth(1.0f);
    }

    public static int getHitAxis(Player player, GameObject selected, double mouseX, double mouseY,
                                 int width, int height) {
        if (selected == null) return -1;

        float[] ray = RayCaster.getMouseRay((float)mouseX, (float)mouseY, width, height,
                Config.FOV, player.pitch, player.yaw);

        float ox = player.x - selected.transform.x;
        float oy = player.y - selected.transform.y;
        float oz = player.z - selected.transform.z;

        float bestT = Float.MAX_VALUE;
        int hitAxis = -1;

        if (selected.transformMode == 0 || selected.transformMode == 2) {
            if (selected.transformMode == 2) {
                float tC = RayCaster.rayAABB(ox, oy, oz, ray[0], ray[1], ray[2],
                        -Config.GIZMO_CENTER_CUBE_SIZE, -Config.GIZMO_CENTER_CUBE_SIZE,
                        -Config.GIZMO_CENTER_CUBE_SIZE,
                        Config.GIZMO_CENTER_CUBE_SIZE, Config.GIZMO_CENTER_CUBE_SIZE,
                        Config.GIZMO_CENTER_CUBE_SIZE);
                if (tC > 0 && tC < bestT) { bestT = tC; hitAxis = 3; }
            }

            float tX = RayCaster.rayAABB(ox, oy, oz, ray[0], ray[1], ray[2],
                    0.15f, -0.06f, -0.06f, Config.GIZMO_ARROW_LENGTH + 0.1f, 0.06f, 0.06f);
            if (tX > 0 && tX < bestT) { bestT = tX; hitAxis = 0; }

            float tY = RayCaster.rayAABB(ox, oy, oz, ray[0], ray[1], ray[2],
                    -0.06f, 0.15f, -0.06f, 0.06f, Config.GIZMO_ARROW_LENGTH + 0.1f, 0.06f);
            if (tY > 0 && tY < bestT) { bestT = tY; hitAxis = 1; }

            float tZ = RayCaster.rayAABB(ox, oy, oz, ray[0], ray[1], ray[2],
                    -0.06f, -0.06f, 0.15f, 0.06f, 0.06f, Config.GIZMO_ARROW_LENGTH + 0.1f);
            if (tZ > 0 && tZ < bestT) { bestT = tZ; hitAxis = 2; }

        } else if (selected.transformMode == 1) {
            float tX = (ray[0] != 0) ? -ox / ray[0] : -1;
            if (tX > 0 && tX < bestT) {
                float hitY = oy + ray[1] * tX;
                float hitZ = oz + ray[2] * tX;
                if (Math.sqrt(hitY*hitY + hitZ*hitZ) > 1.2f && Math.sqrt(hitY*hitY + hitZ*hitZ) < 1.8f) {
                    bestT = tX; hitAxis = 0;
                }
            }

            float tY = (ray[1] != 0) ? -oy / ray[1] : -1;
            if (tY > 0 && tY < bestT) {
                float hitX = ox + ray[0] * tY;
                float hitZ = oz + ray[2] * tY;
                if (Math.sqrt(hitX*hitX + hitZ*hitZ) > 1.2f && Math.sqrt(hitX*hitX + hitZ*hitZ) < 1.8f) {
                    bestT = tY; hitAxis = 1;
                }
            }

            float tZ = (ray[2] != 0) ? -oz / ray[2] : -1;
            if (tZ > 0 && tZ < bestT) {
                float hitX = ox + ray[0] * tZ;
                float hitY = oy + ray[1] * tZ;
                if (Math.sqrt(hitX*hitX + hitY*hitY) > 1.2f && Math.sqrt(hitX*hitX + hitY*hitY) < 1.8f) {
                    bestT = tZ; hitAxis = 2;
                }
            }
        }
        return hitAxis;
    }

    /**
     * Returns the (r,g,b) tint to use for a gizmo axis given its base
     * colour + hover/drag state. Pure white when dragging, half-brightened
     * toward white when hovered (so the axis identity is still visible),
     * unmodified otherwise. Replaces the old `glLineWidth(hover ? 8 : 4)`
     * thickness-based feedback that no longer works in Core profile —
     * Core officially clamps aliased line width to 1.0 on every platform.
     */
    private static float[] axisTint(float r, float g, float b, boolean d, boolean h) {
        if (d) return new float[]{ 1f, 1f, 1f };
        if (h) return new float[]{ 0.5f + r * 0.5f, 0.5f + g * 0.5f, 0.5f + b * 0.5f };
        return new float[]{ r, g, b };
    }

    private static void drawArrow(Matrix4f model,
                                  float ex, float ey, float ez, float r, float g, float b,
                                  boolean d, boolean h) {
        float[] c = axisTint(r, g, b, d, h);
        // Draw the arrow shaft as a stretched-cube ribbon so the gizmo is
        // visibly thick. `glLineWidth` is officially 1.0-only in Core
        // profile (and on macOS attempting any larger value crashed the
        // context). Cube mesh exists already; scale it into a thin box.
        float length = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        float t = (d || h) ? 0.10f : 0.06f;   // thickness; hover → fatter
        Matrix4f shaft = new Matrix4f(model);
        if (Math.abs(ex) > 0.5f)      shaft.translate(length / 2f, 0, 0).scale(length, t, t);
        else if (Math.abs(ey) > 0.5f) shaft.translate(0, length / 2f, 0).scale(t, length, t);
        else                          shaft.translate(0, 0, length / 2f).scale(t, t, length);
        drawGizmoSolidCube(shaft, c[0], c[1], c[2]);
    }

    private static void drawScaleGizmo(Matrix4f model,
                                       float ex, float ey, float ez, float r, float g, float b,
                                       boolean d, boolean h) {
        float[] c = axisTint(r, g, b, d, h);
        // Shaft as stretched cube (same trick as drawArrow — see comment there).
        float length = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        float t = (d || h) ? 0.10f : 0.06f;
        Matrix4f shaft = new Matrix4f(model);
        if (Math.abs(ex) > 0.5f)      shaft.translate(length / 2f, 0, 0).scale(length, t, t);
        else if (Math.abs(ey) > 0.5f) shaft.translate(0, length / 2f, 0).scale(t, length, t);
        else                          shaft.translate(0, 0, length / 2f).scale(t, t, length);
        drawGizmoSolidCube(shaft, c[0], c[1], c[2]);

        // Cube at arrow tip: translate to (ex,ey,ez) in local space, scale 0.2.
        Matrix4f tipModel = new Matrix4f(model)
                .translate(ex, ey, ez)
                .scale(0.2f);
        drawGizmoSolidCube(tipModel, c[0], c[1], c[2]);
    }

    private static void drawGizmoCircle(Matrix4f model,
                                        int axis, float r, float g, float b, boolean d, boolean h) {
        // Each ring segment is drawn as a stretched-cube chord — same
        // approach as drawArrow / drawScaleGizmo — so the ring stays
        // visibly thick on every driver (Core profile clamps glLineWidth
        // to 1.0). For a 64-segment ring that's 64 tiny cubes per axis ×
        // 3 axes = 192 cube draws per frame when rotation mode is active,
        // all going through the existing line-renderer flat-colour pipe.
        // Cheap and gives the ring proper visual mass.
        float[] c = axisTint(r, g, b, d, h);
        float cr = c[0], cg = c[1], cb = c[2];

        float rad = Config.GIZMO_ROTATION_RADIUS;
        int seg = Config.GIZMO_CIRCLE_SEGMENTS;
        float thickness = (d || h) ? 0.10f : 0.06f;

        // Reused per segment to avoid allocation churn.
        Quaternionf q = new Quaternionf();

        for (int i = 0; i < seg; i++) {
            float th0 = (float) (2.0 * Math.PI * i / seg);
            float th1 = (float) (2.0 * Math.PI * ((i + 1) % seg) / seg);
            float c0 = (float) (rad * Math.cos(th0));
            float s0 = (float) (rad * Math.sin(th0));
            float c1 = (float) (rad * Math.cos(th1));
            float s1 = (float) (rad * Math.sin(th1));

            float sx, sy, sz, ex, ey, ez;
            if (axis == 1) {        // Y-ring lies in XZ
                sx = c0; sy = 0;  sz = s0;
                ex = c1; ey = 0;  ez = s1;
            } else if (axis == 0) { // X-ring lies in YZ
                sx = 0;  sy = c0; sz = s0;
                ex = 0;  ey = c1; ez = s1;
            } else {                // Z-ring lies in XY
                sx = c0; sy = s0; sz = 0;
                ex = c1; ey = s1; ez = 0;
            }

            float dx = ex - sx, dy = ey - sy, dz = ez - sz;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-5f) continue;
            float invLen = 1f / len;
            float nx = dx * invLen, ny = dy * invLen, nz = dz * invLen;

            // Build a rotation that maps the cube's local +X axis onto the
            // chord direction. The remaining roll around the chord doesn't
            // affect visuals (cube is symmetric in Y/Z thickness).
            q.identity().rotateTo(1, 0, 0, nx, ny, nz);

            Matrix4f segModel = new Matrix4f(model)
                    .translate((sx + ex) * 0.5f, (sy + ey) * 0.5f, (sz + ez) * 0.5f)
                    .rotate(q)
                    .scale(len, thickness, thickness);
            drawGizmoSolidCube(segModel, cr, cg, cb);
        }
    }

    private static void drawGizmoSolidCube(Matrix4f model, float r, float g, float b) {
        Mesh cube = AssetPool.getMesh(World.CUBE_MESH_KEY);
        if (cube != null) LineRenderer3D.drawMesh(cube, model, r, g, b);
    }
}
