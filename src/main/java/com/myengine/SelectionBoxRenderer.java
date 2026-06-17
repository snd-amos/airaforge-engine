package com.myengine;

import org.joml.Matrix4f;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;

class SelectionBoxRenderer {

    private static Shader outlineShader;
    private static FloatBuffer identityBoneMatrices;

    // Scratch matrices reused across calls (single-threaded engine).
    private static final Matrix4f modelMat = new Matrix4f();
    private static final FloatBuffer modelBuf =
            org.lwjgl.BufferUtils.createFloatBuffer(16);

    public static void configureOutline(Shader shader, FloatBuffer identityBones) {
        outlineShader = shader;
        identityBoneMatrices = identityBones;
    }

    public static void drawSelectionBox(GameObject object, boolean isSelected,
                                        int draggingAxis, boolean isHoveringX,
                                        boolean isHoveringY, boolean isHoveringZ,
                                        boolean isHoveringCenter) {
        if (isSelected) {
            drawGizmoOnly(object, draggingAxis, isHoveringX, isHoveringY, isHoveringZ, isHoveringCenter);
        }
        drawWireframeOnly(object, isSelected);
    }

    public static void drawGizmoOnly(GameObject object, int draggingAxis,
                                     boolean isHoveringX, boolean isHoveringY,
                                     boolean isHoveringZ, boolean isHoveringCenter) {
        // Gizmo builds its own world-axis-aligned model matrix internally
        // (translation only — keeps it consistent across multi-select).
        Gizmo.render(object, false, draggingAxis, isHoveringX, isHoveringY,
                isHoveringZ, isHoveringCenter);
    }

    public static void drawWireframeOnly(GameObject object, boolean isSelected) {
        MeshRenderer mr = object.getComponent(MeshRenderer.class);
        boolean hasMesh = mr != null && mr.mesh != null;

        boolean isMarker = mr != null && (
                World.SPAWN_POINT_KEY.equals(mr.modelPath) ||
                World.WIN_ZONE_KEY.equals(mr.modelPath));
        boolean isThin = hasMesh && (
                (mr.mesh.maxX - mr.mesh.minX) < 0.001f ||
                (mr.mesh.maxY - mr.mesh.minY) < 0.001f ||
                (mr.mesh.maxZ - mr.mesh.minZ) < 0.001f);
        boolean isCharacter = mr != null && mr.isCharacter() && hasMesh;
        boolean drawCapsule = isCharacter && isSelected;
        boolean useAabb = !hasMesh || outlineShader == null || isMarker || isThin;

        GLCompat.lineWidth(isSelected ? 2.5f : 1.5f);

        float r = isSelected ? 1.0f : 0.0f;
        float g = isSelected ? 0.5f : 0.0f;
        float b = 0.0f;

        if (drawCapsule) {
            // Capsules stay vertical regardless of mesh rotation, so the
            // capsule's model matrix is translation only.
            glDisable(GL_DEPTH_TEST);
            Matrix4f capsuleModel = modelMat.identity().translate(
                    object.transform.x, object.transform.y, object.transform.z);
            float height = mr.mesh.maxY - mr.mesh.minY;
            float dx = mr.mesh.maxX - mr.mesh.minX;
            float dz = mr.mesh.maxZ - mr.mesh.minZ;
            float radius = Math.min(dx, dz) * 0.5f;
            float cy = (mr.mesh.minY + mr.mesh.maxY) * 0.5f;
            RenderUtils.drawWireframeCapsule(0, cy, 0, height, radius, capsuleModel, r, g, b);
            glEnable(GL_DEPTH_TEST);
        } else if (!useAabb) {
            drawMeshOutline(object, mr, r, g, b);
        } else {
            float baseMinX = hasMesh ? mr.mesh.minX : -0.5f;
            float baseMinY = hasMesh ? mr.mesh.minY : -0.5f;
            float baseMinZ = hasMesh ? mr.mesh.minZ : -0.5f;
            float baseMaxX = hasMesh ? mr.mesh.maxX : 0.5f;
            float baseMaxY = hasMesh ? mr.mesh.maxY : 0.5f;
            float baseMaxZ = hasMesh ? mr.mesh.maxZ : 0.5f;

            boolean thinX = (baseMaxX - baseMinX) < 0.001f;
            boolean thinY = (baseMaxY - baseMinY) < 0.001f;
            boolean thinZ = (baseMaxZ - baseMinZ) < 0.001f;
            float padX = (thinX ? 0.05f : 0.001f) / Math.max(Math.abs(object.transform.scaleX), 1e-4f);
            float padY = (thinY ? 0.05f : 0.001f) / Math.max(Math.abs(object.transform.scaleY), 1e-4f);
            float padZ = (thinZ ? 0.05f : 0.001f) / Math.max(Math.abs(object.transform.scaleZ), 1e-4f);

            // AABB model = full entity transform (translate, rotate, scale).
            RenderState.modelMatrix(object.transform, modelMat);
            RenderUtils.drawWireframeAABB(
                    baseMinX - padX, baseMinY - padY, baseMinZ - padZ,
                    baseMaxX + padX, baseMaxY + padY, baseMaxZ + padZ,
                    modelMat, r, g, b);
        }
    }

    public static boolean usesStencilSilhouette(GameObject object, boolean isSelected) {
        if (outlineShader == null) return false;
        MeshRenderer mr = object.getComponent(MeshRenderer.class);
        if (mr == null || mr.mesh == null) return false;
        if (World.SPAWN_POINT_KEY.equals(mr.modelPath) ||
            World.WIN_ZONE_KEY.equals(mr.modelPath)) return false;
        if ((mr.mesh.maxX - mr.mesh.minX) < 0.001f ||
            (mr.mesh.maxY - mr.mesh.minY) < 0.001f ||
            (mr.mesh.maxZ - mr.mesh.minZ) < 0.001f) return false;
        if (mr.isCharacter() && isSelected) return false;
        return true;
    }

    // Pass A — mark the object's silhouette in stencil. Model matrix and
    // camera matrices are uploaded as explicit uniforms (no more matrix
    // stack). Depth test off so the mark is precision-immune.
    public static void drawSilhouetteMark(GameObject object) {
        MeshRenderer mr = object.getComponent(MeshRenderer.class);
        if (mr == null || mr.mesh == null || outlineShader == null) return;

        RenderState.modelMatrix(object.transform, modelMat);
        modelBuf.clear();
        modelMat.get(modelBuf);

        outlineShader.use();
        outlineShader.setMat4("modelMatrix", modelBuf);
        outlineShader.setMat4("viewMatrix", RenderState.viewBuf);
        outlineShader.setMat4("projMatrix", RenderState.projBuf);
        outlineShader.setFloat("outlineThickness", 0.0f);

        boolean skinned = mr.mesh.isSkinned;
        outlineShader.setInt("isSkinned", skinned ? 1 : 0);
        if (skinned) {
            Animator anim = object.getComponent(Animator.class);
            FloatBuffer bones = (anim != null && anim.currentAnimation != null)
                    ? anim.matricesBuffer
                    : identityBoneMatrices;
            outlineShader.setMat4Array("finalBonesMatrices", bones);
        }
        boolean useDiffuseAlpha = mr.isFoliage && mr.textureID > 0;
        outlineShader.setInt("useTexture", useDiffuseAlpha ? 1 : 0);
        if (useDiffuseAlpha) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, mr.textureID);
            outlineShader.setInt("texture_diffuse", 0);
        }

        glEnable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);
        glStencilFunc(GL_ALWAYS, 1, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        glStencilMask(0xFF);
        glColorMask(false, false, false, false);
        glDepthMask(false);

        mr.mesh.render();

        glColorMask(true, true, true, true);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_STENCIL_TEST);
        outlineShader.detach();
    }

    private static void drawMeshOutline(GameObject object, MeshRenderer mr,
                                        float r, float g, float bCol) {
        RenderState.modelMatrix(object.transform, modelMat);
        modelBuf.clear();
        modelMat.get(modelBuf);

        outlineShader.use();
        outlineShader.setMat4("modelMatrix", modelBuf);
        outlineShader.setMat4("viewMatrix", RenderState.viewBuf);
        outlineShader.setMat4("projMatrix", RenderState.projBuf);
        outlineShader.setVec3("outlineColor", r, g, bCol);
        outlineShader.setFloat("outlineThickness", 0.003f);

        boolean useDiffuseAlpha = mr.isFoliage && mr.textureID > 0;
        outlineShader.setInt("useTexture", useDiffuseAlpha ? 1 : 0);
        if (useDiffuseAlpha) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, mr.textureID);
            outlineShader.setInt("texture_diffuse", 0);
        }

        boolean skinned = mr.mesh.isSkinned;
        outlineShader.setInt("isSkinned", skinned ? 1 : 0);
        if (skinned) {
            Animator anim = object.getComponent(Animator.class);
            FloatBuffer bones = (anim != null && anim.currentAnimation != null)
                    ? anim.matricesBuffer
                    : identityBoneMatrices;
            outlineShader.setMat4Array("finalBonesMatrices", bones);
        }

        glEnable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);
        glStencilFunc(GL_NOTEQUAL, 1, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glStencilMask(0x00);

        mr.mesh.render();

        glEnable(GL_DEPTH_TEST);
        glDisable(GL_STENCIL_TEST);
        // Restore stencil write mask so next frame's glClear actually clears.
        glStencilMask(0xFF);
        outlineShader.detach();
    }
}
