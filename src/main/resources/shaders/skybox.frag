#version 150

in vec3 vDir;

out vec4 FragColor;

uniform samplerCube skybox;

void main() {
    FragColor = texture(skybox, vDir);
}
