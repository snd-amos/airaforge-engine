#version 150

#define MAX_BONES 100
#define MAX_BONE_INFLUENCE 4

in vec3 aPos;
in vec2 aTexCoords;
in ivec4 aBoneIds;
in vec4 aWeights;

out vec2 TexCoords;

uniform mat4 modelMatrix;
uniform mat4 viewMatrix;
uniform mat4 projMatrix;
uniform int isSkinned;
uniform mat4 finalBonesMatrices[MAX_BONES];

void main() {
    TexCoords = aTexCoords;
    vec4 totalPosition = vec4(0.0);

    if (isSkinned == 1) {
        for (int i = 0; i < MAX_BONE_INFLUENCE; i++) {
            int bId = aBoneIds[i];
            if (bId == -1) continue;
            if (bId >= MAX_BONES) {
                totalPosition = vec4(aPos, 1.0);
                break;
            }
            totalPosition += (finalBonesMatrices[bId] * vec4(aPos, 1.0)) * aWeights[i];
        }
    } else {
        totalPosition = vec4(aPos, 1.0);
    }

    gl_Position = projMatrix * viewMatrix * modelMatrix * totalPosition;
}
