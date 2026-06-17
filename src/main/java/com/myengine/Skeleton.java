package com.myengine;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

class Skeleton {
    public final Map<String, BoneInfo> boneInfoMap = new HashMap<>();
    public int boneCount = 0;
    public NodeData rootNode;
    public Matrix4f globalInverseTransform = new Matrix4f();

    // Static (T-pose) green-bone debug viz. `model` is the host entity's
    // world transform — bone vertices computed in object-local space get
    // multiplied by it (via LineRenderer3D's MVP composition) to land in
    // world space.
    public void drawDebug(Matrix4f model) {
        if (rootNode == null) return;
        glDisable(GL_DEPTH_TEST);
        GLCompat.lineWidth(2.0f);

        List<Float> boneLines = new ArrayList<>();
        List<Float> nodeLines = new ArrayList<>();
        gatherStaticBones(rootNode, new Matrix4f(rootNode.transformation), boneLines, nodeLines);
        if (!boneLines.isEmpty()) LineRenderer3D.drawLines(toFloatArray(boneLines), model, 0.2f, 1.0f, 0.2f);
        if (!nodeLines.isEmpty()) LineRenderer3D.drawLines(toFloatArray(nodeLines), model, 0.2f, 0.6f, 0.8f);

        glEnable(GL_DEPTH_TEST);
        GLCompat.lineWidth(1.0f);
    }

    private void gatherStaticBones(NodeData node, Matrix4f accumulated,
                                   List<Float> boneLines, List<Float> nodeLines) {
        Vector3f parentPos = new Vector3f();
        accumulated.transformPosition(parentPos);  // start from (0,0,0)

        Vector3f childLocal = new Vector3f();
        Vector3f childPos = new Vector3f();
        for (NodeData child : node.children) {
            child.transformation.getTranslation(childLocal);
            accumulated.transformPosition(childLocal, childPos);

            List<Float> target = boneInfoMap.containsKey(child.name) ? boneLines : nodeLines;
            target.add(parentPos.x); target.add(parentPos.y); target.add(parentPos.z);
            target.add(childPos.x);  target.add(childPos.y);  target.add(childPos.z);

            Matrix4f childAccum = new Matrix4f(accumulated).mul(child.transformation);
            gatherStaticBones(child, childAccum, boneLines, nodeLines);
        }
    }

    // Animated red-bone debug viz. Positions are in object-local space
    // (computed via the bone-skinning matrices). `model` is the host
    // entity's world transform, used to lift the local-space vertices
    // into world space.
    public void drawAnimatedDebug(Matrix4f[] debugBoneTransforms, Matrix4f model) {
        if (rootNode == null) return;
        glDisable(GL_DEPTH_TEST);
        GLCompat.lineWidth(2.0f);

        List<Float> boneLines = new ArrayList<>();
        List<Float> nodeLines = new ArrayList<>();
        gatherAnimatedBones(rootNode, new Matrix4f(), debugBoneTransforms, boneLines, nodeLines);
        if (!boneLines.isEmpty()) LineRenderer3D.drawLines(toFloatArray(boneLines), model, 1.0f, 0.2f, 0.2f);
        if (!nodeLines.isEmpty()) LineRenderer3D.drawLines(toFloatArray(nodeLines), model, 0.2f, 0.6f, 0.8f);

        glEnable(GL_DEPTH_TEST);
        GLCompat.lineWidth(1.0f);
    }

    private void gatherAnimatedBones(NodeData node, Matrix4f parentGlobal,
                                     Matrix4f[] debugBoneTransforms,
                                     List<Float> boneLines, List<Float> nodeLines) {
        Matrix4f currentGlobal;
        BoneInfo info = boneInfoMap.get(node.name);
        if (info != null && info.id < 100) {
            currentGlobal = debugBoneTransforms[info.id];
        } else {
            currentGlobal = new Matrix4f(parentGlobal).mul(node.transformation);
        }

        Vector3f parentPos = new Vector3f();
        parentGlobal.getTranslation(parentPos);
        Vector3f currentPos = new Vector3f();
        currentGlobal.getTranslation(currentPos);

        List<Float> target = (info != null) ? boneLines : nodeLines;
        target.add(parentPos.x); target.add(parentPos.y); target.add(parentPos.z);
        target.add(currentPos.x); target.add(currentPos.y); target.add(currentPos.z);

        for (NodeData child : node.children) {
            gatherAnimatedBones(child, currentGlobal, debugBoneTransforms, boneLines, nodeLines);
        }
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    /** GL-free line gather for the Vulkan debug overlay. Returns
     *  {@code [boneLines, nodeLines]} as flat {@code (x0 y0 z0 x1 y1 z1)}
     *  segment arrays in object-local space (caller composes MVP).
     *  {@code debugBoneTransforms != null} → animated pose; else T-pose. */
    float[][] gatherDebugLines(Matrix4f[] debugBoneTransforms) {
        List<Float> boneLines = new ArrayList<>();
        List<Float> nodeLines = new ArrayList<>();
        if (rootNode != null) {
            if (debugBoneTransforms != null) {
                gatherAnimatedBones(rootNode, new Matrix4f(), debugBoneTransforms, boneLines, nodeLines);
            } else {
                gatherStaticBones(rootNode, new Matrix4f(rootNode.transformation), boneLines, nodeLines);
            }
        }
        return new float[][]{ toFloatArray(boneLines), toFloatArray(nodeLines) };
    }
}
