#version 330 core

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV;

uniform mat4 ViewProjection;

out vec2 texCoord;

void main() {
    gl_Position = ViewProjection * vec4(Position, 1.0);
    texCoord = UV;
}
