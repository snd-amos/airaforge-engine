#version 130
in vec3 aPos;
in vec2 aTexCoords;
in vec3 aNormal;

out vec2 TexCoords;
out vec3 Normal;
out vec3 FragPos;

void main() {
    TexCoords = aTexCoords;

    // We use built-in matrices to safely bridge legacy and modern OpenGL!
    Normal = gl_NormalMatrix * aNormal;
    vec4 viewPos = gl_ModelViewMatrix * vec4(aPos, 1.0);
    FragPos = viewPos.xyz;

    gl_Position = gl_ModelViewProjectionMatrix * vec4(aPos, 1.0);
}