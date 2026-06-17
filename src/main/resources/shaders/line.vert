#version 150

// Generic 3D line shader. Colour comes from a uniform (one per draw call)
// so the vertex buffer holds only positions. Transform comes from an
// explicit `mvp` uniform — no more gl_ModelViewProjectionMatrix, so this
// shader works in Core profile.

in vec3 aPos;

uniform mat4 mvp;

void main() {
    gl_Position = mvp * vec4(aPos, 1.0);
}
