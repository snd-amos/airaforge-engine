#version 150

// 2D overlay shader — input is already in NDC (-1 to 1 on both axes), so
// the GL matrix stack is completely sidestepped. Callers convert pixel
// coordinates to NDC themselves (cheap, one multiply per vertex).

in vec2 aPos;

void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
}
