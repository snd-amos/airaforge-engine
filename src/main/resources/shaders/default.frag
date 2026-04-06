#version 130
in vec2 TexCoords;
in vec3 Normal;
in vec3 FragPos;

out vec4 FragColor;

uniform sampler2D texture_diffuse;
uniform float sunIntensity;
uniform vec3 colorTint; // Your painting mechanic!

void main() {
    vec4 texColor = texture(texture_diffuse, TexCoords);
    if(texColor.a < 0.1) discard; // Prevent invisible geometry from casting shadows

    vec3 norm = normalize(Normal);
    // Grab the Sun's exact position from the legacy OpenGL Light0
    vec3 lightDir = normalize(gl_LightSource[0].position.xyz - FragPos);

    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * sunIntensity * texColor.rgb;

    vec3 ambient = 0.4 * texColor.rgb;

    // Apply the custom color tint to the final lit pixel
    FragColor = vec4((ambient + diffuse) * colorTint, texColor.a);
}