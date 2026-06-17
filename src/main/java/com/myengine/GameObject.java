package com.myengine;

import java.util.*;

class GameObject {
    public String uuid;
    public String name = "Object";
    public int transformMode = 0;

    public Transform transform;
    public List<Component> components = new ArrayList<>();

    // Cached world-space AABB (rotation-aware). Recomputed lazily whenever the
    // transform changes — see worldBounds().
    private transient float[] boundsCache;
    private transient float cTx, cTy, cTz, cRx, cRy, cRz, cSx, cSy, cSz;

    public GameObject(float x, float y, float z) {
        this.uuid = UUID.randomUUID().toString();
        this.transform = new Transform(x, y, z);
        this.transform.gameObject = this;
    }

    public void addComponent(Component c) {
        components.add(c);
        c.gameObject = this;
        c.start();
    }

    /** Attach a component WITHOUT invoking its {@link Component#start()}.
     *  Used by no-GL level deserialization (Vulkan renderer): {@code start()}
     *  on most components calls into OpenGL via {@link AssetPool}, which
     *  crashes when no GL context is current. Callers that take this path
     *  are responsible for any equivalent setup (e.g. loading the asset
     *  into a renderer-specific cache). */
    public void addComponentNoStart(Component c) {
        components.add(c);
        c.gameObject = this;
    }

    public <T extends Component> T getComponent(Class<T> componentClass) {
        for (Component c : components) {
            if (componentClass.isAssignableFrom(c.getClass())) {
                return componentClass.cast(c);
            }
        }
        return null;
    }

    public void update(double dt) {
        transform.update(dt);
        for (Component c : components) {
            c.update(dt);
        }
    }

    // World-space AABB that accounts for rotation AND scale. Computed by
    // transforming the 8 corners of the mesh's local box through the full
    // transform (scale -> Rz -> Ry -> Rx -> translate, matching the renderer)
    // and taking the min/max. The previous version ignored rotation, so a
    // rotated block's bounding box was too small — collision pre-filters then
    // wrongly rejected valid hits, and the error grew with the block's size.
    private float[] worldBounds() {
        if (boundsCache != null
                && cTx == transform.x && cTy == transform.y && cTz == transform.z
                && cRx == transform.rotX && cRy == transform.rotY && cRz == transform.rotZ
                && cSx == transform.scaleX && cSy == transform.scaleY && cSz == transform.scaleZ) {
            return boundsCache;
        }

        MeshRenderer mr = getComponent(MeshRenderer.class);
        float lminX, lminY, lminZ, lmaxX, lmaxY, lmaxZ;
        if (mr != null) {
            // Renderer-agnostic accessors — OpenGL path reads from mr.mesh,
            // Vulkan path reads from mr.vkLocalBounds. Falls back to ±0.5
            // when neither has populated bounds (e.g. mesh hasn't loaded yet).
            lminX = mr.localMinX(); lminY = mr.localMinY(); lminZ = mr.localMinZ();
            lmaxX = mr.localMaxX(); lmaxY = mr.localMaxY(); lmaxZ = mr.localMaxZ();
        } else {
            lminX = lminY = lminZ = -0.5f;
            lmaxX = lmaxY = lmaxZ = 0.5f;
        }

        float sx = transform.scaleX, sy = transform.scaleY, sz = transform.scaleZ;
        double rx = Math.toRadians(transform.rotX);
        double ry = Math.toRadians(transform.rotY);
        double rz = Math.toRadians(transform.rotZ);
        float crx = (float) Math.cos(rx), srx = (float) Math.sin(rx);
        float cry = (float) Math.cos(ry), sry = (float) Math.sin(ry);
        float crz = (float) Math.cos(rz), srz = (float) Math.sin(rz);

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (int i = 0; i < 8; i++) {
            float px = ((i & 1) == 0 ? lminX : lmaxX) * sx;
            float py = ((i & 2) == 0 ? lminY : lmaxY) * sy;
            float pz = ((i & 4) == 0 ? lminZ : lmaxZ) * sz;

            float x1 = px * crz - py * srz;
            float y1 = px * srz + py * crz;
            px = x1; py = y1;

            float x2 = px * cry + pz * sry;
            float z2 = -px * sry + pz * cry;
            px = x2; pz = z2;

            float y3 = py * crx - pz * srx;
            float z3 = py * srx + pz * crx;
            py = y3; pz = z3;

            float wx = px + transform.x;
            float wy = py + transform.y;
            float wz = pz + transform.z;
            if (wx < minX) minX = wx;
            if (wx > maxX) maxX = wx;
            if (wy < minY) minY = wy;
            if (wy > maxY) maxY = wy;
            if (wz < minZ) minZ = wz;
            if (wz > maxZ) maxZ = wz;
        }

        boundsCache = new float[]{minX, minY, minZ, maxX, maxY, maxZ};
        cTx = transform.x; cTy = transform.y; cTz = transform.z;
        cRx = transform.rotX; cRy = transform.rotY; cRz = transform.rotZ;
        cSx = transform.scaleX; cSy = transform.scaleY; cSz = transform.scaleZ;
        return boundsCache;
    }

    public float getMinX() { return worldBounds()[0]; }
    public float getMinY() { return worldBounds()[1]; }
    public float getMinZ() { return worldBounds()[2]; }
    public float getMaxX() { return worldBounds()[3]; }
    public float getMaxY() { return worldBounds()[4]; }
    public float getMaxZ() { return worldBounds()[5]; }
}
