#version 150

in vec2 TexCoords;
in vec3 WorldPos;
in vec3 WorldNormal;
in vec4 LightSpacePos;
in vec3 vColorTint;

out vec4 FragColor;

uniform sampler2D texture_diffuse;
uniform sampler2D shadowMap;
uniform int useTexture;
uniform int useWorldSpaceTiling;
uniform float textureScale;
uniform float sunIntensity;
uniform vec3 lightPos;

float ShadowCalculation(vec4 fragPosLightSpace, vec3 lightDir, vec3 normal) {
    vec3 projCoords = fragPosLightSpace.xyz / fragPosLightSpace.w;
    projCoords = projCoords * 0.5 + 0.5;
    if (projCoords.z > 1.0 || projCoords.x < 0.0 || projCoords.x > 1.0 ||
        projCoords.y < 0.0 || projCoords.y > 1.0) return 0.0;
    float currentDepth = projCoords.z;
    float bias = max(0.001 * (1.0 - dot(normal, lightDir)), 0.0002);
    float shadow = 0.0;
    vec2 texelSize = 1.0 / textureSize(shadowMap, 0);
    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            float pcfDepth = texture(shadowMap, projCoords.xy + vec2(x, y) * texelSize).r;
            shadow += currentDepth - bias > pcfDepth ? 1.0 : 0.0;
        }
    }
    shadow /= 9.0;
    return shadow;
}

void main() {
    vec4 texColor = vec4(1.0);
    if (useTexture == 1) {
        if (useWorldSpaceTiling == 1) {
            vec3 n = abs(normalize(WorldNormal));
            n = pow(n, vec3(4.0));
            n = n / (n.x + n.y + n.z);
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
    if (texColor.a < 0.1) discard;

    vec3 norm = normalize(WorldNormal);
    vec3 lightDir = normalize(lightPos);
    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * sunIntensity * texColor.rgb;
    vec3 ambient = 0.4 * texColor.rgb;
    float shadow = ShadowCalculation(LightSpacePos, lightDir, norm);
    vec3 lighting = (ambient + (1.0 - shadow) * diffuse) * vColorTint;

    FragColor = vec4(lighting, texColor.a);
}
