package com.myengine;

import org.joml.Matrix4f;

class BoneInfo {
    public final int id;
    public final Matrix4f offsetMatrix; // bone-local space - mesh-local space (inverse bind pose)

    public BoneInfo(int id, Matrix4f offsetMatrix) {
        this.id = id;
        this.offsetMatrix = offsetMatrix;
    }
}
