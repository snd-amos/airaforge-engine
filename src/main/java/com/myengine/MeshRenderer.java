package com.myengine;

/**
 * Component for rendering 3D models with textures and materials.
 * Holds references to mesh geometry, textures, and material properties.
 */
public class MeshRenderer extends Component {
    public String texturePath = "pwood.jpg";
    public transient int textureID = 0;

    public String modelPath = "";
    public transient Mesh mesh = null;

    public Material material = new Material();

    /**
     * Called when the component is added to a GameObject.
     * Automatically loads assets from the asset pool.
     */
    @Override
    public void start() {
        if (!modelPath.isEmpty()) {
            mesh = AssetPool.getMesh(modelPath);
        }
        if (!texturePath.isEmpty()) {
            textureID = AssetPool.getTexture(texturePath);
        }
    }

    /**
     * Update the renderer (called every frame).
     * Currently used for potential animation updates.
     */
    @Override
    public void update(double dt) {
        // Render updates go here if we ever add animations!
    }
}