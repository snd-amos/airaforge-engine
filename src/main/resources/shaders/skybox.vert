#version 150

// Skybox vertex shader — the cube's local vertex position doubles as the
// cubemap sample direction (cubemap sampling only cares about direction,
// not magnitude, so a ±0.5 vertex and a ±50 scaled vertex sample the same
// texel). The caller renders the cube with the modelview containing only
// the camera's rotation (no translation), so the cube is always centred at
// the camera and the sky appears infinitely far away.

in vec3 aPos;

out vec3 vDir;

uniform mat4 mvp;

void main() {
    vDir = aPos;
    gl_Position = mvp * vec4(aPos, 1.0);
}
