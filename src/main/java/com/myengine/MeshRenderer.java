package com.myengine;

class MeshRenderer extends Component {
    public String texturePath = "test.png";
    public transient int textureID = 0;

    public String modelPath = "";
    public transient Mesh mesh = null;

    // Vulkan-side mirrors of the data that MeshCollider + GameObject.worldBounds
    // read from `mesh` in the OpenGL build. Populated by VkMeshLoader /
    // procedural mesh builders so the shared collision + bounds code
    // works without a GL `Mesh` instance. {@code mesh != null} wins when
    // both are present (OpenGL renderer's start() path is unchanged).
    //   vkLocalPositions: interleaved x,y,z per vertex
    //   vkLocalIndices:   triangle list, 3 indices per triangle
    //   vkLocalBounds:    6 floats — minX, minY, minZ, maxX, maxY, maxZ
    public transient float[] vkLocalPositions;
    public transient int[]   vkLocalIndices;
    public transient float[] vkLocalBounds;
    // Sub-phase G: Skeleton populated by VkMeshLoader for skinned characters.
    // Mirrors the OpenGL `mesh.skeleton` field so Animator.recompute()
    // works on both renderers via {@link #skeleton()}.
    public transient Skeleton vkSkeleton;

    public Material material = new Material();

    // Foliage / alpha-cutout flag. When true, the selection outline pass
    // discards transparent fragments from the diffuse texture so the
    // silhouette follows the visible (opaque) shape of e.g. leaves instead
    // of the underlying flat quad. Default false so cubes / opaque models
    // get a clean solid silhouette regardless of their texture content.
    public boolean isFoliage = false;

    public boolean isCharacter() {
        return modelPath != null && modelPath.startsWith(Config.CHARACTER_PATH_PREFIX);
    }

    // ---- Renderer-agnostic local geometry accessors ----
    // MeshCollider + GameObject.worldBounds call these instead of reaching
    // through mr.mesh directly so the Vulkan build (where mesh is always
    // null) gets the same answers from the vkLocal* fields.

    public boolean hasLocalGeometry() {
        return (mesh != null && mesh.positions != null) || vkLocalPositions != null;
    }
    public float[] localPositions() {
        return (mesh != null && mesh.positions != null) ? mesh.positions : vkLocalPositions;
    }
    public int[] localIndices() {
        return (mesh != null && mesh.indices != null) ? mesh.indices : vkLocalIndices;
    }
    public float localMinX() { return mesh != null ? mesh.minX : (vkLocalBounds != null ? vkLocalBounds[0] : -0.5f); }
    public float localMinY() { return mesh != null ? mesh.minY : (vkLocalBounds != null ? vkLocalBounds[1] : -0.5f); }
    public float localMinZ() { return mesh != null ? mesh.minZ : (vkLocalBounds != null ? vkLocalBounds[2] : -0.5f); }
    public float localMaxX() { return mesh != null ? mesh.maxX : (vkLocalBounds != null ? vkLocalBounds[3] :  0.5f); }
    public float localMaxY() { return mesh != null ? mesh.maxY : (vkLocalBounds != null ? vkLocalBounds[4] :  0.5f); }
    public float localMaxZ() { return mesh != null ? mesh.maxZ : (vkLocalBounds != null ? vkLocalBounds[5] :  0.5f); }
    /** Renderer-agnostic Skeleton accessor — GL Mesh.skeleton or Vulkan vkSkeleton. */
    public Skeleton skeleton() {
        if (mesh != null && mesh.skeleton != null) return mesh.skeleton;
        return vkSkeleton;
    }

    @Override
    public void start() {
        if (!modelPath.isEmpty()) {
            mesh = AssetPool.getMesh(modelPath);
        } else {
            // Procedural cubes use the shared unit-cube mesh so collision and
            // raycasting see real triangles (and respect the object's rotation).
            mesh = AssetPool.getMesh(World.CUBE_MESH_KEY);
        }
        // Auto-flag known foliage by filename. Models with "leaf" in their
        // path (e.g. leaf_tree_-_ps1_low_poly.glb) are alpha-cutout planes
        // whose outline should follow the leaf shape. Inspector-side override
        // can be added later.
        if (modelPath != null && modelPath.toLowerCase().contains("leaf")) {
            isFoliage = true;
        }
        // Procedural geometry (cubes, the built-in floor plane) defaults to
        // world-space (triplanar) texture tiling so that scaling tiles the
        // texture instead of stretching it. Imported models keep their UVs.
        //
        // Only set the default when the user hasn't already chosen — `null`
        // means "Inspector never touched it"; a stored TRUE/FALSE from JSON
        // is the user's explicit choice and we leave it alone.
        if (material.useWorldSpaceTiling == null) {
            boolean procedural = modelPath == null
                    || modelPath.isEmpty()
                    || World.CUBE_MESH_KEY.equals(modelPath);
            material.useWorldSpaceTiling = procedural;
        }
        if (!texturePath.isEmpty()) {
            textureID = AssetPool.getTexture(texturePath);
        }
        // prefer embedded GLB texture, then try foo.glb → foo.png. The
        // characters/ prefix on the model path doesn't apply to textures —
        // those still live under textures/ keyed by the bare file name.
        if (!modelPath.isEmpty() && "test.png".equals(texturePath)) {
            int embedded = AssetPool.getEmbeddedTexture(modelPath);
            if (embedded != 0) {
                textureID = embedded;
            } else {
                String bareName = modelPath.startsWith(Config.CHARACTER_PATH_PREFIX)
                        ? modelPath.substring(Config.CHARACTER_PATH_PREFIX.length())
                        : modelPath;
                String auto = bareName.replaceAll("\\.(glb|gltf|obj|fbx)$", ".png");
                if (Texture.exists(auto)) {
                    texturePath = auto;
                    textureID = AssetPool.getTexture(auto);
                }
            }
        }
    }

    @Override
    public void update(double dt) {
    }
}
