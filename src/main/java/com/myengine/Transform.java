package com.myengine;

import org.joml.Matrix4f;
import org.joml.Vector3f;

class Transform extends Component {
    public float x, y, z;
    public float rotX, rotY, rotZ;
    public float scaleX = 1.0f, scaleY = 1.0f, scaleZ = 1.0f;

    public Transform(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void start() {
    }

    @Override
    public void update(double dt) {
    }

    /** Rotation-only matrix (R = Rx · Ry · Rz). Matches the model matrix's
     *  strict T · R · S order, so the local axes returned below line up
     *  exactly with how the mesh is oriented when it renders. */
    public Matrix4f rotationMatrix() {
        return new Matrix4f()
                .rotateX((float) Math.toRadians(rotX))
                .rotateY((float) Math.toRadians(rotY))
                .rotateZ((float) Math.toRadians(rotZ));
    }

    /** World-space unit direction of a LOCAL axis: 0 = +X (right),
     *  1 = +Y (up), 2 = +Z (forward). Used by the scale gizmo so handles and
     *  the one-sided extrude offset follow the object's rotation. */
    public float[] localAxisDir(int axis) {
        Vector3f v = new Vector3f(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
        rotationMatrix().transformDirection(v).normalize();
        return new float[]{ v.x, v.y, v.z };
    }

    public float[] getRight()   { return localAxisDir(0); }   // local +X
    public float[] getUp()      { return localAxisDir(1); }   // local +Y
    public float[] getForward() { return localAxisDir(2); }   // local +Z
}
