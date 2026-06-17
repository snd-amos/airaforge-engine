package com.myengine;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

class BoneAnim {
    public String name;
    public final List<KeyPosition> positions = new ArrayList<>();
    public final List<KeyRotation> rotations = new ArrayList<>();
    public final List<KeyScale>    scales    = new ArrayList<>();

    /** Writes the animated local transform at `time` into `outLocal`. */
    public void update(double time, Matrix4f outLocal) {
        Vector3f t = interpolatePosition(time);
        Quaternionf r = interpolateRotation(time);
        Vector3f s = interpolateScale(time);
        outLocal.identity().translationRotateScale(t.x, t.y, t.z, r.x, r.y, r.z, r.w, s.x, s.y, s.z);
    }

    private Vector3f interpolatePosition(double time) {
        if (positions.size() == 1) return new Vector3f(positions.get(0).position);
        int i = findPosIndex(time);
        if (i >= positions.size() - 1) return new Vector3f(positions.get(positions.size() - 1).position);
        double t0 = positions.get(i).timeStamp, t1 = positions.get(i + 1).timeStamp;
        float a = (float) ((time - t0) / (t1 - t0));
        return new Vector3f(positions.get(i).position).lerp(positions.get(i + 1).position, a);
    }

    private Quaternionf interpolateRotation(double time) {
        if (rotations.size() == 1) return new Quaternionf(rotations.get(0).orientation);
        int i = findRotIndex(time);
        if (i >= rotations.size() - 1) return new Quaternionf(rotations.get(rotations.size() - 1).orientation);
        double t0 = rotations.get(i).timeStamp, t1 = rotations.get(i + 1).timeStamp;
        float a = (float) ((time - t0) / (t1 - t0));
        return new Quaternionf(rotations.get(i).orientation).slerp(rotations.get(i + 1).orientation, a).normalize();
    }

    private Vector3f interpolateScale(double time) {
        if (scales.size() == 1) return new Vector3f(scales.get(0).scale);
        int i = findScaleIndex(time);
        if (i >= scales.size() - 1) return new Vector3f(scales.get(scales.size() - 1).scale);
        double t0 = scales.get(i).timeStamp, t1 = scales.get(i + 1).timeStamp;
        float a = (float) ((time - t0) / (t1 - t0));
        return new Vector3f(scales.get(i).scale).lerp(scales.get(i + 1).scale, a);
    }

    private int findPosIndex(double t)   { for (int i = 0; i < positions.size() - 1; i++) if (t < positions.get(i+1).timeStamp) return i; return positions.size() - 1; }
    private int findRotIndex(double t)   { for (int i = 0; i < rotations.size() - 1; i++) if (t < rotations.get(i+1).timeStamp) return i; return rotations.size() - 1; }
    private int findScaleIndex(double t) { for (int i = 0; i < scales.size()    - 1; i++) if (t < scales.get(i+1).timeStamp)    return i; return scales.size()    - 1; }
}
