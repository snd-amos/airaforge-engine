package com.myengine;

import org.joml.Matrix4f;

import java.util.*;

class NodeData {
    public final String name;
    public final Matrix4f transformation; // local transform (relative to parent)
    public final List<NodeData> children = new ArrayList<>();

    public NodeData(String name, Matrix4f transformation) {
        this.name = name;
        this.transformation = transformation;
    }
}
