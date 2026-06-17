package com.myengine;

import java.nio.FloatBuffer;

/**
 * Mesh-accurate ray and AABB intersection tests.
 *
 * Approach: transform each triangle's three vertices to world space (handles
 * translation, rotation, scale, and bone-skinning for animated meshes), then
 * run the intersection test in world space. Coarse AABB pre-filter rejects
 * most objects before this work.
 *
 * Objects with no mesh fall back to plain AABB results.
 */
class MeshCollider {

    public static float rayMesh(GameObject obj,
                                float ox, float oy, float oz,
                                float dx, float dy, float dz) {
        float tBBox = RayCaster.rayAABB(ox, oy, oz, dx, dy, dz,
                obj.getMinX(), obj.getMinY(), obj.getMinZ(),
                obj.getMaxX(), obj.getMaxY(), obj.getMaxZ());
        if (tBBox < 0) return -1.0f;

        MeshRenderer mr = obj.getComponent(MeshRenderer.class);
        if (mr == null || !mr.hasLocalGeometry()) return tBBox;
        // Billboards (markers) are rotated to face the camera at render time,
        // so the bind-pose quad doesn't match what you see. Use AABB hit.
        if (World.SPAWN_POINT_KEY.equals(mr.modelPath) ||
            World.WIN_ZONE_KEY.equals(mr.modelPath)) return tBBox;

        float[] worldPos = computeWorldPositions(obj, mr);
        int[] ind = mr.localIndices();

        float closest = Float.MAX_VALUE;
        float[] v0 = new float[3], v1 = new float[3], v2 = new float[3];
        for (int i = 0; i < ind.length; i += 3) {
            int a = ind[i] * 3, b = ind[i + 1] * 3, c = ind[i + 2] * 3;
            v0[0] = worldPos[a]; v0[1] = worldPos[a + 1]; v0[2] = worldPos[a + 2];
            v1[0] = worldPos[b]; v1[1] = worldPos[b + 1]; v1[2] = worldPos[b + 2];
            v2[0] = worldPos[c]; v2[1] = worldPos[c + 1]; v2[2] = worldPos[c + 2];
            float t = rayTriangle(ox, oy, oz, dx, dy, dz,
                    v0[0], v0[1], v0[2], v1[0], v1[1], v1[2], v2[0], v2[1], v2[2]);
            if (t > 0 && t < closest) closest = t;
        }
        return closest == Float.MAX_VALUE ? -1.0f : closest;
    }

    // Like rayMesh, but returns {t, nx, ny, nz} — the hit distance plus the
    // surface normal of the triangle that was hit. Returns null on a miss.
    // The normal is always flipped to point toward the ray origin's side
    // (so for a downward ground probe the normal points up).
    public static float[] rayMeshNormal(GameObject obj,
                                        float ox, float oy, float oz,
                                        float dx, float dy, float dz) {
        float tBBox = RayCaster.rayAABB(ox, oy, oz, dx, dy, dz,
                obj.getMinX(), obj.getMinY(), obj.getMinZ(),
                obj.getMaxX(), obj.getMaxY(), obj.getMaxZ());
        if (tBBox < 0) return null;

        MeshRenderer mr = obj.getComponent(MeshRenderer.class);
        if (mr == null || !mr.hasLocalGeometry()) {
            return new float[]{tBBox, 0, 1, 0};
        }
        if (World.SPAWN_POINT_KEY.equals(mr.modelPath) ||
            World.WIN_ZONE_KEY.equals(mr.modelPath)) return null;

        float[] worldPos = computeWorldPositions(obj, mr);
        int[] ind = mr.localIndices();

        float closest = Float.MAX_VALUE;
        float cnx = 0, cny = 1, cnz = 0;
        for (int i = 0; i < ind.length; i += 3) {
            int a = ind[i] * 3, b = ind[i + 1] * 3, c = ind[i + 2] * 3;
            float ax = worldPos[a], ay = worldPos[a + 1], az = worldPos[a + 2];
            float bx = worldPos[b], by = worldPos[b + 1], bz = worldPos[b + 2];
            float cx = worldPos[c], cy = worldPos[c + 1], cz = worldPos[c + 2];
            float t = rayTriangle(ox, oy, oz, dx, dy, dz,
                    ax, ay, az, bx, by, bz, cx, cy, cz);
            if (t > 0 && t < closest) {
                closest = t;
                float e0x = bx - ax, e0y = by - ay, e0z = bz - az;
                float e1x = cx - bx, e1y = cy - by, e1z = cz - bz;
                float nx = e0y * e1z - e0z * e1y;
                float ny = e0z * e1x - e0x * e1z;
                float nz = e0x * e1y - e0y * e1x;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl > 1e-6f) {
                    nx /= nl; ny /= nl; nz /= nl;
                    // Flip normal toward the ray origin so it's consistent
                    // regardless of triangle winding.
                    if (nx * dx + ny * dy + nz * dz > 0) {
                        nx = -nx; ny = -ny; nz = -nz;
                    }
                    cnx = nx; cny = ny; cnz = nz;
                }
            }
        }
        if (closest == Float.MAX_VALUE) return null;
        return new float[]{closest, cnx, cny, cnz};
    }

    // AABB-vs-mesh that ONLY counts steep triangles (|normal.y| < walkableY).
    // Walkable floors/ramps are ignored — so a tilted ramp never blocks
    // horizontal movement; only true walls do.
    public static boolean intersectsAABBSteep(GameObject obj,
                                              float wMinX, float wMinY, float wMinZ,
                                              float wMaxX, float wMaxY, float wMaxZ,
                                              float walkableY) {
        if (!aabbOverlap(obj.getMinX(), obj.getMinY(), obj.getMinZ(),
                obj.getMaxX(), obj.getMaxY(), obj.getMaxZ(),
                wMinX, wMinY, wMinZ, wMaxX, wMaxY, wMaxZ)) return false;

        MeshRenderer mr = obj.getComponent(MeshRenderer.class);
        if (mr == null || !mr.hasLocalGeometry()) return true;
        if (World.SPAWN_POINT_KEY.equals(mr.modelPath) ||
            World.WIN_ZONE_KEY.equals(mr.modelPath)) return false;

        float[] worldPos = computeWorldPositions(obj, mr);
        int[] ind = mr.localIndices();

        float cx = (wMinX + wMaxX) * 0.5f, cy = (wMinY + wMaxY) * 0.5f, cz = (wMinZ + wMaxZ) * 0.5f;
        float hx = (wMaxX - wMinX) * 0.5f, hy = (wMaxY - wMinY) * 0.5f, hz = (wMaxZ - wMinZ) * 0.5f;

        for (int i = 0; i < ind.length; i += 3) {
            int a = ind[i] * 3, b = ind[i + 1] * 3, c = ind[i + 2] * 3;
            float ax = worldPos[a], ay = worldPos[a + 1], az = worldPos[a + 2];
            float bx = worldPos[b], by = worldPos[b + 1], bz = worldPos[b + 2];
            float cx2 = worldPos[c], cy2 = worldPos[c + 1], cz2 = worldPos[c + 2];

            // Skip walkable (flat-ish) triangles — only walls/steep block.
            float e0x = bx - ax, e0y = by - ay, e0z = bz - az;
            float e1x = cx2 - bx, e1y = cy2 - by, e1z = cz2 - bz;
            float nx = e0y * e1z - e0z * e1y;
            float ny = e0z * e1x - e0x * e1z;
            float nz = e0x * e1y - e0y * e1x;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl > 1e-6f && Math.abs(ny / nl) >= walkableY) continue;

            if (triBoxOverlap(cx, cy, cz, hx, hy, hz,
                    ax, ay, az, bx, by, bz, cx2, cy2, cz2)) {
                return true;
            }
        }
        return false;
    }

    public static boolean intersectsAABB(GameObject obj,
                                         float wMinX, float wMinY, float wMinZ,
                                         float wMaxX, float wMaxY, float wMaxZ) {
        if (!aabbOverlap(obj.getMinX(), obj.getMinY(), obj.getMinZ(),
                obj.getMaxX(), obj.getMaxY(), obj.getMaxZ(),
                wMinX, wMinY, wMinZ, wMaxX, wMaxY, wMaxZ)) return false;

        MeshRenderer mr = obj.getComponent(MeshRenderer.class);
        if (mr == null || !mr.hasLocalGeometry()) return true;
        if (World.SPAWN_POINT_KEY.equals(mr.modelPath) ||
            World.WIN_ZONE_KEY.equals(mr.modelPath)) return true;

        // Characters: use a vertical capsule fitted to the mesh instead of
        // per-triangle. The mesh bind-pose has T-pose arms wide, so triangle
        // collision blocks far past the visible body. A capsule fitted to the
        // narrower body depth gives the player a clean cylindrical hitbox.
        if (mr.isCharacter()) {
            float[] cap = characterCapsule(obj, mr);
            return capsuleVsAABB(cap[0], cap[1], cap[2], cap[3], cap[4],
                    wMinX, wMinY, wMinZ, wMaxX, wMaxY, wMaxZ);
        }

        float[] worldPos = computeWorldPositions(obj, mr);
        int[] ind = mr.localIndices();

        float cx = (wMinX + wMaxX) * 0.5f;
        float cy = (wMinY + wMaxY) * 0.5f;
        float cz = (wMinZ + wMaxZ) * 0.5f;
        float hx = (wMaxX - wMinX) * 0.5f;
        float hy = (wMaxY - wMinY) * 0.5f;
        float hz = (wMaxZ - wMinZ) * 0.5f;

        for (int i = 0; i < ind.length; i += 3) {
            int a = ind[i] * 3, b = ind[i + 1] * 3, c = ind[i + 2] * 3;
            if (triBoxOverlap(cx, cy, cz, hx, hy, hz,
                    worldPos[a],     worldPos[a + 1], worldPos[a + 2],
                    worldPos[b],     worldPos[b + 1], worldPos[b + 2],
                    worldPos[c],     worldPos[c + 1], worldPos[c + 2])) {
                return true;
            }
        }
        return false;
    }

    // ============ World-space vertex transformation ============

    private static float[] computeWorldPositions(GameObject obj, MeshRenderer mr) {
        float[] pos = mr.localPositions();
        float[] out = new float[pos.length];

        float tx = obj.transform.x, ty = obj.transform.y, tz = obj.transform.z;
        float sx = obj.transform.scaleX, sy = obj.transform.scaleY, sz = obj.transform.scaleZ;
        boolean hasRot = obj.transform.rotX != 0 || obj.transform.rotY != 0 || obj.transform.rotZ != 0;

        double rxRad = Math.toRadians(obj.transform.rotX);
        double ryRad = Math.toRadians(obj.transform.rotY);
        double rzRad = Math.toRadians(obj.transform.rotZ);
        float crx = (float) Math.cos(rxRad), srx = (float) Math.sin(rxRad);
        float cry = (float) Math.cos(ryRad), sry = (float) Math.sin(ryRad);
        float crz = (float) Math.cos(rzRad), srz = (float) Math.sin(rzRad);

        // Skinning data only lives on the GL Mesh — the Vulkan path doesn't
        // populate it yet (sub-phase G). When mesh is null, skinning is
        // skipped and the bind-pose triangles are used for collision.
        boolean skinned = (mr.mesh != null) && mr.mesh.isSkinned
                && mr.mesh.boneIds != null && mr.mesh.weights != null;
        FloatBuffer bones = null;
        if (skinned) {
            Animator anim = obj.getComponent(Animator.class);
            bones = (anim != null && anim.currentAnimation != null) ? anim.matricesBuffer : null;
        }

        int vertCount = pos.length / 3;
        for (int v = 0; v < vertCount; v++) {
            float px = pos[v * 3];
            float py = pos[v * 3 + 1];
            float pz = pos[v * 3 + 2];

            // Apply skinning (bind-pose if no animation)
            if (skinned && bones != null) {
                float[] skinned3 = skinVertex(px, py, pz, v, mr.mesh.boneIds, mr.mesh.weights, bones);
                px = skinned3[0]; py = skinned3[1]; pz = skinned3[2];
            }

            // Scale
            px *= sx; py *= sy; pz *= sz;

            // Rotate Z then Y then X (matches glRotatef order applied to a vertex)
            if (hasRot) {
                // Rz
                float x1 = px * crz - py * srz;
                float y1 = px * srz + py * crz;
                px = x1; py = y1;
                // Ry
                float x2 = px * cry + pz * sry;
                float z2 = -px * sry + pz * cry;
                px = x2; pz = z2;
                // Rx
                float y3 = py * crx - pz * srx;
                float z3 = py * srx + pz * crx;
                py = y3; pz = z3;
            }

            // Translate
            out[v * 3]     = px + tx;
            out[v * 3 + 1] = py + ty;
            out[v * 3 + 2] = pz + tz;
        }
        return out;
    }

    private static float[] skinVertex(float px, float py, float pz, int vIdx,
                                      int[] boneIds, float[] weights, FloatBuffer bones) {
        float ox = 0, oy = 0, oz = 0;
        boolean any = false;
        for (int j = 0; j < 4; j++) {
            int bId = boneIds[vIdx * 4 + j];
            if (bId < 0) continue;
            float w = weights[vIdx * 4 + j];
            if (w == 0) continue;
            int base = bId * 16;
            // Column-major mat4 (GLSL convention).
            float m00 = bones.get(base),     m10 = bones.get(base + 1),  m20 = bones.get(base + 2);
            float m01 = bones.get(base + 4), m11 = bones.get(base + 5),  m21 = bones.get(base + 6);
            float m02 = bones.get(base + 8), m12 = bones.get(base + 9),  m22 = bones.get(base + 10);
            float m03 = bones.get(base + 12),m13 = bones.get(base + 13), m23 = bones.get(base + 14);
            float x = m00 * px + m01 * py + m02 * pz + m03;
            float y = m10 * px + m11 * py + m12 * pz + m13;
            float z = m20 * px + m21 * py + m22 * pz + m23;
            ox += x * w; oy += y * w; oz += z * w;
            any = true;
        }
        if (!any) return new float[]{px, py, pz};
        return new float[]{ox, oy, oz};
    }

    private static boolean aabbOverlap(float aMinX, float aMinY, float aMinZ,
                                       float aMaxX, float aMaxY, float aMaxZ,
                                       float bMinX, float bMinY, float bMinZ,
                                       float bMaxX, float bMaxY, float bMaxZ) {
        return aMaxX > bMinX && aMinX < bMaxX
            && aMaxY > bMinY && aMinY < bMaxY
            && aMaxZ > bMinZ && aMinZ < bMaxZ;
    }

    // ============ Möller-Trumbore ray-triangle ============

    private static float rayTriangle(float ox, float oy, float oz,
                                     float dx, float dy, float dz,
                                     float ax, float ay, float az,
                                     float bx, float by, float bz,
                                     float cx, float cy, float cz) {
        float ex = bx - ax, ey = by - ay, ez = bz - az;
        float fx = cx - ax, fy = cy - ay, fz = cz - az;
        float hx = dy * fz - dz * fy;
        float hy = dz * fx - dx * fz;
        float hz = dx * fy - dy * fx;
        float a = ex * hx + ey * hy + ez * hz;
        if (a > -1e-7f && a < 1e-7f) return -1;
        float invA = 1.0f / a;
        float sx = ox - ax, sy = oy - ay, sz = oz - az;
        float u = invA * (sx * hx + sy * hy + sz * hz);
        if (u < 0 || u > 1) return -1;
        float qx = sy * ez - sz * ey;
        float qy = sz * ex - sx * ez;
        float qz = sx * ey - sy * ex;
        float v = invA * (dx * qx + dy * qy + dz * qz);
        if (v < 0 || u + v > 1) return -1;
        float t = invA * (fx * qx + fy * qy + fz * qz);
        return t > 0 ? t : -1;
    }

    // ============ AABB-triangle SAT (Akenine-Möller) ============

    private static boolean triBoxOverlap(float bcx, float bcy, float bcz,
                                         float hx, float hy, float hz,
                                         float ax, float ay, float az,
                                         float bx, float by, float bz,
                                         float c2x, float c2y, float c2z) {
        float v0x = ax - bcx, v0y = ay - bcy, v0z = az - bcz;
        float v1x = bx - bcx, v1y = by - bcy, v1z = bz - bcz;
        float v2x = c2x - bcx, v2y = c2y - bcy, v2z = c2z - bcz;

        float minV, maxV;
        minV = Math.min(v0x, Math.min(v1x, v2x));
        maxV = Math.max(v0x, Math.max(v1x, v2x));
        if (minV > hx || maxV < -hx) return false;
        minV = Math.min(v0y, Math.min(v1y, v2y));
        maxV = Math.max(v0y, Math.max(v1y, v2y));
        if (minV > hy || maxV < -hy) return false;
        minV = Math.min(v0z, Math.min(v1z, v2z));
        maxV = Math.max(v0z, Math.max(v1z, v2z));
        if (minV > hz || maxV < -hz) return false;

        float e0x = v1x - v0x, e0y = v1y - v0y, e0z = v1z - v0z;
        float e1x = v2x - v1x, e1y = v2y - v1y, e1z = v2z - v1z;
        float nx = e0y * e1z - e0z * e1y;
        float ny = e0z * e1x - e0x * e1z;
        float nz = e0x * e1y - e0y * e1x;
        float d = nx * v0x + ny * v0y + nz * v0z;
        float r = Math.abs(nx) * hx + Math.abs(ny) * hy + Math.abs(nz) * hz;
        if (Math.abs(d) > r) return false;

        float e2x = v0x - v2x, e2y = v0y - v2y, e2z = v0z - v2z;
        if (axisSeparates(0, -e0z, e0y, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) return false;
        if (axisSeparates(e0z, 0, -e0x, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) return false;
        if (axisSeparates(-e0y, e0x, 0, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) return false;
        if (axisSeparates(0, -e1z, e1y, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) return false;
        if (axisSeparates(e1z, 0, -e1x, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) return false;
        if (axisSeparates(-e1y, e1x, 0, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) return false;
        if (axisSeparates(0, -e2z, e2y, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) return false;
        if (axisSeparates(e2z, 0, -e2x, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) return false;
        if (axisSeparates(-e2y, e2x, 0, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, hx, hy, hz)) return false;

        return true;
    }

    // ============ Capsule (character collider) ============

    // Returns {worldX, worldYBottom, worldYTop, worldZ, radius} describing a
    // vertical capsule fitted to the character mesh. Radius uses the SMALLER
    // horizontal extent (front-to-back depth) so T-pose arms don't inflate it.
    public static float[] characterCapsule(GameObject obj, MeshRenderer mr) {
        // Reads bounds through the helpers so Vulkan-loaded characters
        // (which have vkLocalBounds populated but mr.mesh == null) work too.
        float sx = obj.transform.scaleX, sy = obj.transform.scaleY, sz = obj.transform.scaleZ;
        float bottomY = obj.transform.y + mr.localMinY() * sy;
        float topY    = obj.transform.y + mr.localMaxY() * sy;
        float dx = (mr.localMaxX() - mr.localMinX()) * Math.abs(sx);
        float dz = (mr.localMaxZ() - mr.localMinZ()) * Math.abs(sz);
        float radius = Math.min(dx, dz) * 0.5f;
        return new float[]{obj.transform.x, bottomY, topY, obj.transform.z, radius};
    }

    public static boolean capsuleVsAABB(float cx, float yBot, float yTop, float cz, float r,
                                        float aMinX, float aMinY, float aMinZ,
                                        float aMaxX, float aMaxY, float aMaxZ) {
        // Capsule's central segment endpoints (top/bottom of cylindrical part)
        float segA = yBot + r;
        float segB = yTop - r;
        if (segA > segB) {
            // Capsule height < 2r — collapse to a sphere
            float midY = (yBot + yTop) * 0.5f;
            return sphereVsAABB(cx, midY, cz, r, aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ);
        }
        // For a vertical segment the closest Y to the AABB is clamped to [segA,segB]
        float aCy = (aMinY + aMaxY) * 0.5f;
        float closestY = Math.max(segA, Math.min(segB, aCy));
        return sphereVsAABB(cx, closestY, cz, r, aMinX, aMinY, aMinZ, aMaxX, aMaxY, aMaxZ);
    }

    private static boolean sphereVsAABB(float sx, float sy, float sz, float r,
                                        float aMinX, float aMinY, float aMinZ,
                                        float aMaxX, float aMaxY, float aMaxZ) {
        float cx = Math.max(aMinX, Math.min(aMaxX, sx));
        float cy = Math.max(aMinY, Math.min(aMaxY, sy));
        float cz = Math.max(aMinZ, Math.min(aMaxZ, sz));
        float dx = sx - cx, dy = sy - cy, dz = sz - cz;
        return (dx * dx + dy * dy + dz * dz) < r * r;
    }

    private static boolean axisSeparates(float ax, float ay, float az,
                                         float v0x, float v0y, float v0z,
                                         float v1x, float v1y, float v1z,
                                         float v2x, float v2y, float v2z,
                                         float hx, float hy, float hz) {
        float p0 = ax * v0x + ay * v0y + az * v0z;
        float p1 = ax * v1x + ay * v1y + az * v1z;
        float p2 = ax * v2x + ay * v2y + az * v2z;
        float minP = Math.min(p0, Math.min(p1, p2));
        float maxP = Math.max(p0, Math.max(p1, p2));
        float r = Math.abs(ax) * hx + Math.abs(ay) * hy + Math.abs(az) * hz;
        return minP > r || maxP < -r;
    }

    // ============ Oriented-box (cube) depenetration ============

    // Pushes a world point (with sphere radius) out of a procedural cube's
    // ORIENTED volume. The cube's local space is a unit box (±0.5), so we
    // transform the point into local space (undo translate -> Rx -> Ry -> Rz
    // -> scale, the inverse of GameObject.worldBounds' scale -> Rz -> Ry -> Rx
    // -> translate), test against ±(0.5 + radius/scale) per axis, and push out
    // the MINIMUM-penetration face. Exact + bounded for boxes: it always exits
    // the nearest face, so it can never teleport the player through the solid.
    // Returns the world push vector {dx,dy,dz}, or null if the point is outside.
    public static float[] pushOutOfCube(GameObject cube, float wx, float wy, float wz, float radius) {
        Transform t = cube.transform;
        float sx = t.scaleX, sy = t.scaleY, sz = t.scaleZ;
        if (sx < 1e-4f || sy < 1e-4f || sz < 1e-4f) return null;

        double rxr = Math.toRadians(t.rotX), ryr = Math.toRadians(t.rotY), rzr = Math.toRadians(t.rotZ);
        float crx = (float) Math.cos(rxr), srx = (float) Math.sin(rxr);
        float cry = (float) Math.cos(ryr), sry = (float) Math.sin(ryr);
        float crz = (float) Math.cos(rzr), srz = (float) Math.sin(rzr);

        // world - translate, then inverse rotation (Rx^-1, Ry^-1, Rz^-1)
        float px = wx - t.x, py = wy - t.y, pz = wz - t.z;
        float y1 =  py * crx + pz * srx, z1 = -py * srx + pz * crx; py = y1; pz = z1;   // Rx^-1
        float x2 =  px * cry - pz * sry, z2 =  px * sry + pz * cry; px = x2; pz = z2;   // Ry^-1
        float x3 =  px * crz + py * srz, y3 = -px * srz + py * crz; px = x3; py = y3;   // Rz^-1

        float lx = px / sx, ly = py / sy, lz = pz / sz;           // local cube coords (±0.5)
        float hx = 0.5f + radius / sx, hy = 0.5f + radius / sy, hz = 0.5f + radius / sz;
        if (Math.abs(lx) >= hx || Math.abs(ly) >= hy || Math.abs(lz) >= hz) return null; // outside

        // Penetration depth per axis, in WORLD units, and pick the smallest.
        float wPenX = (hx - Math.abs(lx)) * sx;
        float wPenY = (hy - Math.abs(ly)) * sy;
        float wPenZ = (hz - Math.abs(lz)) * sz;

        float nlx = 0, nly = 0, nlz = 0, wpen;
        if (wPenX <= wPenY && wPenX <= wPenZ)      { nlx = Math.signum(lx); wpen = wPenX; }
        else if (wPenY <= wPenZ)                   { nly = Math.signum(ly); wpen = wPenY; }
        else                                       { nlz = Math.signum(lz); wpen = wPenZ; }

        // Rotate the chosen local face normal back to world (forward Rz, Ry, Rx).
        float nx = nlx, ny = nly, nz = nlz;
        float nx1 = nx * crz - ny * srz, ny1 = nx * srz + ny * crz; nx = nx1; ny = ny1;  // Rz
        float nx2 = nx * cry + nz * sry, nz2 = -nx * sry + nz * cry; nx = nx2; nz = nz2;  // Ry
        float ny3 = ny * crx - nz * srx, nz3 = ny * srx + nz * crx; ny = ny3; nz = nz3;   // Rx
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-6f) return null;
        nx /= nl; ny /= nl; nz /= nl;
        return new float[]{ nx * wpen, ny * wpen, nz * wpen };
    }
}
