#version 150

// Instanced version of default.vert. Each instance supplies its own model
// matrix and six per-face RGB colours as per-instance vertex attributes —
// set up in CubeBatch with glVertexAttribDivisor(loc, 1) so they advance
// per instance rather than per vertex. Thousands of cubes collapse into a
// single glDrawElementsInstanced call.

in vec3 aPos;
in vec2 aTexCoords;
in vec3 aNormal;

// Per-instance attributes (locations 6..15). Set by CubeBatch.
in vec4 instModel0;
in vec4 instModel1;
in vec4 instModel2;
in vec4 instModel3;
in vec3 instFacePosX;
in vec3 instFaceNegX;
in vec3 instFacePosY;
in vec3 instFaceNegY;
in vec3 instFacePosZ;
in vec3 instFaceNegZ;

out vec2 TexCoords;
out vec3 WorldPos;
out vec3 WorldNormal;
out vec4 LightSpacePos;
out vec3 vColorTint;

uniform mat4 viewMatrix;
uniform mat4 projMatrix;
uniform mat4 lightProjection;
uniform mat4 lightView;

void main() {
    mat4 modelMatrix = mat4(instModel0, instModel1, instModel2, instModel3);

    TexCoords = aTexCoords;
    vec4 worldPosition = modelMatrix * vec4(aPos, 1.0);
    WorldPos = worldPosition.xyz;
    WorldNormal = mat3(modelMatrix) * aNormal;
    LightSpacePos = lightProjection * lightView * worldPosition;

    // Pick the face colour from the vertex's LOCAL normal — the cube mesh
    // has one normal per face (no smoothing on the position attribute), so
    // every vertex on a given face shares the same tint. The fragment
    // interpolation across the face is a no-op (all 4 corners have the
    // same value), so no triangle blending.
    if (aNormal.x > 0.5)        vColorTint = instFacePosX;
    else if (aNormal.x < -0.5)  vColorTint = instFaceNegX;
    else if (aNormal.y > 0.5)   vColorTint = instFacePosY;
    else if (aNormal.y < -0.5)  vColorTint = instFaceNegY;
    else if (aNormal.z > 0.5)   vColorTint = instFacePosZ;
    else                         vColorTint = instFaceNegZ;

    gl_Position = projMatrix * viewMatrix * worldPosition;
}
