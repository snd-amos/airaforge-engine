#version 150

in vec2 TexCoords;

out vec4 FragColor;

uniform vec3 outlineColor;
uniform sampler2D texture_diffuse;
uniform int useTexture; // 1 = sample the diffuse texture for alpha-discard (foliage etc.)

void main() {
    // Foliage / decals are alpha-cutout textures rendered on flat quads.
    // The inverted-hull silhouette would otherwise fill in the transparent
    // gaps with a solid color. Sample the diffuse texture and discard the
    // same fragments the main pass would (matches default.frag's threshold).
    if (useTexture == 1) {
        float a = texture(texture_diffuse, TexCoords).a;
        if (a < 0.1) discard;
    }
    FragColor = vec4(outlineColor, 1.0);
}
