#version 150

in vec2 TexCoords;

uniform sampler2D texture_diffuse;
uniform int useTexture; // 1 = alpha-discard the same way the main pass does (foliage)

void main() {
    // For alpha-cutout foliage we need the shadow map to record the *visible*
    // shape of the leaves, not the rectangular bounds of the quad they're
    // drawn on — otherwise the tree casts polygon-shaped shadows. Matches
    // default.frag's 0.1 threshold so shadow and silhouette agree.
    if (useTexture == 1) {
        float a = texture(texture_diffuse, TexCoords).a;
        if (a < 0.1) discard;
    }
    // Depth is written implicitly.
}
