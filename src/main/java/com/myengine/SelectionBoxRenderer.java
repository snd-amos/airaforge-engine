package com.myengine;

import static org.lwjgl.opengl.GL11.*;

/**
 * Handles rendering of selection boxes and gizmos.
 * Extracted from LevelEditorScene for better separation of concerns.
 */
public class SelectionBoxRenderer {

    /**
     * Draw a selection box around a GameObject.
     */
    public static void drawSelectionBox(GameObject object, boolean isSelected,
                                        int draggingAxis, boolean isHoveringX,
                                        boolean isHoveringY, boolean isHoveringZ,
                                        boolean isHoveringCenter) {
        MeshRenderer mr = object.getComponent(MeshRenderer.class);

        glPushAttrib(GL_ENABLE_BIT | GL_CURRENT_BIT | GL_LINE_BIT);
        glPushMatrix();

        glTranslatef(object.transform.x, object.transform.y, object.transform.z);
        if (object.transform != null) {
            glRotatef(object.transform.rotX, 1, 0, 0);
            glRotatef(object.transform.rotY, 0, 1, 0);
            glRotatef(object.transform.rotZ, 0, 0, 1);
        }

        // Draw gizmo if selected
        if (isSelected) {
            Gizmo.render(object, false, draggingAxis, isHoveringX, isHoveringY,
                    isHoveringZ, isHoveringCenter);
        }

        if (object.transform != null) {
            glScalef(object.transform.scaleX, object.transform.scaleY, object.transform.scaleZ);
        }

        glDisable(GL_LIGHTING);
        glDisable(GL_TEXTURE_2D);
        glLineWidth(isSelected ? 3.0f : 2.0f);
        glColor3f(isSelected ? 1.0f : 0.0f, isSelected ? 0.5f : 0.0f, 0.0f);

        float mx = (mr != null && mr.mesh != null) ? mr.mesh.minX - 0.005f : -0.505f;
        float my = (mr != null && mr.mesh != null) ? mr.mesh.minY - 0.005f : -0.505f;
        float mz = (mr != null && mr.mesh != null) ? mr.mesh.minZ - 0.005f : -0.505f;
        float xx = (mr != null && mr.mesh != null) ? mr.mesh.maxX + 0.005f : 0.505f;
        float xy = (mr != null && mr.mesh != null) ? mr.mesh.maxY + 0.005f : 0.505f;
        float xz = (mr != null && mr.mesh != null) ? mr.mesh.maxZ + 0.005f : 0.505f;

        RenderUtils.drawWireframeAABB(mx, my, mz, xx, xy, xz);

        glPopMatrix();
        glPopAttrib();
    }
}