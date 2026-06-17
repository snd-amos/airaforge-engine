package com.myengine;

import org.lwjgl.assimp.*;

import java.util.*;

class Animation {
    public String name;
    public double duration;        // in ticks
    public double ticksPerSecond;  // 25 if Assimp reports 0
    public final Map<String, BoneAnim> boneAnimMap = new HashMap<>();
    public NodeData rootNode;      // animation own copy of the node hierarchy

    public BoneAnim findBone(String n) { return boneAnimMap.get(n); }
}
