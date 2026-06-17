package com.myengine;

import org.joml.Quaternionf;
import org.joml.Vector3f;

class KeyPosition {
    public Vector3f position;
    public double timeStamp;
    public KeyPosition(Vector3f p, double t) { position = p; timeStamp = t; }
}

class KeyRotation {
    public Quaternionf orientation;
    public double timeStamp;
    public KeyRotation(Quaternionf q, double t) { orientation = q; timeStamp = t; }
}

class KeyScale {
    public Vector3f scale;
    public double timeStamp;
    public KeyScale(Vector3f s, double t) { scale = s; timeStamp = t; }
}
