#version 150
in vec2 TexCoords;
in vec3 WorldPos;
in vec3 WorldNormal;
in vec4 LightSpacePos;

out vec4 FragColor;

uniform sampler2D texture_diffuse;
uniform sampler2D shadowMap;
uniform int useTexture;      // NEW: Toggle for un-textured objects!
uniform int useWorldSpaceTiling; // 1 = triplanar UVs from world pos; 0 = mesh UVs
uniform float textureScale;      // repeats per world unit when triplanar
uniform float sunIntensity;
uniform vec3 colorTint;
uniform vec3 lightPos;

float ShadowCalculation(vec4 fragPosLightSpace, vec3 lightDir, vec3 normal) {
    vec3 projCoords = fragPosLightSpace.xyz / fragPosLightSpace.w;
    projCoords = projCoords * 0.5 + 0.5;

    // NEW: Strict boundary check. If outside the shadow map, don't draw a shadow!
    if(projCoords.z > 1.0 || projCoords.x < 0.0 || projCoords.x > 1.0 || projCoords.y < 0.0 || projCoords.y > 1.0) {
        return 0.0;
    }

    float currentDepth = projCoords.z;
    float bias = max(0.001 * (1.0 - dot(normal, lightDir)), 0.0002);

    float shadow = 0.0;
    vec2 texelSize = 1.0 / textureSize(shadowMap, 0);
    for(int x = -1; x <= 1; ++x) {
        for(int y = -1; y <= 1; ++y) {
            float pcfDepth = texture(shadowMap, projCoords.xy + vec2(x, y) * texelSize).r;
            shadow += currentDepth - bias > pcfDepth ? 1.0 : 0.0;
        }
    }
    shadow /= 9.0;
    return shadow;
}

void main() {
    // NEW: Default to pure white if no texture is bound!
    vec4 texColor = vec4(1.0);
    if (useTexture == 1) {
        if (useWorldSpaceTiling == 1) {
            // Triplanar mapping: sample the texture in three planes (YZ, XZ,
            // XY) keyed off world position, then blend by the surface normal.
            // Tiles the texture in world units; scaling a cube up tiles
            // instead of stretching. Cost is 3 texture samples per fragment.
            vec3 n = abs(normalize(WorldNormal));
            n = pow(n, vec3(4.0));        // sharpen face transitions
            n = n / (n.x + n.y + n.z);    // partition of unity
            vec2 uvX = WorldPos.zy * textureScale;
            vec2 uvY = WorldPos.xz * textureScale;
            vec2 uvZ = WorldPos.xy * textureScale;
            vec4 cX = texture(texture_diffuse, uvX);
            vec4 cY = texture(texture_diffuse, uvY);
            vec4 cZ = texture(texture_diffuse, uvZ);
            texColor = cX * n.x + cY * n.y + cZ * n.z;
        } else {
            texColor = texture(texture_diffuse, TexCoords);
        }
    }

    if(texColor.a < 0.1) discard;

    vec3 norm = normalize(WorldNormal);
    vec3 lightDir = normalize(lightPos);

    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * sunIntensity * texColor.rgb;

    vec3 ambient = 0.4 * texColor.rgb;

    float shadow = ShadowCalculation(LightSpacePos, lightDir, norm);
    vec3 lighting = (ambient + (1.0 - shadow) * diffuse) * colorTint;

    FragColor = vec4(lighting, texColor.a);
}