package com.myengine;

import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

class RenderUtils {

    private static Mesh cubeMesh = null;

    public static void drawCube() {
        if (cubeMesh == null) {
            float[] pos = {
                    -0.5f,  0.5f,  0.5f,   0.5f,  0.5f,  0.5f,   0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,
                    -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f, -0.5f, -0.5f,  -0.5f, -0.5f, -0.5f,
                    -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
                    -0.5f, -0.5f, -0.5f,   0.5f, -0.5f, -0.5f,   0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,
                    0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,   0.5f, -0.5f,  0.5f,   0.5f, -0.5f, -0.5f,
                    -0.5f,  0.5f, -0.5f,  -0.5f,  0.5f,  0.5f,  -0.5f, -0.5f,  0.5f,  -0.5f, -0.5f, -0.5f
            };
            float[] tex = {
                    0,1, 1,1, 1,0, 0,0,  1,1, 0,1, 0,0, 1,0,  0,1, 1,1, 1,0, 0,0,
                    1,1, 0,1, 0,0, 1,0,  1,1, 0,1, 0,0, 1,0,  0,1, 1,1, 1,0, 0,0
            };
            float[] norm = {
                    0,0,1, 0,0,1, 0,0,1, 0,0,1,  0,0,-1, 0,0,-1, 0,0,-1, 0,0,-1,
                    0,1,0, 0,1,0, 0,1,0, 0,1,0,  0,-1,0, 0,-1,0, 0,-1,0, 0,-1,0,
                    1,0,0, 1,0,0, 1,0,0, 1,0,0,  -1,0,0, -1,0,0, -1,0,0, -1,0,0
            };
            int[] ind = {
                    0, 1, 2,  0, 2, 3,   4, 5, 6,  4, 6, 7,   8, 9,10,  8,10,11,
                    12,13,14, 12,14,15,  16,17,18, 16,18,19,  20,21,22, 20,22,23
            };
            cubeMesh = new Mesh(pos, tex, norm, ind);
        }
        cubeMesh.render();
    }

    // 12 edges of an axis-aligned bounding box, drawn in the local space
    // of `model`. `model` is the entity's world transform (or identity if
    // the AABB is already in world space).
    public static void drawWireframeAABB(float minX, float minY, float minZ,
                                         float maxX, float maxY, float maxZ,
                                         Matrix4f model,
                                         float r, float g, float b) {
        float[] v = {
                // bottom ring
                minX, minY, minZ,  maxX, minY, minZ,
                maxX, minY, minZ,  maxX, minY, maxZ,
                maxX, minY, maxZ,  minX, minY, maxZ,
                minX, minY, maxZ,  minX, minY, minZ,
                // top ring
                minX, maxY, minZ,  maxX, maxY, minZ,
                maxX, maxY, minZ,  maxX, maxY, maxZ,
                maxX, maxY, maxZ,  minX, maxY, maxZ,
                minX, maxY, maxZ,  minX, maxY, minZ,
                // vertical pillars
                minX, minY, minZ,  minX, maxY, minZ,
                maxX, minY, minZ,  maxX, maxY, minZ,
                maxX, minY, maxZ,  maxX, maxY, maxZ,
                minX, minY, maxZ,  minX, maxY, maxZ
        };
        LineRenderer3D.drawLines(v, model, r, g, b);
    }

    // Draws a vertical capsule wireframe (Unity-style): horizontal ring at
    // top and bottom of the cylindrical part, two vertical great circles
    // outlining the hemispherical caps, and 4 vertical seam lines.
    // Vertices are in `model`'s local space; the (cx,cy,cz) center is in
    // that same local space.
    public static void drawWireframeCapsule(float cx, float cy, float cz,
                                            float height, float radius,
                                            Matrix4f model,
                                            float r, float g, float b) {
        int segs = 24;
        float halfCyl = Math.max(0, height * 0.5f - radius);
        float topY = cy + halfCyl;
        float botY = cy - halfCyl;

        // 4 contributions: top ring (segs lines), bottom ring (segs lines),
        // 2 vertical great circles (segs lines each), 4 seam lines.
        int lineCount = segs * 4 + 4;
        float[] v = new float[lineCount * 6];
        int idx = 0;

        // Top horizontal ring
        for (int i = 0; i < segs; i++) {
            float a0 = (float) (2 * Math.PI * i / segs);
            float a1 = (float) (2 * Math.PI * (i + 1) / segs);
            v[idx++] = cx + (float) Math.cos(a0) * radius;
            v[idx++] = topY;
            v[idx++] = cz + (float) Math.sin(a0) * radius;
            v[idx++] = cx + (float) Math.cos(a1) * radius;
            v[idx++] = topY;
            v[idx++] = cz + (float) Math.sin(a1) * radius;
        }
        // Bottom horizontal ring
        for (int i = 0; i < segs; i++) {
            float a0 = (float) (2 * Math.PI * i / segs);
            float a1 = (float) (2 * Math.PI * (i + 1) / segs);
            v[idx++] = cx + (float) Math.cos(a0) * radius;
            v[idx++] = botY;
            v[idx++] = cz + (float) Math.sin(a0) * radius;
            v[idx++] = cx + (float) Math.cos(a1) * radius;
            v[idx++] = botY;
            v[idx++] = cz + (float) Math.sin(a1) * radius;
        }
        // Vertical great-circles (XY plane and ZY plane)
        for (int plane = 0; plane < 2; plane++) {
            for (int i = 0; i < segs; i++) {
                float a0 = (float) (2 * Math.PI * i / segs);
                float a1 = (float) (2 * Math.PI * (i + 1) / segs);
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
                float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                float y0 = s0 * radius;
                float y1 = s1 * radius;
                float yOff0 = (y0 > 0) ? halfCyl : -halfCyl;
                float yOff1 = (y1 > 0) ? halfCyl : -halfCyl;
                if (plane == 0) {
                    v[idx++] = cx + c0 * radius; v[idx++] = cy + y0 + yOff0; v[idx++] = cz;
                    v[idx++] = cx + c1 * radius; v[idx++] = cy + y1 + yOff1; v[idx++] = cz;
                } else {
                    v[idx++] = cx; v[idx++] = cy + y0 + yOff0; v[idx++] = cz + c0 * radius;
                    v[idx++] = cx; v[idx++] = cy + y1 + yOff1; v[idx++] = cz + c1 * radius;
                }
            }
        }
        // 4 vertical seam lines connecting top and bottom rings
        for (int i = 0; i < 4; i++) {
            float a = (float) (Math.PI * 0.5 * i);
            float dx = (float) Math.cos(a) * radius;
            float dz = (float) Math.sin(a) * radius;
            v[idx++] = cx + dx; v[idx++] = botY; v[idx++] = cz + dz;
            v[idx++] = cx + dx; v[idx++] = topY; v[idx++] = cz + dz;
        }

        LineRenderer3D.drawLines(v, model, r, g, b);
    }
}
