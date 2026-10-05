#version 330 core

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV;
layout(location = 2) in vec4 Color;

uniform mat4 ViewProjection;
uniform vec3 Offset;
uniform vec2 FogCenter;

out vec2 texCoord;
out vec4 vertexColor;
out float fogDistance;

void main() {
    gl_Position = ViewProjection * vec4(Position + Offset, 1.0);
    texCoord = UV;
    vertexColor = Color;
    fogDistance = length(Position.xz - FogCenter);
}
