#version 150

in vec3 aPos;
in vec2 aTexCoords;
in vec4 instModel0;
in vec4 instModel1;
in vec4 instModel2;
in vec4 instModel3;

out vec2 TexCoords;

uniform mat4 viewMatrix;
uniform mat4 projMatrix;

void main() {
    mat4 modelMatrix = mat4(instModel0, instModel1, instModel2, instModel3);
    TexCoords = aTexCoords;
    gl_Position = projMatrix * viewMatrix * modelMatrix * vec4(aPos, 1.0);
}
