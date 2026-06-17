package com.myengine;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT32;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

class AnimationLoader {

    public static Animation loadAnimation(String fileName, Skeleton skel) {
        String path = Config.ANIMATIONS_PATH + fileName;
        int flags = Assimp.aiProcess_LimitBoneWeights;
        AIScene scene = Assimp.aiImportFile(path, flags);

        if (scene == null) {
            Logger.error("Animation load failed: " + path + " - " + Assimp.aiGetErrorString());
            return null;
        }
        if (scene.mNumAnimations() == 0) {
            Logger.warn("No animations in: " + fileName);
            Assimp.aiReleaseImport(scene);
            return null;
        }

        AIAnimation aiAnim = AIAnimation.create(scene.mAnimations().get(0));

        Animation anim = new Animation();
        anim.name = aiAnim.mName().dataString();
        if (anim.name == null || anim.name.isEmpty()) anim.name = fileName;
        anim.duration = aiAnim.mDuration();
        anim.ticksPerSecond = aiAnim.mTicksPerSecond() != 0 ? aiAnim.mTicksPerSecond() : 25.0;

        AINode aiRoot = scene.mRootNode();
        if (aiRoot != null) anim.rootNode = ModelLoader.copyNodeTree(aiRoot);

        int numChannels = aiAnim.mNumChannels();
        PointerBuffer channels = aiAnim.mChannels();
        int matched = 0;

        // 1. EXTRACT RAW DATA NATIVELY
        for (int i = 0; i < numChannels; i++) {
            AINodeAnim chan = AINodeAnim.create(channels.get(i));
            BoneAnim ba = new BoneAnim();
            ba.name = chan.mNodeName().dataString();

            int numPos = chan.mNumPositionKeys();
            AIVectorKey.Buffer posKeys = chan.mPositionKeys();
            for (int p = 0; p < numPos; p++) {
                AIVectorKey k = posKeys.get(p);
                AIVector3D v = k.mValue();
                ba.positions.add(new KeyPosition(new Vector3f(v.x(), v.y(), v.z()), k.mTime()));
            }

            int numRot = chan.mNumRotationKeys();
            AIQuatKey.Buffer rotKeys = chan.mRotationKeys();
            for (int r = 0; r < numRot; r++) {
                AIQuatKey k = rotKeys.get(r);
                AIQuaternion q = k.mValue();
                ba.rotations.add(new KeyRotation(new Quaternionf(q.x(), q.y(), q.z(), q.w()), k.mTime()));
            }

            int numScl = chan.mNumScalingKeys();
            AIVectorKey.Buffer sclKeys = chan.mScalingKeys();
            for (int s = 0; s < numScl; s++) {
                AIVectorKey k = sclKeys.get(s);
                AIVector3D v = k.mValue();
                ba.scales.add(new KeyScale(new Vector3f(v.x(), v.y(), v.z()), k.mTime()));
            }

            anim.boneAnimMap.put(ba.name, ba);
            if (skel != null && skel.boneInfoMap.containsKey(ba.name)) matched++;
        }

        // 2. SINGLE-BONE ANCHOR (root of the animated chain only)
        // Snaps the root animated bone's frame-0 to the model's bind pose. This
        // catches gross misalignment but leaves a small residual when rigs differ.
        // For the residual, the Animator component exposes trim sliders.
        if (skel != null) {
            BoneAnim rootBA = anim.findBone("spine");
            if (rootBA == null) rootBA = anim.findBone("mixamorig:Hips");
            if (rootBA == null) rootBA = anim.findBone("Root");

            if (rootBA != null) {
                NodeData bindNode = findNodeByName(skel.rootNode, rootBA.name);
                if (bindNode != null && !rootBA.positions.isEmpty() && !rootBA.rotations.isEmpty()) {
                    Vector3f bindPos = new Vector3f();
                    bindNode.transformation.getTranslation(bindPos);
                    Vector3f animStartPos = rootBA.positions.get(0).position;
                    Vector3f posOffset = new Vector3f(bindPos).sub(animStartPos);
                    for (KeyPosition kp : rootBA.positions) kp.position.add(posOffset);

                    Quaternionf bindRot = new Quaternionf();
                    bindNode.transformation.getNormalizedRotation(bindRot);
                    Quaternionf animStartRot = rootBA.rotations.get(0).orientation;
                    Quaternionf rotOffset = new Quaternionf(bindRot).mul(new Quaternionf(animStartRot).invert());
                    for (KeyRotation kr : rootBA.rotations) kr.orientation.premul(rotOffset);
                }
            }
        }

        Logger.info("Loaded animation: " + fileName + " — duration=" + anim.duration
                + " ticks @ " + anim.ticksPerSecond + " tps, matched=" + matched + " bones (Root Anchored)");

        Assimp.aiReleaseImport(scene);
        return anim;
    }

    private static NodeData findNodeByName(NodeData root, String name) {
        if (root == null) return null;
        if (name.equals(root.name)) return root;
        for (NodeData child : root.children) {
            NodeData found = findNodeByName(child, name);
            if (found != null) return found;
        }
        return null;
    }
}