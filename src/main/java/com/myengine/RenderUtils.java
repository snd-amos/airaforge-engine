package com.myengine;

import static org.lwjgl.opengl.GL11.*;

/**
 * Utility class for common rendering operations.
 * Eliminates code duplication across the engine.
 */
public class RenderUtils {

    /**
     * Draws a unit cube (-0.5 to 0.5 on all axes).
     * Must be called within glBegin/glEnd or VAO context.
     */
    public static void drawCube() {
        glBegin(GL_QUADS);

        // Front face (Z+)
        glNormal3f(0, 0, 1);
        glTexCoord2f(0, 0); glVertex3f(-0.5f, 0.5f, 0.5f);
        glTexCoord2f(1, 0); glVertex3f(0.5f, 0.5f, 0.5f);
        glTexCoord2f(1, 1); glVertex3f(0.5f, -0.5f, 0.5f);
        glTexCoord2f(0, 1); glVertex3f(-0.5f, -0.5f, 0.5f);

        // Back face (Z-)
        glNormal3f(0, 0, -1);
        glTexCoord2f(1, 0); glVertex3f(-0.5f, 0.5f, -0.5f);
        glTexCoord2f(0, 0); glVertex3f(0.5f, 0.5f, -0.5f);
        glTexCoord2f(0, 1); glVertex3f(0.5f, -0.5f, -0.5f);
        glTexCoord2f(1, 1); glVertex3f(-0.5f, -0.5f, -0.5f);

        // Top face (Y+)
        glNormal3f(0, 1, 0);
        glTexCoord2f(0, 1); glVertex3f(-0.5f, 0.5f, -0.5f);
        glTexCoord2f(0, 0); glVertex3f(0.5f, 0.5f, -0.5f);
        glTexCoord2f(1, 0); glVertex3f(0.5f, 0.5f, 0.5f);
        glTexCoord2f(1, 1); glVertex3f(-0.5f, 0.5f, 0.5f);

        // Bottom face (Y-)
        glNormal3f(0, -1, 0);
        glTexCoord2f(1, 1); glVertex3f(-0.5f, -0.5f, -0.5f);
        glTexCoord2f(0, 1); glVertex3f(0.5f, -0.5f, -0.5f);
        glTexCoord2f(0, 0); glVertex3f(0.5f, -0.5f, 0.5f);
        glTexCoord2f(1, 0); glVertex3f(-0.5f, -0.5f, 0.5f);

        // Right face (X+)
        glNormal3f(1, 0, 0);
        glTexCoord2f(1, 0); glVertex3f(0.5f, 0.5f, -0.5f);
        glTexCoord2f(1, 1); glVertex3f(0.5f, 0.5f, 0.5f);
        glTexCoord2f(0, 1); glVertex3f(0.5f, -0.5f, 0.5f);
        glTexCoord2f(0, 0); glVertex3f(0.5f, -0.5f, -0.5f);

        // Left face (X-)
        glNormal3f(-1, 0, 0);
        glTexCoord2f(0, 0); glVertex3f(-0.5f, 0.5f, -0.5f);
        glTexCoord2f(1, 0); glVertex3f(-0.5f, 0.5f, 0.5f);
        glTexCoord2f(1, 1); glVertex3f(-0.5f, -0.5f, 0.5f);
        glTexCoord2f(0, 1); glVertex3f(-0.5f, -0.5f, -0.5f);

        glEnd();
    }

    /**
     * Draws a wireframe AABB (Axis-Aligned Bounding Box).
     */
    public static void drawWireframeAABB(float minX, float minY, float minZ,
                                         float maxX, float maxY, float maxZ) {
        glBegin(GL_LINES);

        // Bottom face
        glVertex3f(minX, minY, minZ); glVertex3f(maxX, minY, minZ);
        glVertex3f(maxX, minY, minZ); glVertex3f(maxX, minY, maxZ);
        glVertex3f(maxX, minY, maxZ); glVertex3f(minX, minY, maxZ);
        glVertex3f(minX, minY, maxZ); glVertex3f(minX, minY, minZ);

        // Top face
        glVertex3f(minX, maxY, minZ); glVertex3f(maxX, maxY, minZ);
        glVertex3f(maxX, maxY, minZ); glVertex3f(maxX, maxY, maxZ);
        glVertex3f(maxX, maxY, maxZ); glVertex3f(minX, maxY, maxZ);
        glVertex3f(minX, maxY, maxZ); glVertex3f(minX, maxY, minZ);

        // Vertical edges
        glVertex3f(minX, minY, minZ); glVertex3f(minX, maxY, minZ);
        glVertex3f(maxX, minY, minZ); glVertex3f(maxX, maxY, minZ);
        glVertex3f(maxX, minY, maxZ); glVertex3f(maxX, maxY, maxZ);
        glVertex3f(minX, minY, maxZ); glVertex3f(minX, maxY, maxZ);

        glEnd();
    }
}