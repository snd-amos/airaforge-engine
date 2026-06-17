package com.myengine;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

class Animator extends Component {
    public String animationFilePath = ""; // Saved to JSON
    public double currentTime = 0.0;      // Saved to JSON
    public boolean playing = false;       // Saved to JSON

    // Per-animation trim — applied to the entire skinned mesh to compensate for
    // bind-pose drift between the model and the source animation rig.
    public float trimOffsetX = 0f, trimOffsetY = 0f, trimOffsetZ = 0f;
    public float trimTiltX = 0f, trimTiltY = 0f, trimTiltZ = 0f; // degrees

    public transient Animation currentAnimation;
    public transient Matrix4f[] finalBoneMatrices;
    public transient FloatBuffer matricesBuffer;
    public transient Matrix4f[] debugBoneTransforms;
    private transient Matrix4f scratchLocal;

    public Animator() {
        initTransientFields();
    }

    @Override
    public void start() {
        // Gson bypasses the constructor. We must ensure buffers are created when loaded!
        if (finalBoneMatrices == null) {
            initTransientFields();
        }
    }

    private void initTransientFields() {
        finalBoneMatrices = new Matrix4f[100];
        debugBoneTransforms = new Matrix4f[100];
        for (int i = 0; i < 100; i++) {
            finalBoneMatrices[i] = new Matrix4f();
            debugBoneTransforms[i] = new Matrix4f();
        }
        matricesBuffer = BufferUtils.createFloatBuffer(100 * 16);
        scratchLocal = new Matrix4f();
    }

    public void play(Animation a) {
        this.currentAnimation = a;
        this.currentTime = 0;
        this.playing = (a != null);
        recompute();
    }

    @Override
    public void update(double dt) {
        if (currentAnimation == null) return;
        if (playing) {
            currentTime += currentAnimation.ticksPerSecond * dt;
            currentTime = currentTime % currentAnimation.duration;
            if (currentTime < 0) currentTime += currentAnimation.duration;
        }
        recompute();
    }

    public void recompute() {
        if (currentAnimation == null) return;
        MeshRenderer mr = gameObject.getComponent(MeshRenderer.class);
        if (mr == null || mr.mesh == null || mr.mesh.skeleton == null || mr.mesh.skeleton.rootNode == null) return;

        Matrix4f trim = new Matrix4f()
                .translate(trimOffsetX, trimOffsetY, trimOffsetZ)
                .rotateX((float) Math.toRadians(trimTiltX))
                .rotateY((float) Math.toRadians(trimTiltY))
                .rotateZ((float) Math.toRadians(trimTiltZ));
        calcRecursive(mr.mesh.skeleton.rootNode, trim, mr.mesh.skeleton);

        matricesBuffer.clear();
        for (int i = 0; i < 100; i++) finalBoneMatrices[i].get(i * 16, matricesBuffer);
    }

    private void calcRecursive(NodeData node, Matrix4f parentTransform, Skeleton skel) {
        Matrix4f localTransform = new Matrix4f(node.transformation);
        BoneInfo info = skel.boneInfoMap.get(node.name);

        if (info != null) {
            BoneAnim ba = currentAnimation.findBone(node.name);
            if (ba != null) {
                ba.update(currentTime, scratchLocal);
                localTransform.set(scratchLocal);
            }
        }
        Matrix4f globalTransform = new Matrix4f(parentTransform).mul(localTransform);

        if (info != null && info.id < 100) {
            finalBoneMatrices[info.id].set(globalTransform).mul(info.offsetMatrix);
            debugBoneTransforms[info.id].set(globalTransform);
        }

        for (NodeData child : node.children) calcRecursive(child, globalTransform, skel);
    }
}