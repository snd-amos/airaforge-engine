package com.myengine;

class Material {
    public float r = 1.0f;
    public float g = 1.0f;
    public float b = 1.0f;

    // World-space (triplanar) texture tiling. When TRUE, the fragment shader
    // computes UVs from the fragment's world position projected onto the
    // dominant face plane, tiling the texture at `textureScale` repeats per
    // world unit. Scaling a cube up tiles the texture instead of stretching
    // it. When FALSE, the mesh's stored UVs are used as-is — needed for
    // imported models with proper UV mapping (textures painted onto specific
    // areas of the model, like character clothing).
    //
    // Nullable Boolean: `null` means "user hasn't set this, use the auto-
    // default" (filled in by MeshRenderer.start() based on mesh type).
    // Once the user toggles it via the Inspector it's TRUE/FALSE explicitly
    // and survives save/load. GSON omits null fields so this stays
    // backward-compatible with levels saved before the feature existed.
    public Boolean useWorldSpaceTiling = null;
    public float textureScale = 1.0f;

    // Per-face RGB tints for procedural cubes. `null` = "use the single
    // colour tint (r,g,b) for every face" (back-compat with all existing
    // levels and the simple use case). When set, must be a 6x3 array of
    // [+X, -X, +Y, -Y, +Z, -Z] colours; the cube's instanced fragment
    // shader picks the right row by inspecting the face's local normal.
    //
    // Only consumed by `CubeBatch` for procedural cubes. Imported models
    // (truck, tree, character) keep using the single colour tint — they
    // have UV-mapped textures and per-face colours don't apply to them.
    public float[][] faceColors = null;

    public void setColor(float r, float g, float b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    public float[] getColor() {
        return new float[]{r, g, b};
    }

    // Used by CubeBatch when packing per-instance data. Writes 18 floats
    // (6 RGB triplets in the +X, -X, +Y, -Y, +Z, -Z order matching the
    // instanced shader's face-classification math).
    public void writeFaceColorsInto(float[] dst, int offset) {
        if (faceColors == null) {
            for (int f = 0; f < 6; f++) {
                dst[offset + f * 3] = r;
                dst[offset + f * 3 + 1] = g;
                dst[offset + f * 3 + 2] = b;
            }
        } else {
            for (int f = 0; f < 6; f++) {
                float[] c = faceColors[f];
                if (c != null && c.length >= 3) {
                    dst[offset + f * 3] = c[0];
                    dst[offset + f * 3 + 1] = c[1];
                    dst[offset + f * 3 + 2] = c[2];
                } else {
                    dst[offset + f * 3] = r;
                    dst[offset + f * 3 + 1] = g;
                    dst[offset + f * 3 + 2] = b;
                }
            }
        }
    }

    public void enablePerFaceColors() {
        if (faceColors == null) {
            faceColors = new float[6][3];
            for (int f = 0; f < 6; f++) {
                faceColors[f][0] = r;
                faceColors[f][1] = g;
                faceColors[f][2] = b;
            }
        }
    }

    public void disablePerFaceColors() {
        faceColors = null;
    }
}
