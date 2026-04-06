package com.myengine;

/**
 * Material properties for rendering.
 * Currently stores basic color tint.
 */
public class Material {
    public float r = 1.0f;
    public float g = 1.0f;
    public float b = 1.0f;

    /**
     * Set the color tint of this material.
     */
    public void setColor(float r, float g, float b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    /**
     * Get the RGB values as an array.
     */
    public float[] getColor() {
        return new float[]{r, g, b};
    }
}