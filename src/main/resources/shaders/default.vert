#version 150

#define MAX_BONES 100
#define MAX_BONE_INFLUENCE 4

in vec3 aPos;
in vec2 aTexCoords;
in vec3 aNormal;
in ivec4 aBoneIds;
in vec4 aWeights;

out vec2 TexCoords;
out vec3 WorldPos;
out vec3 WorldNormal;
out vec4 LightSpacePos;

uniform mat4 modelMatrix;
uniform mat4 viewMatrix;
uniform mat4 projMatrix;
uniform mat4 lightProjection;
uniform mat4 lightView;
uniform int isSkinned;
uniform mat4 finalBonesMatrices[MAX_BONES];

void main() {
    TexCoords = aTexCoords;

    // Compute skinned position (or pass-through for static meshes)
    vec4 totalPosition = vec4(0.0);
    vec3 totalNormal = vec3(0.0);

    if (isSkinned == 1) {
        for (int i = 0; i < MAX_BONE_INFLUENCE; i++) {
            int bId = aBoneIds[i];
            if (bId == -1) continue;
            if (bId >= MAX_BONES) {
                totalPosition = vec4(aPos, 1.0);
                totalNormal = aNormal;
                break;
            }
            mat4 boneMat = finalBonesMatrices[bId];
            totalPosition += (boneMat * vec4(aPos, 1.0)) * aWeights[i];
            totalNormal  += (mat3(boneMat) * aNormal) * aWeights[i];
        }
    } else {
        totalPosition = vec4(aPos, 1.0);
        totalNormal = aNormal;
    }

    // World-space position is used for shadow projection lookup and lighting
    vec4 worldPosition = modelMatrix * totalPosition;
    WorldPos = worldPosition.xyz;
    WorldNormal = mat3(modelMatrix) * totalNormal;

    LightSpacePos = lightProjection * lightView * worldPosition;

    gl_Position = projMatrix * viewMatrix * worldPosition;
}
