#version 150 core

in vec2 Position;
in vec2 UV;

out vec2 vUV;

void main() {
    vUV = UV;
    gl_Position = vec4(Position, 0.0, 1.0);
}
