#version 150

#define MAX_BONES 100
#define MAX_BONE_INFLUENCE 4

in vec3 aPos;
in vec2 aTexCoords;
in vec3 aNormal;
in vec3 aSmoothNormal;
in ivec4 aBoneIds;
in vec4 aWeights;

out vec2 TexCoords;

uniform mat4 modelMatrix;
uniform mat4 viewMatrix;
uniform mat4 projMatrix;
uniform int isSkinned;
uniform mat4 finalBonesMatrices[MAX_BONES];
uniform float outlineThickness;

void main() {
    TexCoords = aTexCoords;

    vec4 totalPosition = vec4(0.0);
    vec3 totalNormal = vec3(0.0);

    if (isSkinned == 1) {
        for (int i = 0; i < MAX_BONE_INFLUENCE; i++) {
            int bId = aBoneIds[i];
            if (bId == -1) continue;
            if (bId >= MAX_BONES) {
                totalPosition = vec4(aPos, 1.0);
                totalNormal = aSmoothNormal;
                break;
            }
            mat4 bm = finalBonesMatrices[bId];
            totalPosition += (bm * vec4(aPos, 1.0)) * aWeights[i];
            totalNormal  += (mat3(bm) * aSmoothNormal) * aWeights[i];
        }
    } else {
        totalPosition = vec4(aPos, 1.0);
        totalNormal = aSmoothNormal;
    }

    mat4 mvp = projMatrix * viewMatrix * modelMatrix;
    gl_Position = mvp * totalPosition;

    // Screen-space outline: push the vertex AWAY from the mesh's screen-space
    // centre by a constant NDC amount. View-direction-independent so the halo
    // extends in every direction at every camera angle.
    //
    // The previous smooth-normal-projection technique collapsed to zero when
    // a vertex's normal pointed along the view direction (cubes lost their
    // top/bottom edges at certain pitches, foliage lost outlines when faced
    // head-on). The radial direction from the projected mesh centre never
    // collapses, so the halo width is consistent everywhere.
    //
    // Assumes the mesh's local origin is roughly its visual centre. True for
    // all the engine's built-ins (cube, quad, plane) and the imported models
    // shipped so far; an offset origin would slightly skew the halo but never
    // make it disappear.
    vec4 clipCenter = mvp * vec4(0.0, 0.0, 0.0, 1.0);
    vec2 ndcDir = gl_Position.xy / gl_Position.w - clipCenter.xy / clipCenter.w;
    float ndcLen = length(ndcDir);
    if (ndcLen > 1e-6) {
        gl_Position.xy += (ndcDir / ndcLen) * outlineThickness * gl_Position.w;
    }
}
